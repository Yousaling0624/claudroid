import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../bridge/api.dart';
import '../bridge/events.dart';
import '../models/models.dart';
import 'glyphs.dart';
import 'layout.dart';
import 'selection_menu.dart';
import 'scaffold_bar.dart';
import 'terminal_buffer.dart';

/// The width of one terminal column. The grid measurement uses the same style
/// as the rows, so the screen and the cursor stay aligned.
const double _terminalFontSize = 12.5;
const double _terminalLineHeight = 1.35;
const double _terminalRowHeight = _terminalFontSize * _terminalLineHeight;

const Duration _cursorBlinkPeriod = Duration(milliseconds: 530);
const Duration _resizeDebounce = Duration(milliseconds: 300);
const Duration _startTimeout = Duration(seconds: 10);

/// Interactive workspace terminal: a persistent PTY rendered as a Termius-like
/// pane with a glass key row and a live-mirrored input capsule.
///
/// Kotlin owns the shell; this page feeds every chunk through [TerminalBuffer]
/// and sends keystrokes back with `terminal.write`. It subscribes before
/// calling `terminal.start` so no early output is lost, debounces resizes and
/// stops the shell in [dispose] so nothing keeps running behind the workspace.
class TerminalPage extends StatefulWidget {
  const TerminalPage({
    super.key,
    required this.bridge,
    required this.onBack,
    required this.onToast,
  });

  final Bridge bridge;
  final VoidCallback onBack;
  final void Function(String text, {bool isError}) onToast;

  @override
  State<TerminalPage> createState() => _TerminalPageState();
}

enum _TerminalStatus { connecting, live, exited, stopped, error }

class _TerminalPageState extends State<TerminalPage> {
  final TerminalEvents _events = TerminalEvents();
  final TerminalBuffer _buffer = TerminalBuffer();
  final TextEditingController _input = TextEditingController();
  final ScrollController _scroll = ScrollController();
  late final FocusNode _inputFocus;

  StreamSubscription<TerminalEvent>? _subscription;
  TerminalInfo? _info;
  bool _infoLoading = true;

  _TerminalStatus _status = _TerminalStatus.connecting;
  int? _exitCode;
  String? _errorText;

  /// Ctrl is sticky until the next character maps to a control code.
  bool _ctrlArmed = false;

  /// True between the `started` event and the shell's `exit`; writes are only
  /// sent while the shell is listening.
  bool _started = false;
  bool _startInFlight = false;
  bool _restartInFlight = false;

  /// The page starts once, after the first layout. Exiting or failing shows a
  /// reconnect action instead of starting a new shell on the next rotation.
  bool _startAttempted = false;
  int _startedColumns = 0;
  int _startedRows = 0;
  Timer? _startTimer;

  bool _followBottom = true;
  int _lastScrollbackLength = 0;
  bool _scrollSyncScheduled = false;
  bool _repaintScheduled = false;
  bool _viewportScheduled = false;
  bool _dimensionsReady = false;

  bool _blinkOn = true;
  Timer? _blinkTimer;
  Timer? _resizeTimer;

  /// The text the PTY has already received from the input capsule. Edits are
  /// diffed against it so the shell sees the same line the user sees.
  String _mirroredInput = '';

  @override
  void initState() {
    super.initState();
    _inputFocus = FocusNode(
      debugLabel: 'terminalInput',
      onKeyEvent: _onHardwareKey,
    );
    _inputFocus.addListener(_onInputFocusChanged);
    // Subscribe before announcing the page: `terminal.start` answers with a
    // stream of events and the first prompt must not race the listener.
    _subscription = _events.events.listen(_onEvent, onError: (_) {});
    unawaited(_loadInfo());
  }

  @override
  void dispose() {
    _blinkTimer?.cancel();
    _resizeTimer?.cancel();
    _startTimer?.cancel();
    _subscription?.cancel();
    // Leaving the pane must kill the child, as DESIGN_FLUTTER.md requires;
    // otherwise the shell would keep running behind the workspace. The page is
    // going away, so a failed stop has nowhere to report.
    unawaited(_stopShell());
    _inputFocus.removeListener(_onInputFocusChanged);
    _inputFocus.dispose();
    _input.dispose();
    _scroll.dispose();
    super.dispose();
  }

  Future<void> _stopShell() async {
    try {
      await widget.bridge.terminalStop();
    } catch (_) {
      // Shutdown is best-effort; the host also stops the session when the sink
      // goes away.
    }
  }

  // -- connection ----------------------------------------------------------

  Future<void> _loadInfo() async {
    try {
      final info = await widget.bridge.terminalInfo();
      if (!mounted) return;
      setState(() {
        _info = info;
        _infoLoading = false;
      });
      _maybeStart();
    } catch (error) {
      if (!mounted) return;
      setState(() => _infoLoading = false);
      widget.onToast('$error', isError: true);
    }
  }

  void _maybeStart() {
    if (!mounted ||
        _infoLoading ||
        _startAttempted ||
        _startInFlight ||
        !_dimensionsReady ||
        !(_info?.available ?? false)) {
      return;
    }
    unawaited(_startTerminal());
  }

