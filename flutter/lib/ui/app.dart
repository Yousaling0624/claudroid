import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:prism_ui/prism_ui.dart';

import '../bridge/api.dart';
import '../bridge/events.dart';
import '../models/host_scope.dart';
import '../models/models.dart';
import '../models/remote.dart';
import 'chat_page.dart';
import 'home_page.dart';
import 'left_side_sheet.dart';
import 'localizations.dart';
import 'palette.dart';
import 'remote_host_picker.dart';
import 'scroll_behavior.dart';
import 'session_drawer.dart';
import 'settings/remote_files_page.dart';
import 'settings/settings_destination.dart';
import 'settings_page.dart';
import 'workspace_page.dart';

/// Folds an `app.init` reply into a state that may already hold events.
///
/// The init payload has no hosts: assigning a fresh [AppState] after a
/// `ready`/`hosts` event has landed would erase the paired-host list until the
/// manager next changes state (a restart looked like every host had vanished).
/// Init is only the first-state fallback, so it must fill fields in place.
AppState mergeInitState(AppState current, InitState init) => current.copyWith(
      settings: init.settings,
      labels: init.labels,
      engine: init.engine,
      sessions: init.sessions,
      ready: true,
    );

/// The shell: owns the app-wide state stream, the active session, and the
/// destinations. All business state lives in Kotlin; this only folds the
/// events it sends.
class ClaudroidApp extends StatefulWidget {
  const ClaudroidApp({super.key, this.bridge = const Bridge()});

  final Bridge bridge;

  @override
  State<ClaudroidApp> createState() => _ClaudroidAppState();
}

class _ClaudroidAppState extends State<ClaudroidApp> {
  final GlobalEvents _global = GlobalEvents();
  StreamSubscription<AppEvent>? _subscription;

  final ValueNotifier<AppState> _state =
      ValueNotifier<AppState>(const AppState());
  String? _sessionId;

  /// The freshly created session before the next `sessions` event lands. It
  /// carries the host binding, so the chat page can label a remote turn from
  /// the first frame instead of briefly looking local.
  ChatSession? _sessionHint;
  String? _startupError;

  /// Incremented on every "new chat"; the home page drops its draft and asks
  /// for a fresh set of suggestions.
  int _homeEpoch = 0;

  /// A context under [BannerHost], for showing errors from async callbacks.
  BuildContext? _hostContext;

  @override
  void initState() {
    super.initState();
    _subscription = _global.events.listen(_onEvent, onError: (_) {});
    _init();
  }

  @override
  void dispose() {
    _subscription?.cancel();
    _state.dispose();
    super.dispose();
  }

  Future<void> _init() async {
    try {
      final init = await widget.bridge.init();
      if (!mounted) return;
      setState(() {
        _state.value = mergeInitState(_state.value, init);
      });
    } catch (error) {
      // The host may not be attached yet (tests, a stale engine). The shell
      // still renders and the event stream fills it in when it arrives.
      if (!mounted) return;
      setState(() => _startupError = '$error');
    }
  }

  void _onEvent(AppEvent event) {
    if (!mounted) return;
    final previous = _state.value;
    final next = event.apply(previous);
    _state.value = next;
    // Switching the current host switches the whole context: its history and
    // settings replace the ones on screen, so an open conversation from the
    // previous host would be a leftover. Fall back to the new host's home.
    if (!sameHost(
          previous.hosts.defaultHostId,
          next.hosts.defaultHostId,
        ) &&
        _sessionId != null) {
      ChatSession? open;
      for (final session in next.sessions) {
        if (session.id == _sessionId) {
          open = session;
          break;
        }
      }
      open ??= _sessionHint?.id == _sessionId ? _sessionHint : null;
      if (!sameHost(open?.hostId, next.hosts.defaultHostId)) {
        setState(() {
          _sessionId = null;
          _sessionHint = null;
        });
      }
    }
    // `_Shell` rebuilds through the ValueListenableBuilder inside `home`, so an
    // engine progress tick no longer rebuilds PrismApp and its theme. ThemeMode
    // lives above that builder; only a theme change needs the whole shell rebuilt.
    if (next.settings.intOr('themeMode') !=
        previous.settings.intOr('themeMode')) {
      setState(() {});
    }
  }

