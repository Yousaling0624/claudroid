/// A pure-Dart VT screen model for the workspace's persistent shell.
///
/// Kotlin owns the PTY; this file only turns the bytes it forwards into a cell
/// grid the Flutter layer can paint. Nothing here depends on widgets, so the
/// parser, the alternate screen and the scrollback are all unit-testable.
library;

import 'dart:collection';
import 'dart:math' as math;

/// How many lines the main screen keeps above the viewport.
const int kTerminalScrollbackLines = 2000;

/// One character cell on the terminal grid.
///
/// A cell covers one column. The leading cell of a wide character (CJK, most
/// emoji) is followed by a [isContinuation] cell that paints nothing and keeps
/// the same attributes, so every row can stay exactly `columns` wide.
///
/// Colors are packed into a single `int` so the parser stays independent of
/// Flutter's theme:
///
/// - `-1`: the terminal default (foreground or background),
/// - `0..255`: a palette index; `0..15` are the classic ANSI colors,
///   `16..231` the 6×6×6 color cube and `232..255` the grey ramp,
/// - `>= 0x1000000`: 24-bit truecolor; the low 24 bits are `0xRRGGBB`.
class TerminalCell {
  const TerminalCell({
    this.char = ' ',
    this.fg = -1,
    this.bg = -1,
    this.bold = false,
    this.dim = false,
    this.italic = false,
    this.underline = false,
    this.reverse = false,
  });

  /// The empty cell every blank row is filled with; cells are immutable, so
  /// blank rows share it until something is written.
  static const TerminalCell blank = TerminalCell();

  final String char;
  final int fg;
  final int bg;
  final bool bold;
  final bool dim;
  final bool italic;
  final bool underline;
  final bool reverse;

  /// True for the second column of a wide character.
  bool get isContinuation => char.isEmpty;

  /// Whether the two cells paint with the same attributes. Used by the UI to
  /// merge a run of cells into one span.
  bool sameStyleAs(TerminalCell other) =>
      fg == other.fg &&
      bg == other.bg &&
      bold == other.bold &&
      dim == other.dim &&
      italic == other.italic &&
      underline == other.underline &&
      reverse == other.reverse;
}

/// A grid of [TerminalCell]s with the VT parser that feeds it.
///
/// The parser implements the subset a shell and full-screen programs need:
/// printable text, auto-wrap with pending-wrap semantics, the alternate screen
/// (`?1049`), cursor movement/clamping, erase/insert/delete, scroll regions and
/// SGR (16/256/truecolor). Unknown CSIs, OSC payloads and half-finished escape
/// sequences are either skipped or buffered until the next [feed].
class TerminalBuffer {
  TerminalBuffer({int columns = 80, int rows = 24}) {
    _columns = columns < 1 ? 1 : columns;
    _rows = rows < 1 ? 1 : rows;
    _screen = _blankGrid(_columns, _rows);
    _scrollBottom = _rows - 1;
  }

  late int _columns;
  late int _rows;
  late List<List<TerminalCell>> _screen;
  final List<List<TerminalCell>> _scrollback = <List<TerminalCell>>[];

  final _Pen _pen = _Pen();
  int _row = 0;
  int _column = 0;
  bool _pendingWrap = false;
  int _scrollTop = 0;
  late int _scrollBottom;

  bool _cursorVisible = true;
  bool _applicationCursorKeys = false;
  bool _bracketedPaste = false;

  bool _altActive = false;
  List<List<TerminalCell>>? _mainScreen;
  int _mainRow = 0;
  int _mainColumn = 0;
  bool _mainPendingWrap = false;
  int _mainScrollTop = 0;
  int _mainScrollBottom = 0;

  int _savedRow = 0;
  int _savedColumn = 0;
  bool _savedPendingWrap = false;
  _Pen? _savedPen;

  _ParseState _parseState = _ParseState.ground;
  final StringBuffer _csi = StringBuffer();
  int? _pendingHighSurrogate;
  _Charset _g0Charset = _Charset.ascii;
  _CharsetSlot _charsetSlot = _CharsetSlot.none;

  // -- public state --------------------------------------------------------

  int get columns => _columns;

  int get rows => _rows;

  int get cursorRow => _row;

  int get cursorColumn => _column;

  bool get cursorVisible => _cursorVisible;

  bool get applicationCursorKeys => _applicationCursorKeys;

  bool get bracketedPaste => _bracketedPaste;

  int get scrollbackLength => _scrollback.length;

  /// Scrollback rows followed by the visible screen rows.
  int get totalLines => _scrollback.length + _rows;

