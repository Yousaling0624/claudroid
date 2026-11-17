import 'package:claudroid_ui/models/model_info.dart';
import 'package:claudroid_ui/ui/settings/model_picker.dart';
import 'package:flutter_test/flutter_test.dart';

const _deepseek = ModelInfo(
  id: 'auto/dfmodel',
  displayName: 'DeepSeek-Flash',
  contextWindow: 1000000,
  maxOutputTokens: 128000,
  ownedBy: 'qoder-cn',
  reasoning: true,
  reasoningLevels: ['low', 'high'],
  modalities: ['text', 'image'],
);

const _qwen = ModelInfo(
  id: 'auto/qfmodel',
  displayName: 'Qwen3.8-Flash',
  contextWindow: 258624,
  ownedBy: 'opencode-zen-free',
);

const _plain = ModelInfo(id: 'plain-model');

void main() {
  test('filters by title, id, owner and label', () {
    const labelled = ModelInfo(
      id: 'group/x',
      displayName: 'Hy4',
      ownedBy: 'workbuddy',
      label: 'routing group',
    );
    const models = [_deepseek, _qwen, _plain, labelled];
    expect(filterModels(models, 'deepseek'), [_deepseek]);
    expect(filterModels(models, 'qoder'), [_deepseek]);
    expect(filterModels(models, 'workbuddy'), [labelled]);
    expect(filterModels(models, 'routing'), [labelled]);
    expect(filterModels(models, '  '), models);
    expect(filterModels(models, 'nothing'), isEmpty);
  });

  test('groups by owner, sorts owners and puts the unattributed last', () {
    final groups = groupModels([_deepseek, _qwen, _plain]);
    expect(groups.keys.toList(), ['opencode-zen-free', 'qoder-cn', '其他']);
    expect(groups['其他']!.single.id, 'plain-model');
  });

  test('token labels use compact units', () {
    expect(tokenLabel(null), '');
    expect(tokenLabel(0), '');
    expect(tokenLabel(999), '999');
    expect(tokenLabel(1000), '1K');
    expect(tokenLabel(128000), '128K');
    expect(tokenLabel(258624), '259K');
    expect(tokenLabel(1000000), '1M');
  });

  test('meta labels expose context, output, reasoning and image input', () {
    expect(metaLabels(_deepseek), [
      '1M 上下文',
      '128K 输出',
      '推理 low/high',
      '图像输入',
    ]);
    expect(metaLabels(_qwen), ['259K 上下文']);
    expect(metaLabels(_plain), isEmpty);
  });
}
