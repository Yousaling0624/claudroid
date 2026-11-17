import 'package:claudroid_ui/models/models.dart';
import 'package:claudroid_ui/ui/chat_search.dart';
import 'package:flutter_test/flutter_test.dart';

ChatMessage _message(String id, Role role, List<Block> blocks) =>
    ChatMessage(id: id, role: role, blocks: blocks);

void main() {
  group('messagePlainText', () {
    test('keeps prose and errors, drops thinking and tool payloads', () {
      final message = _message('m', Role.assistant, [
        const ThinkingBlock('内部推理'),
        const TextBlock('答案在这里'),
        const ToolBlock(id: 't', name: 'Bash', input: '{"command":"ls"}'),
        const ErrorBlock('连接失败'),
      ]);
      expect(messagePlainText(message), '答案在这里\n连接失败');
    });
  });

  group('searchMessages', () {
    final messages = [
      _message('u1', Role.user, const [TextBlock('帮我找一个 Flutter 组件')]),
      _message('a1', Role.assistant, const [TextBlock('可以试试 prism_ui')]),
      _message('a2', Role.assistant, const [ThinkingBlock('PRISM 内部推理')]),
      _message('s1', Role.system, const [TextBlock('flutter 启动完成')]),
    ];

    test('a blank query keeps the transcript untouched', () {
      expect(searchMessages(messages, ''), same(messages));
      expect(searchMessages(messages, '   '), same(messages));
    });

    test('matches case-insensitively across prose', () {
      expect(searchMessages(messages, 'FLUTTER').map((m) => m.id), ['u1']);
      expect(searchMessages(messages, 'prism').map((m) => m.id), ['a1']);
    });

    test('ignores system turns and thinking-only blocks', () {
      expect(searchMessages(messages, '启动'), isEmpty);
      expect(searchMessages(messages, '内部'), isEmpty);
    });
  });
}