  /// The last [kTerminalScrollbackLines] lines that scrolled off the main
  /// screen, oldest first.
  List<TerminalCell> scrollbackRow(int index) {
    if (index < 0 || index >= _scrollback.length) {
      return const <TerminalCell>[];
    }
    return UnmodifiableListView<TerminalCell>(_scrollback[index]);
  }

  /// A row of the visible screen, `0` being the top line.
  List<TerminalCell> screenRow(int index) {
    if (index < 0 || index >= _rows) return const <TerminalCell>[];
    return UnmodifiableListView<TerminalCell>(_screen[index]);
  }

  /// [`scrollbackLength`] + [totalLines] addressing: the first rows are
  /// scrollback, then the screen. The UI renders this single list.
  List<TerminalCell> rowAt(int index) {
    if (index < 0 || index >= totalLines) return const <TerminalCell>[];
    final scrolled = _scrollback.length;
    return index < scrolled
        ? UnmodifiableListView<TerminalCell>(_scrollback[index])
        : UnmodifiableListView<TerminalCell>(_screen[index - scrolled]);
  }

  /// Scrollback plus screen as plain text, oldest line first. Trailing spaces
  /// are trimmed from every line and trailing blank lines are dropped, which
  /// is what "copy all output" wants.
  String get text => _renderLines().join('\n');

  /// [text] split into lines; empty when nothing was written.
  List<String> get lines => UnmodifiableListView<String>(_renderLines());

  // -- feeding -------------------------------------------------------------

  /// Consumes one chunk from the PTY. Escape sequences may split across calls;
  /// the parser keeps its state and buffers in flight.
  void feed(String text) {
    for (var i = 0; i < text.length; i++) {
      final code = text.codeUnitAt(i);
      if (_parseState == _ParseState.ground) {
        if (_pendingHighSurrogate != null) {
          if (code >= 0xDC00 && code <= 0xDFFF) {
            final high = _pendingHighSurrogate!;
            _pendingHighSurrogate = null;
            _printRune(0x10000 + ((high - 0xD800) << 10) + (code - 0xDC00));
            continue;
          }
          _pendingHighSurrogate = null;
        }
        if (code >= 0xD800 && code <= 0xDBFF) {
          _pendingHighSurrogate = code;
          continue;
        }
        _handleGround(code);
        continue;
      }
      switch (_parseState) {
        case _ParseState.escape:
          _handleEscape(code);
        case _ParseState.escapeCharset:
          // The byte after ESC ( / ) / # / % is a designation. Only G0 is
          // remembered; G1..G3 are consumed but never invoked (no SO/SI).
          if (code == 0x1b) {
            _parseState = _ParseState.escape;
          } else {
            _designateCharset(code);
            _parseState = _ParseState.ground;
          }
        case _ParseState.csi:
          _handleCsiByte(code);
        case _ParseState.osc:
          if (code == 0x07) {
            _parseState = _ParseState.ground;
          } else if (code == 0x1b) {
            _parseState = _ParseState.oscEscape;
          }
        case _ParseState.oscEscape:
          if (code == 0x5c) {
            _parseState = _ParseState.ground;
          } else {
            _parseState = _ParseState.escape;
            _handleEscape(code);
          }
        case _ParseState.ground:
          break;
      }
    }
  }

  /// Resizes the grid in place. The cursor and the scroll region are clamped
  /// into the new bounds; rows are padded or truncated on the right, and the
  /// bottom rows are dropped when the screen gets shorter.
  void resize({required int columns, required int rows}) {
    final newColumns = math.max(1, columns);
    final newRows = math.max(1, rows);
    if (newColumns == _columns && newRows == _rows) return;

    _screen = _resizeGrid(_screen, newColumns, newRows);
    final main = _mainScreen;
    if (main != null) _mainScreen = _resizeGrid(main, newColumns, newRows);
    for (var i = 0; i < _scrollback.length; i++) {
      _scrollback[i] = _resizeRow(_scrollback[i], newColumns);
    }

    _columns = newColumns;
    _rows = newRows;
    _row = _clampInt(_row, 0, _rows - 1);
    _column = _clampInt(_column, 0, _columns - 1);
    _mainRow = _clampInt(_mainRow, 0, _rows - 1);
    _mainColumn = _clampInt(_mainColumn, 0, _columns - 1);
    _savedRow = _clampInt(_savedRow, 0, _rows - 1);
    _savedColumn = _clampInt(_savedColumn, 0, _columns - 1);
    _scrollTop = _clampInt(_scrollTop, 0, _rows - 1);
    _scrollBottom = _clampInt(_scrollBottom, 0, _rows - 1);
    if (_scrollTop >= _scrollBottom) {
      _scrollTop = 0;
      _scrollBottom = _rows - 1;
    }
    _pendingWrap = false;
    _mainPendingWrap = false;
  }

