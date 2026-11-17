import 'dart:convert';

/// One tappable search result parsed out of a WebSearch tool output.
class WebSource {
  WebSource({required this.title, required this.url})
      : host = WebSources.hostOf(url);

  final String title;
  final String url;
  final String host;
}

/// Port of `core/WebSources.kt`: Claude Code's WebSearch result is prose plus a
/// `Links: [{"title","url"}]` payload (older builds) or plain markdown links.
/// The transcript turns those into source cards and hides a trailing plain
/// "Sources:" list that would otherwise duplicate them.
abstract final class WebSources {
  static const maxSources = 12;

  static final RegExp _markdownLink =
      RegExp(r'\[([^\]\n]{1,160})]\((https?://[^)\s]+)\)');
  static final RegExp _bareUrl = RegExp(r'''https?://[^\s<>"']+''');
  static final RegExp _leadingNumber = RegExp(r'^\d{1,2}[.)]\s+');

  static final RegExp _sourceHeading = RegExp(
    r'(?:^|[\n\r]|[ \t]{2,})[ \t]*(?:sources?|links?|来源|参考来源|参考资料|链接)[ \t]*[:：]',
    multiLine: true,
    caseSensitive: false,
  );

  static final RegExp _bulletItem = RegExp(r'^[-*•·]\s*');
  static final RegExp _numberedItem = RegExp(r'^\d{1,2}[.、)]\s');
  static final RegExp _markdownLinkItem =
      RegExp(r'^\[[^\]\n]{1,160}]\(https?://');
  static final RegExp _bareUrlItem = RegExp(r'^https?://\S+');

  static String stripTrailingSources(String text) {
    if (text.trim().isEmpty) return text;
    final matches = _sourceHeading.allMatches(text).toList();
    for (final match in matches.reversed) {
      if (_insideCodeFence(text, match.start)) continue;
      final tail = text.substring(match.end);
      if (!_looksLikeSourceList(tail)) continue;
      return text.substring(0, match.start).trimRight();
    }
    return text;
  }

  /// A source block is only recognised when *every* non-empty line in the tail
  /// reads like a list item, a URL, or an indented continuation of one. A prose
  /// line means the heading was followed by answer text, so stripping from it
  /// would eat real content.
  static bool _looksLikeSourceList(String tail) {
    var sawItem = false;
    for (final line in const LineSplitter().convert(tail)) {
      if (line.trim().isEmpty) continue;
      final item = line.trimLeft();
      if (_isSourceItem(item)) {
        sawItem = true;
      } else if (sawItem && item.length < line.length) {
        // Indented continuation of the item above.
        continue;
      } else {
        return false;
      }
    }
    return true;
  }

  static bool _isSourceItem(String line) =>
      _bulletItem.hasMatch(line) ||
      _numberedItem.hasMatch(line) ||
      _markdownLinkItem.hasMatch(line) ||
      _bareUrlItem.hasMatch(line);

  /// Fences are markdown, so a heading inside one is code, never a source block.
  static bool _insideCodeFence(String text, int index) {
    var fences = 0;
    var lineStart = 0;
    while (lineStart < index) {
      final newline = text.indexOf('\n', lineStart);
      final lineEnd = newline < 0 || newline > index ? index : newline;
      if (text.substring(lineStart, lineEnd).trimLeft().startsWith('```')) {
        fences++;
      }
      if (lineEnd >= index) break;
      lineStart = lineEnd + 1;
    }
    return fences.isOdd;
  }

  static List<WebSource> parse(String output) {
    if (output.trim().isEmpty) return const [];
    final sources = <WebSource>[];

    final lowercase = output.toLowerCase();
    final markers = ['links:', 'sources:', '"links"']
        .map(lowercase.indexOf)
        .where((index) => index >= 0);
    final marker = markers.isEmpty ? null : markers.reduce((a, b) => a < b ? a : b);
    if (marker != null) {
      final open = output.indexOf('[', marker);
      final close = open >= 0 ? _jsonArrayEnd(output, open) : null;
      if (open >= 0 && close != null) {
        final parsed = _tryDecodeList(output.substring(open, close + 1));
        for (final element in parsed) {
          final url = '${element['url'] ?? ''}'.trim();
          final title = '${element['title'] ?? ''}'.trim();
          if (url.startsWith('http')) {
            sources.add(WebSource(
              title: _cleanTitle(title).isEmpty ? hostOf(url) : _cleanTitle(title),
              url: url,
            ));
          }
        }
      }
    }

    if (sources.isEmpty) {
      for (final match in _markdownLink.allMatches(output)) {
        final title = _cleanTitle(match.group(1) ?? '');
        final url = match.group(2)!;
        sources.add(WebSource(
          title: title.isEmpty ? hostOf(url) : title,
          url: url,
        ));
      }
    }

    if (sources.isEmpty) {
      for (final match in _bareUrl.allMatches(output)) {
        final url = match.group(0)!.replaceFirst(RegExp(r'[.,;:)\]}]+$'), '');
        sources.add(WebSource(title: hostOf(url), url: url));
      }
    }

    final seen = <String>{};
    return [
      for (final source in sources)
        if (source.url.startsWith('http') &&
            seen.add(source.url.replaceFirst(RegExp(r'/+$'), '').toLowerCase()))
          source,
    ].take(maxSources).toList(growable: false);
  }

  static List<Map<String, dynamic>> _tryDecodeList(String raw) {
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! List) return const [];
      return decoded.whereType<Map<String, dynamic>>().toList(growable: false);
    } catch (_) {
      return const [];
    }
  }

  static String _cleanTitle(String raw) =>
      raw.replaceFirst(_leadingNumber, '').replaceAll(RegExp(r'\s+'), ' ').trim();

  static String hostOf(String url) {
    final host = Uri.tryParse(url)?.host ?? '';
    return host.startsWith('www.') ? host.substring(4) : host;
  }

  /// Finds the bracket matching [start], skipping brackets inside JSON strings.
  static int? _jsonArrayEnd(String text, int start) {
    var depth = 0;
    var inString = false;
    var escaped = false;
    var i = start;
    while (i < text.length) {
      final c = text[i];
      if (inString) {
        if (escaped) {
          escaped = false;
        } else if (c == '\\') {
          escaped = true;
        } else if (c == '"') {
          inString = false;
        }
      } else {
        if (c == '"') {
          inString = true;
        } else if (c == '[') {
          depth++;
        } else if (c == ']') {
          depth--;
          if (depth == 0) return i;
        }
      }
      i++;
    }
    return null;
  }
}
