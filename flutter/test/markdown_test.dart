import 'package:claudroid_ui/ui/markdown.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

/// 与 `app/src/main/java/app/claudroid/ui/md/MarkdownTest.kt` 一一对应：
/// 断言的是纯文本，渲染样式留给界面本身，这样解析逻辑不需要 widget 环境。
void main() {
  const accent = Color(0xFFD97757);
  const codeBackground = Color(0xFF262624);

  List<MdBlock> blocks(String markdown) => Markdown.parse(
        markdown,
        accent: accent,
        codeBackground: codeBackground,
        codeColor: accent,
      );

  List<MdText> texts(String markdown) =>
      blocks(markdown).whereType<MdText>().toList();

  test('ordered and nested lists keep their markers and depth', () {
    final parsed = texts('1. 第一步\n2. 第二步\n  - 子项');

    expect(parsed, hasLength(3));
    expect(parsed[0].span.toPlainText(), '1.  第一步');
    expect(parsed[0].indent, 0);
    expect(parsed[2].span.toPlainText(), '•  子项');
    expect(parsed[2].indent, 1);
  });

  test('task lists become checkboxes', () {
    final parsed = texts('- [x] 完成\n- [ ] 待办');

    expect(
      [for (final block in parsed) block.span.toPlainText()],
      ['☑  完成', '☐  待办'],
    );
  });

  test('table requires a divider row', () {
    final parsed = blocks('| 项目 | 状态 |\n| --- | --- |\n| A | 完成 |');

    expect(parsed, hasLength(1));
    final table = parsed.single;
    expect(table, isA<MdTable>());
    table as MdTable;
    expect(table.headers, hasLength(2));
    expect(table.rows, hasLength(1));
    expect(table.rows.single[1].toPlainText(), '完成');

    // 没有分隔行的一行竖线文字只是正文，不能当表格。
    expect(blocks('只有 A | B 的一行文字').whereType<MdTable>(), isEmpty);
  });

  test('rules and quotes are separate blocks', () {
    final parsed = blocks('---\n> 重要说明');

    expect(parsed, hasLength(2));
    expect(parsed[0], isA<MdRule>());
    final quote = parsed[1];
    expect(quote, isA<MdText>());
    quote as MdText;
    expect(quote.quote, isTrue);
    expect(quote.kind, MdTextKind.quote);
  });

  test('inline formatting leaves readable text', () {
    final parsed = texts('普通 **粗体** 与 `代码`');

    expect(parsed, hasLength(1));
    expect(parsed.single.span.toPlainText(), '普通 粗体 与 代码');
  });

  test('emoji stay whole across inline spans', () {
    // 逐字符建 TextSpan 时，一个 emoji（UTF-16 代理对）会被劈成两个 span，
    // 排版器会把每个半个字符画成 U+FFFD。普通文字必须整段进同一个 span。
    const source = '你好 😀 世界 👨‍👩‍👧‍👦 **粗体 😀** 结束';
    final parsed = texts(source);

    expect(parsed, hasLength(1));
    expect(parsed.single.span.toPlainText(), '你好 😀 世界 👨‍👩‍👧‍👦 粗体 😀 结束');
    _visitSpans(parsed.single.span, (text) {
      expect(
        _hasLoneSurrogate(text),
        isFalse,
        reason: 'span splits a surrogate pair: $text',
      );
    });
  });

  test('short code stays unfolded', () {
    expect(CodeFold.isLong('val x = 1\nval y = 2'), isFalse);
  });

  test('more than eight lines folds', () {
    final code = List.generate(9, (i) => 'line $i').join('\n');

    expect(CodeFold.isLong(code), isTrue);
  });

  test('exactly eight lines does not fold', () {
    final code = List.generate(8, (i) => 'line $i').join('\n');

    expect(CodeFold.isLong(code), isFalse);
  });

  test('long single line folds on character count', () {
    expect(CodeFold.isLong('x' * 481), isTrue);
  });

  test('header meta reports line and character counts', () {
    expect(CodeFold.headerMeta('ab\ncd'), '2 行 · 5 字');
    expect(CodeFold.headerMeta(''), '0 行 · 0 字');
  });
}

void _visitSpans(InlineSpan span, void Function(String text) visit) {
  if (span is! TextSpan) return;
  final text = span.text;
  if (text != null) visit(text);
  for (final child in span.children ?? const <InlineSpan>[]) {
    _visitSpans(child, visit);
  }
}

bool _hasLoneSurrogate(String text) {
  for (var i = 0; i < text.length; i++) {
    final unit = text.codeUnitAt(i);
    if (unit >= 0xD800 && unit <= 0xDBFF) {
      final next = i + 1 < text.length ? text.codeUnitAt(i + 1) : -1;
      if (next < 0xDC00 || next > 0xDFFF) return true;
      i++;
    } else if (unit >= 0xDC00 && unit <= 0xDFFF) {
      return true;
    }
  }
  return false;
}