  /// Clears the visible screen and the scrollback, as the local "clear"
  /// action does.
  void clear() {
    _screen = _blankGrid(_columns, _rows);
    _scrollback.clear();
    _row = 0;
    _column = 0;
    _pendingWrap = false;
    _scrollTop = 0;
    _scrollBottom = _rows - 1;
    if (_altActive) {
      // The main screen is still there behind a full-screen program; clearing
      // the terminal must clear that history too once the program exits.
      _mainScreen = _blankGrid(_columns, _rows);
      _mainRow = 0;
      _mainColumn = 0;
      _mainPendingWrap = false;
      _mainScrollTop = 0;
      _mainScrollBottom = _rows - 1;
    }
    _parseState = _ParseState.ground;
    _csi.clear();
    _pendingHighSurrogate = null;
    _charsetSlot = _CharsetSlot.none;
  }

  /// Full reset (RIS): drops both screens, modes and the current pen.
  void reset() {
    _altActive = false;
    _mainScreen = null;
    _screen = _blankGrid(_columns, _rows);
    _scrollback.clear();
    _row = 0;
    _column = 0;
    _pendingWrap = false;
    _scrollTop = 0;
    _scrollBottom = _rows - 1;
    _pen.reset();
    _cursorVisible = true;
    _applicationCursorKeys = false;
    _bracketedPaste = false;
    _savedPen = null;
    _savedRow = 0;
    _savedColumn = 0;
    _savedPendingWrap = false;
    _parseState = _ParseState.ground;
    _csi.clear();
    _pendingHighSurrogate = null;
    _g0Charset = _Charset.ascii;
    _charsetSlot = _CharsetSlot.none;
  }

  /// Drops transient state before a new shell takes over while keeping the
  /// visible history: an alternate screen left behind by a crashed full-screen
  /// program, the input modes it set, and any half-finished escape sequence
  /// that would otherwise swallow the new prompt.
  void prepareForNewSession() {
    _parseState = _ParseState.ground;
    _csi.clear();
    _pendingHighSurrogate = null;
    _g0Charset = _Charset.ascii;
    _charsetSlot = _CharsetSlot.none;
    if (_altActive) _leaveAltScreen();
    _cursorVisible = true;
    _applicationCursorKeys = false;
    _bracketedPaste = false;
    _pendingWrap = false;
    _pen.reset();
  }

  // -- ground state --------------------------------------------------------

  void _handleGround(int code) {
    switch (code) {
      case 0x1b:
        _parseState = _ParseState.escape;
        _csi.clear();
      case 0x9b:
        _parseState = _ParseState.csi;
        _csi.clear();
      case 0x9d:
        _parseState = _ParseState.osc;
      case 0x0d:
        _column = 0;
        _pendingWrap = false;
      case 0x0a:
      case 0x0b:
      case 0x0c:
        _lineFeed();
      case 0x08:
        _column = math.max(0, _column - 1);
        _pendingWrap = false;
      case 0x09:
        _tab();
      case 0x07:
        // BEL is visual noise on a phone; the pane does not ring.
        break;
      default:
        if (code < 0x20 || code == 0x7f) break;
        if (code >= 0xDC00 && code <= 0xDFFF) break;
        _printRune(code);
    }
  }

  void _handleEscape(int code) {
    switch (code) {
      case 0x1b:
        _parseState = _ParseState.escape;
        _csi.clear();
      case 0x5b: // [
        _csi.clear();
        _parseState = _ParseState.csi;
      case 0x5d: // ]
      case 0x50: // P (DCS)
      case 0x58: // X (SOS)
      case 0x5e: // ^ (PM)
      case 0x5f: // _ (APC)
        _parseState = _ParseState.osc;
      case 0x28: // ( G0
        _charsetSlot = _CharsetSlot.g0;
        _parseState = _ParseState.escapeCharset;
      case 0x29: // ) G1
        _charsetSlot = _CharsetSlot.g1;
        _parseState = _ParseState.escapeCharset;
      case 0x2a:
      case 0x2b:
      case 0x2d:
      case 0x2e:
      case 0x2f:
      case 0x23:
      case 0x25:
        // G2/G3 and the other designators only need their byte consumed.
        _charsetSlot = _CharsetSlot.none;
        _parseState = _ParseState.escapeCharset;
      case 0x37: // 7
        _saveCursor();
        _parseState = _ParseState.ground;
      case 0x38: // 8
        _restoreCursor();
        _parseState = _ParseState.ground;
      case 0x44: // D index
        _lineFeed();
        _parseState = _ParseState.ground;
      case 0x45: // E next line
        _column = 0;
        _lineFeed();
        _parseState = _ParseState.ground;
      case 0x4d: // M reverse index
        _reverseIndex();
        _parseState = _ParseState.ground;
      case 0x63: // c full reset
        reset();
        _parseState = _ParseState.ground;
      default:
        _parseState = _ParseState.ground;
    }
  }

