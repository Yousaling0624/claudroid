import 'dart:async';

import 'package:flutter/gestures.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import 'palette.dart';
import 'typography.dart';

/// 刻意做小的 Markdown 渲染器：标题、有序/嵌套列表、任务列表、引用、围栏与行内代码、
/// 表格、分隔线、粗体/斜体/删除线与链接。聊天记录要靠这些才读得像 CLI 输出；
/// 不认识的语法一律当文本原样输出，绝不吞字。
///
/// 与 Compose 版唯一的刻意差异：块模型只带 [MdTextKind]，不带 TextStyle，
/// 因此 [Markdown.parse] 是纯函数，单测不需要 widget 环境。
sealed class MdBlock {
  const MdBlock();
}

enum MdTextKind { body, headingLarge, heading, headingSmall, headingTiny, quote }

class MdText extends MdBlock {
  const MdText(
    this.span, {
    this.kind = MdTextKind.body,
    this.quote = false,
    this.indent = 0,
    this.heading = false,
    this.list = false,
  });

  final TextSpan span;
  final MdTextKind kind;
  final bool quote;
  final int indent; // 0..4，每级 16dp
  final bool heading; // 只影响段前间距
  final bool list; // 列表项之间用更紧的间距，读起来才像一个整体
}

class MdCode extends MdBlock {
  const MdCode(this.code, this.lang);

  final String code;
  final String? lang;
}

class MdTable extends MdBlock {
  const MdTable({
    required this.headers,
    required this.rows,
    required this.widths,
  });

  final List<TextSpan> headers;
  final List<List<TextSpan>> rows;

  /// 估算的列宽（dp），表头与表体共用同一份，才能对齐。
  final List<int> widths;
}

class MdRule extends MdBlock {
  const MdRule();
}

/// 围栏代码块的折叠策略。纯函数，既能单测，也保证流式重组不会把块在
/// 折叠/展开之间来回翻。超过任一阈值（行数或字符数）就算「长」；短代码整段展示。
abstract final class CodeFold {
  static const int lineThreshold = 8;
  static const int charThreshold = 480;
  static const int collapsedLines = 6;

  static bool isLong(String code) {
    final lines = _lineCount(code);
    return lines > lineThreshold || code.length > charThreshold;
  }

  /// 代码块头部显示的「行 · 字」统计。
  static String headerMeta(String code) =>
      '${_lineCount(code)} 行 · ${code.length} 字';

  static int _lineCount(String code) =>
      '\n'.allMatches(code).length + (code.isEmpty ? 0 : 1);
}

/// 逐行状态机。保持纯函数（不接 BuildContext），颜色由调用方传入。
abstract final class Markdown {
  // Kotlin 的 `$` 只锚定行尾（允许末尾一个 \n），Dart 同；`matchEntire` 的语义
  // 在 Dart 里靠 firstMatch + 手动校验 start/end，见 [_matchEntire]。
  static final RegExp _listMarker =
      RegExp(r'^(\s*)([-*+]|\d{1,3}[.)])\s+(.*)$');
  static final RegExp _ruleLine = RegExp(r'^(?:-{3,}|\*{3,}|_{3,})$');
  static final RegExp _tableDivider = RegExp(r'^:?-{2,}:?$');

