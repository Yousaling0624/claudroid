import 'package:claudroid_ui/models/models.dart';
import 'package:claudroid_ui/ui/conversation_export.dart';
import 'package:flutter_test/flutter_test.dart';

ChatMessage _message(String id, Role role, List<Block> blocks) =>
    ChatMessage(id: id, role: role, blocks: blocks);

void main() {
  test('joins user and assistant prose in order', () {
    final messages = [
      _message('u', Role.user, const [TextBlock('你好')]),
      _message('a', Role.assistant, const [TextBlock('你好！有什么可以帮你？')]),
    ];
    expect(
      conversationToText(messages),
      '你：你好\n\nClaudroid：你好！有什么可以帮你？',
    );
  });

  test('skips system turns, thinking, tools and errors', () {
    final messages = [
      _message('s', Role.system, const [TextBlock('系统提示')]),
      _message('u', Role.user, const [
        TextBlock('查一下时间'),
        ThinkingBlock('推理'),
        ToolBlock(id: 't', name: 'Bash', input: '{}'),
        ErrorBlock('命令失败'),
      ]),
      _message('a', Role.assistant, const [ThinkingBlock('先看时区')]),
    ];
    expect(conversationToText(messages), '你：查一下时间');
  });

  test('strips a trailing source list from assistant prose', () {
    final messages = [
      _message('a', Role.assistant, const [
        TextBlock(
          '答案在这里。\n\nSources:\n- [A](https://a.com)\n- [B](https://b.com)',
        ),
      ]),
    ];
    expect(conversationToText(messages), 'Claudroid：答案在这里。');
  });

  test('joins several text blocks and drops empty prose', () {
    final messages = [
      _message('a', Role.assistant, const [
        TextBlock('第一段'),
        TextBlock('   '),
        TextBlock('第二段'),
      ]),
      _message('empty', Role.user, const [TextBlock('')]),
    ];
    expect(conversationToText(messages), 'Claudroid：第一段\n\n第二段');
  });

  test('an empty transcript exports as empty', () {
    expect(conversationToText(const []), '');
  });
}
