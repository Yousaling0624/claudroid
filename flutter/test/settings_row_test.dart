import 'package:claudroid_ui/ui/glyphs.dart';
import 'package:claudroid_ui/ui/settings/settings_widgets.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

Widget _harness(Widget child) => PrismApp(
  home: ViewScope(
    size: const Size(400, 800),
    isMobile: true,
    child: Builder(
      builder: (context) => IconTheme(
        data: IconThemeData(color: context.colorScheme.onSurface, size: 24),
        child: Surface(
          color: context.colorScheme.surface,
          child: child,
        ),
      ),
    ),
  ),
);

void main() {
  testWidgets('settings rows distinguish navigation, menus and text edits', (
    tester,
  ) async {
    await tester.pumpWidget(
      _harness(
        Column(
          children: [
            SettingsEntryRow(
              glyph: AppGlyphs.folder,
              title: '工作区',
              onTap: () {},
            ),
            const SettingsPickerRow<String>(
              label: '协议',
              value: 'Anthropic',
              options: [],
            ),
            SettingsValueRow(
              label: 'Base URL',
              value: '',
              onPressed: () {},
            ),
            SettingsValueRow(
              label: '存储占用',
              value: '点击读取',
              trailingGlyph: AppGlyphs.refresh,
              onPressed: () {},
            ),
          ],
        ),
      ),
    );

    expect(
      find.byWidgetPredicate(
        (w) => w is GlyphIcon && w.glyph == Glyphs.chevronForward,
      ),
      findsOneWidget,
    );
    expect(
      find.byWidgetPredicate(
        (w) => w is GlyphIcon && w.glyph == Glyphs.picker,
      ),
      findsOneWidget,
    );
    expect(
      find.byWidgetPredicate(
        (w) => w is GlyphIcon && w.glyph == AppGlyphs.edit,
      ),
      findsOneWidget,
    );
    expect(
      find.byWidgetPredicate(
        (w) => w is GlyphIcon && w.glyph == AppGlyphs.refresh,
      ),
      findsOneWidget,
    );
  });
}
