import 'package:claudroid_ui/ui/terminal_buffer.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('text and cursor movement', () {
    test('prints, wraps on Enter and backspaces', () {
      final buffer = TerminalBuffer(columns: 10, rows: 3);
      buffer.feed('hello');
      expect(buffer.text, 'hello');
      expect(buffer.cursorRow, 0);
      expect(buffer.cursorColumn, 5);

      buffer.feed('\r\nworld');
      expect(buffer.text, 'hello\nworld');

      buffer.feed('\bX');
      expect(buffer.text, 'hello\nworlX');
      expect(buffer.cursorColumn, 5);
    });

    test('tab advances to the next eight-column stop', () {
      final buffer = TerminalBuffer(columns: 12, rows: 2);
      buffer.feed('\tA');
      expect(buffer.text, '        A');
      expect(buffer.cursorColumn, 9);
    });

    test(
      'auto-wrap keeps the cursor on the last column until the next char',
      () {
        final buffer = TerminalBuffer(columns: 5, rows: 3);
        buffer.feed('abcde');
        expect(buffer.cursorRow, 0);
        expect(buffer.cursorColumn, 4);

        buffer.feed('f');
        expect(buffer.text, 'abcde\nf');
        expect(buffer.cursorRow, 1);
        expect(buffer.cursorColumn, 1);

        // CR cancels pending wrap and overwrites the line.
        buffer.feed('\rX');
        expect(buffer.text, 'abcde\nX');
      },
    );

    test('CJK wide characters occupy two cells', () {
      final buffer = TerminalBuffer(columns: 8, rows: 2);
      buffer.feed('你好');
      expect(buffer.cursorColumn, 4);
      expect(buffer.text, '你好');

      final row = buffer.screenRow(0);
      expect(row[0].char, '你');
      expect(row[1].isContinuation, isTrue);
      expect(row[2].char, '好');
      expect(row[3].isContinuation, isTrue);
    });

    test('a wide character that does not fit wraps before it is written', () {
      final buffer = TerminalBuffer(columns: 3, rows: 2);
      buffer.feed('ab你');
      expect(buffer.text, 'ab\n你');
      expect(buffer.cursorRow, 1);
      expect(buffer.cursorColumn, 2);
    });
  });

  group('CSI', () {
    test('cursor addressing and erase in line/display', () {
      final buffer = TerminalBuffer(columns: 10, rows: 4);
      buffer.feed('abc\r\ndef\r\nghi');
      expect(buffer.text, 'abc\ndef\nghi');

      buffer.feed('\x1b[2;2H');
      expect(buffer.cursorRow, 1);
      expect(buffer.cursorColumn, 1);

      buffer.feed('\x1b[K');
      expect(buffer.text, 'abc\nd\nghi');

      buffer.feed('\x1b[H\x1b[2J');
      expect(buffer.text, '');
      expect(buffer.cursorRow, 0);
      expect(buffer.cursorColumn, 0);
    });

    test('relative movement, column addressing and erase cells', () {
      final buffer = TerminalBuffer(columns: 12, rows: 3);
      buffer.feed('abcdef');
      buffer.feed('\x1b[3G');
      expect(buffer.cursorColumn, 2);
      buffer.feed('\x1b[2X');
      expect(buffer.text, 'ab  ef');

      buffer.feed('\x1b[H\x1b[2C\x1b[1B');
      expect(buffer.cursorRow, 1);
      expect(buffer.cursorColumn, 2);

      buffer.feed('\x1b[1;1H\x1b[1E');
      expect(buffer.cursorRow, 1);
      expect(buffer.cursorColumn, 0);
    });

    test('inserts and deletes characters in place', () {
      final buffer = TerminalBuffer(columns: 12, rows: 2);
      buffer.feed('abcdef');
      buffer.feed('\x1b[3G\x1b[2@');
      expect(buffer.text, 'ab  cdef');
      buffer.feed('\x1b[2P');
      expect(buffer.text, 'abcdef');
    });

    test('inserts, deletes and scrolls lines', () {
      final buffer = TerminalBuffer(columns: 5, rows: 4);
      buffer.feed('1\r\n2\r\n3');
      buffer.feed('\x1b[1;1H\x1b[L');
      expect(buffer.text, '\n1\n2\n3');

      buffer.feed('\x1b[M');
      expect(buffer.text, '1\n2\n3');

      buffer.feed('\x1b[2S');
      expect(buffer.scrollbackLength, 2);
      expect(buffer.screenRow(0)[0].char, '3');
      expect(buffer.screenRow(1)[0].char, ' ');

      buffer.feed('\x1b[T');
      expect(buffer.screenRow(0)[0].char, ' ');
      expect(buffer.screenRow(1)[0].char, '3');
    });

    test('a scroll region scrolls only its own rows', () {
      final buffer = TerminalBuffer(columns: 5, rows: 4);
      buffer.feed('a\r\nb\r\nc\r\nd');
      buffer.feed('\x1b[2;3r\x1b[3;1H\n');
      expect(buffer.text, 'a\nc\n\nd');
    });

    test('scrollback keeps the last 2000 lines of the main screen', () {
      final buffer = TerminalBuffer(columns: 4, rows: 2);
      for (var i = 0; i < 2010; i++) {
        buffer.feed('x\r\n');
      }
      expect(buffer.scrollbackLength, kTerminalScrollbackLines);
      expect(buffer.scrollbackRow(0)[0].char, 'x');
      expect(buffer.text.split('\n').length, greaterThan(2000));
    });
  });

  group('SGR', () {
    test('bold, reset, palette, 256 and truecolor', () {
      final buffer = TerminalBuffer(columns: 16, rows: 2);
      buffer.feed('\x1b[1;31mA\x1b[0mB');
      final a = buffer.screenRow(0)[0];
      expect(a.char, 'A');
      expect(a.bold, isTrue);
      expect(a.fg, 1);

      final b = buffer.screenRow(0)[1];
      expect(b.char, 'B');
      expect(b.bold, isFalse);
      expect(b.fg, -1);

      buffer.feed('\x1b[38;5;196mC');
      expect(buffer.screenRow(0)[2].fg, 196);

      buffer.feed('\x1b[48;2;1;2;3mD');
      expect(buffer.screenRow(0)[3].bg, 0x1000000 | (1 << 16) | (2 << 8) | 3);

      buffer.feed('\x1b[38;2;255;100;0mE');
      expect(buffer.screenRow(0)[4].fg, 0x1000000 | (255 << 16) | (100 << 8));
    });

    test('dim, italic, underline and reverse toggle independently', () {
      final buffer = TerminalBuffer(columns: 8, rows: 2);
      buffer.feed('\x1b[2;3;4;7mX');
      var cell = buffer.screenRow(0)[0];
      expect(cell.dim, isTrue);
      expect(cell.italic, isTrue);
      expect(cell.underline, isTrue);
      expect(cell.reverse, isTrue);

      buffer.feed('\x1b[22;23;24;27mY');
      cell = buffer.screenRow(0)[1];
      expect(cell.dim, isFalse);
      expect(cell.italic, isFalse);
      expect(cell.underline, isFalse);
      expect(cell.reverse, isFalse);
    });

    test('colon-separated truecolor is accepted', () {
      final buffer = TerminalBuffer(columns: 8, rows: 2);
      buffer.feed('\x1b[38:2::10:20:30mX');
      expect(
        buffer.screenRow(0)[0].fg,
        0x1000000 | (10 << 16) | (20 << 8) | 30,
      );
    });
  });

  group('alternate screen and modes', () {
    test('?1049 switches screens and restores the main one', () {
      final buffer = TerminalBuffer(columns: 10, rows: 3);
      buffer.feed('main');
      buffer.feed('\x1b[?1049h');
      expect(buffer.text, '');
      buffer.feed('top');
      expect(buffer.text, 'top');
      buffer.feed('\x1b[?1049l');
      expect(buffer.text, 'main');
      expect(buffer.cursorColumn, 4);
    });

    test('cursor visibility, application keys and bracketed paste', () {
      final buffer = TerminalBuffer(columns: 10, rows: 3);
      expect(buffer.cursorVisible, isTrue);
      expect(buffer.applicationCursorKeys, isFalse);
      expect(buffer.bracketedPaste, isFalse);

      buffer.feed('\x1b[?25l\x1b[?1h\x1b[?2004h');
      expect(buffer.cursorVisible, isFalse);
      expect(buffer.applicationCursorKeys, isTrue);
      expect(buffer.bracketedPaste, isTrue);

      buffer.feed('\x1b[?25h\x1b[?1l\x1b[?2004l');
      expect(buffer.cursorVisible, isTrue);
      expect(buffer.applicationCursorKeys, isFalse);
      expect(buffer.bracketedPaste, isFalse);
    });

    test('prepareForNewSession recovers from a crashed full-screen app', () {
      final buffer = TerminalBuffer(columns: 10, rows: 3);
      buffer.feed('main');
      buffer.feed('\x1b[?1049h\x1b[?1h\x1b[?2004h\x1b[?25l');
      buffer.feed('top');
      buffer.prepareForNewSession();
      expect(buffer.text, 'main');
      expect(buffer.applicationCursorKeys, isFalse);
      expect(buffer.bracketedPaste, isFalse);
      expect(buffer.cursorVisible, isTrue);
    });

    test('clear inside the alternate screen also clears the main screen', () {
      final buffer = TerminalBuffer(columns: 8, rows: 2);
      buffer.feed('main');
      buffer.feed('\x1b[?1049h');
      buffer.feed('alt');
      buffer.clear();
      expect(buffer.text, '');
      buffer.feed('\x1b[?1049l');
      expect(buffer.text, '');
    });
  });

  group('parser robustness', () {
    test('an escape sequence split across feeds still applies', () {
      final buffer = TerminalBuffer(columns: 20, rows: 3);
      buffer.feed('a\x1b[3');
      buffer.feed('1mB');
      final row = buffer.screenRow(0);
      expect(row[0].char, 'a');
      expect(row[1].char, 'B');
      expect(row[1].fg, 1);
    });

    test('OSC payloads are skipped, including a split terminator', () {
      final buffer = TerminalBuffer(columns: 20, rows: 3);
      buffer.feed('\x1b]0;ti');
      buffer.feed('tle\x07ok');
      expect(buffer.text, 'ok');
    });

    test('unknown escape sequences are dropped without eating text', () {
      final buffer = TerminalBuffer(columns: 10, rows: 2);
      buffer.feed('\x1bZhi');
      expect(buffer.text, 'hi');
    });
  });

  group('DEC special graphics', () {
    test('ESC ( 0 turns the line-drawing range into box glyphs', () {
      final buffer = TerminalBuffer(columns: 10, rows: 3);
      buffer.feed('\x1b(0lqk');
      expect(buffer.text, '┌─┐');
      expect(buffer.cursorColumn, 3);
    });

    test('ESC ( B restores ASCII', () {
      final buffer = TerminalBuffer(columns: 10, rows: 3);
      buffer.feed('\x1b(0lqk\x1b(Blqk');
      expect(buffer.text, '┌─┐lqk');
    });

    test('a designation split across feeds is not printed', () {
      final buffer = TerminalBuffer(columns: 10, rows: 3);
      buffer.feed('\x1b(');
      expect(buffer.text, '');
      buffer.feed('0lqk');
      expect(buffer.text, '┌─┐');

      final second = TerminalBuffer(columns: 10, rows: 3);
      second.feed('\x1b');
      second.feed('(0lqk');
      expect(second.text, '┌─┐');
    });

    test('reset and a new session fall back to ASCII', () {
      final buffer = TerminalBuffer(columns: 10, rows: 3);
      buffer.feed('\x1b(0lqk');
      buffer.reset();
      buffer.feed('lqk');
      expect(buffer.text, 'lqk');

      buffer.feed('\r\n\x1b(0lqk');
      buffer.prepareForNewSession();
      buffer.feed('\r\nlqk');
      expect(buffer.lines.last, 'lqk');
    });

    test('maps every code in the 0x5f..0x7e range', () {
      final buffer = TerminalBuffer(columns: 40, rows: 2);
      buffer.feed('\x1b(0_`abcdefghijklmnopqrstuvwxyz{|}~');
      expect(buffer.text, ' ◆▒␉␌␍␊°±␤␋┘┐┌└┼⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·');
    });
  });

  group('resize and clear', () {
    test('resize clamps the cursor and keeps content', () {
      final buffer = TerminalBuffer(columns: 20, rows: 5);
      buffer.feed('x\x1b[5;20H');
      expect(buffer.cursorRow, 4);
      expect(buffer.cursorColumn, 19);

      buffer.resize(columns: 10, rows: 3);
      expect(buffer.columns, 10);
      expect(buffer.rows, 3);
      expect(buffer.cursorRow, 2);
      expect(buffer.cursorColumn, 9);
      expect(buffer.text, 'x');
    });

    test('resize also reshapes scrollback rows', () {
      final buffer = TerminalBuffer(columns: 8, rows: 2);
      for (var i = 0; i < 4; i++) {
        buffer.feed('line$i\r\n');
      }
      expect(buffer.scrollbackLength, greaterThan(0));
      buffer.resize(columns: 5, rows: 2);
      expect(buffer.scrollbackRow(0).length, 5);
      expect(buffer.screenRow(0).length, 5);
    });

    test('clear empties the screen and scrollback', () {
      final buffer = TerminalBuffer(columns: 8, rows: 3);
      buffer.feed('left');
      for (var i = 0; i < 5; i++) {
        buffer.feed('\r\n');
      }
      expect(buffer.scrollbackLength, greaterThan(0));

      buffer.clear();
      expect(buffer.text, '');
      expect(buffer.lines, isEmpty);
      expect(buffer.scrollbackLength, 0);
      expect(buffer.cursorRow, 0);
      expect(buffer.cursorColumn, 0);
    });
  });
}
