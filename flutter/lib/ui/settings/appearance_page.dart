import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../palette.dart';
import 'settings_controller.dart';
import 'settings_widgets.dart';

class AppearancePage extends StatelessWidget {
  const AppearancePage({super.key, required this.controller});

  final SettingsController controller;

  @override
  Widget build(BuildContext context) {
    final mode = controller.settings.intOr('themeMode');
    return SettingsScaffold(
      title: '外观',
      listenable: controller,
      builder: (context) => [
        generateSection(
          title: '主题模式',
          footer: '跟随系统会随 Android 的深色模式切换。',
          items: [
            _ThemeCards(
              value: mode,
              onChanged: (value) => controller.patch({'themeMode': value}),
            ),
          ],
        ),
      ],
    );
  }
}

class _ThemeCards extends StatelessWidget {
  const _ThemeCards({required this.value, required this.onChanged});

  final int value;
  final ValueChanged<int> onChanged;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 10),
      child: Align(
        alignment: AlignmentDirectional.centerStart,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: _ThemeCard(
                  label: '跟随系统',
                  selected: value == 0,
                  preview: const _SplitPreview(),
                  onPressed: () => onChanged(0),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: _ThemeCard(
                  label: '浅色',
                  selected: value == 1,
                  preview: _MiniPreview(
                    scheme: claudroidColorScheme(Brightness.light),
                  ),
                  onPressed: () => onChanged(1),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: _ThemeCard(
                  label: '深色',
                  selected: value == 2,
                  preview: _MiniPreview(
                    scheme: claudroidColorScheme(Brightness.dark),
                  ),
                  onPressed: () => onChanged(2),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ThemeCard extends StatelessWidget {
  const _ThemeCard({
    required this.label,
    required this.selected,
    required this.preview,
    required this.onPressed,
  });

  final String label;
  final bool selected;
  final Widget preview;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    return PressSurface(
      fill: const Color(0x00000000),
      shape: AppShape.all(AppCorner.md),
      onPressed: onPressed,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Stack(
            children: [
              Container(
                padding: const EdgeInsets.all(6),
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(AppCorner.md),
                  border: Border.all(
                    color: selected ? scheme.primary : scheme.outlineVariant,
                    width: selected ? 1.6 : 1,
                  ),
                ),
                child: AspectRatio(
                  aspectRatio: 0.82,
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(10),
                    child: preview,
                  ),
                ),
              ),
              if (selected)
                Positioned(
                  top: 4,
                  right: 4,
                  child: Container(
                    width: 18,
                    height: 18,
                    decoration: BoxDecoration(
                      color: scheme.primary,
                      shape: BoxShape.circle,
                    ),
                    child: Center(
                      child: GlyphIcon(
                        Glyphs.check,
                        size: 12,
                        color: scheme.onPrimary,
                      ),
                    ),
                  ),
                ),
            ],
          ),
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Text(
              label,
              textAlign: TextAlign.center,
              style: AppTextStyle.caption2
                  .of(context, emphasized: selected)
                  .copyWith(
                    color: selected ? scheme.primary : scheme.onSurfaceVariant,
                  ),
            ),
          ),
        ],
      ),
    );
  }
}

class _SplitPreview extends StatelessWidget {
  const _SplitPreview();

  @override
  Widget build(BuildContext context) {
    final light = claudroidColorScheme(Brightness.light);
    final dark = claudroidColorScheme(Brightness.dark);
    return Row(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Expanded(child: _MiniPreview(scheme: light)),
        Expanded(child: _MiniPreview(scheme: dark)),
      ],
    );
  }
}

class _MiniPreview extends StatelessWidget {
  const _MiniPreview({required this.scheme});

  final ColorScheme scheme;

  @override
  Widget build(BuildContext context) {
    return Container(
      color: scheme.surface,
      padding: const EdgeInsets.all(7),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Container(
                width: 5,
                height: 5,
                decoration: BoxDecoration(
                  color: scheme.primary,
                  shape: BoxShape.circle,
                ),
              ),
              const SizedBox(width: 4),
              Expanded(
                child: _bar(scheme.onSurfaceVariant.withValues(alpha: 0.24), 3),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Align(
            alignment: Alignment.centerRight,
            child: Container(
              width: 28,
              height: 9,
              decoration: BoxDecoration(
                color: scheme.primary,
                borderRadius: BorderRadius.circular(4.5),
              ),
            ),
          ),
          const SizedBox(height: 5),
          Align(
            alignment: Alignment.centerLeft,
            child: FractionallySizedBox(
              widthFactor: 0.8,
              child: _bar(scheme.onSurfaceVariant.withValues(alpha: 0.3), 5),
            ),
          ),
          const SizedBox(height: 3),
          Align(
            alignment: Alignment.centerLeft,
            child: FractionallySizedBox(
              widthFactor: 0.55,
              child: _bar(scheme.onSurfaceVariant.withValues(alpha: 0.3), 5),
            ),
          ),
        ],
      ),
    );
  }

  Widget _bar(Color color, double height) => Container(
    height: height,
    decoration: BoxDecoration(
      color: color,
      borderRadius: BorderRadius.circular(height / 2),
    ),
  );
}