  static List<MdBlock> parse(
    String source, {
    required Color accent,
    required Color codeBackground,
    required Color codeColor,
    Color? markerColor,
    ValueChanged<String>? onLinkTap,
    List<TapGestureRecognizer>? recognizers,
  }) {
    final blocks = <MdBlock>[];
    final bulletColor = markerColor ?? accent;
    final lines = source.replaceAll('\r\n', '\n').split('\n');
    final paragraph = StringBuffer();
    String? fence;
    final code = StringBuffer();

    void flushParagraph() {
      if (paragraph.isEmpty) return;
      final text = paragraph.toString().trim();
      if (text.isNotEmpty) {
        blocks.add(
          MdText(
            inline(
              text,
              accent: accent,
              codeBackground: codeBackground,
              codeColor: codeColor,
              onLinkTap: onLinkTap,
              recognizers: recognizers,
            ),
          ),
        );
      }
      paragraph.clear();
    }

    var i = 0;
    while (i < lines.length) {
      final line = lines[i];
      final trimmed = line.trimLeft();
      if (fence != null) {
        if (trimmed.startsWith('```')) {
          blocks.add(MdCode(code.toString().trimRight(), fence));
          code.clear();
          fence = null;
        } else {
          code.write('$line\n');
        }
        i++;
        continue;
      }

      // 「竖线行 + |---|:--:| 分隔行」才是表格。先看下一行，才不会把正文里
      // 随便出现的 `|` 当成表格。
      if (trimmed.contains('|') && i + 1 < lines.length) {
        final header = _splitRow(trimmed);
        final divider = _splitRow(lines[i + 1]);
        if (header.length >= 2 &&
            divider.length >= 2 &&
            divider.every(_tableDivider.hasMatch)) {
          flushParagraph();
          final rows = <List<String>>[];
          var j = i + 2;
          while (j < lines.length &&
              lines[j].trim().isNotEmpty &&
              lines[j].contains('|')) {
            rows.add(_splitRow(lines[j]));
            j++;
          }
          final widths = _columnWidths(header, rows);
          blocks.add(
            MdTable(
              headers: [
                for (final cell in header)
                  inline(
                    cell,
                    accent: accent,
                    codeBackground: codeBackground,
                    codeColor: codeColor,
                    onLinkTap: onLinkTap,
                    recognizers: recognizers,
                  ),
              ],
              rows: [
                for (final row in rows)
                  [
                    for (final cell in row)
                      inline(
                        cell,
                        accent: accent,
                        codeBackground: codeBackground,
                        codeColor: codeColor,
                        onLinkTap: onLinkTap,
                        recognizers: recognizers,
                      ),
                  ],
              ],
              widths: widths,
            ),
          );
          i = j;
          continue;
        }
      }

      if (trimmed.startsWith('```')) {
        flushParagraph();
        final label = trimmed.substring(3).trim();
        final space = label.indexOf(' ');
        final word = space >= 0 ? label.substring(0, space) : label;
        fence = word.trim().isEmpty ? null : word.trim();
        i++;
        continue;
      }

      if (_ruleLine.hasMatch(trimmed)) {
        flushParagraph();
        blocks.add(const MdRule());
        i++;
        continue;
      }

      if (trimmed.startsWith('#')) {
        flushParagraph();
        var level = 0;
        while (level < trimmed.length && trimmed[level] == '#') {
          level++;
        }
        // 标题内容末尾的 `###` 也剥掉，`# 标题 #` 这种写法才不会把井号露出来。
        final content = trimmed
            .substring(level)
            .trim()
            .replaceAll(RegExp(r'#+$'), '')
            .trim();
        if (content.isEmpty) {
          // 只有井号的「标题」退回成普通段落行，不丢内容。
          paragraph.write('$line\n');
        } else {
          blocks.add(
            MdText(
              inline(
                content,
                accent: accent,
                codeBackground: codeBackground,
                codeColor: codeColor,
                onLinkTap: onLinkTap,
                recognizers: recognizers,
              ),
              kind: switch (level) {
                1 => MdTextKind.headingLarge,
                2 => MdTextKind.heading,
                3 => MdTextKind.headingSmall,
                _ => MdTextKind.headingTiny,
              },
              heading: true,
            ),
          );
        }
        i++;
        continue;
      }

      if (trimmed.startsWith('>')) {
        flushParagraph();
        final quote = StringBuffer();
        while (i < lines.length && lines[i].trimLeft().startsWith('>')) {
          if (quote.isNotEmpty) quote.write('\n');
          quote.write(lines[i].trimLeft().substring(1).trimLeft());
          i++;
        }
        final text = quote.toString().trim();
        if (text.isNotEmpty) {
          blocks.add(
            MdText(
              inline(
                text,
                accent: accent,
                codeBackground: codeBackground,
                codeColor: codeColor,
                onLinkTap: onLinkTap,
                recognizers: recognizers,
              ),
              kind: MdTextKind.quote,
              quote: true,
            ),
          );
        }
        continue;
      }

      final match = _matchEntire(_listMarker, line);
      if (match != null) {
        flushParagraph();
        final indent = match.group(1) ?? '';
        final marker = match.group(2) ?? '';
        var body = match.group(3) ?? '';
        // 制表符按两个空格算，再折半成层级，最多 4 级（再深屏幕也放不下）。
        final tabs = '\t'.allMatches(indent).length;
        final spaces = ' '.allMatches(indent).length;
        final depth = ((tabs * 2 + spaces) ~/ 2).clamp(0, 4).toInt();
        final String? checkbox;
        if (body.startsWith('[ ] ')) {
          checkbox = '☐';
        } else if (body.toLowerCase().startsWith('[x] ')) {
          checkbox = '☑';
        } else {
          checkbox = null;
        }
        if (checkbox != null) body = body.substring(4);
        final isDigitMarker = marker.isNotEmpty &&
            RegExp(r'\d').hasMatch(marker[0]);
        final prefix = checkbox ?? (isDigitMarker ? marker : '•');
        blocks.add(
          MdText(
            TextSpan(
              children: [
                TextSpan(
                  text: prefix,
                  style: TextStyle(
                    color: bulletColor,
                    fontWeight: FontWeight.w500,
                  ),
                ),
                const TextSpan(text: '  '),
                inline(
                  body,
                  accent: accent,
                  codeBackground: codeBackground,
                  codeColor: codeColor,
                  onLinkTap: onLinkTap,
                  recognizers: recognizers,
                ),
              ],
            ),
            indent: depth,
            list: true,
          ),
        );
        i++;
        continue;
      }

      if (line.trim().isEmpty) {
        flushParagraph();
        i++;
        continue;
      }

      if (paragraph.isNotEmpty) paragraph.write('\n');
      paragraph.write(line.trim());
      i++;
    }

    // 围栏没闭合（流式还在吐代码）时也要把已有内容显示出来。
    if (fence != null && code.isNotEmpty) {
      blocks.add(MdCode(code.toString().trimRight(), fence));
    }
    flushParagraph();
    return blocks;
  }