  Future<void> _startTerminal() async {
    if (_startInFlight) return;
    _startInFlight = true;
    _startAttempted = true;
    _startTimer?.cancel();
    _buffer.prepareForNewSession();
    _input.clear();
    _mirroredInput = '';
    setState(() {
      _status = _TerminalStatus.connecting;
      _exitCode = null;
      _errorText = null;
      _ctrlArmed = false;
      _started = false;
    });
    _startedColumns = _buffer.columns;
    _startedRows = _buffer.rows;
    _startTimer = Timer(_startTimeout, () {
      if (!mounted || _status != _TerminalStatus.connecting) return;
      setState(() {
        _status = _TerminalStatus.error;
        _errorText = '终端启动超时，请重试。';
      });
    });
    try {
      await widget.bridge.terminalStart(
        rows: _buffer.rows,
        cols: _buffer.columns,
      );
    } catch (error) {
      if (!mounted) return;
      _startTimer?.cancel();
      setState(() {
        _status = _TerminalStatus.error;
        _errorText = '$error';
      });
    } finally {
      _startInFlight = false;
    }
  }

  void _reconnect() {
    if (_startInFlight || _restartInFlight) return;
    unawaited(_restart());
  }

  Future<void> _restart() async {
    _restartInFlight = true;
    try {
      // A timed-out or crashed session may still be registered on the host;
      // stop is idempotent and clears it before the new start.
      await _stopShell();
      if (!mounted) return;
      await _startTerminal();
    } finally {
      _restartInFlight = false;
    }
  }

  void _onEvent(TerminalEvent event) {
    if (!mounted) return;
    switch (event) {
      case TerminalStartedEvent():
        _startTimer?.cancel();
        setState(() {
          _status = _TerminalStatus.live;
          _started = true;
          _errorText = null;
          _exitCode = null;
        });
        _updateBlink();
        if (_startedColumns != _buffer.columns ||
            _startedRows != _buffer.rows) {
          _scheduleRemoteResize();
        }
        _scheduleScrollSync();
      case TerminalChunkEvent(:final text):
        _buffer.feed(text);
        _refresh();
      case TerminalExitEvent(:final exitCode):
        _startTimer?.cancel();
        // A crashed full-screen program may leave the alternate screen behind;
        // restore the shell history before showing the exit banner.
        _buffer.prepareForNewSession();
        _buffer.feed('\r\n');
        setState(() {
          _status = _TerminalStatus.exited;
          _exitCode = exitCode;
          _started = false;
          _input.clear();
          _mirroredInput = '';
          _ctrlArmed = false;
        });
        _stopBlink();
        _scheduleScrollSync();
      case TerminalStoppedEvent():
        if (_status == _TerminalStatus.live ||
            _status == _TerminalStatus.connecting) {
          setState(() {
            _status = _TerminalStatus.stopped;
            _started = false;
            _input.clear();
            _mirroredInput = '';
            _ctrlArmed = false;
          });
          _stopBlink();
        }
      case TerminalErrorEvent(:final message):
        _startTimer?.cancel();
        _buffer.prepareForNewSession();
        setState(() {
          _status = _TerminalStatus.error;
          _errorText = message;
          _started = false;
          _input.clear();
          _mirroredInput = '';
          _ctrlArmed = false;
        });
        _stopBlink();
      case TerminalDoneEvent(:final exitCode, :final error):
        // The one-off `terminal.run` is gone, but an older host may still send
        // its completion event; treat it as the shell leaving.
        _startTimer?.cancel();
        setState(() {
          _status = _TerminalStatus.exited;
          _exitCode = exitCode;
          _errorText = error;
          _started = false;
        });
        _stopBlink();
    }
  }

  // -- viewport, resize and scroll ----------------------------------------

