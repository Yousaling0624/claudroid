import 'dart:math' as math;

import 'package:claudroid_ui/ui/glyphs.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:prism_ui/icons/icons.dart';

void main() {
  test('refresh is stroke-only and its head caps the arc', () {
    // 顶栏图标键用 fill: 1 绘制；body 角色的开放弧线会被填充成一块实心扇面，
    // 箭头再作为 detail 被挖掉，看上去就是一团黑。开放线条必须标 line。
    for (final shape in AppGlyphs.refresh.shapes) {
      expect(shape.role, GlyphRole.line);
    }

    final arc = AppGlyphs.refresh.shapes[0] as GlyphArc;
    final head = AppGlyphs.refresh.shapes[1] as GlyphPolyline;
    final apex = head.vertices[1].offset;
    final end = Offset(
      arc.x + arc.radius * math.cos(arc.start + arc.sweep),
      arc.y + arc.radius * math.sin(arc.start + arc.sweep),
    );

    // 箭头顶点必须落在弧线端点上，而不是浮在弧线中间。
    expect(apex.dx, closeTo(end.dx, 0.01));
    expect(apex.dy, closeTo(end.dy, 0.01));
  });

  test('share joins its nodes with line-role strokes', () {
    // The action row is made of icon buttons painting `fill: 1`; a body-role
    // join would fill into solid wedges instead of strokes.
    for (final shape in AppGlyphs.share.shapes) {
      if (shape is GlyphPolyline) {
        expect(shape.role, GlyphRole.line);
      }
    }
    expect(AppGlyphs.share.shapes.whereType<GlyphCircle>(), hasLength(3));
  });
}
