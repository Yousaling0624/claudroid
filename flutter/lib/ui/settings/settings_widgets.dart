import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../glyphs.dart';
import '../layout.dart';
import '../scaffold_bar.dart';

/// A settings page: floating bar, back button, and a grouped list that rebuilds
/// from [listenable]. Subpages use this instead of repeating the scaffold.
class SettingsScaffold extends StatelessWidget {
  const SettingsScaffold({
    super.key,
    required this.title,
    required this.listenable,
    this.builder,
    this.body,
    this.actions = const [],
  }) : assert(builder != null || body != null);

  final String title;
  final Listenable listenable;
  final List<Widget> Function(BuildContext context)? builder;
  final Widget? body;
  final List<Widget> actions;

  @override
  Widget build(BuildContext context) {
    return PrismScaffold(
      bar: ClaudroidBar(
        isCompact: context.isInCompactSheet,
        listenable: listenable,
        loading: settingsNeverValue,
        leading: (context) => IconButton(
          onPressed: () => Navigator.of(context).maybePop(),
          icon: const GlyphIcon(Glyphs.arrowBack),
          tooltip: '返回',
        ),
        title: (context) => Text(title),
        actions: [for (final action in actions) (context) => action],
      ),
      body: ContentWidth(
        maxWidth: kSettingsContentWidth,
        child:
            body ??
            ListenableBuilder(
              listenable: listenable,
              builder: (context, _) => ListView(
                padding: EdgeInsets.only(
                  top: context.contentTopPadding,
                  bottom: 32 + context.bottomInset,
                ),
                children: builder!(context),
              ),
            ),
      ),
    );
  }
}

/// A root-settings entry: icon, title, live subtitle, chevron. On mobile it
/// opens [pageBuilder] with prism_ui's container transition, the way FlClash's
/// `ListItem.open` does; a row with [onTap] stays plain.
class SettingsEntryRow extends StatelessWidget {
  const SettingsEntryRow({
    super.key,
    required this.glyph,
    required this.title,
    this.subtitle,
    this.pageBuilder,
    this.onTap,
  }) : assert(pageBuilder != null || onTap != null);

  final Glyph glyph;
  final String title;
  final String? subtitle;
  final WidgetBuilder? pageBuilder;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final subtitle = this.subtitle;
    Widget row(VoidCallback? open) => DecorationListItem(
      leading: GlyphIcon(glyph),
      title: Text(title),
      subtitle: subtitle == null
          ? null
          : Text(subtitle, maxLines: 1, overflow: TextOverflow.ellipsis),
      trailing: const GlyphIcon(Glyphs.chevronForward),
      onPressed: open,
    );

    final pageBuilder = this.pageBuilder;
    if (pageBuilder == null) {
      return row(onTap);
    }
    final position = ItemPositionProvider.of(context)?.position;
    return OpenContainer<void>(
      closedShape: position == null
          ? null
          : AppShape.of(listItemRadiusOf(position)),
      closedBuilder: (context, open) => row(open),
      openBuilder: (context, _) => pageBuilder(context),
    );
  }
}

/// Label on the left, the live value and a chevron on the right. Tapping the
/// row or the value opens the same menu.
class SettingsPickerRow<T> extends StatefulWidget {
  const SettingsPickerRow({
    super.key,
    required this.label,
    required this.value,
    required this.options,
  });

  final String label;
  final String value;
  final List<PopupMenuItem> options;

  @override
  State<SettingsPickerRow<T>> createState() => _SettingsPickerRowState<T>();
}

class _SettingsPickerRowState<T> extends State<SettingsPickerRow<T>> {
  PopupOpen? _open;

  @override
  Widget build(BuildContext context) {
    return DecorationListItem(
      title: Text(widget.label),
      onPressed: () => _open?.call(),
      trailing: PopupAnchor(
        targetBuilder: (open) {
          _open = open;
          return SettingsValueButton(label: widget.value, onPressed: open);
        },
        popupBuilder: (_) => PopupMenu(items: widget.options),
      ),
    );
  }
}

class SettingsValueRow extends StatelessWidget {
  const SettingsValueRow({
    super.key,
    required this.label,
    required this.value,
    required this.onPressed,
    this.trailingGlyph = AppGlyphs.edit,
  });

  final String label;
  final String value;
  final VoidCallback onPressed;

  /// Edits read as a pencil; rows that open a model list pass
  /// [Glyphs.picker], and pure actions (refresh, navigation) pass their own.
  final Glyph trailingGlyph;

