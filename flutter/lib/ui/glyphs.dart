import 'package:prism_ui/icons/icons.dart';

/// Glyphs prism_ui does not ship. Same 24-unit grid, same stroke roles, so
/// they sit next to [Glyphs] without a second visual language.
abstract final class AppGlyphs {
  static const menu = Glyph([
    GlyphPolyline([
      GlyphVertex(4, 7),
      GlyphVertex(20, 7),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(4, 12),
      GlyphVertex(20, 12),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(4, 17),
      GlyphVertex(20, 17),
    ], role: GlyphRole.line),
  ]);

  static const plus = Glyph([
    GlyphPolyline([
      GlyphVertex(12, 5),
      GlyphVertex(12, 19),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(5, 12),
      GlyphVertex(19, 12),
    ], role: GlyphRole.line),
  ]);

  static const send = Glyph([
    GlyphPolyline([
      GlyphVertex(12, 19),
      GlyphVertex(12, 5),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(6.5, 10.5),
      GlyphVertex(12, 5),
      GlyphVertex(17.5, 10.5),
    ], role: GlyphRole.line),
  ]);

  static const stop = Glyph([
    GlyphBox(7, 7, 17, 17, 2.5),
  ]);

  static const sparkle = Glyph([
    GlyphPolyline([
      GlyphVertex(12, 4),
      GlyphVertex(13.9, 10.1),
      GlyphVertex(20, 12),
      GlyphVertex(13.9, 13.9),
      GlyphVertex(12, 20),
      GlyphVertex(10.1, 13.9),
      GlyphVertex(4, 12),
      GlyphVertex(10.1, 10.1),
    ], closed: true, role: GlyphRole.line),
  ]);

  static const trash = Glyph([
    GlyphPolyline([
      GlyphVertex(5, 7),
      GlyphVertex(19, 7),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(9, 7),
      GlyphVertex(9, 4.5),
      GlyphVertex(15, 4.5),
      GlyphVertex(15, 7),
    ], role: GlyphRole.detail),
    GlyphPolyline([
      GlyphVertex(6.5, 7),
      GlyphVertex(7.5, 20),
      GlyphVertex(16.5, 20),
      GlyphVertex(17.5, 7),
    ], role: GlyphRole.line),
  ]);

  static const edit = Glyph([
    GlyphPolyline([
      GlyphVertex(4, 20),
      GlyphVertex(5, 15.5),
      GlyphVertex(15.5, 5),
      GlyphVertex(19, 8.5),
      GlyphVertex(8.5, 19),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(4, 20),
      GlyphVertex(8.5, 19),
    ], role: GlyphRole.detail),
  ]);

  static const refresh = Glyph([
    GlyphArc(12, 12, 7.5, 3.6, 4.4),
    GlyphPolyline([
      GlyphVertex(18.6, 4.6),
      GlyphVertex(19.4, 9.2),
      GlyphVertex(14.9, 8.2),
    ], role: GlyphRole.detail),
  ]);
}