  void _syncViewport(int columns, int rows) {
    if (_dimensionsReady &&
        _buffer.columns == columns &&
        _buffer.rows == rows) {
      return;
    }
    if (_viewportScheduled) return;
    _viewportScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _viewportScheduled = false;
      if (!mounted) return;
      _dimensionsReady = true;
      if (_buffer.columns != columns || _buffer.rows != rows) {
        _buffer.resize(columns: columns, rows: rows);
        setState(() {});
        _scheduleRemoteResize();
        _scheduleScrollSync();
      }
      _maybeStart();
    });
  }

  void _scheduleRemoteResize() {
    _resizeTimer?.cancel();
    _resizeTimer = Timer(_resizeDebounce, () {
      if (!mounted || !_started) return;
      unawaited(_resizeRemote());
    });
  }

  Future<void> _resizeRemote() async {
    try {
      await widget.bridge.terminalResize(
        rows: _buffer.rows,
        cols: _buffer.columns,
      );
    } catch (_) {
      // A resize is best-effort: the shell keeps its old size until the next
      // successful resize, which is still usable.
    }
  }

  void _scheduleScrollSync() {
    if (_scrollSyncScheduled) return;
    _scrollSyncScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _scrollSyncScheduled = false;
      if (!mounted || !_scroll.hasClients) return;
      final added = _buffer.scrollbackLength - _lastScrollbackLength;
      _lastScrollbackLength = _buffer.scrollbackLength;
      final position = _scroll.position;
      if (_followBottom) {
        _scroll.jumpTo(position.maxScrollExtent);
      } else if (added > 0) {
        // Scrollback rows are inserted above the viewport; keeping the offset
        // unchanged would shift the text under the user's finger.
        final target = (position.pixels + added * _terminalRowHeight).clamp(
          0.0,
          position.maxScrollExtent,
        );
        _scroll.jumpTo(target.toDouble());
      }
    });
  }

  /// Rebuilds at most once per frame. A busy command streams many tiny chunks;
  /// repainting the whole page (glass included) for each one would drop frames.
  /// The buffer is already up to date, so skipped rebuilds lose no output.
  void _refresh() {
    if (!_repaintScheduled) {
      _repaintScheduled = true;
      setState(() {});
      WidgetsBinding.instance.addPostFrameCallback((_) {
        _repaintScheduled = false;
      });
    }
    _scheduleScrollSync();
  }

  bool _onScroll(ScrollNotification notification) {
    if (notification is ScrollUpdateNotification &&
        notification.dragDetails != null) {
      final atBottom = notification.metrics.extentAfter < 2;
      if (atBottom != _followBottom) {
        setState(() => _followBottom = atBottom);
      }
    } else if (notification is ScrollEndNotification) {
      final atBottom = notification.metrics.extentAfter < 2;
      if (atBottom && !_followBottom) {
        setState(() => _followBottom = true);
      }
    }
    return false;
  }

  // -- blink ---------------------------------------------------------------

  void _onInputFocusChanged() {
    if (!mounted) return;
    _updateBlink();
  }

  void _updateBlink() {
    final shouldBlink =
        _status == _TerminalStatus.live &&
        _inputFocus.hasFocus &&
        !context.disableAnimations;
    if (!shouldBlink) {
      _blinkTimer?.cancel();
      _blinkTimer = null;
      if (!_blinkOn) setState(() => _blinkOn = true);
      return;
    }
    if (_blinkTimer != null) return;
    _blinkTimer = Timer.periodic(_cursorBlinkPeriod, (_) {
      if (!mounted) return;
      setState(() => _blinkOn = !_blinkOn);
    });
  }

  void _stopBlink() {
    _blinkTimer?.cancel();
    _blinkTimer = null;
    if (!_blinkOn) setState(() => _blinkOn = true);
  }

  // -- input ---------------------------------------------------------------

  Future<void> _send(String data) async {
    if (data.isEmpty || !_started || _status != _TerminalStatus.live) return;
    try {
      await widget.bridge.terminalWrite(data);
    } catch (_) {
      // Keystrokes are fire-and-forget; the exit/error event reports a dead
      // shell, so a failed write needs no extra toast per character.
    }
  }

  void _sendNow(String data) => unawaited(_send(data));

  void _focusInput() {
    if (_status == _TerminalStatus.live) _inputFocus.requestFocus();
  }

  void _toggleCtrl() {
    if (_status != _TerminalStatus.live) return;
    setState(() => _ctrlArmed = !_ctrlArmed);
    if (_ctrlArmed) _inputFocus.requestFocus();
  }

  void _onInputChanged(String text) {
    if (_status != _TerminalStatus.live) return;
    final value = _input.value;
    if (terminalIsComposing(value)) {
      // Leave half-composed IME text alone; the commit fires onChanged again.
      return;
    }
    var current = text;
    if (_ctrlArmed && current.startsWith(_mirroredInput)) {
      final inserted = current.substring(_mirroredInput.length);
      if (inserted.isNotEmpty) {
        final first = String.fromCharCode(inserted.runes.first);
        final mapped = terminalControlCode(first);
        if (mapped != null) {
          current = '$_mirroredInput${inserted.substring(first.length)}';
          _input.value = TextEditingValue(
            text: current,
            selection: TextSelection.collapsed(offset: current.length),
          );
          _sendNow(String.fromCharCode(mapped));
        }
        // Any typed character consumes the sticky Ctrl, mapped or not.
        setState(() => _ctrlArmed = false);
      }
    }
    final payload = terminalEditPayload(_mirroredInput, current);
    _mirroredInput = current;
    _sendNow(payload);
  }

  void _submitLine() {
    if (_status != _TerminalStatus.live) return;
    _sendNow('\r');
    _input.clear();
    _mirroredInput = '';
    if (_ctrlArmed) setState(() => _ctrlArmed = false);
  }

  void _hardwareBackspace() {
    final value = _input.value;
    final text = value.text;
    if (text.isEmpty) {
      _sendNow('\x7f');
      return;
    }
    final last = String.fromCharCode(text.runes.last);
    final next = text.substring(0, text.length - last.length);
    _input.value = TextEditingValue(
      text: next,
      selection: TextSelection.collapsed(offset: next.length),
    );
    _mirroredInput = next;
    _sendNow('\x7f');
  }

  KeyEventResult _onHardwareKey(FocusNode node, KeyEvent event) {
    if (event is! KeyDownEvent && event is! KeyRepeatEvent) {
      return KeyEventResult.ignored;
    }
    if (_status != _TerminalStatus.live) return KeyEventResult.ignored;
    if (terminalIsComposing(_input.value)) {
      // The IME owns every key while a composition is open.
      return KeyEventResult.ignored;
    }

    final key = event.logicalKey;
    final keyboard = HardwareKeyboard.instance;
    if (keyboard.isControlPressed) {
      final label = key.keyLabel.toLowerCase();
      if (label.length == 1) {
        final mapped = terminalControlCode(label);
        if (mapped != null) {
          _sendNow(String.fromCharCode(mapped));
          return KeyEventResult.handled;
        }
      }
      if (key == LogicalKeyboardKey.backspace) {
        _sendNow('\x08');
        return KeyEventResult.handled;
      }
    }

    if (key == LogicalKeyboardKey.escape) {
      _sendNow('\x1b');
      return KeyEventResult.handled;
    }
    if (key == LogicalKeyboardKey.tab) {
      _sendNow('\t');
      return KeyEventResult.handled;
    }
    if (key == LogicalKeyboardKey.enter ||
        key == LogicalKeyboardKey.numpadEnter) {
      _submitLine();
      return KeyEventResult.handled;
    }
    if (key == LogicalKeyboardKey.backspace) {
      _hardwareBackspace();
      return KeyEventResult.handled;
    }

    final cursorKey = switch (key) {
      LogicalKeyboardKey.arrowUp => TerminalCursorKey.up,
      LogicalKeyboardKey.arrowDown => TerminalCursorKey.down,
      LogicalKeyboardKey.arrowLeft => TerminalCursorKey.left,
      LogicalKeyboardKey.arrowRight => TerminalCursorKey.right,
      LogicalKeyboardKey.home => TerminalCursorKey.home,
      LogicalKeyboardKey.end => TerminalCursorKey.end,
      LogicalKeyboardKey.pageUp => TerminalCursorKey.pageUp,
      LogicalKeyboardKey.pageDown => TerminalCursorKey.pageDown,
      _ => null,
    };
    if (cursorKey != null) {
      _sendNow(
        terminalCursorSequence(
          cursorKey,
          applicationCursorKeys: _buffer.applicationCursorKeys,
        ),
      );
      return KeyEventResult.handled;
    }
    // Everything else (printable text, IME) stays with the text field.
    return KeyEventResult.ignored;
  }

  Future<void> _paste() async {
    if (_status != _TerminalStatus.live) return;
    final data = await Clipboard.getData(Clipboard.kTextPlain);
    if (!mounted) return;
    final text = data?.text;
    if (text == null || text.isEmpty) {
      widget.onToast('剪贴板没有可粘贴的文本');
      return;
    }
    await _send(
      terminalPasteText(text, bracketedPaste: _buffer.bracketedPaste),
    );
  }

  Future<void> _copyAll() async {
    final text = _buffer.text;
    if (text.isEmpty) {
      widget.onToast('终端还没有输出');
      return;
    }
    await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) return;
    unawaited(HapticFeedback.selectionClick());
    widget.onToast('终端输出已复制');
  }

  void _clearOutput() {
    _sendNow('\x0c');
    _buffer.clear();
    _lastScrollbackLength = 0;
    _followBottom = true;
    setState(() {});
  }

  // -- build ---------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    final available = _info?.available ?? false;
    return PrismScaffold(
      bar: ClaudroidBar(
        isCompact: context.isInCompactSheet,
        listenable: _neverBar,
        loading: _neverLoading,
        leading: (context) => IconButton(
          onPressed: widget.onBack,
          icon: const GlyphIcon(Glyphs.arrowBack),
          tooltip: '返回',
        ),
        title: (context) => const Text('终端'),
        actions: [
          (context) => IconButton(
            onPressed: () => unawaited(_copyAll()),
            icon: const GlyphIcon(Glyphs.copy, size: 19),
            tooltip: '复制全部输出',
          ),
          (context) => IconButton(
            onPressed: _status == _TerminalStatus.live
                ? () => unawaited(_paste())
                : null,
            icon: const GlyphIcon(Glyphs.paste, size: 19),
            tooltip: '粘贴',
          ),
          (context) =>
              TextButton(onPressed: _clearOutput, child: const Text('清空')),
        ],
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _buildHeader(context),
          Expanded(
            child: ContentWidth(
              maxWidth: kWorkspaceContentWidth,
              child: _infoLoading
                  ? const _Loading()
                  : !available
                  ? _TerminalUnavailable(reason: _info?.issue)
                  : _buildCanvas(context),
            ),
          ),
          if (!_infoLoading && available) ...[
            if (_buildBanner(context) case final banner?)
              ContentWidth(
                maxWidth: kWorkspaceContentWidth,
                heightFactor: 1,
                alignment: Alignment.center,
                child: banner,
              ),
            ContentWidth(
              maxWidth: kWorkspaceContentWidth,
              heightFactor: 1,
              alignment: Alignment.center,
              child: _buildControls(context),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildHeader(BuildContext context) {
    final scheme = context.colorScheme;
    return Padding(
      padding: EdgeInsets.fromLTRB(
        kPageInset, context.contentTopPadding, kPageInset, 6),
      child: Row(
        children: [
          Expanded(
            child: Text(
              _statusLine,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: AppTextStyle.footnote
                  .of(context)
                  .copyWith(color: scheme.onSurfaceVariant),
            ),
          ),
          const SizedBox(width: 8),
          Text(
            '${_buffer.columns}×${_buffer.rows}',
            style: AppTextStyle.caption2
                .of(context)
                .copyWith(
                  color: scheme.onSurfaceVariant.withValues(alpha: 0.7),
                ),
          ),
        ],
      ),
    );
  }

  String get _statusLine {
    if (_infoLoading) return '正在读取终端…';
    final available = _info?.available ?? false;
    if (!available) return _info?.issue ?? '终端不可用';
    final label = _info?.label?.trim() ?? '';
    final shell = label.isEmpty ? '持久 shell · /work' : '持久 shell · $label';
    return switch (_status) {
      _TerminalStatus.connecting => '正在连接…',
      _TerminalStatus.live => shell,
      _TerminalStatus.exited || _TerminalStatus.stopped => '连接已断开',
      _TerminalStatus.error => '连接失败',
    };
  }

  Widget _buildCanvas(BuildContext context) {
    final scheme = context.colorScheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 0, 12, 4),
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: scheme.surfaceContainerHigh,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(
            color: scheme.outlineVariant.withValues(alpha: 0.55),
          ),
        ),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(16),
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: _focusInput,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(12, 8, 8, 8),
              child: LayoutBuilder(
                builder: (context, constraints) {
                  return MediaQuery.withNoTextScaling(
                    child: _buildScreen(context, constraints),
                  );
                },
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildScreen(BuildContext context, BoxConstraints constraints) {
    final style = _terminalTextStyle(context);
    final cellWidth = _measureCellWidth(style);
    final columns = constraints.maxWidth.isFinite
        ? (constraints.maxWidth / cellWidth).floor().clamp(20, 500).toInt()
        : 80;
    final rows = constraints.maxHeight.isFinite
        ? (constraints.maxHeight / _terminalRowHeight)
              .floor()
              .clamp(5, 300)
              .toInt()
        : 24;
    _syncViewport(columns, rows);

    final total = _buffer.totalLines;
    final cursorAbsolute = _buffer.scrollbackLength + _buffer.cursorRow;
    final showCursor =
        _status == _TerminalStatus.live && _buffer.cursorVisible && _blinkOn;
    final showEmptyHint =
        _status == _TerminalStatus.live &&
        _buffer.scrollbackLength == 0 &&
        _buffer.cursorRow == 0 &&
        _buffer.cursorColumn == 0;

    return Stack(
      children: [
        if (total > 0)
          NotificationListener<ScrollNotification>(
            onNotification: _onScroll,
            child: ListView.builder(
              controller: _scroll,
              padding: const EdgeInsets.symmetric(vertical: 4),
              itemExtent: _terminalRowHeight,
              itemCount: total,
              itemBuilder: (context, index) => _buildRow(
                context,
                index,
                style,
                showCursor && index == cursorAbsolute,
              ),
            ),
          ),
        if (_status == _TerminalStatus.connecting)
          const Center(child: _ConnectingBadge()),
        if (showEmptyHint)
          Center(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 28),
              child: Text(
                '在 /work 中运行交互式 shell\n支持 top、vim 等全屏程序',
                textAlign: TextAlign.center,
                style: context.listSubtitleStyle,
              ),
            ),
          ),
      ],
    );
  }

  Widget _buildRow(
    BuildContext context,
    int index,
    TextStyle style,
    bool withCursor,
  ) {
    final spans = _spansFor(
      context,
      _buffer.rowAt(index),
      style,
      cursorColumn: withCursor ? _buffer.cursorColumn : -1,
    );
    if (spans.isEmpty) return const SizedBox.shrink();
    return SizedBox(
      height: _terminalRowHeight,
      child: RichText(
        textDirection: TextDirection.ltr,
        maxLines: 1,
        softWrap: false,
        overflow: TextOverflow.clip,
        text: TextSpan(children: spans),
      ),
    );
  }

  List<TextSpan> _spansFor(
    BuildContext context,
    List<TerminalCell> cells,
    TextStyle base, {
    required int cursorColumn,
  }) {
    var lastContent = -1;
    for (var c = cells.length - 1; c >= 0; c--) {
      final cell = cells[c];
      if (cell.isContinuation) continue;
      if (cell.char != ' ' || cell.bg != -1) {
        lastContent = c;
        break;
      }
    }
    final last = math.max(lastContent, cursorColumn);
    if (last < 0) return const <TextSpan>[];

    final scheme = context.colorScheme;
    final spans = <TextSpan>[];
    final buffer = StringBuffer();
    TerminalCell? run;
    var runCursor = false;

    void flush() {
      if (buffer.isEmpty) return;
      spans.add(
        TextSpan(
          text: buffer.toString(),
          style: _cellStyle(base, scheme, cell: run!, cursor: runCursor),
        ),
      );
      buffer.clear();
      run = null;
    }

    for (var c = 0; c <= last; c++) {
      final cell = cells[c];
      if (cell.isContinuation) continue;
      final isCursor = c == cursorColumn;
      if (run != null && (!run!.sameStyleAs(cell) || runCursor != isCursor)) {
        flush();
      }
      run = cell;
      runCursor = isCursor;
      buffer.write(cell.char);
    }
    flush();
    return spans;
  }

  TextStyle _cellStyle(
    TextStyle base,
    ColorScheme scheme, {
    required TerminalCell cell,
    required bool cursor,
  }) {
    final defaultBg = scheme.surfaceContainerHigh;
    var fg = cell.fg < 0
        ? scheme.onSurface
        : _ansiColor(cell.fg, scheme, bold: cell.bold);
    var bg = cell.bg < 0 ? null : _ansiColor(cell.bg, scheme);
    if (cell.reverse) {
      final previousFg = fg;
      fg = bg ?? defaultBg;
      bg = previousFg;
    }
    if (cell.dim) {
      fg = Color.alphaBlend(fg.withValues(alpha: 0.5), bg ?? defaultBg);
    }
    if (cursor) {
      final previousFg = fg;
      fg = bg ?? defaultBg;
      bg = previousFg;
    }
    return base.copyWith(
      color: fg,
      backgroundColor: bg,
      fontWeight: cell.bold ? FontWeight.w700 : null,
      fontStyle: cell.italic ? FontStyle.italic : null,
      decoration: cell.underline ? TextDecoration.underline : null,
      decorationColor: cell.underline ? fg : null,
    );
  }

  TextStyle _terminalTextStyle(BuildContext context) => AppTextStyle.custom(
    context,
    size: _terminalFontSize,
    leading: _terminalRowHeight,
  ).copyWith(fontFamily: 'monospace');

  double _measureCellWidth(TextStyle style) {
    final painter = TextPainter(
      text: TextSpan(text: 'M', style: style),
      textDirection: TextDirection.ltr,
      maxLines: 1,
    )..layout();
    final width = painter.width;
    painter.dispose();
    return width <= 0 ? _terminalFontSize * 0.6 : width;
  }

  Widget _buildControls(BuildContext context) {
    return Padding(
      padding: EdgeInsets.fromLTRB(12, 6, 12, 8 + context.bottomInset),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          _buildKeyRow(context),
          const SizedBox(height: 8),
          _buildInputCapsule(context),
        ],
      ),
    );
  }

  Widget _buildKeyRow(BuildContext context) {
    final scheme = context.colorScheme;
    final enabled = _status == _TerminalStatus.live;
    final keys = <Widget>[
      _TerminalKey(
        label: 'Esc',
        onPressed: enabled ? () => _sendNow('\x1b') : null,
      ),
      _TerminalKey(
        label: 'Tab',
        onPressed: enabled ? () => _sendNow('\t') : null,
      ),
      _TerminalKey(
        label: 'Ctrl',
        active: _ctrlArmed,
        onPressed: enabled ? _toggleCtrl : null,
      ),
      _arrowKey(
        context,
        enabled: enabled,
        turns: math.pi / 2,
        onPressed: () => _sendCursor(TerminalCursorKey.up),
      ),
      _arrowKey(
        context,
        enabled: enabled,
        turns: -math.pi / 2,
        onPressed: () => _sendCursor(TerminalCursorKey.down),
      ),
      _arrowKey(
        context,
        enabled: enabled,
        onPressed: () => _sendCursor(TerminalCursorKey.left),
      ),
      _arrowKey(
        context,
        enabled: enabled,
        turns: math.pi,
        onPressed: () => _sendCursor(TerminalCursorKey.right),
      ),
      _TerminalKey(
        label: '^C',
        onPressed: enabled ? () => _sendNow('\x03') : null,
      ),
      _TerminalKey(
        label: '^D',
        onPressed: enabled ? () => _sendNow('\x04') : null,
      ),
      _TerminalKey(
        label: '^L',
        onPressed: enabled ? () => _sendNow('\x0c') : null,
      ),
      _TerminalKey(
        label: '粘贴',
        onPressed: enabled ? () => unawaited(_paste()) : null,
      ),
    ];
    final row = SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.symmetric(horizontal: 4),
      child: Row(mainAxisSize: MainAxisSize.min, children: keys),
    );
    final body = SizedBox(height: 42, child: row);
    if (!LiquidGlassScope.of(context)) {
      return DecoratedBox(
        decoration: ShapeDecoration(
          color: scheme.surfaceContainerHigh,
          shape: AppShape.full,
        ),
        child: body,
      );
    }
    return LiquidGlass(
      style: LiquidGlassStyle.regular(scheme),
      borderRadius: AppRadius.full,
      child: body,
    );
  }

  Widget _arrowKey(
    BuildContext context, {
    required bool enabled,
    required VoidCallback onPressed,
    double turns = 0,
  }) {
    Widget icon = const GlyphIcon(Glyphs.arrowBack, size: 18);
    if (turns != 0) {
      icon = Transform.rotate(angle: turns, child: icon);
    }
    return _TerminalKey(onPressed: enabled ? onPressed : null, child: icon);
  }

  void _sendCursor(TerminalCursorKey key) {
    _sendNow(
      terminalCursorSequence(
        key,
        applicationCursorKeys: _buffer.applicationCursorKeys,
      ),
    );
  }

  Widget _buildInputCapsule(BuildContext context) {
    final scheme = context.colorScheme;
    final live = _status == _TerminalStatus.live;
    return Container(
      padding: const EdgeInsets.only(left: 14, right: 6),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerHigh,
        borderRadius: AppRadius.full,
      ),
      child: Row(
        children: [
          Text(
            '\$',
            style: _terminalTextStyle(
              context,
            ).copyWith(color: scheme.onSurfaceVariant),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: TextField(
              controller: _input,
              focusNode: _inputFocus,
              contextMenuBuilder: appEditableTextMenu,
              enabled: live,
              maxLines: 1,
              keyboardType: TextInputType.text,
              textInputAction: TextInputAction.send,
              style: _terminalTextStyle(
                context,
              ).copyWith(color: scheme.onSurface),
              cursorColor: scheme.primary,
              onChanged: _onInputChanged,
              onSubmitted: (_) => _submitLine(),
              decoration: InputDecoration(
                hintText: live ? '输入命令…' : '终端未连接',
                border: const NoInputBorder(),
                isDense: true,
                contentPadding: const EdgeInsets.symmetric(vertical: 12),
              ),
            ),
          ),
          const SizedBox(width: 4),
          IconButton(
            onPressed: live ? _submitLine : null,
            icon: const GlyphIcon(AppGlyphs.send, size: 18),
            tooltip: '发送回车',
          ),
        ],
      ),
    );
  }

  Widget? _buildBanner(BuildContext context) {
    final scheme = context.colorScheme;
    final (String message, String action, bool isError) = switch (_status) {
      _TerminalStatus.exited => ('终端已退出（退出码 ${_exitCode ?? 0}）', '重新连接', false),
      _TerminalStatus.stopped => ('终端已停止', '重新连接', false),
      _TerminalStatus.error => (_errorText ?? '终端连接失败，请重试。', '重试', true),
      _ => ('', '', false),
    };
    if (message.isEmpty) return null;
    return Container(
      margin: const EdgeInsets.fromLTRB(12, 2, 12, 4),
      padding: const EdgeInsets.fromLTRB(14, 6, 6, 6),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(16),
        border: isError
            ? Border.all(color: scheme.error.withValues(alpha: 0.45))
            : null,
      ),
      child: Row(
        children: [
          GlyphIcon(
            isError ? AppGlyphs.plug : AppGlyphs.terminal,
            size: 18,
            color: isError ? scheme.error : scheme.onSurfaceVariant,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              message,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: AppTextStyle.footnote
                  .of(context)
                  .copyWith(color: scheme.onSurface),
            ),
          ),
          TextButton(
            // `_reconnect` ignores a second tap while a start is in flight;
            // keeping the callback non-null avoids disabling the button inside
            // its own press (prism asserts on that).
            onPressed: _reconnect,
            child: Text(action),
          ),
        ],
      ),
    );
  }
}

