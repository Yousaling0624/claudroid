import 'package:claudroid_ui/ui/tool_presentation.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('tool families follow the Kotlin mapping order', () {
    expect(ToolPresentation.visual('Bash'), ToolVisual.terminal);
    expect(ToolPresentation.visual('PowerShell'), ToolVisual.terminal);
    expect(ToolPresentation.visual('Read'), ToolVisual.read);
    expect(ToolPresentation.visual('Edit'), ToolVisual.write);
    expect(ToolPresentation.visual('WebFetch'), ToolVisual.globe);
    // Search wins over the web family, matching ToolPresentation.kt.
    expect(ToolPresentation.visual('WebSearch'), ToolVisual.search);
    expect(ToolPresentation.visual('Grep'), ToolVisual.search);
    expect(ToolPresentation.visual('Task'), ToolVisual.agents);
    expect(ToolPresentation.visual('TodoWrite'), ToolVisual.checklist);
    expect(ToolPresentation.visual('mcp__server__list_tools'), ToolVisual.plug);
    expect(ToolPresentation.visual('Zzz'), ToolVisual.generic);
  });

  test('verbs are the Chinese labels shown before output', () {
    expect(ToolPresentation.verb('Bash'), '执行');
    expect(ToolPresentation.verb('Read'), '读取');
    expect(ToolPresentation.verb('Edit'), '写入');
    expect(ToolPresentation.verb('WebFetch'), '查看网页');
  });

  test('tool call detail extracts one readable target', () {
    expect(
      toolCallDetail('{"command":"apt list --installed\\n| head"}'),
      'apt list --installed | head',
    );
    expect(toolCallDetail('{"file_path":"/work/README.md"}'), '/work/README.md');
    expect(toolCallDetail('{"pattern":"TODO"}'), 'TODO');
    expect(toolCallDetail('{"query":"华为 2025"}'), '华为 2025');
    expect(toolCallDetail('{"url":"https://example.com/a"}'), 'https://example.com/a');
    // First recognised field wins; unrelated keys are ignored.
    expect(toolCallDetail('{"description":"x","path":"/data/adb"}'), '/data/adb');
    // Model output is untrusted: malformed and non-object input stay blank.
    expect(toolCallDetail('not json'), '');
    expect(toolCallDetail('["command"]'), '');
    expect(toolCallDetail('{"command":42}'), '');
  });

  test('tool call detail clips long values to one line', () {
    final detail = toolCallDetail('{"command":"${'x' * 90}"}');
    expect(detail.length, 70);
    expect(detail, isNot(contains('\n')));
  });

  test('WebFetch failures get an actionable hint', () {
    const refresh = '刷新网络配置';
    expect(webFetchFailureHint('Bash', 'getaddrinfo failed'), isNull);
    expect(
      webFetchFailureHint('WebFetch', 'getaddrinfo: Name or service not known'),
      allOf(contains('DNS'), contains(refresh)),
    );
    expect(
      webFetchFailureHint('WebFetch', 'domain_info returned 403'),
      allOf(contains('域名校验'), contains(refresh)),
    );
    expect(
      webFetchFailureHint('WebFetch', 'Connection timed out'),
      allOf(contains('连接网页失败'), contains('网络代理')),
    );
    expect(
      webFetchFailureHint('WebFetch', 'unknown error'),
      contains('网页抓取失败'),
    );
  });

  test('work disclosure label merges thinking and tool counts', () {
    expect(workLabel(0, 0, 0), '');
    expect(workLabel(2, 0, 0), '思考过程 2 段');
    expect(workLabel(0, 3, 0), '工具调用 3');
    expect(workLabel(1, 2, 1), '思考过程 1 段 · 工具调用 2 · 1 个错误');
  });
}
