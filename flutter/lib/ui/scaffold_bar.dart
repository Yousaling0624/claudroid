import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:prism_ui/prism_ui.dart';

/// prism_ui's [ScaffoldBar] starts fading its scrim at the foot of the status
/// bar (`fadeStart: top / (top + pageToolbarHeight)`), which on a phone leaves
/// only ~58% of it behind the title and keeps scrolled text legible under the
/// bar. This holds the scrim for the whole bar and lets it fall off across the
/// overhang instead, the way FlClash's floating header reads.
///
/// Same slots and geometry as [ScaffoldBar]. The window-controls clearance is
/// dropped: the Android host passes no [ViewScope.windowControls].
class ClaudroidBar extends StatelessWidget implements PreferredSizeWidget {
  const ClaudroidBar({
    super.key,
    required this.isCompact,
    required this.listenable,
    required this.builder,
    required this.loading,
  });

  /// Matches prism_ui's private `_headerOverhang`.
  static const double _overhang = 16;

  final bool isCompact;

  final Listenable listenable;
  final WidgetBuilder builder;
  final ValueListenable<bool> loading;

  @override
  Size get preferredSize =>
      Size.fromHeight(isCompact ? sheetToolbarHeight : pageToolbarHeight);

  @override
  Widget build(BuildContext context) {
    return Stack(
      alignment: Alignment.bottomCenter,
      clipBehavior: Clip.none,
      children: [
        ListenableBuilder(
          listenable: listenable,
          builder: (context, _) {
            final bar = builder(context);
            if (isCompact) return bar;
            return FloatingHeader(
              backgroundColor: SurfaceLevel.page.colorOf(context.colorScheme),
              fadeStart: 1,
              overhang: _overhang,
              child: bar,
            );
          },
        ),
        Positioned(
          left: 0,
          right: 0,
          bottom: -LoadingBar.extent,
          height: LoadingBar.extent,
          child: ValueListenableBuilder(
            valueListenable: loading,
            builder: (_, value, _) => LoadingBar(active: value),
          ),
        ),
      ],
    );
  }
}