  /// 行内样式：单次从左到右扫描，标记不配对就原样吐出去。
  ///
  /// 刻意不接 [BuildContext]：单测要能在纯 Dart 里跑，且行内样式只依赖调用方
  /// 传入的三个颜色。字号走 `TextStyle` 字面量是因为 prism_ui 的
  /// `AppTextStyle.custom` 需要 context；行内片段最终会被 merge 到外层正文样式上。
  static TextSpan inline(
    String text, {
    required Color accent,
    required Color codeBackground,
    required Color codeColor,
    ValueChanged<String>? onLinkTap,
    List<TapGestureRecognizer>? recognizers,
  }) {
    final mono = _monoInline;
    final children = <TextSpan>[];
    final linkStyle = TextStyle(
      color: accent,
      decoration: TextDecoration.underline,
    );

    // 普通文字先攒起来整段输出：逐字符建 span 不只会把一屏正文拆成上千个
    // run，还会让代理对（emoji 等 BMP 外字符）落进两个相邻的 span 里，
    // 排版时每个半个字符都会变成 U+FFFD。
    final plain = StringBuffer();

    void flushPlain() {
      if (plain.isEmpty) return;
      children.add(TextSpan(text: plain.toString()));
      plain.clear();
    }

    void append(String value, [TextStyle? style]) {
      if (value.isEmpty) return;
      flushPlain();
      children.add(TextSpan(text: value, style: style));
    }

    void appendLink(String label, String url) {
      if (onLinkTap == null) {
        append(label, linkStyle);
        return;
      }
      final recognizer = TapGestureRecognizer()..onTap = () => onLinkTap(url);
      recognizers?.add(recognizer);
      children.add(
        TextSpan(text: label, style: linkStyle, recognizer: recognizer),
      );
    }

    var i = 0;
    while (i < text.length) {
      if (text.startsWith('`', i)) {
        final end = text.indexOf('`', i + 1);
        if (end > i) {
          append(
            text.substring(i + 1, end),
            mono.copyWith(backgroundColor: codeBackground, color: codeColor),
          );
          i = end + 1;
        } else {
          plain.write('`');
          i++;
        }
      } else if (text.startsWith('***', i)) {
        final end = text.indexOf('***', i + 3);
        if (end > i) {
          append(
            text.substring(i + 3, end),
            const TextStyle(
              fontWeight: FontWeight.bold,
              fontStyle: FontStyle.italic,
            ),
          );
          i = end + 3;
        } else {
          plain.write('***');
          i += 3;
        }
      } else if (text.startsWith('**', i)) {
        final end = text.indexOf('**', i + 2);
        if (end > i) {
          append(
            text.substring(i + 2, end),
            const TextStyle(fontWeight: FontWeight.bold),
          );
          i = end + 2;
        } else {
          plain.write('**');
          i += 2;
        }
      } else if (text.startsWith('~~', i)) {
        final end = text.indexOf('~~', i + 2);
        if (end > i) {
          append(
            text.substring(i + 2, end),
            const TextStyle(decoration: TextDecoration.lineThrough),
          );
          i = end + 2;
        } else {
          plain.write('~~');
          i += 2;
        }
      } else if (text[i] == '*') {
        final end = text.indexOf('*', i + 1);
        if (end > i) {
          append(
            text.substring(i + 1, end),
            const TextStyle(fontStyle: FontStyle.italic),
          );
          i = end + 1;
        } else {
          plain.write('*');
          i++;
        }
      } else if (text[i] == '[') {
        final close = text.indexOf(']', i);
        final open = close > 0 ? text.indexOf('(', close) : -1;
        final end = open == close + 1 ? text.indexOf(')', open) : -1;
        if (close > i && open == close + 1 && end > open) {
          appendLink(
            text.substring(i + 1, close),
            text.substring(open + 1, end),
          );
          i = end + 1;
        } else {
          plain.write('[');
          i++;
        }
      } else if (text.startsWith('http://', i) ||
          text.startsWith('https://', i)) {
        var end = i;
        while (end < text.length &&
            !_isWhitespace(text[end]) &&
            !'<>"\'）】'.contains(text[end])) {
          end++;
        }
        // 裸 URL 常紧跟句末标点，剥掉尾部的 `.,;:!?)]}>` 才不会被当成链接一部分。
        while (end > i && '.,;:!?)]}>'.contains(text[end - 1])) {
          end--;
        }
        appendLink(text.substring(i, end), text.substring(i, end));
        i = end;
      } else {
        plain.write(text[i]);
        i++;
      }
    }
    flushPlain();
    return TextSpan(children: children);
  }

