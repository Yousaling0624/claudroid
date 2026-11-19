import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

/// Reveals [child] the way the Compose front end does: 180 ms of height on
/// the chrome ease, 120 ms back out, with the fade finishing before the height
/// so long thinking text does not smear. A zero `motionDuration` (reduce
/// motion) collapses immediately.
///
/// Pass `fade: false` for structural swaps between two state-driven layouts
/// (the live thinking/activity rows and the final work disclosure): the height
/// still eases, but the block no longer cross-fades when a turn starts or ends.
class AnimatedFold extends StatefulWidget {
  const AnimatedFold({
    super.key,
    required this.open,
    this.fade = true,
    required this.child,
  });

  final bool open;
  final bool fade;
  final Widget child;

  @override
  State<AnimatedFold> createState() => _AnimatedFoldState();
}

class _AnimatedFoldState extends State<AnimatedFold>
    with SingleTickerProviderStateMixin {
  static const _openDuration = Duration(milliseconds: 180);
  static const _closeDuration = Duration(milliseconds: 120);
  static const _easeChrome = Cubic(0.0, 0.0, 0.2, 1.0);

  late final AnimationController _controller = AnimationController(
    vsync: this,
    value: widget.open ? 1 : 0,
  )..addStatusListener(_handleStatus);
  late final CurvedAnimation _height = CurvedAnimation(
    parent: _controller,
    curve: _easeChrome,
    reverseCurve: const Cubic(0.4, 0.0, 1.0, 1.0),
  );
  late final CurvedAnimation _fade = CurvedAnimation(
    parent: _controller,
    // 120 of 180 ms: the content is fully opaque before the height settles.
    curve: const Interval(0, 0.67, curve: Curves.easeOut),
  );

  late bool _includeChild = widget.open;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _controller.duration = context.motionDuration(_openDuration);
    _controller.reverseDuration = context.motionDuration(_closeDuration);
  }

  @override
  void didUpdateWidget(covariant AnimatedFold oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.open == oldWidget.open) return;
    if (widget.open) {
      _includeChild = true;
      _controller.forward();
      return;
    }
    if (_controller.isDismissed) {
      _includeChild = false;
    } else {
      _controller.reverse();
    }
  }

  void _handleStatus(AnimationStatus status) {
    if (status != AnimationStatus.dismissed || widget.open || !_includeChild) {
      return;
    }
    setState(() => _includeChild = false);
  }

  @override
  void dispose() {
    _controller.removeStatusListener(_handleStatus);
    _height.dispose();
    _fade.dispose();
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (!_includeChild) return const SizedBox.shrink();
    return ClipRect(
      child: SizeTransition(
        sizeFactor: _height,
        // Leading edge, not center: a narrow child (the live thinking pill) must sit
        // on the transcript's left margin like every other block.
        alignment: AlignmentDirectional.topStart,
        child: widget.fade
            ? FadeTransition(opacity: _fade, child: widget.child)
            : widget.child,
      ),
    );
  }
}

/// The chevron used by every fold header: one glyph, rotated a quarter turn
/// when open, so opening and closing read as the same motion.
class FoldChevron extends StatelessWidget {
  const FoldChevron({
    super.key,
    required this.open,
    this.size = 15,
    this.color,
  });

  final bool open;
  final double size;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    return AnimatedRotation(
      turns: open ? 0.25 : 0,
      duration: context.motionDuration(const Duration(milliseconds: 180)),
      curve: const Cubic(0.0, 0.0, 0.2, 1.0),
      child: GlyphIcon(
        Glyphs.chevronForward,
        size: size,
        color: color ?? context.colorScheme.onSurfaceVariant,
      ),
    );
  }
}