class _TerminalKey extends StatelessWidget {
  const _TerminalKey({
    this.label,
    this.child,
    this.onPressed,
    this.active = false,
  });

  final String? label;
  final Widget? child;
  final VoidCallback? onPressed;
  final bool active;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final foreground = active ? scheme.primary : scheme.onSurface;
    final buttonStyle = ButtonStyle.from(
      foregroundColor: foreground,
      disabledForegroundColor: scheme.onSurfaceVariant.withValues(alpha: 0.38),
      backgroundColor: active && onPressed != null
          ? scheme.primaryContainer
          : null,
      minimumSize: const Size(40, 38),
      padding: const EdgeInsets.symmetric(horizontal: 10),
      textStyle: AppTextStyle.caption1
          .of(context)
          .copyWith(fontWeight: FontWeight.w600, letterSpacing: 0),
      tapTargetSize: TapTargetSize.shrinkWrap,
    );
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 1, vertical: 2),
      child: TextButton(
        onPressed: onPressed,
        style: buttonStyle,
        child:
            child ??
            Text(
              label ?? '',
              style: AppTextStyle.caption1
                  .of(context)
                  .copyWith(
                    color: foreground,
                    fontWeight: FontWeight.w600,
                    letterSpacing: 0,
                  ),
            ),
      ),
    );
  }
}