  /// 行内代码的 mono 字号（14/21）。用常量避免每次扫描重建 TextStyle。
  static const TextStyle _monoInline =
      TextStyle(fontSize: 14, height: 21 / 14, fontFamily: 'monospace');
}

/// Kotlin 的 `matchEntire`：整行从头到尾都要匹配。Dart 的 `$` 会放过行尾换行，
/// 所以用 firstMatch 再校验跨度，而不是直接 `hasMatch`。
RegExpMatch? _matchEntire(RegExp pattern, String line) {
  final match = pattern.firstMatch(line);
  if (match == null || match.start != 0 || match.end != line.length) {
    return null;
  }
  return match;
}

List<String> _splitRow(String line) {
  var text = line.trim();
  if (text.startsWith('|')) text = text.substring(1);
  if (text.endsWith('|')) text = text.substring(0, text.length - 1);
  return [for (final cell in text.split('|')) cell.trim()];
}

List<int> _columnWidths(List<String> header, List<List<String>> rows) {
  return [
    for (var column = 0; column < header.length; column++)
      _columnWidth(column, header, rows),
  ];
}

int _columnWidth(int column, List<String> header, List<List<String>> rows) {
  // 与 Kotlin 一致：各行的最大可视宽度，缺列按空串算；没有任何行时退化成 6。
  var longest = 0;
  for (final row in [header, ...rows]) {
    final cell = column < row.length ? row[column] : '';
    final width = _visualWidth(cell);
    if (width > longest) longest = width;
  }
  if (longest == 0) longest = 6;
  return (longest * 6.5 + 24).round().clamp(78, 230).toInt();
}

/// 同字号下 CJK 字形大约占两个拉丁字符的宽度。按 code unit 遍历，与 Compose 一致。
int _visualWidth(String text) {
  var width = 0;
  for (final unit in text.codeUnits) {
    width += (unit >= 0x1100 && unit <= 0x115F) ||
            (unit >= 0x2E80 && unit <= 0xA4CF) ||
            (unit >= 0xAC00 && unit <= 0xD7A3) ||
            (unit >= 0xF900 && unit <= 0xFAFF) ||
            (unit >= 0xFE30 && unit <= 0xFE4F) ||
            (unit >= 0xFF00 && unit <= 0xFF60) ||
            (unit >= 0xFFE0 && unit <= 0xFFE6)
        ? 2
        : 1;
  }
  return width;
}

