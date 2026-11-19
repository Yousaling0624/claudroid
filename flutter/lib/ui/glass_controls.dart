import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/physics.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:prism_ui/icons/icons.dart';
import 'package:prism_ui/prism_ui.dart';

/// A primary action button on FlClash-style liquid glass. Destructive actions
/// keep prism_ui's plain [TonalButton] so their red affordance is unchanged.
class GlassTonalButton extends StatelessWidget {
  const GlassTonalButton({
    super.key,
    required this.label,
    required this.onPressed,
    this.glyph,
    this.size = TonalButtonSize.compact,
  });

  final String label;
  final VoidCallback? onPressed;
  final Glyph? glyph;
  final TonalButtonSize size;

  @override
  Widget build(BuildContext context) {
    return LiquidGlassMorph(
      child: TonalButtonTheme(
        glass: true,
        grouped: false,
        size: size,
        child: glyph == null
            ? FilledButton(onPressed: onPressed, child: Text(label))
            : FilledButton.icon(
                onPressed: onPressed,
                icon: GlyphIcon(glyph!),
                label: Text(label),
              ),
      ),
    );
  }
}

/// A liquid-glass control that swells while held, stretches after the finger,
/// and springs back when let go.
///
/// The morph starts only after [kLongPressTimeout] with the finger still
/// inside [kTouchSlop], so a tap, a scroll, and every other gesture stay with
/// the child. The child keeps its resting layout; [Transform] only paints (and
/// hit-tests) it deformed, which is what gives the glass its elastic look
/// without disturbing the bar's spacing.
class LiquidGlassMorph extends StatefulWidget {
  const LiquidGlassMorph({super.key, required this.child});

  final Widget child;

  @override
  State<LiquidGlassMorph> createState() => _LiquidGlassMorphState();
}

/// How far a drag stretches each axis, and the largest pull it can reach.
const double _maxSwell = 0.12;
const double _stretchReach = 90;
const double _maxStretch = 0.18;
const double _maxPull = 14;

// The dock's own elastics: a fast, lightly bouncy swell and a tight track.
final SpringDescription _pressSpring = SpringDescription.withDampingRatio(
  mass: 1,
  stiffness: 311,
  ratio: 0.593,
);
final SpringDescription _trackSpring = SpringDescription.withDurationAndBounce(
  duration: const Duration(milliseconds: 120),
);

class _LiquidGlassMorphState extends State<LiquidGlassMorph>
    with TickerProviderStateMixin {
  static const _offsetKey = ValueKey('liquidGlassMorphOffset');
  static const _scaleKey = ValueKey('liquidGlassMorphScale');

  late final _MorphSpring _lift = _MorphSpring(this);
  late final _MorphSpring _pullX = _MorphSpring(this);
  late final _MorphSpring _pullY = _MorphSpring(this);
  late final Listenable _motion = Listenable.merge([_lift, _pullX, _pullY]);

  Timer? _hold;
  int? _pointer;
  Offset _origin = Offset.zero;
  bool _morphing = false;
  bool _reduceMotion = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _reduceMotion = MediaQuery.maybeDisableAnimationsOf(context) ?? false;
  }

  @override
  void dispose() {
    _hold?.cancel();
    _lift.dispose();
    _pullX.dispose();
    _pullY.dispose();
    super.dispose();
  }

  void _handlePointerDown(PointerDownEvent event) {
    if (_reduceMotion || _pointer != null) return;
    if (event.buttons & kPrimaryButton == 0) return;
    _pointer = event.pointer;
    _origin = event.position;
    _hold?.cancel();
    _hold = Timer(kLongPressTimeout, _beginMorph);
  }

  void _beginMorph() {
    _hold = null;
    if (!mounted || _pointer == null) return;
    _morphing = true;
    unawaited(HapticFeedback.selectionClick());
    _lift.springTo(1, _pressSpring);
  }

  void _handlePointerMove(PointerMoveEvent event) {
    if (event.pointer != _pointer) return;
    final delta = event.position - _origin;
    if (!_morphing) {
      // The finger is scrolling or dragging, not holding: cancel the morph and
      // leave the gesture to the child.
      if (delta.distance > kTouchSlop) {
        _hold?.cancel();
        _hold = null;
        _pointer = null;
      }
      return;
    }
    _pullX.springTo(_rubberBand(delta.dx, _maxPull), _trackSpring);
    _pullY.springTo(_rubberBand(delta.dy, _maxPull), _trackSpring);
  }

  void _handlePointerEnd(PointerEvent event) {
    if (event.pointer != _pointer) return;
    _hold?.cancel();
    _hold = null;
    _pointer = null;
    if (!_morphing) return;
    _morphing = false;
    _pullX.springTo(0, _pressSpring);
    _pullY.springTo(0, _pressSpring);
    _lift.springTo(0, _pressSpring);
  }

  /// Follows 55% of a short drag, easing to [limit] however far it goes.
  double _rubberBand(double value, double limit) {
    final pull = 1 - 1 / (value.abs() * 0.55 / limit + 1);
    return limit * pull * value.sign;
  }

  double _stretchOf(double pull) =>
      1 + math.min(pull.abs() / _stretchReach, _maxStretch);

  @override
  Widget build(BuildContext context) {
    return Listener(
      onPointerDown: _handlePointerDown,
      onPointerMove: _handlePointerMove,
      onPointerUp: _handlePointerEnd,
      onPointerCancel: _handlePointerEnd,
      child: AnimatedBuilder(
        animation: _motion,
        child: widget.child,
        builder: (context, child) => Transform.translate(
          key: _offsetKey,
          offset: Offset(_pullX.value, _pullY.value),
          child: Transform.scale(
            key: _scaleKey,
            scaleX: (1 + _maxSwell * _lift.value) * _stretchOf(_pullX.value),
            scaleY: (1 + _maxSwell * _lift.value) * _stretchOf(_pullY.value),
            child: child,
          ),
        ),
      ),
    );
  }
}

/// A spring on a ticker that keeps its clock running when retargeted, so a
/// stream of pointer moves never restarts the animation from t=0.
class _MorphSpring extends ChangeNotifier implements ValueListenable<double> {
  _MorphSpring(TickerProvider vsync) {
    _ticker = vsync.createTicker(_tick);
  }

  static const double _rest = 1e-3;

  late final Ticker _ticker;
  double _value = 0;
  double _target = 0;
  double _now = 0;
  double _start = 0;
  SpringSimulation? _simulation;

  @override
  double get value => _value;

  double get velocity => _simulation?.dx(_now - _start) ?? 0;

  void springTo(double target, SpringDescription spring) {
    _simulation = SpringSimulation(
      spring,
      _value,
      target,
      velocity,
      tolerance: Tolerance(
        distance: _rest,
        velocity: _rest * math.sqrt(spring.stiffness / spring.mass),
      ),
    );
    _target = target;
    if (_ticker.isActive) {
      _start = _now;
      return;
    }
    _now = _start = 0;
    _ticker.start();
  }

  void _tick(Duration elapsed) {
    _now = elapsed.inMicroseconds / Duration.microsecondsPerSecond;
    final simulation = _simulation!;
    final time = _now - _start;
    if (simulation.isDone(time)) {
      _value = _target;
      _simulation = null;
      _ticker.stop();
    } else {
      _value = simulation.x(time);
    }
    notifyListeners();
  }

  @override
  void dispose() {
    _ticker.dispose();
    super.dispose();
  }
}
