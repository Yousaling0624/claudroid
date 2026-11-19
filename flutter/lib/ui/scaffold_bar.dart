import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:prism_ui/prism_ui.dart';

import 'glass_controls.dart';

/// The page chrome: a static, page-colored header with the leading control,
/// title and actions on one row. The backdrop stays still so scrolled content
/// never reads through the title, while the bar's icon buttons sit on liquid
/// glass — the material FlClash gives its chrome, and what makes the menu,
/// search and new-chat keys read as controls instead of loose glyphs.
///
/// `preferredSize` stays 72/48 so [PrismScaffold]'s body padding and every
/// page's `contentTopPadding` are unchanged.
class ClaudroidBar extends StatefulWidget implements PreferredSizeWidget {
  const ClaudroidBar({
    super.key,
    required this.isCompact,
    required this.listenable,
    required this.loading,
    this.leading,
    this.title,
    this.actions = const [],
  });

  final bool isCompact;

  final Listenable listenable;
  final ValueListenable<bool> loading;

  final WidgetBuilder? leading;
  final WidgetBuilder? title;
  final List<WidgetBuilder> actions;

  @override
  Size get preferredSize =>
      Size.fromHeight(isCompact ? sheetToolbarHeight : pageToolbarHeight);

  @override
  State<ClaudroidBar> createState() => _ClaudroidBarState();
}

class _ClaudroidBarState extends State<ClaudroidBar> {
  @override
  Widget build(BuildContext context) {
    return Stack(
      alignment: Alignment.bottomCenter,
      clipBehavior: Clip.none,
      children: [
        ListenableBuilder(
          listenable: widget.listenable,
          builder: (context, _) =>
              widget.isCompact ? _compactBar(context) : _pageBar(context),
        ),
        Positioned(
          left: 0,
          right: 0,
          bottom: -LoadingBar.extent,
          height: LoadingBar.extent,
          child: ValueListenableBuilder(
            valueListenable: widget.loading,
            builder: (_, value, _) => LoadingBar(active: value),
          ),
        ),
      ],
    );
  }

  Widget _compactBar(BuildContext context) {
    final leading = widget.leading?.call(context);
    return _glassButtons(
      context,
      AppBar(
        leading: leading == null ? null : LiquidGlassMorph(child: leading),
        title: widget.title?.call(context),
        actions: [
          for (final action in widget.actions)
            LiquidGlassMorph(child: action(context)),
        ],
        toolbarHeight: sheetToolbarHeight,
      ),
    );
  }

  /// Every bar icon button goes through this theme; FlClash wraps its whole app
  /// bar the same way. Text actions (the model key) keep their text style.
  Widget _glassButtons(BuildContext context, Widget child) {
    return TonalButtonTheme(
      glass: LiquidGlassScope.of(context),
      size: TonalButtonSize.bar,
      child: child,
    );
  }

  Widget _pageBar(BuildContext context) {
    final scheme = context.colorScheme;
    final page = SurfaceLevel.page.colorOf(scheme);
    final leading = widget.leading;
    final title = widget.title;
    final actions = [for (final action in widget.actions) action(context)];

    return _glassButtons(
      context,
      Stack(
        children: [
          // A still scrim over the whole bar: titles stay legible while the
          // transcript scrolls beneath, and nothing shows through the gaps the
          // old glass capsules left. The last few dp fade out so the boundary
          // has no hard edge.
          Positioned.fill(
            child: IgnorePointer(
              child: DecoratedBox(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: [page, page, page.withValues(alpha: 0)],
                    stops: const [0, 0.84, 1],
                  ),
                ),
              ),
            ),
          ),
          AnnotatedRegion<SystemUiOverlayStyle>(
            value: _overlayStyleOn(page),
            child: SafeArea(
              bottom: false,
              child: SizedBox(
                height: pageToolbarHeight,
                child: IconTheme.merge(
                  data: IconThemeData(color: scheme.onSurfaceVariant, size: 24),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 8),
                    child: Row(
                      children: [
                        if (leading != null)
                          SizedBox(
                            width: 44,
                            height: 44,
                            child: Center(
                              child: LiquidGlassMorph(
                                child: leading(context),
                              ),
                            ),
                          ),
                        if (leading != null && title != null)
                          const SizedBox(width: 4),
                        if (title != null)
                          Expanded(
                            child: Align(
                              alignment: AlignmentDirectional.centerStart,
                              child: Padding(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 6,
                                ),
                                child: MediaQuery.withClampedTextScaling(
                                  maxScaleFactor: 1.34,
                                  child: DefaultTextStyle(
                                    style: AppTextStyle.title3
                                        .of(context)
                                        .copyWith(color: scheme.onSurface),
                                    softWrap: false,
                                    overflow: TextOverflow.ellipsis,
                                    child: Semantics(
                                      namesRoute: true,
                                      header: true,
                                      child: title(context),
                                    ),
                                  ),
                                ),
                              ),
                            ),
                          ),
                        for (final action in actions) ...[
                          const SizedBox(width: 4),
                          LiquidGlassMorph(child: action),
                        ],
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

SystemUiOverlayStyle _overlayStyleOn(Color color) {
  final luminance = color.computeLuminance();
  final style = (luminance + 0.05) * (luminance + 0.05) > 0.15
      ? SystemUiOverlayStyle.dark
      : SystemUiOverlayStyle.light;
  return SystemUiOverlayStyle(
    statusBarColor: const Color(0x00000000),
    statusBarBrightness: style.statusBarBrightness,
    statusBarIconBrightness: style.statusBarIconBrightness,
    systemStatusBarContrastEnforced: style.systemStatusBarContrastEnforced,
  );
}