bool _isWhitespace(String char) => char == ' ' ||
    char == '\t' ||
    char == '\n' ||
    char == '\r' ||
    char == '\f' ||
    char == '\v';

/// 助手正文的 Markdown 渲染入口。解析是纯函数，样式在这里按 [MdTextKind] 解析，
/// 所以单测只测 [Markdown.parse] 就够。
///
/// 链接的 [TapGestureRecognizer] 由这个 State 持有：识别器一旦挂到 TextSpan
/// 就必须有人释放，而流式输出每帧都会重建 span。
class MarkdownText extends StatefulWidget {
  const MarkdownText(
    this.text, {
    super.key,
    this.streaming = false,
    this.serif = false,
    this.onLinkTap,
  });

  final String text;
  final bool streaming;

  /// Assistant prose uses the serif voice; workspace previews stay sans.
  final bool serif;

  /// Called with an http(s) URL when a markdown link or bare URL is tapped.
  final ValueChanged<String>? onLinkTap;

  @override
  State<MarkdownText> createState() => _MarkdownTextState();
}

class _MarkdownTextState extends State<MarkdownText> {
  List<TapGestureRecognizer> _recognizers = const [];

  // Parsing builds a TextSpan tree with gesture recognizers, so it is only
  // safe to reuse while the text, colours and callback are unchanged. This is
  // the common case: a streaming sibling rebuilds the page every frame, but
  // completed messages keep their parsed blocks.
  String? _parsedText;
  Color? _parsedAccent;
  Color? _parsedMarker;
  Color? _parsedCodeBackground;
  Color? _parsedCodeColor;
  ValueChanged<String>? _parsedOnLinkTap;
  bool? _parsedSerif;
  List<MdBlock>? _parsedBlocks;