  /// Applies the byte after `ESC (` / `ESC )` / … Only the G0 designator is
  /// rendered: `0` selects DEC Special Graphics (the line-drawing set dialog
  /// boxes are built from) and everything else falls back to ASCII.
  void _designateCharset(int designator) {
    final slot = _charsetSlot;
    _charsetSlot = _CharsetSlot.none;
    if (slot != _CharsetSlot.g0) return;
    _g0Charset = designator == 0x30
        ? _Charset.decSpecialGraphics
        : _Charset.ascii;
  }

  void _handleCsiByte(int code) {
    if (code == 0x1b) {
      _csi.clear();
      _parseState = _ParseState.escape;
      return;
    }
    if (code == 0x18 || code == 0x1a) {
      // CAN / SUB abort the sequence.
      _csi.clear();
      _parseState = _ParseState.ground;
      return;
    }
    if (code >= 0x30 && code <= 0x3f) {
      _csi.writeCharCode(code);
      return;
    }
    if (code >= 0x40 && code <= 0x7e) {
      _dispatchCsi(code);
    }
    // Intermediates (0x20..0x2f) and C0 controls inside a CSI are dropped.
  }

  // -- cursor and screen operations ---------------------------------------

  void _moveTo(int row, int column) {
    _row = _clampInt(row, 0, _rows - 1);
    _column = _clampInt(column, 0, _columns - 1);
    _pendingWrap = false;
  }

  void _lineFeed() {
    _pendingWrap = false;
    if (_row == _scrollBottom) {
      _scrollUp(1);
    } else if (_row < _rows - 1) {
      _row++;
    }
  }

  void _reverseIndex() {
    _pendingWrap = false;
    if (_row == _scrollTop) {
      _scrollDown(1);
    } else if (_row > 0) {
      _row--;
    }
  }

  void _tab() {
    final next = ((_column ~/ 8) + 1) * 8;
    _column = next >= _columns ? _columns - 1 : next;
    _pendingWrap = false;
  }

  void _printRune(int rune) {
    rune = _translateCharset(rune);
    if (_pendingWrap) {
      _column = 0;
      _lineFeed();
    }
    var width = _runeWidth(rune);
    if (width == 0) {
      _combineRune(rune);
      return;
    }
    if (width > _columns) width = 1;
    if (width == 2 && _column == _columns - 1) {
      // A wide glyph does not fit in the last column; leave it blank and wrap.
      _screen[_row][_column] = _blankCell(penBackground: false);
      _column = 0;
      _lineFeed();
    }

    final cells = _screen[_row];
    _clearWideAt(_row, _column);
    cells[_column] = _pen.cell(String.fromCharCode(rune));
    if (width == 2) {
      _clearWideAt(_row, _column + 1);
      cells[_column + 1] = _continuationOf(cells[_column]);
    }

    final next = _column + width;
    if (next >= _columns) {
      _column = _columns - 1;
      _pendingWrap = true;
    } else {
      _column = next;
    }
  }

  /// Maps `0x5f..0x7e` while G0 is DEC Special Graphics. Every mapped glyph is
  /// one column wide, so boxes stay aligned with the grid.
  int _translateCharset(int rune) {
    if (_g0Charset != _Charset.decSpecialGraphics) return rune;
    if (rune < 0x5f || rune > 0x7e) return rune;
    return _decSpecialGraphics[rune - 0x5f];
  }

  void _combineRune(int rune) {
    var column = _pendingWrap ? _column : _column - 1;
    if (column < 0) return;
    final cells = _screen[_row];
    if (cells[column].isContinuation && column > 0) column--;
    final cell = cells[column];
    if (cell.isContinuation) return;
    cells[column] = TerminalCell(
      char: '${cell.char}${String.fromCharCode(rune)}',
      fg: cell.fg,
      bg: cell.bg,
      bold: cell.bold,
      dim: cell.dim,
      italic: cell.italic,
      underline: cell.underline,
      reverse: cell.reverse,
    );
  }

  void _saveCursor() {
    _savedRow = _row;
    _savedColumn = _column;
    _savedPendingWrap = _pendingWrap;
    _savedPen = _pen.copy();
  }

  void _restoreCursor() {
    final pen = _savedPen;
    if (pen == null) return;
    _row = _clampInt(_savedRow, 0, _rows - 1);
    _column = _clampInt(_savedColumn, 0, _columns - 1);
    _pendingWrap = _savedPendingWrap;
    _pen.copyFrom(pen);
  }