class _ConnectingBadge extends StatelessWidget {
  const _ConnectingBadge();

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    return DecoratedBox(
      decoration: ShapeDecoration(
        color: scheme.surfaceContainerHighest,
        shape: AppShape.full,
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            SizedBox(width: 36, child: LinearProgressIndicator(minHeight: 2)),
            const SizedBox(width: 10),
            Text(
              '正在连接…',
              style: AppTextStyle.footnote
                  .of(context)
                  .copyWith(color: scheme.onSurface),
            ),
          ],
        ),
      ),
    );
  }
}

class _TerminalUnavailable extends StatelessWidget {
  const _TerminalUnavailable({this.reason});

  final String? reason;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 36),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            GlyphIcon(
              AppGlyphs.sparkle,
              size: 34,
              color: context.colorScheme.primary,
            ),
            const SizedBox(height: 16),
            Text(
              reason ?? '引擎未安装，请先在设置中安装 Claude Code。',
              textAlign: TextAlign.center,
              style: AppTextStyle.headline.of(context),
            ),
            const SizedBox(height: 8),
            Text(
              '请在设置中安装引擎或切换引擎模式，然后重新打开终端。',
              textAlign: TextAlign.center,
              style: context.listSubtitleStyle,
            ),
          ],
        ),
      ),
    );
  }
}

