import 'package:claudroid_ui/ui/composer.dart';
import 'package:claudroid_ui/ui/glyphs.dart';
import 'package:flutter_test/flutter_test.dart';

SlashAction _action(String command, String label, {bool enabled = true}) =>
    SlashAction(
      command: command,
      label: label,
      glyph: AppGlyphs.plus,
      enabled: enabled,
      onPressed: () {},
    );

void main() {
  final actions = [
    _action('new', '新对话'),
    _action('model', '切换模型'),
    _action('plan', '规划模式（只读）'),
    _action('permissions', '权限模式'),
    _action('workspace', '工作区'),
    _action('switch', '切换工作区'),
    _action('settings', '设置'),
  ];

  group('slashPaletteOpen', () {
    test('opens only for a single-line slash prefix', () {
      expect(slashPaletteOpen('/'), isTrue);
      expect(slashPaletteOpen('/pl'), isTrue);
      expect(slashPaletteOpen(' /pl'), isFalse);
      expect(slashPaletteOpen('/pl\nmore'), isFalse);
      expect(slashPaletteOpen('hello'), isFalse);
    });
  });

  group('filterSlashActions', () {
    test('a blank query keeps every command', () {
      expect(filterSlashActions(actions, '/').length, actions.length);
    });

    test('matches commands and Chinese labels', () {
      expect(
        filterSlashActions(actions, '/pl').map((a) => a.command),
        ['plan'],
      );
      expect(
        filterSlashActions(actions, '/工作').map((a) => a.command),
        ['workspace', 'switch'],
      );
    });

    test('a non-slash text has no palette', () {
      expect(filterSlashActions(actions, 'plan'), isEmpty);
    });
  });

  group('slashEnterAction', () {
    test('runs an exact command', () {
      expect(slashEnterAction(actions, '/plan')?.command, 'plan');
    });

    test('runs a unique partial match', () {
      expect(slashEnterAction(actions, '/pl')?.command, 'plan');
      expect(slashEnterAction(actions, '/sett')?.command, 'settings');
    });

    test('returns null for a bare slash or an unknown command', () {
      // Both must fall through to a normal message, so `/` can be sent.
      expect(slashEnterAction(actions, '/'), isNull);
      expect(slashEnterAction(actions, '/nope'), isNull);
    });

    test('keeps an exact match even when the command is disabled', () {
      final withDisabled = [
        ...actions,
        _action('retry', '重新回答', enabled: false),
      ];
      final action = slashEnterAction(withDisabled, '/retry');
      expect(action, isNotNull);
      expect(action!.enabled, isFalse);
    });

    test('ambiguous partials are left to the user', () {
      expect(slashEnterAction(actions, '/s'), isNull);
    });
  });
}
