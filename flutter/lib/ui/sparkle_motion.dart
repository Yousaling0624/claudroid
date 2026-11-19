import 'dart:math' as math;

import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

import 'glyphs.dart';

/// How long one spin-and-pulse cycle takes. Slow enough that the 14 dp glyph
/// reads as "thinking" rather than as a loading spinner.
const Duration _cycle = Duration(milliseconds: 1600);

/// Half a turn per cycle. Any multiple of a quarter turn lands on the same
/// silhouette (the four-point star is symmetric every 90°), so `repeat()`
/// restarts without a seam while the glyph turns at ~112°/s: clearly spinning
/// at 14 dp, still calm next to the label.
const double _turnsPerCycle = 0.5;

/// Peak of the elastic pulse. At 14 dp this is about 2 dp of extra diameter:
/// visible as a beat, never as a wobble.
const double _scalePeak = 1.15;

/// The pop takes the first 40% of the cycle; the rest settles back. A quick
/// attack and a long, soft release are what make it feel springy instead of
/// like a breath.
const double _popFraction = 0.4;

/// One instant of the loop: rotation in radians and uniform scale around the
/// glyph's centre. Exposed so tests pin the motion directly instead of
/// reconstructing it from frames.
typedef ThinkingSparklePose = ({double angle, double scale});

/// Pose at progress [t] of the cycle (any value; wrapped into `[0, 1)`).
///
/// Pose at the seam equals the pose at rest (angle 0 modulo the glyph's
/// symmetry, scale 1), so repeating the controller never shows a step.
ThinkingSparklePose thinkingSparklePose(double t) {
  final phase = t - t.floorToDouble();
  final angle = 2 * math.pi * _turnsPerCycle * phase;
  final double scale;
  if (phase < _popFraction) {
    scale = 1 + (_scalePeak - 1) * _easeOutCubic(phase / _popFraction);
  } else {
    final settle = (phase - _popFraction) / (1 - _popFraction);
    scale = _scalePeak - (_scalePeak - 1) * _easeInOutSine(settle);
  }
  return (angle: angle, scale: scale);
}

// Both ends at zero velocity; the rise approaches the peak from full speed,
// which is the little "kick" at the start of every cycle.
double _easeOutCubic(double x) {
  final rest = 1 - x;
  return 1 - rest * rest * rest;
}

double _easeInOutSine(double x) => 0.5 - 0.5 * math.cos(math.pi * x);

/// The sparkle beside the live "思考中…" label: one clockwise half turn and
/// one elastic pulse per cycle.
///
/// Both transforms only paint around the glyph's centre; its 14 dp box, the
/// pill's layout and the text baseline never move. Reduce motion (see
/// [context.disableAnimations]) keeps the still glyph and never starts the
/// controller.
class ThinkingSparkle extends StatefulWidget {
  const ThinkingSparkle({
    super.key,
    required this.active,
    required this.color,
    this.size = 14,
  });

  /// Animates only while the turn is streaming; idle shows the resting glyph.
  final bool active;

  /// Token colour for the glyph. The caller keeps the idle/active contrast
  /// (primary while thinking, the muted text role once folded).
  final Color color;

  final double size;

  @override
  State<ThinkingSparkle> createState() => _ThinkingSparkleState();
}

class _ThinkingSparkleState extends State<ThinkingSparkle>
    with SingleTickerProviderStateMixin {
  static const _rotationKey = ValueKey('thinkingSparkleRotation');
  static const _scaleKey = ValueKey('thinkingSparkleScale');

  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: _cycle,
  );

  bool _still = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _still = context.disableAnimations;
    _syncMotion();
  }

  @override
  void didUpdateWidget(covariant ThinkingSparkle oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.active != oldWidget.active) _syncMotion();
  }

  /// Runs only while the turn is live and motion is allowed; anything else
  /// parks the glyph upright. The four-point star looks the same at every
  /// quarter turn, so resetting to 0 cannot show a visible snap.
  void _syncMotion() {
    if (widget.active && !_still) {
      if (!_controller.isAnimating) _controller.repeat();
      return;
    }
    if (_controller.isAnimating) _controller.stop();
    if (_controller.value != 0) _controller.value = 0;
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final glyph = GlyphIcon(
      AppGlyphs.sparkle,
      size: widget.size,
      color: widget.color,
    );
    // Decorative: the pill's label already says what is happening.
    if (!widget.active || _still) {
      return ExcludeSemantics(child: glyph);
    }
    return ExcludeSemantics(
      child: AnimatedBuilder(
        animation: _controller,
        // Only the transforms rebuild; the glyph is painted once and reused.
        child: glyph,
        builder: (context, child) {
          final pose = thinkingSparklePose(_controller.value);
          return Transform.rotate(
            key: _rotationKey,
            angle: pose.angle,
            child: Transform.scale(
              key: _scaleKey,
              scale: pose.scale,
              child: child,
            ),
          );
        },
      ),
    );
  }
}