class _Loading extends StatelessWidget {
  const _Loading();

  @override
  Widget build(BuildContext context) {
    return const Center(
      child: SizedBox(width: 44, child: LinearProgressIndicator(minHeight: 2)),
    );
  }
}

// ---------------------------------------------------------------------------
// Pure input helpers
// ---------------------------------------------------------------------------

/// The cursor key sequences a VT expects. The application mode (DECCKM,
/// `?1h`) uses the SS3 form; the normal mode uses CSI.
enum TerminalCursorKey { up, down, left, right, home, end, pageUp, pageDown }

String terminalCursorSequence(
  TerminalCursorKey key, {
  required bool applicationCursorKeys,
}) {
  final application = applicationCursorKeys;
  return switch (key) {
    TerminalCursorKey.up => application ? '\x1bOA' : '\x1b[A',
    TerminalCursorKey.down => application ? '\x1bOB' : '\x1b[B',
    TerminalCursorKey.right => application ? '\x1bOC' : '\x1b[C',
    TerminalCursorKey.left => application ? '\x1bOD' : '\x1b[D',
    TerminalCursorKey.home => application ? '\x1bOH' : '\x1b[H',
    TerminalCursorKey.end => application ? '\x1bOF' : '\x1b[F',
    TerminalCursorKey.pageUp => '\x1b[5~',
    TerminalCursorKey.pageDown => '\x1b[6~',
  };
}

