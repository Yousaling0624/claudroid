import 'dart:math' as math;

import 'package:flutter/widgets.dart';
import 'package:prism_ui/prism_ui.dart';

/// A slow organic halo behind the home sparkle: the silhouette breathes
/// between a circle and a soft five-point star while rotating once every
/// 42 seconds. Ported from `ui/components/OrganicHalo.kt`, with a Catmull-Rom
/// approximation of the `graphics-shapes` morph (the outline rhythm matters,
/// not the exact library geometry).
class OrganicHalo extends StatefulWidget {
  const OrganicHalo({
    super.key,
    this.size = 84,
    required this.color,
  });

  final double size;
  final Color color;

  @override
  State<OrganicHalo> createState() => _OrganicHaloState();
}

class _OrganicHaloState extends State<OrganicHalo>
    with SingleTickerProviderStateMixin {
  static const _rotationPeriod = Duration(seconds: 42);
  static const _breathPeriodSeconds = 5.6;

  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: _rotationPeriod,
  );

  bool _static = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _static = context.disableAnimations;
    if (_static) {
      _controller.stop();
    } else if (!_controller.isAnimating) {
      _controller.repeat();
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ExcludeSemantics(
      child: SizedBox(
        width: widget.size,
        height: widget.size,
        child: AnimatedBuilder(
          animation: _controller,
          builder: (context, _) {
            final t = _controller.value;
            final breath =
                0.5 + 0.5 * math.sin(2 * math.pi * t * 42 / _breathPeriodSeconds);
            return CustomPaint(
              painter: _HaloPainter(
                color: widget.color,
                morph: _static ? 0.35 : breath,
                rotation: _static ? 0 : 2 * math.pi * t,
              ),
            );
          },
        ),
      ),
    );
  }
}

class _HaloPainter extends CustomPainter {
  const _HaloPainter({
    required this.color,
    required this.morph,
    required this.rotation,
  });

  final Color color;

  /// 0 = circle, 1 = star; the Compose halo's breathing value.
  final double morph;
  final double rotation;

  static const _points = 10;

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final radius = size.shortestSide / 2;
    final points = <Offset>[];
    for (var i = 0; i < _points; i++) {
      final angle = -math.pi / 2 + 2 * math.pi * i / _points;
      // Alternating radii make the five-point star; the circle is the same
      // ten vertices at radius 1.
      final starRadius = i.isEven ? 1.0 : 0.78;
      final r = 1 + (starRadius - 1) * morph;
      points.add(
        center +
            Offset(math.cos(angle) * r * radius, math.sin(angle) * r * radius),
      );
    }
    final path = _smoothClosedPath(points);
    canvas.save();
    canvas.translate(center.dx, center.dy);
    canvas.rotate(rotation);
    canvas.translate(-center.dx, -center.dy);
    canvas.drawPath(path, Paint()..color = color);
    canvas.restore();
  }

  @override
  bool shouldRepaint(_HaloPainter oldDelegate) =>
      oldDelegate.color != color ||
      oldDelegate.morph != morph ||
      oldDelegate.rotation != rotation;
}

/// Closed Catmull-Rom spline through [points], expressed as cubic segments.
Path _smoothClosedPath(List<Offset> points) {
  final path = Path();
  final n = points.length;
  path.moveTo(points.first.dx, points.first.dy);
  for (var i = 0; i < n; i++) {
    final p0 = points[(i - 1 + n) % n];
    final p1 = points[i];
    final p2 = points[(i + 1) % n];
    final p3 = points[(i + 2) % n];
    final c1 = p1 + (p2 - p0) / 6;
    final c2 = p2 - (p3 - p1) / 6;
    path.cubicTo(c1.dx, c1.dy, c2.dx, c2.dy, p2.dx, p2.dy);
  }
  path.close();
  return path;
}