  // -- scrolling -----------------------------------------------------------

  void _scrollUp(int count) {
    final lines = math.min(count, _scrollBottom - _scrollTop + 1);
    for (var i = 0; i < lines; i++) {
      final removed = _screen.removeAt(_scrollTop);
      if (!_altActive && _scrollTop == 0) {
        _scrollback.add(removed);
        if (_scrollback.length > kTerminalScrollbackLines) {
          _scrollback.removeRange(
            0,
            _scrollback.length - kTerminalScrollbackLines,
          );
        }
      }
      _screen.insert(_scrollBottom, _blankRow(_columns));
    }
  }

  void _scrollDown(int count) {
    final lines = math.min(count, _scrollBottom - _scrollTop + 1);
    for (var i = 0; i < lines; i++) {
      _screen.removeAt(_scrollBottom);
      _screen.insert(_scrollTop, _blankRow(_columns));
    }
  }

  void _setScrollRegion(int top, int bottom) {
    final newTop = _clampInt(top, 0, _rows - 1);
    final newBottom = _clampInt(bottom, 0, _rows - 1);
    if (newTop >= newBottom) {
      _scrollTop = 0;
      _scrollBottom = _rows - 1;
    } else {
      _scrollTop = newTop;
      _scrollBottom = newBottom;
    }
    _moveTo(newTop, 0);
  }

  void _insertLines(int count) {
    if (_row < _scrollTop || _row > _scrollBottom) return;
    final lines = math.min(count, _scrollBottom - _row + 1);
    for (var r = _scrollBottom; r >= _row + lines; r--) {
      _screen[r] = _screen[r - lines];
    }
    for (var r = _row; r < _row + lines; r++) {
      _screen[r] = _blankRow(_columns);
    }
  }

  void _deleteLines(int count) {
    if (_row < _scrollTop || _row > _scrollBottom) return;
    final lines = math.min(count, _scrollBottom - _row + 1);
    for (var r = _row; r <= _scrollBottom - lines; r++) {
      _screen[r] = _screen[r + lines];
    }
    for (var r = _scrollBottom - lines + 1; r <= _scrollBottom; r++) {
      _screen[r] = _blankRow(_columns);
    }
  }

  // -- erase / insert / delete cells --------------------------------------

  void _eraseDisplay(int mode) {
    switch (mode) {
      case 0:
        _blankRange(_row, _column, _columns);
        for (var r = _row + 1; r < _rows; r++) {
          _blankRange(r, 0, _columns);
        }
      case 1:
        for (var r = 0; r < _row; r++) {
          _blankRange(r, 0, _columns);
        }
        _blankRange(_row, 0, _column + 1);
      case 2:
        for (var r = 0; r < _rows; r++) {
          _blankRange(r, 0, _columns);
        }
      case 3:
        for (var r = 0; r < _rows; r++) {
          _blankRange(r, 0, _columns);
        }
        _scrollback.clear();
      default:
        break;
    }
  }

  void _eraseLine(int mode) {
    switch (mode) {
      case 0:
        _blankRange(_row, _column, _columns);
      case 1:
        _blankRange(_row, 0, _column + 1);
      case 2:
        _blankRange(_row, 0, _columns);
      default:
        break;
    }
  }

  void _eraseCells(int count) {
    _blankRange(_row, _column, math.min(_columns, _column + count));
  }

  void _insertCells(int count) {
    final cells = _screen[_row];
    final amount = math.min(count, _columns - _column);
    for (var c = _columns - 1; c >= _column + amount; c--) {
      cells[c] = cells[c - amount];
    }
    for (var c = _column; c < _column + amount; c++) {
      cells[c] = _blankCell(penBackground: true);
    }
  }

  void _deleteCells(int count) {
    final cells = _screen[_row];
    final amount = math.min(count, _columns - _column);
    for (var c = _column; c < _columns - amount; c++) {
      cells[c] = cells[c + amount];
    }
    for (var c = _columns - amount; c < _columns; c++) {
      cells[c] = _blankCell(penBackground: true);
    }
  }

  void _blankRange(int row, int start, int endExclusive) {
    if (start >= endExclusive) return;
    final cells = _screen[row];
    final end = math.min(endExclusive, _columns);
    // A wide glyph cut by either boundary loses both halves.
    if (start > 0 && cells[start].isContinuation) {
      cells[start - 1] = _blankCell(penBackground: true);
    }
    if (end < _columns && cells[end].isContinuation) {
      cells[end] = _blankCell(penBackground: true);
    }
    for (var c = start; c < end; c++) {
      cells[c] = _blankCell(penBackground: true);
    }
  }