  @override
  Widget build(BuildContext context) {
    return DecorationListItem(
      title: Text(label),
      onPressed: onPressed,
      trailing: SettingsValueButton(
        label: value,
        glyph: trailingGlyph,
        onPressed: onPressed,
      ),
    );
  }
}

/// A one-line action that is not a value picker: checking for updates, running
/// a self-test, refreshing DNS. The glyph sits at the row end, so the list
/// keeps FlClash's "label left, affordance right" rhythm.
class SettingsActionRow extends StatelessWidget {
  const SettingsActionRow({
    super.key,
    required this.label,
    required this.glyph,
    required this.onPressed,
    this.busy = false,
  });

  final String label;
  final Glyph glyph;
  final VoidCallback? onPressed;
  final bool busy;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    return DecorationListItem(
      title: Text(label),
      onPressed: onPressed,
      trailing: busy
          ? ActivityIndicator(size: 16, color: scheme.onSurfaceVariant)
          : GlyphIcon(
              glyph,
              size: 18,
              color: onPressed == null
                  ? scheme.onSurfaceVariant.withValues(alpha: 0.45)
                  : scheme.onSurfaceVariant,
            ),
    );
  }
}

/// A label with a long, wrapping value and a copy affordance. Used for the
/// jail path, which does not fit [SettingsInfoRow]'s 180dp trailing value.
class SettingsCopyRow extends StatelessWidget {
  const SettingsCopyRow({
    super.key,
    required this.label,
    required this.value,
    required this.onPressed,
  });

  final String label;
  final String value;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    return DecorationListItem(
      title: Text(label),
      onPressed: onPressed,
      subtitle: SelectableText(
        value,
        style: context.listCaptionStyle.copyWith(
          fontFamily: 'monospace',
          height: 1.35,
        ),
      ),
      trailing: GlyphIcon(
        Glyphs.copy,
        size: 16,
        color: scheme.onSurfaceVariant,
      ),
    );
  }
}

class SettingsSwitchRow extends StatelessWidget {  const SettingsSwitchRow({
    super.key,
    required this.label,
    required this.value,
    required this.onChanged,
  });

  final String label;
  final bool value;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    return DecorationListItem(
      title: Text(label),
      onPressed: () => onChanged(!value),
      trailing: Switch(
        value: value,
        onChanged: onChanged,
        semanticLabel: label,
      ),
    );
  }
}

class SettingsInfoRow extends StatelessWidget {
  const SettingsInfoRow({super.key, required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return DecorationListItem(
      title: Text(label),
      trailing: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 180),
        child: Text(
          value,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          textAlign: TextAlign.end,
          style: context.listSubtitleStyle,
        ),
      ),
    );
  }
}

/// The FlClash picker value: the current label, then its affordance glyph.
/// Pickers keep [Glyphs.picker]; editable values pass [AppGlyphs.edit].
class SettingsValueButton extends StatelessWidget {
  const SettingsValueButton({
    super.key,
    required this.label,
    required this.onPressed,
    this.glyph = Glyphs.picker,
  });

  final String label;
  final VoidCallback onPressed;
  final Glyph glyph;

  @override
  Widget build(BuildContext context) {
    return TextButton(
      onPressed: onPressed,
      style: ButtonStyle.from(
        foregroundColor: context.colorScheme.onSurfaceVariant,
        textStyle: context.listTitleStyle,
        padding: const EdgeInsets.symmetric(horizontal: 8),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 150),
            child: PopupValueText(
              label,
              alignment: AlignmentDirectional.centerEnd,
            ),
          ),
          const SizedBox(width: 4),
          GlyphIcon(glyph, size: 16),
        ],
      ),
    );
  }
}

class SettingsNote extends StatelessWidget {
  const SettingsNote({super.key, required this.text, this.isError = false});

  final String text;
  final bool isError;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 8, 14, 10),
      child: Text(
        text,
        style: AppTextStyle.caption1
            .of(context)
            .copyWith(
              color: isError ? scheme.error : scheme.onSurfaceVariant,
              height: 1.4,
            ),
      ),
    );
  }
}

/// The bar never changes on settings pages; it only needs a listenable.
/// `ChangeNotifier` has no const constructor, so this is a cached instance.
final ValueListenable<bool> settingsNeverValue = _NeverValue();
final Listenable settingsNeverBar = _NeverBar();

class _NeverBar extends ChangeNotifier {}

class _NeverValue implements ValueListenable<bool> {
  const _NeverValue();

  @override
  bool get value => false;

  @override
  void addListener(VoidCallback listener) {}

  @override
  void removeListener(VoidCallback listener) {}
}
