import 'dart:async';

import 'package:flutter/foundation.dart' show listEquals;
import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import 'glyphs.dart';

/// The selection menu used by every selectable text in the app.
///
/// prism_ui's own menu mirrors the platform's buttons but drops share, and its
/// select-all closes the menu; here every framework item is mapped, and
/// selecting all reopens the menu with the fresh selection so the reader can
/// pick copy or share without starting over.
Widget appSelectionMenu(BuildContext context, SelectableRegionState state) =>
    _AppSelectionMenu(
      owner: state.context,
      anchors: () => state.contextMenuAnchors,
      buttons: () => state.contextMenuButtonItems,
    );

/// The same menu for [SelectableText] and prism_ui's editable text fields,
/// whose selection lives in an [EditableTextState] instead of a selection
/// region.
///
/// prism's default `TextSelectionMenu.editableText` also closes on select-all,
/// which is why read-only tool output and search fields lost every action after
/// "全选". This adapter keeps the framework's button order and adds the
/// collapsed-selection "选择" word item that prism provides on touch.
Widget appEditableTextMenu(BuildContext context, EditableTextState state) =>
    _AppSelectionMenu(
      owner: state.context,
      anchors: () => state.contextMenuAnchors,
      buttons: () => _editableButtons(context, state),
    );

List<ContextMenuButtonItem> _editableButtons(
  BuildContext context,
  EditableTextState state,
) {
  final value = state.textEditingValue;
  final selection = value.selection;
  final field = state.widget;
  final canSelectWord = !isDesktopPlatform &&
      selection.isCollapsed &&
      !field.obscureText &&
      field.enableInteractiveSelection &&
      value.text.isNotEmpty;
  final buttons = [...state.contextMenuButtonItems];
  if (canSelectWord) {
    // The framework has no "select word" kind; prism models it as a custom
    // item and recognizes it by label. Keep it just before select-all.
    final item = ContextMenuButtonItem(
      label: UiLocalizations.of(context).select,
      onPressed: () => _selectWord(state),
    );
    final selectAll = buttons.indexWhere(
      (button) => button.kind == ContextMenuButtonKind.selectAll,
    );
    if (selectAll < 0) {
      buttons.add(item);
    } else {
      buttons.insert(selectAll, item);
    }
  }
  return buttons;
}

void _selectWord(EditableTextState state) {
  final value = state.textEditingValue;
  final word = state.renderEditable.getWordBoundary(value.selection.base);
  state.userUpdateTextEditingValue(
    value.copyWith(
      selection: TextSelection(baseOffset: word.start, extentOffset: word.end),
    ),
    SelectionChangedCause.toolbar,
  );
  WidgetsBinding.instance.addPostFrameCallback((_) {
    if (state.mounted) state.showToolbar();
  });
}

class _AppSelectionMenu extends StatefulWidget {
  const _AppSelectionMenu({
    required this.owner,
    required this.anchors,
    required this.buttons,
  });

  /// Held rather than snapshotted: every open reads the live button items and
  /// anchors, which is what makes reopening after select-all work.
  final BuildContext owner;
  final TextSelectionToolbarAnchors Function() anchors;
  final List<ContextMenuButtonItem> Function() buttons;

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
    final keys = _keysOf(widget.buttons());
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
    for (final button in widget.buttons()) {
      final onPressed = button.onPressed;
      if (onPressed == null) continue;
      final kind = button.kind;
      if (kind == ContextMenuButtonKind.cut) {
        items.add(
          PopupMenuItem(
            glyph: Glyphs.cut,
            label: localizations.cut,
            onPressed: onPressed,
          ),
        );
      } else if (kind == ContextMenuButtonKind.copy) {
        items.add(
          PopupMenuItem(
            glyph: Glyphs.copy,
            label: localizations.copy,
            onPressed: onPressed,
          ),
        );
      } else if (kind == ContextMenuButtonKind.paste) {
        items.add(
          PopupMenuItem(
            glyph: Glyphs.paste,
            label: localizations.paste,
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
      } else if (kind == ContextMenuButtonKind.custom &&
          button.label == localizations.select) {
        // prism's word-level "选择" item; selecting a word reopens the menu
        // through showToolbar once the selection has settled.
        items.add(
          PopupMenuItem(
            glyph: Glyphs.selectAll,
            label: localizations.select,
            onPressed: onPressed,
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
    final owner = widget.owner;
    if (!owner.mounted) return;
    final buttons = widget.buttons();
    final items = _menuItems();
    final navigator = Navigator.maybeOf(owner);
    final navigatorBox = navigator?.context.findRenderObject();
    if (items.isEmpty || navigator == null || navigatorBox is! RenderBox) {
      return;
    }
    _updateSelection();
    final route = _route = EditMenuRoute(
      barrierLabel: UiLocalizations.of(owner).close,
      elevated: ElevatedSurfaceTheme.isIn(owner),
      items: items,
      selection: _selection,
    );
    _openKeys = _keysOf(buttons);
    // Any request made before a route opened is served by this one.
    _reopenRequested = false;
    unawaited(navigator.push(route).whenComplete(() => _handlePopped(route)));
  }

  void _updateSelection() {
    final box = Navigator.maybeOf(widget.owner)?.context.findRenderObject();
    if (box is! RenderBox || !box.attached) return;
    final anchors = widget.anchors();
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