  /// "New chat" is the home page, not an empty session: the session is created
  /// when the first prompt is sent, matching `MainActivity`.
  void _newSession() {
    setState(() {
      _sessionId = null;
      _sessionHint = null;
      _homeEpoch++;
    });
  }

  void _openSession(String id) {
    ChatSession? hint;
    for (final session in _state.value.sessions) {
      if (session.id == id) {
        hint = session;
        break;
      }
    }
    setState(() {
      _sessionId = id;
      _sessionHint = hint;
    });
  }

  Future<bool> _sendFromHome(
    String text, {
    String? hostId,
    String? remoteCwd,
  }) async {
    try {
      final session = await widget.bridge.newSession(
        hostId: hostId,
        remoteCwd: remoteCwd,
      );
      final accepted = await widget.bridge.send(session.id, text);
      if (!mounted) return false;
      if (!accepted) {
        _toast('这一轮还没结束。先点停止，再发送。', isError: true);
        return false;
      }
      setState(() {
        _sessionId = session.id;
        _sessionHint = session;
      });
      return true;
    } catch (error) {
      _toast('没能开始对话（$error）。再试一次。', isError: true);
      return false;
    }
  }

  Future<void> _renameSession(String id, String title) async {
    try {
      await widget.bridge.renameSession(id, title);
    } catch (error) {
      _toast('$error', isError: true);
    }
  }

  Future<void> _deleteSession(String id) async {
    try {
      await widget.bridge.deleteSession(id);
      if (!mounted) return;
      if (_sessionId == id) {
        setState(() {
          _sessionId = null;
          _sessionHint = null;
        });
      }
    } catch (error) {
      _toast('$error', isError: true);
    }
  }

  Future<void> _updateSettings(Map<String, dynamic> patch) async {
    final next = _state.value.settings.merge(patch);
    try {
      await widget.bridge.updateSettings(next);
      // `settings` events push the authoritative copy right after this returns,
      // so there is no optimistic local write for the two to disagree on.
    } catch (error) {
      _toast('$error', isError: true);
    }
  }

  /// The current host is persisted as the host store's default. Every surface
  /// (drawer history, home page, new chats, settings root) reads it back, so
  /// the switch survives a restart instead of being a per-draft selection.
  Future<void> _setActiveHost(String? hostId) async {
    try {
      await widget.bridge.setDefaultHost(hostId);
    } catch (error) {
      _toast('切换主机失败（$error）', isError: true);
    }
  }

  void _toast(String text, {bool isError = false}) {
    final host = _hostContext;
    if (host == null || !host.mounted) return;
    BannerHost.maybeOf(host)?.show(
      text,
      level: isError ? BannerLevel.error : BannerLevel.info,
    );
  }

  @override
  Widget build(BuildContext context) {
    return PrismApp(
      title: 'Claudroid',
      theme: ThemeData(
        colorScheme: claudroidColorScheme(Brightness.light),
      ),
      darkTheme: ThemeData(
        colorScheme: claudroidColorScheme(Brightness.dark),
      ),
      themeMode: switch (_state.value.settings.intOr('themeMode')) {
        1 => ThemeMode.light,
        2 => ThemeMode.dark,
        _ => ThemeMode.system,
      },
      liquidGlass: LiquidGlassQuality.standard,
      // The stock DEBUG ribbon sits on top of the status bar and reads as a
      // rendering bug in screenshots; debug builds are identifiable from the
      // version string in 关于 instead.
      debugShowCheckedModeBanner: false,
      // PrismApp does not provide a ViewScope, and every PrismScaffold needs
      // one; this is the phone/tablet split prism_ui assumes.
      builder: (context, child) {
        final size = MediaQuery.sizeOf(context);
        return ViewScope(
          size: size,
          isMobile: size.width < 600,
          // Above the navigator, so every route and its lists use the app's
          // spring scroll instead of Android's default clamping stop.
          child: ScrollConfiguration(
            behavior: const ClaudroidScrollBehavior(),
            child: child!,
          ),
        );
      },
      localizationsDelegates: const [ZhUiLocalizations.delegate],
      supportedLocales: const [Locale('zh'), Locale('en')],
      home: ValueListenableBuilder<AppState>(
        valueListenable: _state,
        builder: (context, state, _) {
          _hostContext = context;
          return _Shell(
            state: state,
            stateListenable: _state,
            sessionId: _sessionId,
            activeSession: _sessionHint,
            homeEpoch: _homeEpoch,
            startupError: _startupError,
            bridge: widget.bridge,
            onNewSession: _newSession,
            onSendFromHome: _sendFromHome,
            onOpenSession: _openSession,
            onRenameSession: _renameSession,
            onDeleteSession: _deleteSession,
            onSelectHost: _setActiveHost,
            onUpdateSettings: _updateSettings,
            onToast: _toast,
          );
        },
      ),
    );
  }
}

