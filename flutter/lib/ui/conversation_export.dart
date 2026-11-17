import '../models/models.dart';
import 'web_sources.dart';

/// A plain-text transcript suitable for the clipboard: user and assistant
/// prose in message order, with trailing `Sources:` lists stripped and the
/// internal thinking/tool blocks omitted.
String conversationToText(List<ChatMessage> messages) {
  final parts = <String>[];
  for (final message in messages) {
    final speaker = switch (message.role) {
      Role.user => '你',
      Role.assistant => 'Claudroid',
      Role.system => null,
    };
    if (speaker == null) continue;
    final text = message.blocks
        .whereType<TextBlock>()
        .map((block) => WebSources.stripTrailingSources(block.text))
        .where((text) => text.trim().isNotEmpty)
        .join('\n\n')
        .trim();
    if (text.isEmpty) continue;
    parts.add('$speaker：$text');
  }
  return parts.join('\n\n');
}