  void _clearWideAt(int row, int column) {
    final cells = _screen[row];
    final cell = cells[column];
    if (cell.isContinuation && column > 0) {
      cells[column - 1] = _blankCell(penBackground: false);
    } else if (!cell.isContinuation &&
        column + 1 < _columns &&
        cells[column + 1].isContinuation) {
      cells[column + 1] = _blankCell(penBackground: false);
    }
  }

  // -- CSI dispatch --------------------------------------------------------

  void _dispatchCsi(int finalCode) {
    final raw = _csi.toString();
    _csi.clear();
    _parseState = _ParseState.ground;

    var body = raw;
    var prefix = '';
    if (body.isNotEmpty) {
      final first = body.codeUnitAt(0);
      if (first == 0x3f || first == 0x3c || first == 0x3d || first == 0x3e) {
        prefix = String.fromCharCode(first);
        body = body.substring(1);
      }
    }
    // Modern SGR packs sub-parameters with colons; flattening them makes the
    // semicolon and colon forms share one parser.
    body = body.replaceAll(':', ';');
    final parts = body.isEmpty ? const <String>[] : body.split(';');

    int param(int index, int fallback) {
      if (index >= parts.length) return fallback;
      return int.tryParse(parts[index]) ?? fallback;
    }

    int amount(int index) => math.max(1, param(index, 1));

    switch (String.fromCharCode(finalCode)) {
      case 'A':
        _moveTo(_row - amount(0), _column);
      case 'B':
        _moveTo(_row + amount(0), _column);
      case 'C':
        _moveTo(_row, _column + amount(0));
      case 'D':
        _moveTo(_row, _column - amount(0));
      case 'E':
        _moveTo(_row + amount(0), 0);
      case 'F':
        _moveTo(_row - amount(0), 0);
      case 'G':
        _moveTo(_row, param(0, 1) - 1);
      case 'H':
      case 'f':
        _moveTo(param(0, 1) - 1, param(1, 1) - 1);
      case 'd':
        _moveTo(param(0, 1) - 1, _column);
      case 'J':
        _eraseDisplay(param(0, 0));
      case 'K':
        _eraseLine(param(0, 0));
      case 'X':
        _eraseCells(amount(0));
      case '@':
        _insertCells(amount(0));
      case 'P':
        _deleteCells(amount(0));
      case 'L':
        _insertLines(amount(0));
      case 'M':
        _deleteLines(amount(0));
      case 'S':
        _scrollUp(amount(0));
      case 'T':
        _scrollDown(amount(0));
      case 'r':
        _setScrollRegion(param(0, 1) - 1, param(1, _rows) - 1);
      case 'm':
        _applySgr(parts);
      case 'h':
        if (prefix == '?') _setPrivateModes(parts, true);
      case 'l':
        if (prefix == '?') _setPrivateModes(parts, false);
      case 's':
        _saveCursor();
      case 'u':
        _restoreCursor();
      default:
        // Cursor reports, device attributes, window ops: nothing to render.
        break;
    }
  }

  void _setPrivateModes(List<String> parts, bool enabled) {
    for (final part in parts) {
      switch (int.tryParse(part)) {
        case 1:
          _applicationCursorKeys = enabled;
        case 25:
          _cursorVisible = enabled;
        case 47:
        case 1047:
        case 1049:
          if (enabled) {
            _enterAltScreen();
          } else {
            _leaveAltScreen();
          }
        case 2004:
          _bracketedPaste = enabled;
        default:
          break;
      }
    }
  }

  void _enterAltScreen() {
    if (_altActive) return;
    _mainScreen = _screen;
    _mainRow = _row;
    _mainColumn = _column;
    _mainPendingWrap = _pendingWrap;
    _mainScrollTop = _scrollTop;
    _mainScrollBottom = _scrollBottom;
    _screen = _blankGrid(_columns, _rows);
    _row = 0;
    _column = 0;
    _pendingWrap = false;
    _scrollTop = 0;
    _scrollBottom = _rows - 1;
    _altActive = true;
  }

  void _leaveAltScreen() {
    if (!_altActive) return;
    final main = _mainScreen;
    _screen = main ?? _blankGrid(_columns, _rows);
    _mainScreen = null;
    _altActive = false;
    _row = _clampInt(_mainRow, 0, _rows - 1);
    _column = _clampInt(_mainColumn, 0, _columns - 1);
    _pendingWrap = _mainPendingWrap;
    _scrollTop = _clampInt(_mainScrollTop, 0, _rows - 1);
    _scrollBottom = _clampInt(_mainScrollBottom, 0, _rows - 1);
    if (_scrollTop >= _scrollBottom) {
      _scrollTop = 0;
      _scrollBottom = _rows - 1;
    }
  }

