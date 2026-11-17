import 'package:claudroid_ui/models/model_info.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('parses gateway metadata and falls back to the id as title', () {
    final model = ModelInfo.fromJson({
      'id': 'auto/dfmodel',
      'displayName': 'DeepSeek-Flash',
      'contextWindow': 1000000,
      'maxOutputTokens': 128000,
      'maxInputTokens': 1000000,
      'ownedBy': 'qoder-cn',
      'reasoning': true,
      'reasoningLevels': ['low', 'high'],
      'label': 'DeepSeek routing',
      'modalities': ['text', 'image'],
      'supportedParameters': ['tools'],
    });
    expect(model.title, 'DeepSeek-Flash');
    expect(model.contextWindow, 1000000);
    expect(model.ownedBy, 'qoder-cn');
    expect(model.reasoningLevels, ['low', 'high']);
    expect(model.modalities, ['text', 'image']);
    expect(model.hasMeta, isTrue);
  });

  test('id-only entries stay usable', () {
    final model = ModelInfo.idOnly('claude-sonnet-5-5');
    expect(model.title, 'claude-sonnet-5-5');
    expect(model.hasMeta, isFalse);
    expect(model.contextWindow, isNull);
  });
}