class _Shell extends StatelessWidget {
  const _Shell({
    required this.state,
    required this.stateListenable,
    required this.sessionId,
    required this.activeSession,
    required this.homeEpoch,
    required this.startupError,
    required this.bridge,
    required this.onNewSession,
    required this.onSendFromHome,
    required this.onOpenSession,
    required this.onRenameSession,
    required this.onDeleteSession,
    required this.onSelectHost,
    required this.onUpdateSettings,
    required this.onToast,
  });

  final AppState state;
  final ValueListenable<AppState> stateListenable;
  final String? sessionId;
  final ChatSession? activeSession;
  final int homeEpoch;
  final String? startupError;
  final Bridge bridge;
  final VoidCallback onNewSession;
  final Future<bool> Function(
    String text, {
    String? hostId,
    String? remoteCwd,
  }) onSendFromHome;
  final ValueChanged<String> onOpenSession;
  final void Function(String id, String title) onRenameSession;
  final ValueChanged<String> onDeleteSession;
  final Future<void> Function(String? hostId) onSelectHost;
  final Future<void> Function(Map<String, dynamic> patch) onUpdateSettings;
  final void Function(String text, {bool isError}) onToast;

  String? get _activeHostId => normalizeHostId(state.hosts.defaultHostId);

  RemoteHost? get _activeHost => state.hosts.byId(_activeHostId);

  void _openSettings(
    BuildContext context, {
    SettingsDestination destination = SettingsDestination.root,
    String? remoteHostId,
    bool pairRemoteHost = false,
  }) {
    BaseNavigator.push<void>(
      context,
      SettingsPage(
        bridge: bridge,
        stateListenable: stateListenable,
        onUpdateSettings: onUpdateSettings,
        onToast: onToast,
        destination: destination,
        remoteHostId: remoteHostId,
        pairRemoteHost: pairRemoteHost,
      ),
    );
  }

  /// Switching the current host is a context switch, not a per-draft choice:
  /// the picker persists the selection and every surface re-scopes to it.
  Future<void> _chooseHost(BuildContext context) async {
    final current = stateListenable.value;
    final choice = await showRemoteHostPicker(
      context,
      hosts: current.hosts,
      currentHostId: current.hosts.defaultHostId,
    );
    if (choice == null || !context.mounted) return;
    switch (choice.intent) {
      case RemoteHostPickerIntent.configureLocal:
        _openSettings(context, destination: SettingsDestination.engine);
      case RemoteHostPickerIntent.configureHost:
        final hostId = choice.hostId;
        if (hostId != null) {
          _openSettings(
            context,
            destination: SettingsDestination.remoteHosts,
            remoteHostId: hostId,
          );
        }
      case RemoteHostPickerIntent.addHost:
        final host = await BaseNavigator.push<RemoteHost>(
          context,
          SettingsPage(
            bridge: bridge,
            stateListenable: stateListenable,
            onUpdateSettings: onUpdateSettings,
            onToast: onToast,
            destination: SettingsDestination.remoteHosts,
            pairRemoteHost: true,
          ),
        );
        if (host == null || !context.mounted) return;
        await onSelectHost(host.id);
        onToast('已添加并切换到「${host.displayName}」');
      case null:
        await onSelectHost(choice.hostId);
    }
  }

