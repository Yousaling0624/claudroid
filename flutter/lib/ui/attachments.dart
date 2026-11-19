import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../bridge/api.dart';
import '../models/models.dart';
import 'glyphs.dart';

/// Attachment references are how the CLI learns about picked files: the host
/// copies every pick into the workspace and the prompt carries
/// `@attachments/<name>` tokens. The interface shows thumbnails instead of
/// those tokens, so the pure split/compose helpers live here and the composer
/// only ever displays [splitAttachmentRefs]'s `text`.
const String kAttachmentPrefix = 'attachments/';

/// Paths arriving from `attachments.pick` already start with `attachments/`,
/// so a reference is exactly `@` followed by that path.
const String _refToken = '@';

/// Splits [raw] into the visible text and the attachment paths it references.
/// Paths are workspace-relative (`attachments/foo.jpg`), matching
/// `attachments.pick`'s return value.
({String text, List<String> paths}) splitAttachmentRefs(String raw) {
  final paths = <String>[];
  final matches = RegExp(
    '(^|\\s)@($kAttachmentPrefix\\S+)',
  ).allMatches(raw);
  for (final match in matches) {
    final path = match.group(2);
    if (path != null && path.isNotEmpty) paths.add(path);
  }
  if (paths.isEmpty) return (text: raw, paths: const []);
  final text = raw
      .replaceAll(RegExp('(^|\\s)@$kAttachmentPrefix\\S+'), ' ')
      .replaceAll(RegExp(r'[ \t]{2,}'), ' ')
      .replaceAll(RegExp(r' *\n *'), '\n')
      .replaceAll(RegExp(r'\n{3,}'), '\n\n')
      .trim();
  return (text: text, paths: paths);
}

/// Builds the prompt actually sent to the host. The visible composer text
/// stays clean; the references are appended on their own line so the CLI reads
/// the files without the interface ever showing a path.
String composeChatPrompt(String text, List<String> paths) {
  final refs = [
    for (final path in paths)
      if (path.trim().isNotEmpty) '$_refToken$path',
  ];
  final trimmed = text.trim();
  if (refs.isEmpty) return trimmed;
  return trimmed.isEmpty ? refs.join(' ') : '$trimmed\n\n${refs.join(' ')}';
}

final RegExp _imageExtension = RegExp(
  r'\.(jpe?g|png|webp|gif|bmp|heic|heif)$',
  caseSensitive: false,
);

bool isImageAttachment(String path) => _imageExtension.hasMatch(path);

String attachmentName(String path) {
  final name = path.split('/').last.trim();
  return name.isEmpty ? path : name;
}

/// A horizontal (or wrapped, in a message bubble) strip of attachment
/// previews. Images render from `workspace.preview` bytes; every other file
/// falls back to a compact file tile with its name.
class AttachmentPreviewStrip extends StatelessWidget {
  const AttachmentPreviewStrip({
    super.key,
    required this.bridge,
    required this.paths,
    this.onRemove,
    this.size = 58,
    this.wrap = false,
    this.padding = const EdgeInsets.fromLTRB(6, 6, 6, 0),
  });

  final Bridge bridge;
  final List<String> paths;
  final ValueChanged<String>? onRemove;
  final double size;
  final bool wrap;
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context) {
    if (paths.isEmpty) return const SizedBox.shrink();
    final chips = [
      for (final path in paths)
        AttachmentChip(
          bridge: bridge,
          path: path,
          size: size,
          onRemove: onRemove == null ? null : () => onRemove!(path),
        ),
    ];
    return Padding(
      padding: padding,
      child: wrap
          ? Wrap(spacing: 6, runSpacing: 6, children: chips)
          : SizedBox(
              height: size,
              child: ListView.separated(
                scrollDirection: Axis.horizontal,
                itemCount: chips.length,
                separatorBuilder: (_, _) => const SizedBox(width: 6),
                itemBuilder: (_, index) => chips[index],
              ),
            ),
    );
  }
}

class AttachmentChip extends StatefulWidget {
  const AttachmentChip({
    super.key,
    required this.bridge,
    required this.path,
    this.size = 58,
    this.onRemove,
  });

  final Bridge bridge;
  final String path;
  final double size;
  final VoidCallback? onRemove;

  @override
  State<AttachmentChip> createState() => _AttachmentChipState();
}

class _AttachmentChipState extends State<AttachmentChip> {
  Future<WorkspacePreview>? _preview;

  @override
  void initState() {
    super.initState();
    if (isImageAttachment(widget.path)) {
      _preview = widget.bridge.previewWorkspace(widget.path);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    final size = widget.size;
    final future = _preview;
    Widget content;
    if (future == null) {
      content = _fileTile(context);
    } else {
      content = FutureBuilder<WorkspacePreview>(
        future: future,
        builder: (context, snapshot) {
          final bytes = snapshot.data?.bytes;
          if (bytes == null || bytes.isEmpty) return _fileTile(context);
          return Image.memory(
            bytes,
            width: size,
            height: size,
            fit: BoxFit.cover,
            gaplessPlayback: true,
            filterQuality: FilterQuality.medium,
          );
        },
      );
    }

    return SizedBox(
      width: size,
      height: size,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Positioned.fill(
            child: ClipRRect(
              borderRadius: AppRadius.sm,
              child: ColoredBox(
                color: scheme.surfaceContainerHigh,
                child: content,
              ),
            ),
          ),
          if (widget.onRemove != null)
            Positioned(
              top: -5,
              right: -5,
              child: GestureDetector(
                onTap: widget.onRemove,
                child: Container(
                  width: 22,
                  height: 22,
                  decoration: BoxDecoration(
                    color: scheme.surfaceContainerHighest,
                    shape: BoxShape.circle,
                    border: Border.all(
                      color: scheme.surface.withValues(alpha: 0.8),
                    ),
                  ),
                  child: Center(
                    child: GlyphIcon(
                      Glyphs.close,
                      size: 11,
                      color: scheme.onSurfaceVariant,
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _fileTile(BuildContext context) {
    final scheme = context.colorScheme;
    final name = attachmentName(widget.path);
    final dot = name.lastIndexOf('.');
    final extension = dot > 0 && dot < name.length - 1
        ? name.substring(dot + 1).toUpperCase()
        : 'FILE';
    return Padding(
      padding: const EdgeInsets.all(6),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          GlyphIcon(
            AppGlyphs.file,
            size: 20,
            color: scheme.onSurfaceVariant,
          ),
          const SizedBox(height: 4),
          Text(
            extension,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: AppTextStyle.caption2
                .of(context)
                .copyWith(color: scheme.onSurfaceVariant),
          ),
        ],
      ),
    );
  }
}
