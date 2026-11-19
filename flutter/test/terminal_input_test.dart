import 'package:claudroid_ui/ui/terminal_page.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('input diffing', () {
    test('typing appends the new text verbatim', () {
      expect(terminalEditPayload('', 'ls'), 'ls');
      expect(terminalEditPayload('ls', 'ls -l'), ' -l');
      expect(terminalEditPayload('ls -l', 'ls -l'), '');
    });

    test('deleting sends one DEL per removed character', () {
      expect(terminalEditPayload('ls', 'l'), '\x7f');
      expect(terminalEditPayload('你好', ''), '\x7f\x7f');
    });

    test('replacement deletes the rejected tail and retypes the rest', () {
      expect(terminalEditPayload('abc', 'axc'), '\x7f\x7fxc');
      expect(terminalEditPayload('abc', 'ac'), '\x7f\x7fc');
    });
  });

  group('Ctrl mapping', () {
    test('letters map to 0x01..0x1a', () {
      expect(terminalControlCode('a'), 0x01);
      expect(terminalControlCode('c'), 0x03);
      expect(terminalControlCode('d'), 0x04);
      expect(terminalControlCode('l'), 0x0c);
      expect(terminalControlCode('z'), 0x1a);
      expect(terminalControlCode('A'), 0x01);
    });

    test('the punctuation block and space map too', () {
      expect(terminalControlCode('['), 0x1b);
      expect(terminalControlCode('\\'), 0x1c);
      expect(terminalControlCode(']'), 0x1d);
      expect(terminalControlCode('^'), 0x1e);
      expect(terminalControlCode('_'), 0x1f);
      expect(terminalControlCode(' '), 0x00);
    });

    test('digits and non-Latin text are not control codes', () {
      expect(terminalControlCode('1'), isNull);
      expect(terminalControlCode('你'), isNull);
      expect(terminalControlCode(''), isNull);
    });
  });

  test('IME composition blocks mirroring until it commits', () {
    expect(
      terminalIsComposing(
        const TextEditingValue(
          text: 'ni',
          composing: TextRange(start: 0, end: 2),
        ),
      ),
      isTrue,
    );
    expect(terminalIsComposing(const TextEditingValue(text: 'ni')), isFalse);
    expect(
      terminalIsComposing(
        const TextEditingValue(text: 'ni', composing: TextRange.empty),
      ),
      isFalse,
    );
  });

  group('paste', () {
    test('multi-line text becomes carriage returns', () {
      expect(terminalPasteText('a\nb', bracketedPaste: false), 'a\rb');
      expect(terminalPasteText('a\r\nb', bracketedPaste: false), 'a\rb');
    });

    test('bracketed paste wraps the payload', () {
      expect(
        terminalPasteText('a\nb', bracketedPaste: true),
        '\x1b[200~a\rb\x1b[201~',
      );
      expect(terminalPasteText('', bracketedPaste: true), '');
    });
  });

  group('cursor sequences', () {
    test('normal mode uses CSI', () {
      expect(
        terminalCursorSequence(
          TerminalCursorKey.up,
          applicationCursorKeys: false,
        ),
        '\x1b[A',
      );
      expect(
        terminalCursorSequence(
          TerminalCursorKey.left,
          applicationCursorKeys: false,
        ),
        '\x1b[D',
      );
      expect(
        terminalCursorSequence(
          TerminalCursorKey.home,
          applicationCursorKeys: false,
        ),
        '\x1b[H',
      );
    });

    test('application mode uses SS3', () {
      expect(
        terminalCursorSequence(
          TerminalCursorKey.down,
          applicationCursorKeys: true,
        ),
        '\x1bOB',
      );
      expect(
        terminalCursorSequence(
          TerminalCursorKey.right,
          applicationCursorKeys: true,
        ),
        '\x1bOC',
      );
      expect(
        terminalCursorSequence(
          TerminalCursorKey.end,
          applicationCursorKeys: true,
        ),
        '\x1bOF',
      );
    });

    test('paging keeps its fixed sequence', () {
      expect(
        terminalCursorSequence(
          TerminalCursorKey.pageUp,
          applicationCursorKeys: true,
        ),
        '\x1b[5~',
      );
      expect(
        terminalCursorSequence(
          TerminalCursorKey.pageDown,
          applicationCursorKeys: false,
        ),
        '\x1b[6~',
      );
    });
  });
}
