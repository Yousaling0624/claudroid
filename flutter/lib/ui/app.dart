import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:prism_ui/prism_ui.dart';

import '../bridge/api.dart';
import '../bridge/events.dart';
import 'chat_page.dart';
import 'localizations.dart';
import 'session_drawer.dart';
import 'settings_page.dart';

/// Seed for the whole palette: Claudroid's terracotta, the same value as
/// `ui/theme/Theme.kt`'s `orange`, so the derived surfaces stay on the warm
/// paper side instead of drifting blue.
const kSeedColor = Color(0xFFD97757);

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

  AppState _state = const AppState();
  String? _sessionId;
  String? _startupError;

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
    super.dispose();
  }

  Future<void> _init() async {
    try {
      final init = await widget.bridge.init();
      if (!mounted) return;
      setState(() {
        _state = AppState(
          settings: init.settings,
          labels: init.labels,
          engine: init.engine,
          sessions: init.sessions,
          ready: true,
        );
        _sessionId = init.sessions.isEmpty ? null : init.sessions.first.id;
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
    setState(() {
      _state = event.apply(_state);
      // The first session on a cold start should open itself.
      if (_sessionId == null && _state.sessions.isNotEmpty) {
        _sessionId = _state.sessions.first.id;
      }
    });
  }

  Future<void> _newSession() async {
    try {
      final session = await widget.bridge.newSession();
      if (!mounted) return;
      setState(() => _sessionId = session.id);
    } catch (error) {
      _toast('$error', isError: true);
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
      if (_sessionId == id) setState(() => _sessionId = null);
    } catch (error) {
      _toast('$error', isError: true);
    }
  }

  Future<void> _updateSettings(Map<String, dynamic> patch) async {
    final next = _state.settings.merge(patch);
    try {
      await widget.bridge.updateSettings(next);
      // `settings` events push the authoritative copy right after this returns,
      // so there is no optimistic local write for the two to disagree on.
    } catch (error) {
      _toast('$error', isError: true);
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
        colorScheme: seededColorScheme(
          seedColor: kSeedColor,
          brightness: Brightness.light,
        ),
      ),
      darkTheme: ThemeData(
        colorScheme: seededColorScheme(
          seedColor: kSeedColor,
          brightness: Brightness.dark,
        ),
      ),
      themeMode: switch (_state.settings.intOr('themeMode')) {
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
          child: child!,
        );
      },
      localizationsDelegates: const [ZhUiLocalizations.delegate],
      supportedLocales: const [Locale('zh'), Locale('en')],
      home: Builder(
        builder: (context) {
          _hostContext = context;
          return _Shell(
            state: _state,
            sessionId: _sessionId,
            startupError: _startupError,
            bridge: widget.bridge,
            onNewSession: _newSession,
            onOpenSession: (id) => setState(() => _sessionId = id),
            onRenameSession: _renameSession,
            onDeleteSession: _deleteSession,
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
    required this.sessionId,
    required this.startupError,
    required this.bridge,
    required this.onNewSession,
    required this.onOpenSession,
    required this.onRenameSession,
    required this.onDeleteSession,
    required this.onUpdateSettings,
    required this.onToast,
  });

  final AppState state;
  final String? sessionId;
  final String? startupError;
  final Bridge bridge;
  final VoidCallback onNewSession;
  final ValueChanged<String> onOpenSession;
  final void Function(String id, String title) onRenameSession;
  final ValueChanged<String> onDeleteSession;
  final Future<void> Function(Map<String, dynamic> patch) onUpdateSettings;
  final void Function(String text, {bool isError}) onToast;

  void _openSettings(BuildContext context) {
    BaseNavigator.push<void>(
      context,
      SettingsPage(
        bridge: bridge,
        settings: state.settings,
        labels: state.labels,
        engine: state.engine,
        onUpdateSettings: onUpdateSettings,
        onToast: onToast,
      ),
    );
  }

  void _openDrawer(BuildContext context) {
    showModalSideSheet<void>(
      context: context,
      barrierColor: context.colorScheme.modalScrim,
      builder: (sheetContext) => SessionDrawer(
        sessions: state.sessions,
        activeId: sessionId,
        onSelect: (id) {
          onOpenSession(id);
          Navigator.of(sheetContext).maybePop();
        },
        onNew: () {
          Navigator.of(sheetContext).maybePop();
          onNewSession();
        },
        onRename: onRenameSession,
        onDelete: onDeleteSession,
        onClose: () => Navigator.of(sheetContext).maybePop(),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final id = sessionId;
    if (id == null) {
      return EmptyChatPage(
        notice: startupError,
        onOpenDrawer: () => _openDrawer(context),
        onOpenSettings: () => _openSettings(context),
        onNewChat: onNewSession,
      );
    }
    return ChatPage(
      key: ValueKey(id),
      bridge: bridge,
      sessionId: id,
      settings: state.settings,
      labels: state.labels,
      onOpenDrawer: () => _openDrawer(context),
      onOpenSettings: () => _openSettings(context),
      onNewChat: onNewSession,
      onUpdateSettings: onUpdateSettings,
      onToast: onToast,
    );
  }
}