  // -- SGR -----------------------------------------------------------------

  void _applySgr(List<String> parts) {
    if (parts.isEmpty) {
      _pen.reset();
      return;
    }
    final params = <int?>[for (final part in parts) int.tryParse(part)];
    var i = 0;
    while (i < params.length) {
      final code = params[i] ?? 0;
      if (code == 0) {
        _pen.reset();
      } else if (code == 1) {
        _pen.bold = true;
      } else if (code == 2) {
        _pen.dim = true;
      } else if (code == 3) {
        _pen.italic = true;
      } else if (code == 4) {
        _pen.underline = true;
      } else if (code == 7) {
        _pen.reverse = true;
      } else if (code == 22) {
        _pen.bold = false;
        _pen.dim = false;
      } else if (code == 23) {
        _pen.italic = false;
      } else if (code == 24) {
        _pen.underline = false;
      } else if (code == 27) {
        _pen.reverse = false;
      } else if (code >= 30 && code <= 37) {
        _pen.fg = code - 30;
      } else if (code == 39) {
        _pen.fg = -1;
      } else if (code >= 40 && code <= 47) {
        _pen.bg = code - 40;
      } else if (code == 49) {
        _pen.bg = -1;
      } else if (code >= 90 && code <= 97) {
        _pen.fg = code - 90 + 8;
      } else if (code >= 100 && code <= 107) {
        _pen.bg = code - 100 + 8;
      } else if (code == 38) {
        i = _extendedColor(params, i, foreground: true);
      } else if (code == 48) {
        i = _extendedColor(params, i, foreground: false);
      }
      i++;
    }
  }

  /// Consumes `38;5;n` / `38;2;r;g;b` (and their colon variants) and returns
  /// the index of the last parameter it used.
  int _extendedColor(List<int?> params, int start, {required bool foreground}) {
    if (start + 1 >= params.length) return params.length;
    final mode = params[start + 1];
    if (mode == 5) {
      if (start + 2 < params.length) {
        final index = _byte(params[start + 2]);
        if (foreground) {
          _pen.fg = index;
        } else {
          _pen.bg = index;
        }
      }
      return start + 2;
    }
    if (mode == 2) {
      var cursor = start + 2;
      // `38:2::r:g:b` has an empty colour-space slot after the mode.
      if (cursor < params.length && params[cursor] == null) cursor++;
      if (cursor + 2 >= params.length) return params.length;
      final color =
          0x1000000 |
          (_byte(params[cursor]) << 16) |
          (_byte(params[cursor + 1]) << 8) |
          _byte(params[cursor + 2]);
      if (foreground) {
        _pen.fg = color;
      } else {
        _pen.bg = color;
      }
      return cursor + 2;
    }
    return params.length;
  }

  // -- helpers -------------------------------------------------------------

  /// DEC Special Graphics for `0x5f..0x7e`: the line-drawing set dialog and
  /// ncurses use for boxes, mapped to Unicode glyphs every font can paint.
  static const List<int> _decSpecialGraphics = <int>[
    0x0020,
    0x25c6,
    0x2592,
    0x2409,
    0x240c,
    0x240d,
    0x240a,
    0x00b0,
    0x00b1,
    0x2424,
    0x240b,
    0x2518,
    0x2510,
    0x250c,
    0x2514,
    0x253c,
    0x23ba,
    0x23bb,
    0x2500,
    0x23bc,
    0x23bd,
    0x251c,
    0x2524,
    0x2534,
    0x252c,
    0x2502,
    0x2264,
    0x2265,
    0x03c0,
    0x2260,
    0x00a3,
    0x00b7,
  ];

  TerminalCell _blankCell({required bool penBackground}) {
    if (!penBackground || _pen.bg < 0) return TerminalCell.blank;
    return TerminalCell(bg: _pen.bg);
  }

  static TerminalCell _continuationOf(TerminalCell lead) => TerminalCell(
    char: '',
    fg: lead.fg,
    bg: lead.bg,
    bold: lead.bold,
    dim: lead.dim,
    italic: lead.italic,
    underline: lead.underline,
    reverse: lead.reverse,
  );

  List<String> _renderLines() {
    final all = <String>[
      for (final row in _scrollback) _rowText(row),
      for (final row in _screen) _rowText(row),
    ];
    var end = all.length;
    while (end > 0 && all[end - 1].isEmpty) {
      end--;
    }
    return end == 0 ? const <String>[] : all.sublist(0, end);
  }

