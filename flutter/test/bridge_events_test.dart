import 'dart:convert';

import 'package:claudroid_ui/bridge/events.dart';
import 'package:claudroid_ui/models/models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  ChatState stateWith(ChatMessage message) => ChatState(
        messages: [message],
        running: true,
      );

  test('delta appends to the streamed text block', () {
    final state = stateWith(
      const ChatMessage(
        id: 'm1',
        role: Role.assistant,
        blocks: [TextBlock('你')],
        streaming: true,
      ),
    );
    final event = ChatEvent.fromMap({
      'type': 'delta',
      'messageId': 'm1',
      'block': 'text',
      'append': '好',
    })!;
    final next = event.apply(state)!;
    expect((next.messages.single.blocks.single as TextBlock).text, '你好');
  });

  test('the model label survives delta folding and a JSON round-trip', () {
    const original = ChatMessage(
      id: 'm1',
      role: Role.assistant,
      blocks: [TextBlock('a')],
      streaming: true,
      model: 'Opus · claude-opus-4-5',
    );
    final streamed = original.appendDelta('text', 'b')!;
    expect(streamed.model, 'Opus · claude-opus-4-5');

    final decoded = ChatMessage.fromJson(
      jsonDecode(jsonEncode(streamed.toJson())) as Map<String, dynamic>,
    );
    expect(decoded.model, 'Opus · claude-opus-4-5');

    // Records written before the field existed decode with no label, and the
    // header then falls back to the live settings label.
    const legacy = ChatMessage(id: 'm2', role: Role.assistant, blocks: []);
    expect(ChatMessage.fromJson(legacy.toJson()).model, isNull);
  });

  test('a delta for a missing message asks the caller to resync', () {
    final event = ChatEvent.fromMap({
      'type': 'delta',
      'messageId': 'gone',
      'block': 'text',
      'append': 'x',
    })!;
    expect(event.apply(const ChatState()), isNull);
  });

  test('tool_result keeps a missing output as null', () {
    final tool = const ToolBlock(id: 't1', name: 'Bash', input: '{}');
    final state = stateWith(
      ChatMessage(
        id: 'm1',
        role: Role.assistant,
        blocks: [tool],
        streaming: true,
      ),
    );
    final event = ChatEvent.fromMap({
      'type': 'tool_result',
      'messageId': 'm1',
      'id': 't1',
      'isError': false,
    })!;
    final next = event.apply(state)!;
    final result = next.messages.single.blocks.single as ToolBlock;
    expect(result.output, isNull);
    expect(result.running, isFalse);
  });

  test('a null title notice clears the previous notice', () {
    const previous = ChatState(titleNotice: '标题已更新');
    final event = ChatEvent.fromMap({'type': 'titleNotice'})!;
    expect(event.apply(previous)!.titleNotice, isNull);
  });

  test('terminal events decode chunks, completion and errors', () {
    final chunk = TerminalEvent.fromMap({'type': 'chunk', 'text': 'hi'});
    expect((chunk as TerminalChunkEvent).text, 'hi');

    final done = TerminalEvent.fromMap({
      'type': 'done',
      'exitCode': 2,
      'cwdRel': 'docs',
      'error': '退出码 2',
      'truncated': true,
    })! as TerminalDoneEvent;
    expect(done.exitCode, 2);
    expect(done.cwdRel, 'docs');
    expect(done.error, '退出码 2');
    expect(done.truncated, isTrue);

    expect(
      TerminalEvent.fromMap({'type': 'stopped'}),
      isA<TerminalStoppedEvent>(),
    );
    expect(
      (TerminalEvent.fromMap({'type': 'error', 'message': 'boom'})!
              as TerminalErrorEvent)
          .message,
      'boom',
    );
    expect(TerminalEvent.fromMap({'type': 'unknown'}), isNull);
  });

  test('workspace entries decode directory flags and relative paths', () {
    final entry = WorkspaceEntry.fromJson({
      'name': 'report.md',
      'path': 'docs/report.md',
      'kind': 'MARKDOWN',
      'size': 12,
      'modifiedAt': 34,
      'isDirectory': false,
    });
    expect(entry.path, 'docs/report.md');
    expect(entry.kind, WorkspaceKind.markdown);
    expect(entry.isDirectory, isFalse);
    expect(entry.size, 12);
    expect(entry.modifiedAt, 34);
  });

  test('workspace spaces decode the active id and fall back safely', () {
    final spaces = WorkspaceSpaces.fromJsonString(jsonEncode({
      'activeId': 'ws-2',
      'spaces': [
        {'id': 'default', 'name': '默认工作区', 'createdAt': 0},
        {'id': 'ws-2', 'name': '项目 A', 'createdAt': 123},
      ],
    }));
    expect(spaces.activeId, 'ws-2');
    expect(spaces.spaces.length, 2);
    expect(spaces.active?.name, '项目 A');
    expect(spaces.spaces.first.isDefault, isTrue);

    final empty = WorkspaceSpaces.fromJsonString('not json');
    expect(empty.activeId, 'default');
    expect(empty.spaces, isEmpty);
    expect(empty.active, isNull);
  });
}