  @override
  void dispose() {
    for (final recognizer in _recognizers) {
      recognizer.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final accent = linkColor(scheme);
    // 列表符号用次要文字色，不跟链接抢赤陶色；行内代码底比代码块浅一档，
    // 嵌在正文里不会像一块补丁。
    final markerColor = scheme.onSurfaceVariant;
    final codeBackground = scheme.surfaceContainerHigh;
    final codeColor = scheme.onSurface;
    final List<MdBlock> blocks;
    final cached = _parsedBlocks != null &&
        _parsedText == widget.text &&
        _parsedAccent == accent &&
        _parsedMarker == markerColor &&
        _parsedCodeBackground == codeBackground &&
        _parsedCodeColor == codeColor &&
        _parsedSerif == widget.serif &&
        _parsedOnLinkTap == widget.onLinkTap;
    if (cached) {
      blocks = _parsedBlocks!;
    } else {
      final recognizers = <TapGestureRecognizer>[];
      blocks = Markdown.parse(
        widget.text,
        accent: accent,
        markerColor: markerColor,
        codeBackground: codeBackground,
        codeColor: codeColor,
        onLinkTap: widget.onLinkTap,
        recognizers: recognizers,
      );
      // The spans from the previous parse may still be on screen until this
      // frame commits, so their recognizers are released after it.
      final previous = _recognizers;
      _recognizers = recognizers;
      if (previous.isNotEmpty) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          for (final recognizer in previous) {
            recognizer.dispose();
          }
        });
      }
      _parsedText = widget.text;
      _parsedAccent = accent;
      _parsedMarker = markerColor;
      _parsedCodeBackground = codeBackground;
      _parsedCodeColor = codeColor;
      _parsedSerif = widget.serif;
      _parsedOnLinkTap = widget.onLinkTap;
      _parsedBlocks = blocks;
    }
    // 光标只跟最后一个文字块：后面若还有工具块，光标仍停在那段正文上，
    // 与 Compose 的 lastTextIndex 语义一致。
    final lastText = blocks.lastIndexWhere((block) => block is MdText);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (var index = 0; index < blocks.length; index++)
          _buildBlock(context, blocks[index], index, lastText),
      ],
    );
  }

  Widget _buildBlock(BuildContext context, MdBlock block, int index, int lastText) {
    if (block is MdText) {
      return _buildText(
        context,
        block,
        index,
        widget.streaming && index == lastText,
      );
    }
    if (block is MdCode) {
      return Padding(
        padding: const EdgeInsets.only(top: 12),
        child: _CodeBlock(block.code, block.lang),
      );
    }
    if (block is MdTable) {
      return Padding(
        padding: const EdgeInsets.only(top: 12),
        child: _TableBlock(block),
      );
    }
    // MdRule：Kotlin 用 sand 色的 HorizontalDivider；prism_ui 的 Divider 没有
    // color 参数，所以这里画 1dp 的 outlineVariant 细线，颜色与 Compose 一致。
    return Padding(
      padding: const EdgeInsets.only(top: 16, bottom: 4),
      child: Container(
        height: 1,
        color: context.colorScheme.outlineVariant,
      ),
    );
  }

  Widget _buildText(BuildContext context, MdText block, int index, bool showCaret) {
    final scheme = context.colorScheme;
    final base = AppTextStyle.body.of(context).copyWith(
          color: scheme.onSurface,
          height: 1.5,
        );
    final styled = widget.serif ? claudroidSerif(base) : base;
    final span = showCaret ? _withCaret(block.span) : block.span;
    final child = SelectableText.rich(
      span,
      style: styled.merge(_styleFor(context, block.kind)),
    );

    // 段落之间留 9dp；列表项彼此只留 5dp，整组列表才不会被拆成散点。
    final top = index == 0
        ? 0.0
        : block.list
            ? 5.0
            : (block.heading ? 20.0 : 9.0);
    final left = block.indent * 16.0;
    if (block.quote) {
      // 竖条要贴满整块高度（含段前间距），所以边框放在外层，间距算进内层 padding。
      return Padding(
        padding: EdgeInsets.only(left: left),
        child: Container(
          decoration: BoxDecoration(
            border: Border(
              left: BorderSide(color: scheme.outlineVariant, width: 2),
            ),
          ),
          padding: EdgeInsets.only(left: 12, top: 2 + top, bottom: 2),
          child: child,
        ),
      );
    }
    return Padding(
      padding: EdgeInsets.only(top: top, left: left),
      child: child,
    );
  }

  /// 在末尾追加空格 + 光标块。整段正文作为子 span 嵌套而不是摊平，样式才能原样保留。
  /// WidgetSpan 里不能放手势组件：`SelectableText` 背后是只读 `EditableText`，
  /// 会把点击吞掉。
  TextSpan _withCaret(TextSpan span) => TextSpan(
        children: [
          span,
          const TextSpan(text: ' '),
          const WidgetSpan(
            alignment: PlaceholderAlignment.middle,
            child: _StreamingCaret(),
          ),
        ],
      );

  TextStyle _styleFor(BuildContext context, MdTextKind kind) => switch (kind) {
        MdTextKind.body => const TextStyle(),
        // 标题统一半粗、行距略紧；不再用 pageTitle 的 24/30 + w700，
        // 否则一段回答里出现两级标题时字号跳得太厉害。
        MdTextKind.headingLarge => AppTextStyle.custom(
            context,
            size: 22,
            leading: 29,
            weight: FontWeight.w600,
          ),
        MdTextKind.heading => AppTextStyle.custom(
            context,
            size: 20,
            leading: 27,
            weight: FontWeight.w600,
          ),
        MdTextKind.headingSmall => AppTextStyle.custom(
            context,
            size: 17,
            leading: 24,
            weight: FontWeight.w600,
          ),
        MdTextKind.headingTiny => AppTextStyle.custom(
            context,
            size: 16,
            leading: 22,
            weight: FontWeight.w600,
          ),
        // 引用不斜体：中文斜体是机器合成，读起来别扭，靠左竖线和次要色区分即可。
        MdTextKind.quote => AppTextStyle.custom(context, size: 16, leading: 25)
            .copyWith(color: context.colorScheme.onSurfaceVariant),
      };
}

/// 流式光标：8×18 的竖块，在 1.0 与 0.12 不透明度之间往返。
/// 关掉系统动画时保持常亮，不做 0 时长 `repeat`（那会让 controller 空转）。
class _StreamingCaret extends StatefulWidget {
  const _StreamingCaret();

  @override
  State<_StreamingCaret> createState() => _StreamingCaretState();
}

