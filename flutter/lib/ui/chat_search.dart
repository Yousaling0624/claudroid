import '../models/models.dart';

/// Plain text for one message: user/assistant prose and errors, without the
/// thinking stream or tool payloads (those would make every search match the
/// model's internal noise).
String messagePlainText(ChatMessage message) => [
      for (final block in message.blocks)
        if (block is TextBlock)
          block.text
        else if (block is ErrorBlock)
          block.text,
    ].join('\n');

/// Messages whose prose contains [query], case-insensitively. A blank query
/// keeps the transcript intact so the search bar can open without hiding
/// everything before the user types.
List<ChatMessage> searchMessages(
  List<ChatMessage> messages,
  String query,
) {
  final needle = query.trim().toLowerCase();
  if (needle.isEmpty) return messages;
  return [
    for (final message in messages)
      if (message.role != Role.system &&
          messagePlainText(message).toLowerCase().contains(needle))
        message,
  ];
}
