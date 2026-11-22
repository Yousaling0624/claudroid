import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/rendering.dart';
import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import '../models/models.dart';
import 'attachments.dart';
import 'dialog_motion.dart';
import 'glyphs.dart';
import 'web_sources.dart';

/// The text a reply contributes to the clipboard or the system share sheet:
/// visible text only, with trailing source lists and attachment tokens gone,
/// joined the same way the transcript stacks its text blocks.
String replyPlainText(ChatMessage message) => message.blocks
    .whereType<TextBlock>()
    .map(
      (block) =>
          splitAttachmentRefs(WebSources.stripTrailingSources(block.text)).text,
    )
    .where((text) => text.trim().isNotEmpty)
    .join('\n\n')
    .trim();

/// Logical breathing room around a captured reply, in the composed PNG.
const double kReplyImagePadding = 20;

/// The largest PNG side the encoder is asked to produce. `8192` is the
/// practical ceiling for a single Skia texture on Android.
const double _maxImageSide = 8192;

/// The composed image is held twice while it is encoded (raw layer + padded
/// copy), so a phone must not be asked for a 100-megapixel sheet even when both
/// sides are under [_maxImageSide]. 12 MP keeps the peak around 100 MB.
const double _maxImagePixels = 12 * 1000 * 1000;

/// The scale a reply snapshot can be taken at without exceeding
/// [_maxImageSide] on either side or [_maxImagePixels] in total once
/// [kReplyImagePadding] is added.
///
/// A device pixel ratio of 2 is plenty for a shared image, so a 3x screen is
/// downsampled rather than tripling the pixels. Ratios below 0.5 would be too
/// blurry to read; the caller then offers plain text instead. An empty or not
/// yet laid out boundary yields null.
double? replyImagePixelRatio(Size size, double devicePixelRatio) {
  if (size.isEmpty) return null;
  final dpr = devicePixelRatio.isFinite && devicePixelRatio > 0
      ? devicePixelRatio
      : 1.0;
  final width = size.width + 2 * kReplyImagePadding;
  final height = size.height + 2 * kReplyImagePadding;
  final ratio = math.min(
    math.min(dpr, 2.0),
    math.min(
      math.min(_maxImageSide / height, _maxImageSide / width),
      math.sqrt(_maxImagePixels / (width * height)),
    ),
  );
  if (!ratio.isFinite || ratio < 0.5) return null;
  return ratio;
}

/// Renders [boundary] to a PNG with [background] padding around it.
///
/// The padding is added while composing, not on screen: the transcript keeps
/// its own spacing and only the exported image gets the paper margin. Throws
/// [StateError] when the reply exceeds the size limit; callers check
/// [replyImagePixelRatio] first so they can explain that before the capture.
Future<Uint8List> captureReplyImage({
  required RenderRepaintBoundary boundary,
  required double devicePixelRatio,
  required Color background,
}) async {
  final ratio = replyImagePixelRatio(boundary.size, devicePixelRatio);
  if (ratio == null) throw StateError('回复太长');

  final image = await boundary.toImage(pixelRatio: ratio);
  ui.Picture? picture;
  ui.Image? padded;
  try {
    final pad = (kReplyImagePadding * ratio).round();
    final width = image.width + 2 * pad;
    final height = image.height + 2 * pad;
    final recorder = ui.PictureRecorder();
    final canvas = ui.Canvas(recorder);
    canvas.drawRect(
      ui.Rect.fromLTWH(0, 0, width.toDouble(), height.toDouble()),
      ui.Paint()..color = background,
    );
    canvas.drawImage(
      image,
      ui.Offset(pad.toDouble(), pad.toDouble()),
      ui.Paint(),
    );
    picture = recorder.endRecording();
    padded = await picture.toImage(width, height);
    final bytes = await padded.toByteData(format: ui.ImageByteFormat.png);
    if (bytes == null) throw StateError('PNG 编码失败');
    return bytes.buffer.asUint8List(bytes.offsetInBytes, bytes.lengthInBytes);
  } finally {
    picture?.dispose();
    padded?.dispose();
    image.dispose();
  }
}

enum ReplyShareChoice { text, image }

/// Asks whether this reply goes out as text or as one long PNG.
Future<ReplyShareChoice?> showReplyShareDialog(BuildContext context) {
  return showClaudroidDialog<ReplyShareChoice>(
    context: context,
    child: const _ReplyShareDialog(),
  );
}

class _ReplyShareDialog extends StatelessWidget {
  const _ReplyShareDialog();

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: '分享回复',
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
      ],
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _ChoiceRow(
            index: 0,
            glyph: AppGlyphs.share,
            title: '分享文字',
            subtitle: '发送纯文本，可存进便签或聊天应用',
            onTap: () => Navigator.of(context).pop(ReplyShareChoice.text),
          ),
          _ChoiceRow(
            index: 1,
            glyph: AppGlyphs.image,
            title: '分享图片',
            subtitle: '生成一张带留白的长图，可存相册或便签',
            onTap: () => Navigator.of(context).pop(ReplyShareChoice.image),
          ),
        ],
      ),
    );
  }
}

class _ChoiceRow extends StatelessWidget {
  const _ChoiceRow({
    required this.index,
    required this.glyph,
    required this.title,
    required this.subtitle,
    required this.onTap,
  });

  final int index;
  final Glyph glyph;
  final String title;
  final String subtitle;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    return DialogRowReveal(
      index: index,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 10),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(
                padding: const EdgeInsets.only(top: 2),
                child: GlyphIcon(
                  glyph,
                  size: 20,
                  color: scheme.onSurfaceVariant,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: context.listTitleStyle),
                    const SizedBox(height: 2),
                    Text(subtitle, style: context.listSubtitleStyle),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