  void _openDrawer(BuildContext context) {
    showLeftSideSheet<void>(
      context: context,
      barrierColor: context.colorScheme.modalScrim,
      builder: (sheetContext) => SessionDrawer(
        sessions: sessionsForHost(state.sessions, _activeHostId),
        activeId: sessionId,
        engine: state.engine,
        activeHost: _activeHost,
        hosts: state.hosts,
        onSelect: (id) {
          // Pop synchronously: `maybePop` is async and would race the route
          // pushed right after it, popping the new page instead of the drawer.
          Navigator.of(sheetContext).pop();
          onOpenSession(id);
        },
        onNew: () {
          Navigator.of(sheetContext).pop();
          onNewSession();
        },
        onRename: onRenameSession,
        onDelete: onDeleteSession,
        onChooseHost: () {
          Navigator.of(sheetContext).pop();
          _chooseHost(context);
        },
        onSelectHost: (hostId) {
          Navigator.of(sheetContext).pop();
          unawaited(onSelectHost(hostId));
        },
        onWorkspace: () {
          final host = _activeHost;
          Navigator.of(sheetContext).pop();
          if (host == null) {
            _openWorkspace(context);
          } else {
            BaseNavigator.push<void>(
              context,
              RemoteFilesPage(bridge: bridge, host: host, onToast: onToast),
            );
          }
        },
        onOpenHostSettings: (hostId) {
          Navigator.of(sheetContext).pop();
          _openSettings(
            context,
            destination: SettingsDestination.remoteHosts,
            remoteHostId: hostId,
          );
        },
        onSettings: (destination) {
          Navigator.of(sheetContext).pop();
          _openSettings(context, destination: destination);
        },
        onClose: () => Navigator.of(sheetContext).pop(),
      ),
    );
  }

  void _openWorkspace(BuildContext context) {
    BaseNavigator.push<void>(
      context,
      WorkspacePage(bridge: bridge, onToast: onToast),
    );
  }

  @override
  Widget build(BuildContext context) {
    final id = sessionId;
    if (id == null) {
      return HomePage(
        bridge: bridge,
        settings: state.settings,
        labels: state.labels,
        engine: state.engine,
        hosts: state.hosts,
        homeEpoch: homeEpoch,
        sessions: sessionsForHost(state.sessions, _activeHostId),
        onOpenSession: onOpenSession,
        onSelectHost: onSelectHost,
        notice: startupError,
        onOpenDrawer: () => _openDrawer(context),
        onOpenSettings: (destination) =>
            _openSettings(context, destination: destination),
        onOpenRemoteHost: (hostId) => _openSettings(
          context,
          destination: SettingsDestination.remoteHosts,
          remoteHostId: hostId,
        ),
        onAddRemoteHost: () => BaseNavigator.push<RemoteHost>(
          context,
          SettingsPage(
            bridge: bridge,
            stateListenable: stateListenable,
            onUpdateSettings: onUpdateSettings,
            onToast: onToast,
            destination: SettingsDestination.remoteHosts,
            pairRemoteHost: true,
          ),
        ),
        onOpenWorkspace: () => _openWorkspace(context),
        onNewChat: onNewSession,
        onSend: onSendFromHome,
        onUpdateSettings: onUpdateSettings,
        onToast: onToast,
      );
    }
    ChatSession? session;
    for (final candidate in state.sessions) {
      if (candidate.id == id) {
        session = candidate;
        break;
      }
    }
    if (session == null && activeSession?.id == id) {
      session = activeSession;
    }
    return ChatPage(
      key: ValueKey(id),
      bridge: bridge,
      sessionId: id,
      session: session,
      hosts: state.hosts,
      settings: state.settings,
      labels: state.labels,
      onOpenDrawer: () => _openDrawer(context),
      onOpenSettings: (destination) =>
          _openSettings(context, destination: destination),
      onOpenWorkspace: () => _openWorkspace(context),
      onNewChat: onNewSession,
      onOpenSession: onOpenSession,
      onUpdateSettings: onUpdateSettings,
      onToast: onToast,
    );
  }
}
