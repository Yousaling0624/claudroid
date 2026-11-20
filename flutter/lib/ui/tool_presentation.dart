import 'dart:convert';

import 'package:prism_ui/icons/icons.dart';

import 'glyphs.dart';

/// Pure mapping from a Claude Code tool name to a short Chinese verb and one of
/// the hand-drawn glyphs. Kept UI-free so it can be unit-tested and so a stream
/// of tool events never resolves an icon inside a hot rebuild.
///
/// Port of `ui/chat/ToolPresentation.kt`; the matching order is significant and
/// must stay in sync with it.
enum ToolVisual {
  terminal('执行', AppGlyphs.terminal),
  read('读取', AppGlyphs.read),
  write('写入', AppGlyphs.edit),
  globe('查看网页', AppGlyphs.globe),
  search('搜索', AppGlyphs.search),
  agents('调用代理', AppGlyphs.agents),
  checklist('整理清单', AppGlyphs.checklist),
  plug('连接服务', AppGlyphs.plug),
  wrench('处理', AppGlyphs.settings),
  sparkle('生成', AppGlyphs.sparkle),
  generic('调用工具', AppGlyphs.sparkle);

  const ToolVisual(this.verb, this.glyph);

  final String verb;
  final Glyph glyph;
}

abstract final class ToolPresentation {
  static ToolVisual visual(String name) {
    final n = name.toLowerCase();
    if (n.contains('bash') ||
        n.contains('shell') ||
        n.contains('terminal') ||
        n.contains('command') ||
        n == 'kill' ||
        n.contains('powershell') ||
        n.contains('computer')) {
      return ToolVisual.terminal;
    }

    // MCP discovery/listing: `list_tools` is a connector action, not a web or
    // file search, so it is recognised before the generic "list" match below.
    if (n.contains('mcp') &&
        (n.contains('list_tools') ||
            n.contains('listtools') ||
            (n.contains('tool') && n.contains('list')))) {
      return ToolVisual.plug;
    }

    if (n == 'grep' ||
        n == 'glob' ||
        n.contains('search') ||
        n.contains('grep') ||
        n.contains('glob') ||
        n.contains('find') ||
        n.contains('list') ||
        n.contains('ls')) {
      return ToolVisual.search;
    }

    if (n.contains('webfetch') ||
        n.contains('web') ||
        n.contains('http') ||
        n.contains('url') ||
        n.contains('fetch') ||
        n.contains('browser')) {
      return ToolVisual.globe;
    }

    if (n == 'task' ||
        n.contains('agent') ||
        n.contains('subagent') ||
        n.contains('team') ||
        n.contains('dispatch') ||
        n.contains('delegate') ||
        n.contains('workflow')) {
      return ToolVisual.agents;
    }

    if (n.contains('todo') ||
        n.contains('checklist') ||
        n.contains('taskcreate') ||
        n.contains('taskupdate') ||
        n.contains('plan')) {
      return ToolVisual.checklist;
    }

    if (n.contains('mcp') ||
        n.contains('plugin') ||
        n.contains('connector')) {
      return ToolVisual.plug;
    }

    if (n.contains('write') ||
        n.contains('edit') ||
        n.contains('create') ||
        n.contains('notebook') ||
        n.contains('replace') ||
        n.contains('patch') ||
        n.contains('delete') ||
        n.contains('mkdir') ||
        n.contains('move') ||
        n.contains('copy')) {
      return ToolVisual.write;
    }

    if (n.contains('read') ||
        n.contains('view') ||
        n.contains('cat') ||
        n.contains('open') ||
        n.contains('file') ||
        n.contains('pdf') ||
        n.contains('image')) {
      return ToolVisual.read;
    }

    if (n.contains('think') ||
        n.contains('reason') ||
        n.contains('generate') ||
        n.contains('spark') ||
        n.contains('summar')) {
      return ToolVisual.sparkle;
    }

    if (n.contains('skill') ||
        n.contains('config') ||
        n.contains('setting') ||
        n.contains('hook') ||
        n.contains('wrench')) {
      return ToolVisual.wrench;
    }

    return ToolVisual.generic;
  }

  static String verb(String name) => visual(name).verb;

  static Glyph icon(String name) => visual(name).glyph;
}

/// One-line summary of a tool call's input: the first recognised target field,
/// flattened to a single line and clipped for the live activity row and the
/// tool-card subtitle. Tool input is model output, so malformed JSON simply has
/// no summary.
String toolCallDetail(String input) {
  try {
    final decoded = jsonDecode(input);
    if (decoded is! Map) return '';
    for (final key in const [
      'command',
      'file_path',
      'path',
      'pattern',
      'query',
      'url',
    ]) {
      final value = decoded[key];
      if (value is String && value.trim().isNotEmpty) {
        final flat = value.replaceAll('\n', ' ').trim();
        return flat.length <= 70 ? flat : flat.substring(0, 70);
      }
    }
  } catch (_) {
    // Malformed or non-object input: the row keeps only the verb.
  }
  return '';
}

/// An actionable one-line hint for a failed WebFetch call. The CLI performs
/// its own domain check inside the jail, so DNS is the most common cause;
/// keep the raw error in the expanded card and only classify it here.
String? webFetchFailureHint(String name, String? output) {
  if (!name.toLowerCase().contains('webfetch')) return null;
  final text = (output ?? '').toLowerCase();
  const refresh = '设置 → Claude Code 引擎 → 刷新网络配置';
  if (RegExp(
    r'could not resolve|name or service not known|getaddrinfo|enotfound|nxdomain|dns|temporary failure in name resolution',
  ).hasMatch(text)) {
    return 'DNS 解析失败：先到「$refresh」，再确认当前网络可用。';
  }
  if (RegExp(
    r'domain_info|safe to fetch|unable to verify|403|forbidden',
  ).hasMatch(text)) {
    return '网页域名校验失败：检查网络/代理，或到「$refresh」。';
  }
  if (RegExp(
    r'timed out|timeout|connection refused|connection reset|unreachable|tls|certificate|socket|econn',
  ).hasMatch(text)) {
    return '连接网页失败：检查网络、代理与证书；VPN 按应用分流时，到「设置 → Claude Code 引擎 → 网络代理」填写本机代理。';
  }
  return '网页抓取失败：展开查看原始错误；网络切换后可到「$refresh」。';
}

/// Stable, human-readable label for the collapsed "工具调用 N" disclosure.
String toolCallLabel(int count, int errorCount) => errorCount > 0
    ? '工具调用 $count · $errorCount 个错误'
    : '工具调用 $count';

/// Label for the unified work disclosure; both halves are optional, so a turn
/// that only reasoned or only called tools still reads meaningfully.
String workLabel(int thinkingCount, int toolCount, int errorCount) {
  final parts = <String>[
    if (thinkingCount > 0) '思考过程 $thinkingCount 段',
    if (toolCount > 0 || errorCount > 0)
      toolCallLabel(toolCount, errorCount),
  ];
  return parts.join(' · ');
}
