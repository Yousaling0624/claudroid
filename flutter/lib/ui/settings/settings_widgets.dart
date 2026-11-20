import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../glyphs.dart';
import '../layout.dart';
import '../palette.dart';
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
        centerTitle: true,
        listenable: listenable,
        loading: settingsNeverValue,
        leading: (context) => IconButton(
          onPressed: () => Navigator.of(context).maybePop(),
          icon: const GlyphIcon(Glyphs.arrowBack),
          tooltip: '返回',
        ),
        title: (context) => Text(
          title,
          style: AppTextStyle.headline
              .of(context)
              .copyWith(color: context.colorScheme.onSurface),
        ),
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

/// A settings hub section: a quiet header, then one rounded card holding the
/// rows. The hub deliberately leaves FlClash's flush list behind — the card
/// carries the grouping, so the rows can breathe and lead with a tinted glyph
/// badge the way the reference layout does.
class SettingsGroup extends StatelessWidget {
  const SettingsGroup({
    super.key,
    required this.title,
    required this.children,
    this.first = false,
  });

  final String title;
  final List<Widget> children;

  /// First group sits closer to the bar; the rest get the section gap.
  final bool first;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final radius = BorderRadius.circular(22);
    final divider = scheme.outlineVariant.withValues(alpha: 0.55);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: EdgeInsets.fromLTRB(8, first ? 4 : 26, 8, 10),
            child: Text(
              title,
              style: AppTextStyle.callout
                  .of(context, emphasized: true)
                  .copyWith(color: scheme.onSurfaceVariant),
            ),
          ),
          DecoratedBox(
            decoration: BoxDecoration(
              color: scheme.surfaceBright,
              borderRadius: radius,
              border: Border.all(
                color: scheme.outlineVariant.withValues(alpha: 0.6),
              ),
            ),
            child: ClipRRect(
              borderRadius: radius,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  for (var index = 0; index < children.length; index++) ...[
                    if (index > 0)
                      Padding(
                        padding: const EdgeInsetsDirectional.only(start: 66),
                        child: Container(height: 1, color: divider),
                      ),
                    children[index],
                  ],
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// A root-settings entry: tinted glyph badge, title, live status value and a
/// chevron. On mobile it opens [pageBuilder] with prism_ui's container
/// transition, the way FlClash's `ListItem.open` does; a row with [onTap]
/// stays plain.
class SettingsEntryRow extends StatelessWidget {
  const SettingsEntryRow({
    super.key,
    required this.glyph,
    required this.title,
    this.value,
    this.tone = SettingsTone.clay,
    this.pageBuilder,
    this.onTap,
  }) : assert(pageBuilder != null || onTap != null);

  final Glyph glyph;
  final String title;

  /// One-line live status on the trailing side; long values ellipsize.
  final String? value;
  final SettingsTone tone;
  final WidgetBuilder? pageBuilder;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final value = this.value;
    Widget row(VoidCallback open) => PressSurface(
      fill: const Color(0x00000000),
      shape: AppShape.all(AppCorner.md),
      onPressed: open,
      padding: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 10, 10, 10),
        child: Row(
          children: [
            _GlyphBadge(glyph: glyph, tone: tone),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: AppTextStyle.callout.of(context),
              ),
            ),
            if (value != null && value.isNotEmpty) ...[
              const SizedBox(width: 10),
              ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 100),
                child: Text(
                  value,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  textAlign: TextAlign.end,
                  style: AppTextStyle.footnote
                      .of(context)
                      .copyWith(color: scheme.onSurfaceVariant),
                ),
              ),
            ],
            const SizedBox(width: 4),
            GlyphIcon(
              Glyphs.chevronForward,
              size: 16,
              color: scheme.onSurfaceVariant.withValues(alpha: 0.8),
            ),
          ],
        ),
      ),
    );

    final pageBuilder = this.pageBuilder;
    if (pageBuilder == null) {
      return row(onTap!);
    }
    return OpenContainer<void>(
      closedShape: AppShape.all(AppCorner.md),
      closedBuilder: (context, open) => row(open),
      openBuilder: (context, _) => pageBuilder(context),
    );
  }
}

/// The circular, tinted badge that gives each hub section its colour.
class _GlyphBadge extends StatelessWidget {
  const _GlyphBadge({required this.glyph, required this.tone});

  final Glyph glyph;
  final SettingsTone tone;

  @override
  Widget build(BuildContext context) {
    final colors = settingsTone(context.colorScheme, tone);
    return Container(
      width: 42,
      height: 42,
      decoration: BoxDecoration(color: colors.fill, shape: BoxShape.circle),
      child: Center(
        child: GlyphIcon(glyph, size: 20, color: colors.ink),
      ),
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
    this.enabled = true,
  });

  final String label;
  final String value;
  final List<PopupMenuItem> options;

  /// A disabled picker keeps its value visible but drops the menu affordance,
  /// which is how a transport in progress locks target edits.
  final bool enabled;

  @override
  State<SettingsPickerRow<T>> createState() => _SettingsPickerRowState<T>();
}

class _SettingsPickerRowState<T> extends State<SettingsPickerRow<T>> {
  PopupOpen? _open;

  @override
  Widget build(BuildContext context) {
    if (!widget.enabled) {
      return SettingsInfoRow(label: widget.label, value: widget.value);
    }
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

class SettingsSwitchRow extends StatelessWidget {
  const SettingsSwitchRow({
    super.key,
    required this.label,
    required this.value,
    required this.onChanged,
  });

  final String label;
  final bool value;

  /// Null renders the switch disabled while keeping its current value.
  final ValueChanged<bool>? onChanged;

  @override
  Widget build(BuildContext context) {
    final onChanged = this.onChanged;
    return DecorationListItem(
      title: Text(label),
      onPressed: onChanged == null ? null : () => onChanged(!value),
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