  static String _rowText(List<TerminalCell> row) {
    final buffer = StringBuffer();
    for (final cell in row) {
      if (!cell.isContinuation) buffer.write(cell.char);
    }
    final text = buffer.toString();
    var end = text.length;
    while (end > 0 && text.codeUnitAt(end - 1) == 0x20) {
      end--;
    }
    return end == text.length ? text : text.substring(0, end);
  }

  static List<TerminalCell> _blankRow(int columns) =>
      List<TerminalCell>.filled(columns, TerminalCell.blank);

  static List<List<TerminalCell>> _blankGrid(int columns, int rows) =>
      List<List<TerminalCell>>.generate(
        rows,
        (_) => _blankRow(columns),
        growable: true,
      );

  static List<List<TerminalCell>> _resizeGrid(
    List<List<TerminalCell>> grid,
    int columns,
    int rows,
  ) {
    final result = <List<TerminalCell>>[];
    for (var r = 0; r < rows; r++) {
      result.add(
        r < grid.length ? _resizeRow(grid[r], columns) : _blankRow(columns),
      );
    }
    return result;
  }

  static List<TerminalCell> _resizeRow(List<TerminalCell> row, int columns) {
    final result = _blankRow(columns);
    final take = math.min(row.length, columns);
    for (var c = 0; c < take; c++) {
      result[c] = row[c];
    }
    if (take > 0 && take < row.length && row[take].isContinuation) {
      // The kept last cell is the lead of a wide glyph whose tail was cut.
      result[take - 1] = TerminalCell.blank;
    }
    return result;
  }

  static int _clampInt(int value, int min, int max) =>
      value < min ? min : (value > max ? max : value);

  static int _byte(int? value) {
    if (value == null || value < 0) return 0;
    return value > 255 ? 255 : value;
  }

  static bool _isCombiningRune(int rune) =>
      (rune >= 0x0300 && rune <= 0x036f) ||
      (rune >= 0x1ab0 && rune <= 0x1aff) ||
      (rune >= 0x1dc0 && rune <= 0x1dff) ||
      (rune >= 0x20d0 && rune <= 0x20ff) ||
      (rune >= 0xfe20 && rune <= 0xfe2f);

  static bool _isWideRune(int rune) =>
      (rune >= 0x1100 && rune <= 0x115f) ||
      rune == 0x2329 ||
      rune == 0x232a ||
      (rune >= 0x2e80 && rune <= 0x303e) ||
      (rune >= 0x3041 && rune <= 0x33ff) ||
      (rune >= 0x3400 && rune <= 0x4dbf) ||
      (rune >= 0x4e00 && rune <= 0x9fff) ||
      (rune >= 0xa000 && rune <= 0xa4cf) ||
      (rune >= 0xa960 && rune <= 0xa97f) ||
      (rune >= 0xac00 && rune <= 0xd7a3) ||
      (rune >= 0xf900 && rune <= 0xfaff) ||
      (rune >= 0xfe10 && rune <= 0xfe19) ||
      (rune >= 0xfe30 && rune <= 0xfe6f) ||
      (rune >= 0xff00 && rune <= 0xff60) ||
      (rune >= 0xffe0 && rune <= 0xffe6) ||
      (rune >= 0x1f300 && rune <= 0x1faff) ||
      (rune >= 0x20000 && rune <= 0x3fffd);

  static int _runeWidth(int rune) {
    if (rune < 0x20 || rune == 0x7f) return 0;
    if (_isCombiningRune(rune)) return 0;
    return _isWideRune(rune) ? 2 : 1;
  }
}

enum _ParseState { ground, escape, escapeCharset, csi, osc, oscEscape }

enum _Charset { ascii, decSpecialGraphics }

/// Which designation slot `ESC (`, `ESC )`, … is about to fill.
enum _CharsetSlot { none, g0, g1 }

class _Pen {
  int fg = -1;
  int bg = -1;
  bool bold = false;
  bool dim = false;
  bool italic = false;
  bool underline = false;
  bool reverse = false;

  void reset() {
    fg = -1;
    bg = -1;
    bold = false;
    dim = false;
    italic = false;
    underline = false;
    reverse = false;
  }

  TerminalCell cell(String char) => TerminalCell(
    char: char,
    fg: fg,
    bg: bg,
    bold: bold,
    dim: dim,
    italic: italic,
    underline: underline,
    reverse: reverse,
  );

  _Pen copy() => _Pen()
    ..fg = fg
    ..bg = bg
    ..bold = bold
    ..dim = dim
    ..italic = italic
    ..underline = underline
    ..reverse = reverse;

  void copyFrom(_Pen other) {
    fg = other.fg;
    bg = other.bg;
    bold = other.bold;
    dim = other.dim;
    italic = other.italic;
    underline = other.underline;
    reverse = other.reverse;
  }
}