/// The control byte Ctrl + [character] produces, or `null` when Ctrl does not
/// map that character. Activates for letters and the `[ \ ] ^ _` punctuation
/// block, plus the space bar (NUL).
int? terminalControlCode(String character) {
  if (character.isEmpty) return null;
  final rune = character.runes.first;
  if (rune >= 0x61 && rune <= 0x7a) return rune - 0x60;
  if (rune >= 0x41 && rune <= 0x5a) return rune - 0x40;
  return switch (rune) {
    0x5b => 0x1b, // [
    0x5c => 0x1c, // backslash
    0x5d => 0x1d, // ]
    0x5e => 0x1e, // ^
    0x5f => 0x1f, // _
    0x20 => 0x00, // space
    _ => null,
  };
}

/// Turns the edit from [previous] (already in the PTY) to [current] into the
/// bytes a readline shell understands: one DEL per removed character, then the
/// inserted text. Replacement and middle-of-line edits degrade to
/// "delete the rejected tail, retype the rest".
String terminalEditPayload(String previous, String current) {
  if (previous == current) return '';
  var prefix = 0;
  final limit = math.min(previous.length, current.length);
  while (prefix < limit &&
      previous.codeUnitAt(prefix) == current.codeUnitAt(prefix)) {
    prefix++;
  }
  final removed = previous.substring(prefix).runes.length;
  final inserted = current.substring(prefix);
  return '${'\x7f' * removed}$inserted';
}

