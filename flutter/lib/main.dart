import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

/// Seed for the whole palette. Every surface colour derives from it, so this is
/// the only brand constant the UI needs.
const _seed = Color(0xFF3B6EF6);

void main() {
  runApp(const ClaudroidApp());
}

class ClaudroidApp extends StatelessWidget {
  const ClaudroidApp({super.key});

  @override
  Widget build(BuildContext context) {
    return PrismApp(
      theme: ThemeData(
        colorScheme: seededColorScheme(seedColor: _seed, brightness: Brightness.light),
      ),
      darkTheme: ThemeData(
        colorScheme: seededColorScheme(seedColor: _seed, brightness: Brightness.dark),
      ),
      themeMode: ThemeMode.system,
      liquidGlass: LiquidGlassQuality.standard,
      supportedLocales: const [Locale('zh'), Locale('en')],
      home: const _HomePage(),
    );
  }
}

/// Placeholder shell: proves the engine, the theme and prism_ui all load inside
/// the Android host. The real chat and settings pages replace this.
class _HomePage extends StatelessWidget {
  const _HomePage();

  @override
  Widget build(BuildContext context) {
    return PrismScaffold(
      bar: ScaffoldBar(
        isCompact: false,
        listenable: Listenable.merge(const []),
        loading: ValueNotifier<bool>(false),
        builder: (context) => AppBar(
          title: const Text('Claudroid'),
          actions: [
            AppBarActionButton(
              data: IconButtonData(
                tooltip: '设置',
                glyph: Glyphs.more,
                onPressed: () {},
              ),
            ),
          ],
        ),
      ),
      body: Builder(
        builder: (context) => ListView(
          padding: EdgeInsets.only(
            top: context.appBarInset,
            bottom: 32 + context.bottomInset,
          ),
          children: [
            generateSection(
              title: 'Flutter 界面',
              items: [
                DecorationListItem(
                  title: const Text('引擎'),
                  trailing: const Text('未接入'),
                  onPressed: () {},
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