class _StreamingCaretState extends State<_StreamingCaret>
    with SingleTickerProviderStateMixin {
  AnimationController? _controller;
  Animation<double>? _alpha;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (context.disableAnimations) {
      _controller?.dispose();
      _controller = null;
      _alpha = null;
      return;
    }
    if (_controller != null) return;
    final controller = AnimationController(
      vsync: this,
      duration: context.motionDuration(const Duration(milliseconds: 520)),
    );
    _controller = controller;
    _alpha = Tween<double>(begin: 1, end: 0.12).animate(controller);
    controller.repeat(reverse: true);
  }

  @override
  void dispose() {
    _controller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final bar = Container(
      width: 8,
      height: 18,
      decoration: BoxDecoration(
        color: context.colorScheme.primary,
        borderRadius: AppRadius.xs,
      ),
    );
    final alpha = _alpha;
    if (alpha == null) return bar;
    return AnimatedBuilder(
      animation: alpha,
      builder: (context, child) => Opacity(opacity: alpha.value, child: child),
      child: bar,
    );
  }
}

/// 围栏代码块。长代码先折叠成六行，头部带语言与行/字数；整行头部可切换折叠，
/// 复制按钮自带点击。折叠态存在 [State] 里、不用内容做 key——流式追加字符
/// 绝不能重置用户已经做出的展开/收起选择。
class _CodeBlock extends StatefulWidget {
  const _CodeBlock(this.code, this.lang);

  final String code;
  final String? lang;

  @override
  State<_CodeBlock> createState() => _CodeBlockState();
}

class _CodeBlockState extends State<_CodeBlock> {
  static const double _collapsedHeight = CodeFold.collapsedLines * 21 + 24;

  bool _expanded = false;
  bool _copied = false;
  Timer? _copiedTimer;