/// Normalises a paste to carriage returns (the PTY's Enter) and wraps it in
/// bracketed-paste markers when the shell asked for them.
String terminalPasteText(String text, {required bool bracketedPaste}) {
  final normalized = text.replaceAll('\r\n', '\r').replaceAll('\n', '\r');
  if (!bracketedPaste || normalized.isEmpty) return normalized;
  return '\x1b[200~$normalized\x1b[201~';
}

/// True while the IME is composing; edits then must not be mirrored to the
/// PTY until the composition commits.
bool terminalIsComposing(TextEditingValue value) =>
    value.composing.isValid && !value.composing.isCollapsed;

// ---------------------------------------------------------------------------
// ANSI palette
// ---------------------------------------------------------------------------

/// ANSI colors tuned for the warm paper background. Bright variants are only
/// used for bold palette colors (0–7), as most terminals do.
const List<Color> _ansiLight = [
  Color(0xFF3B322B),
  Color(0xFFB3402F),
  Color(0xFF4F7A3F),
  Color(0xFF8A6516),
  Color(0xFF3F6594),
  Color(0xFF8A4F7D),
  Color(0xFF2F7A78),
  Color(0xFF8C8175),
  Color(0xFF6A5E52),
  Color(0xFFD24E3A),
  Color(0xFF5E8F4A),
  Color(0xFFA97E1F),
  Color(0xFF4C7AAE),
  Color(0xFFA25F92),
  Color(0xFF3D918F),
  Color(0xFF2D2520),
];

/// ANSI colors for the dark paper surfaces; the same hues lifted so every
/// entry keeps body-size contrast.
const List<Color> _ansiDark = [
  Color(0xFF4A4038),
  Color(0xFFE06C5B),
  Color(0xFF8FBC6E),
  Color(0xFFD8B060),
  Color(0xFF7FA6D6),
  Color(0xFFC98FBB),
  Color(0xFF6FC1BD),
  Color(0xFFC9BFB3),
  Color(0xFF6B5F53),
  Color(0xFFF07F6E),
  Color(0xFFA6CF82),
  Color(0xFFE6C577),
  Color(0xFF93B9E8),
  Color(0xFFD9A2CB),
  Color(0xFF85D2CE),
  Color(0xFFF2EAE0),
];

Color _ansiColor(int value, ColorScheme scheme, {bool bold = false}) {
  if (value < 0) return scheme.onSurface;
  if (value >= 0x1000000) {
    return Color(0xFF000000 | (value & 0xFFFFFF));
  }
  if (value < 16) {
    final index = bold && value < 8 ? value + 8 : value;
    final palette = scheme.brightness == Brightness.dark
        ? _ansiDark
        : _ansiLight;
    return palette[index];
  }
  if (value < 232) {
    final cube = value - 16;
    final r = cube ~/ 36;
    final g = (cube % 36) ~/ 6;
    final b = cube % 6;
    int level(int step) => step == 0 ? 0 : 55 + step * 40;
    return Color.fromARGB(255, level(r), level(g), level(b));
  }
  final grey = 8 + (value - 232) * 10;
  return Color.fromARGB(255, grey, grey, grey);
}

// ---------------------------------------------------------------------------
// Static bar listenables
// ---------------------------------------------------------------------------

final Listenable _neverBar = _NeverNotifier();
final ValueListenable<bool> _neverLoading = _NeverValue();

class _NeverNotifier extends ChangeNotifier {}

class _NeverValue implements ValueListenable<bool> {
  const _NeverValue();

  @override
  bool get value => false;

  @override
  void addListener(VoidCallback listener) {}

  @override
  void removeListener(VoidCallback listener) {}
}
