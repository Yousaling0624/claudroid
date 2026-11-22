import 'dart:async';

import 'package:flutter/foundation.dart' show listEquals;
import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import 'glyphs.dart';

/// The selection menu used by the chat body and the workspace preview.
///
/// prism_ui's own menu mirrors the platform's buttons but drops share, and its
/// select-all closes the menu; here every framework item is mapped, and
/// selecting all reopens the menu with the fresh selection so the reader can
/// pick copy or share without starting over.
Widget appSelectionMenu(BuildContext context, SelectableRegionState state) =>
    _AppSelectionMenu(state: state);

class _AppSelectionMenu extends StatefulWidget {
  const _AppSelectionMenu({required this.state});

  /// Held rather than snapshotted: every open reads the live button items and
  /// anchors, which is what makes reopening after select-all work.
  final SelectableRegionState state;

  @override
  State<_AppSelectionMenu> createState() => _AppSelectionMenuState();
}

class _AppSelectionMenuState extends State<_AppSelectionMenu> {
  PopupRoute<void>? _route;
  final ValueNotifier<Rect> _selection = ValueNotifier<Rect>(Rect.zero);

  /// Set when select-all was picked; the route's completion reopens the menu.
  bool _reopenRequested = false;

  /// Kinds and labels of the items the open route was built from.
  List<(ContextMenuButtonKind, String?)> _openKeys = const [];

  @override
  void initState() {
    super.initState();
    _scheduleOpen();
  }

  @override
  void didUpdateWidget(_AppSelectionMenu oldWidget) {
    super.didUpdateWidget(oldWidget);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _updateSelection();
    });
    if (_route == null) return;
    final keys = _keysOf(widget.state.contextMenuButtonItems);
    if (listEquals(keys, _openKeys)) return;
    _close();
    _scheduleOpen();
  }

  @override
  void dispose() {
    _close();
    WidgetsBinding.instance.addPostFrameCallback((_) => _selection.dispose());
    super.dispose();
  }

  static List<(ContextMenuButtonKind, String?)> _keysOf(
    List<ContextMenuButtonItem> items,
  ) => [for (final item in items) (item.kind, item.label)];

  // This runs while the tree builds or unmounts, when nothing may push or pop.
  void _scheduleOpen() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && _route == null) _open();
    });
  }

  void _close() {
    final route = _route;
    if (route == null) return;
    _route = null;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (route.isCurrent) {
        route.navigator!.pop();
      } else if (route.isActive) {
        route.navigator!.removeRoute(route);
      }
    });
  }

  List<PopupMenuItem> _menuItems() {
    final localizations = UiLocalizations.of(context);
    final items = <PopupMenuItem>[];
    for (final button in widget.state.contextMenuButtonItems) {
      final onPressed = button.onPressed;
      if (onPressed == null) continue;
      final kind = button.kind;
      if (kind == ContextMenuButtonKind.copy) {
        items.add(
          PopupMenuItem(
            glyph: Glyphs.copy,
            label: localizations.copy,
            onPressed: onPressed,
          ),
        );
      } else if (kind == ContextMenuButtonKind.share) {
        items.add(
          PopupMenuItem(
            glyph: AppGlyphs.share,
            label: '分享',
            onPressed: onPressed,
          ),
        );
      } else if (kind == ContextMenuButtonKind.selectAll) {
        items.add(
          PopupMenuItem(
            glyph: Glyphs.selectAll,
            label: localizations.selectAll,
            onPressed: () {
              // The framework's select-all redraws the toolbar; our route
              // must come back after the pop it is about to perform.
              onPressed();
              _reopenRequested = true;
            },
          ),
        );
      }
    }
    return items;
  }

  void _open() {
    // Reopening after select-all races the overlay rebuild; whichever path
    // arrives second must not push a duplicate route.
    if (!mounted || _route != null) return;
    final state = widget.state;
    if (!state.mounted) return;
    final items = _menuItems();
    final navigator = Navigator.maybeOf(state.context);
    final navigatorBox = navigator?.context.findRenderObject();
    if (items.isEmpty || navigator == null || navigatorBox is! RenderBox) {
      return;
    }
    _updateSelection();
    final route = _route = EditMenuRoute(
      barrierLabel: UiLocalizations.of(state.context).close,
      elevated: ElevatedSurfaceTheme.isIn(state.context),
      items: items,
      selection: _selection,
    );
    _openKeys = _keysOf(state.contextMenuButtonItems);
    // Any request made before a route opened is served by this one.
    _reopenRequested = false;
    unawaited(navigator.push(route).whenComplete(() => _handlePopped(route)));
  }

  void _updateSelection() {
    final box = Navigator.maybeOf(
      widget.state.context,
    )?.context.findRenderObject();
    if (box is! RenderBox || !box.attached) return;
    final anchors = widget.state.contextMenuAnchors;
    _selection.value = Rect.fromPoints(
      box.globalToLocal(anchors.primaryAnchor),
      box.globalToLocal(anchors.secondaryAnchor ?? anchors.primaryAnchor),
    );
  }

  void _handlePopped(PopupRoute<void> route) {
    if (identical(_route, route)) _route = null;
    if (!mounted) return;
    if (_reopenRequested) {
      _reopenRequested = false;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _open();
      });
      // Keep the invisible context-menu entry alive so this state survives
      // until the fresh route is pushed.
      return;
    }
    // A route already replaced this one (selection changed under it); removing
    // the context menu would unmount the widget and close that new route.
    if (_route == null) ContextMenuController.removeAny();
  }

  @override
  Widget build(BuildContext context) => const SizedBox.shrink();
}