  @override
  void didUpdateWidget(covariant _CodeBlock oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.code != widget.code && _copied) {
      _copiedTimer?.cancel();
      _copiedTimer = null;
      _copied = false;
    }
  }

  @override
  void dispose() {
    _copiedTimer?.cancel();
    super.dispose();
  }

  void _toggleExpanded() => setState(() => _expanded = !_expanded);

  void _copy() {
    Clipboard.setData(ClipboardData(text: widget.code));
    _copiedTimer?.cancel();
    setState(() => _copied = true);
    _copiedTimer = Timer(const Duration(milliseconds: 1500), () {
      if (!mounted) return;
      setState(() => _copied = false);
    });
  }

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final codeBackground = scheme.surfaceContainerHighest;
    final codeFg = scheme.onSurface;
    final long = CodeFold.isLong(widget.code);
    final caption = AppTextStyle.caption2.of(context);
    final label = (widget.lang ?? '').toLowerCase().trim();
    final mono = AppTextStyle.custom(context, size: 14, leading: 21)
        .copyWith(fontFamily: 'monospace', color: codeFg);

    Widget header = Container(
      color: codeFg.withValues(alpha: 0.045),
      padding: const EdgeInsets.fromLTRB(14, 8, 6, 8),
      child: Row(
        children: [
          Text(
            label.isEmpty ? '代码' : label,
            style: caption.copyWith(color: codeFg.withValues(alpha: 0.62)),
          ),
          if (long) ...[
            const SizedBox(width: 8),
            Text(
              CodeFold.headerMeta(widget.code),
              style: caption.copyWith(color: codeFg.withValues(alpha: 0.4)),
            ),
          ],
          const Spacer(),
          if (long) ...[
            GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: _toggleExpanded,
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 4),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    AnimatedRotation(
                      turns: _expanded ? 0.5 : 0,
                      duration:
                          context.motionDuration(const Duration(milliseconds: 200)),
                      child: GlyphIcon(
                        Glyphs.chevronDown,
                        size: 12,
                        color: codeFg.withValues(alpha: 0.62),
                      ),
                    ),
                    const SizedBox(width: 3),
                    Text(
                      _expanded ? '收起' : '展开',
                      style: caption.copyWith(
                        color: codeFg.withValues(alpha: 0.62),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(width: 2),
          ],
          // 内层 GestureDetector 在命中测试里优先，复制点击不会同时触发整行折叠。
          GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: _copy,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 4),
              child: AnimatedSwitcher(
                duration: context.motionDuration(const Duration(milliseconds: 150)),
                child: Row(
                  key: ValueKey<bool>(_copied),
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    GlyphIcon(
                      _copied ? Glyphs.check : Glyphs.copy,
                      size: 12,
                      color: _copied ? codeFg : codeFg.withValues(alpha: 0.62),
                    ),
                    const SizedBox(width: 4),
                    Text(
                      _copied ? '已复制' : '复制',
                      style: caption.copyWith(
                        color: _copied ? codeFg : codeFg.withValues(alpha: 0.62),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
    if (long) {
      header = GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: _toggleExpanded,
        child: header,
      );
    }

    Widget body = SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        // prism 的 SelectableText 是只读 EditableText，放进横向滚动会被喂无界
        // 宽度、布局断言失败且不画字。折叠时用 Text 保留「点正文展开」，展开
        // 后再用 SelectionArea 让代码可选中。
        child: long && !_expanded
            ? Text(widget.code, style: mono)
            : SelectionArea(child: Text(widget.code, style: mono)),
      ),
    );
    if (long && !_expanded) {
      // 折叠时点正文尾部是最自然的「展开」手势；展开后正文要能选中，不再吞点击。
      body = GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: () => setState(() => _expanded = true),
        child: SizedBox(
          width: double.infinity,
          height: _collapsedHeight,
          child: Stack(
            children: [
              body,
              Positioned(
                left: 0,
                right: 0,
                bottom: 0,
                height: 30,
                child: IgnorePointer(
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      gradient: LinearGradient(
                        begin: Alignment.topCenter,
                        end: Alignment.bottomCenter,
                        colors: [
                          codeBackground.withValues(alpha: 0),
                          codeBackground,
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      );
    }

    return Container(
      width: double.infinity,
      clipBehavior: Clip.antiAlias,
      decoration: BoxDecoration(
        color: codeBackground,
        borderRadius: AppRadius.md,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          header,
          AnimatedSize(
            duration: context.motionDuration(const Duration(milliseconds: 200)),
            curve: Curves.easeOut,
            alignment: Alignment.topCenter,
            child: body,
          ),
        ],
      ),
    );
  }
}

/// 表格：列宽由 [Markdown.parse] 按内容估算，表头与表体共用同一份才能对齐。
class _TableBlock extends StatelessWidget {
  const _TableBlock(this.table);

  final MdTable table;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    // 表格字号贴着正文走：12/16 在 17/25.5 的正文旁边显得像脚注。
    final headerStyle = AppTextStyle.custom(
      context,
      size: 13.5,
      leading: 18,
      weight: FontWeight.w600,
    ).copyWith(color: scheme.onSurface);
    final cellStyle = AppTextStyle.custom(context, size: 13, leading: 18)
        .copyWith(color: scheme.onSurfaceVariant);
    final widths = <double>[
      for (var column = 0; column < table.headers.length; column++)
        column < table.widths.length ? table.widths[column].toDouble() : 120,
    ];
    // 列宽是固定值，直接给整张表定宽，行分隔线才能拉满；两侧 4dp 是表头/表体的内边距。
    final tableWidth = widths.fold<double>(0, (sum, width) => sum + width) + 8;

    return Container(
      decoration: BoxDecoration(
        border: Border.all(color: scheme.outlineVariant),
        borderRadius: AppRadius.sm,
      ),
      clipBehavior: Clip.antiAlias,
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: SizedBox(
          width: tableWidth,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Container(
                color: scheme.surfaceContainerHighest,
                padding: const EdgeInsets.symmetric(horizontal: 4),
                child: Row(
                  children: [
                    for (var column = 0; column < table.headers.length; column++)
                      SizedBox(
                        width: widths[column],
                        child: Padding(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 10,
                            vertical: 9,
                          ),
                          child: SelectableText.rich(
                            table.headers[column],
                            style: headerStyle,
                          ),
                        ),
                      ),
                  ],
                ),
              ),
              for (final row in table.rows) ...[
                Container(height: 1, color: scheme.outlineVariant),
                Row(
                  children: [
                    for (var column = 0; column < table.headers.length; column++)
                      SizedBox(
                        width: widths[column],
                        child: Padding(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 10,
                            vertical: 9,
                          ),
                          child: SelectableText.rich(
                            column < row.length ? row[column] : const TextSpan(),
                            style: cellStyle,
                          ),
                        ),
                      ),
                  ],
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
