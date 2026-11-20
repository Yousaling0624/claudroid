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

  static const stop = Glyph([GlyphBox(7, 7, 17, 17, 2.5)]);

  static const sparkle = Glyph([
    GlyphPolyline(
      [
        GlyphVertex(12, 4),
        GlyphVertex(13.9, 10.1),
        GlyphVertex(20, 12),
        GlyphVertex(13.9, 13.9),
        GlyphVertex(12, 20),
        GlyphVertex(10.1, 13.9),
        GlyphVertex(4, 12),
        GlyphVertex(10.1, 10.1),
      ],
      closed: true,
      role: GlyphRole.line,
    ),
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

  /// A clockwise circular arrow. Both pieces are [GlyphRole.line]: icon buttons
  /// paint with `fill: 1`, and a body-role arc would be filled as a pie there
  /// instead of stroked.
  static const refresh = Glyph([
    GlyphArc(12, 12, 7.5, -0.7, 5.45, role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(9.17, 7.39),
      GlyphVertex(12.28, 4.5),
      GlyphVertex(9.39, 1.39),
    ], role: GlyphRole.line),
  ]);

  /// A branch leaving the conversation: one trunk, a second lane, three nodes.
  static const fork = Glyph([
    GlyphCircle(6.5, 5.5, 2),
    GlyphCircle(6.5, 18.5, 2),
    GlyphCircle(17.5, 9, 2),
    GlyphPolyline([
      GlyphVertex(6.5, 7.5),
      GlyphVertex(6.5, 16.5),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(17.5, 11),
      GlyphVertex(17.5, 12.5),
      GlyphVertex(15, 15),
      GlyphVertex(6.5, 15),
    ], role: GlyphRole.line),
  ]);

  /// Sliders rather than a gear: it stays legible at 14dp and needs no arcs.
  static const settings = Glyph([
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
    GlyphBox(8, 5, 11, 9, 1.6),
    GlyphBox(14, 10, 17, 14, 1.6),
    GlyphBox(7, 15, 10, 19, 1.6),
  ]);

  static const attachment = Glyph([
    GlyphPolyline([
      GlyphVertex(16.5, 7),
      GlyphVertex(9, 14.5),
      GlyphVertex(8.5, 17),
      GlyphVertex(10, 19),
      GlyphVertex(12.5, 19),
      GlyphVertex(19.5, 12),
      GlyphVertex(19.5, 8.5),
      GlyphVertex(17, 6),
      GlyphVertex(13.5, 6),
      GlyphVertex(6.5, 13),
      GlyphVertex(6.5, 16.5),
    ], role: GlyphRole.line),
  ]);

  static const file = Glyph([
    GlyphPolyline(
      [
        GlyphVertex(6, 3.5),
        GlyphVertex(14, 3.5),
        GlyphVertex(18.5, 8),
        GlyphVertex(18.5, 20.5),
        GlyphVertex(6, 20.5),
      ],
      closed: true,
      role: GlyphRole.line,
    ),
    GlyphPolyline([
      GlyphVertex(14, 3.5),
      GlyphVertex(14, 8),
      GlyphVertex(18.5, 8),
    ], role: GlyphRole.detail),
  ]);

  static const image = Glyph([
    GlyphBox(3.5, 5, 20.5, 19, 3),
    GlyphCircle(8.8, 9.8, 1.5, solid: true, role: GlyphRole.detail),
    GlyphPolyline([
      GlyphVertex(5.5, 16.5),
      GlyphVertex(10, 12),
      GlyphVertex(13, 15),
      GlyphVertex(15.5, 12.5),
      GlyphVertex(18.5, 15.5),
    ], role: GlyphRole.detail),
  ]);

  static const code = Glyph([
    GlyphPolyline([
      GlyphVertex(9, 7.5),
      GlyphVertex(4.5, 12),
      GlyphVertex(9, 16.5),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(15, 7.5),
      GlyphVertex(19.5, 12),
      GlyphVertex(15, 16.5),
    ], role: GlyphRole.line),
  ]);

  static const folder = Glyph([
    GlyphPolyline(
      [
        GlyphVertex(4, 7),
        GlyphVertex(9, 7),
        GlyphVertex(11, 9.5),
        GlyphVertex(20, 9.5),
        GlyphVertex(20, 19),
        GlyphVertex(4, 19),
      ],
      closed: true,
      role: GlyphRole.line,
    ),
  ]);

  static const terminal = Glyph([
    GlyphBox(3.5, 4.5, 20.5, 19.5, 3),
    GlyphPolyline([
      GlyphVertex(7, 9.5),
      GlyphVertex(10, 12),
      GlyphVertex(7, 14.5),
    ], role: GlyphRole.detail),
    GlyphPolyline([
      GlyphVertex(12.5, 15),
      GlyphVertex(17, 15),
    ], role: GlyphRole.detail),
  ]);

  static const globe = Glyph([
    GlyphPolyline(
      [
        GlyphVertex(12, 4),
        GlyphVertex(17, 5.5),
        GlyphVertex(19.5, 10),
        GlyphVertex(19, 15),
        GlyphVertex(16, 19),
        GlyphVertex(12, 20),
        GlyphVertex(8, 19),
        GlyphVertex(5, 15),
        GlyphVertex(4.5, 10),
        GlyphVertex(7, 5.5),
      ],
      closed: true,
      role: GlyphRole.line,
    ),
    GlyphPolyline([
      GlyphVertex(4.5, 12),
      GlyphVertex(19.5, 12),
    ], role: GlyphRole.detail),
  ]);

  static const checklist = Glyph([
    GlyphBox(4, 5, 8, 9, 1.5),
    GlyphPolyline([
      GlyphVertex(10.5, 7),
      GlyphVertex(20, 7),
    ], role: GlyphRole.line),
    GlyphBox(4, 13.5, 8, 17.5, 1.5),
    GlyphPolyline([
      GlyphVertex(10.5, 15.5),
      GlyphVertex(20, 15.5),
    ], role: GlyphRole.line),
  ]);

  static const agents = Glyph([
    GlyphPolyline(
      [
        GlyphVertex(12, 4.5),
        GlyphVertex(9.5, 7),
        GlyphVertex(9.5, 10.5),
        GlyphVertex(12, 13),
        GlyphVertex(14.5, 10.5),
        GlyphVertex(14.5, 7),
      ],
      closed: true,
      role: GlyphRole.line,
    ),
    GlyphPolyline([
      GlyphVertex(5, 19.5),
      GlyphVertex(6, 16),
      GlyphVertex(8.5, 14.5),
      GlyphVertex(15.5, 14.5),
      GlyphVertex(18, 16),
      GlyphVertex(19, 19.5),
    ], role: GlyphRole.detail),
  ]);

  static const read = Glyph([
    GlyphPolyline(
      [
        GlyphVertex(3, 12),
        GlyphVertex(7, 7),
        GlyphVertex(12, 5.5),
        GlyphVertex(17, 7),
        GlyphVertex(21, 12),
        GlyphVertex(17, 17),
        GlyphVertex(12, 18.5),
        GlyphVertex(7, 17),
      ],
      closed: true,
      role: GlyphRole.line,
    ),
    GlyphPolyline(
      [
        GlyphVertex(12, 9),
        GlyphVertex(14.5, 12),
        GlyphVertex(12, 15),
        GlyphVertex(9.5, 12),
      ],
      closed: true,
      role: GlyphRole.detail,
    ),
  ]);

  static const search = Glyph([
    GlyphPolyline(
      [
        GlyphVertex(10.5, 4.5),
        GlyphVertex(14.5, 6),
        GlyphVertex(16.5, 10.5),
        GlyphVertex(14.5, 15),
        GlyphVertex(10.5, 16.5),
        GlyphVertex(6.5, 15),
        GlyphVertex(4.5, 10.5),
        GlyphVertex(6.5, 6),
      ],
      closed: true,
      role: GlyphRole.line,
    ),
    GlyphPolyline([
      GlyphVertex(15, 15),
      GlyphVertex(20, 20),
    ], role: GlyphRole.line),
  ]);

  // The four settings-entry glyphs below are ported from FlClash's
  // `AppGlyphs` (key, cpu, paintbrush, sliders); see THIRD_PARTY_NOTICES.md.

  static const key = Glyph([
    GlyphCircle(9.27, 7.99, 5),
    GlyphCircle(9.27, 7.99, 1.4, role: GlyphRole.detail, solid: true),
    GlyphPolyline([
      GlyphVertex(12.8, 11.53),
      GlyphVertex(19.73, 18.46, 0.8),
      GlyphVertex(17.19, 21.01),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(17.19, 15.91),
      GlyphVertex(15.07, 18.04),
    ], role: GlyphRole.line),
  ]);

  static const cpu = Glyph([
    GlyphBox(5.5, 5.5, 18.5, 18.5, 2.2),
    GlyphBox(9.2, 9.2, 14.8, 14.8, 1.2, role: GlyphRole.detail),
    GlyphPolyline([
      GlyphVertex(8.4, 5.5),
      GlyphVertex(8.4, 2.9),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(12, 5.5),
      GlyphVertex(12, 2.9),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(15.6, 5.5),
      GlyphVertex(15.6, 2.9),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(8.4, 18.5),
      GlyphVertex(8.4, 21.1),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(12, 18.5),
      GlyphVertex(12, 21.1),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(15.6, 18.5),
      GlyphVertex(15.6, 21.1),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(5.5, 8.4),
      GlyphVertex(2.9, 8.4),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(5.5, 12),
      GlyphVertex(2.9, 12),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(5.5, 15.6),
      GlyphVertex(2.9, 15.6),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(18.5, 8.4),
      GlyphVertex(21.1, 8.4),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(18.5, 12),
      GlyphVertex(21.1, 12),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(18.5, 15.6),
      GlyphVertex(21.1, 15.6),
    ], role: GlyphRole.line),
  ]);

  static const paintbrush = Glyph([
    GlyphPolyline([
      GlyphVertex(9.1, 10.51, 1.2),
      GlyphVertex(13.49, 14.9, 1.2),
      GlyphVertex(9.95, 18.43, 3.6),
      GlyphVertex(4.08, 20.2, 1),
      GlyphVertex(5.57, 14.05, 3.6),
    ], closed: true),
    GlyphPolyline([
      GlyphVertex(12.64, 8.96, 0.6),
      GlyphVertex(15.04, 11.36, 0.6),
      GlyphVertex(21.4, 5, 1.7),
      GlyphVertex(19, 2.6, 1.7),
    ], closed: true),
  ]);

  static const sliders = Glyph([
    GlyphPolyline([
      GlyphVertex(3.2, 5.5),
      GlyphVertex(10.4, 5.5),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(18.6, 5.5),
      GlyphVertex(20.8, 5.5),
    ], role: GlyphRole.line),
    GlyphCircle(14.5, 5.5, 2.5),
    GlyphPolyline([
      GlyphVertex(3.2, 12),
      GlyphVertex(4.4, 12),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(12.6, 12),
      GlyphVertex(20.8, 12),
    ], role: GlyphRole.line),
    GlyphCircle(8.5, 12, 2.5),
    GlyphPolyline([
      GlyphVertex(3.2, 18.5),
      GlyphVertex(11.4, 18.5),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(19.6, 18.5),
      GlyphVertex(20.8, 18.5),
    ], role: GlyphRole.line),
    GlyphCircle(15.5, 18.5, 2.5),
  ]);

  /// Erlenmeyer flask: neck, tapered body, liquid line and two bubbles.
  static const flask = Glyph([
    GlyphPolyline([
      GlyphVertex(9.6, 3.6, 0.6),
      GlyphVertex(9.6, 8.4, 0.5),
      GlyphVertex(5.2, 17.2, 1.8),
      GlyphVertex(4.8, 19.4, 1.2),
      GlyphVertex(6, 20.4, 1),
      GlyphVertex(18, 20.4, 1),
      GlyphVertex(19.2, 19.4, 1.2),
      GlyphVertex(18.8, 17.2, 1.8),
      GlyphVertex(14.4, 8.4, 0.5),
      GlyphVertex(14.4, 3.6, 0.6),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(8.7, 3.4),
      GlyphVertex(15.3, 3.4),
    ], role: GlyphRole.detail),
    GlyphPolyline([
      GlyphVertex(6.9, 14.8),
      GlyphVertex(17.1, 14.8),
    ], role: GlyphRole.detail),
    GlyphCircle(10.2, 16.8, 0.9, role: GlyphRole.detail, solid: true),
    GlyphCircle(13.8, 17.8, 0.7, role: GlyphRole.detail, solid: true),
  ]);

  /// Clipboard with a heartbeat line: versions, logs and diagnostics.
  static const clipboardPulse = Glyph([
    GlyphBox(4.8, 4.8, 19.2, 20.6, 2.6),
    GlyphBox(9, 2.8, 15, 5.8, 1.4, role: GlyphRole.detail),
    GlyphPolyline([
      GlyphVertex(7.6, 12.6),
      GlyphVertex(10, 12.6),
      GlyphVertex(11.4, 9.6),
      GlyphVertex(13.2, 15.6),
      GlyphVertex(14.6, 12.6),
      GlyphVertex(16.4, 12.6),
    ], role: GlyphRole.detail),
    GlyphPolyline([
      GlyphVertex(8, 17.4),
      GlyphVertex(16, 17.4),
    ], role: GlyphRole.detail),
  ]);

  /// Archive box with an arrow entering it: the backup/export entry.
  static const backup = Glyph([
    GlyphPolyline([
      GlyphVertex(4.5, 9.8),
      GlyphVertex(4.5, 19.6),
      GlyphVertex(19.5, 19.6),
      GlyphVertex(19.5, 9.8),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(3.2, 7),
      GlyphVertex(9, 7),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(15, 7),
      GlyphVertex(20.8, 7),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(12, 2.8),
      GlyphVertex(12, 14.2),
    ], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(9, 11.2),
      GlyphVertex(12, 14.2),
      GlyphVertex(15, 11.2),
    ], role: GlyphRole.line),
  ]);

  static const plug = Glyph([
    GlyphPolyline([GlyphVertex(9, 4), GlyphVertex(9, 9)], role: GlyphRole.line),
    GlyphPolyline([
      GlyphVertex(15, 4),
      GlyphVertex(15, 9),
    ], role: GlyphRole.line),
    GlyphPolyline(
      [
        GlyphVertex(6, 9),
        GlyphVertex(18, 9),
        GlyphVertex(18, 13),
        GlyphVertex(15, 16),
        GlyphVertex(9, 16),
        GlyphVertex(6, 13),
      ],
      closed: true,
      role: GlyphRole.line,
    ),
    GlyphPolyline([
      GlyphVertex(12, 16),
      GlyphVertex(12, 20),
    ], role: GlyphRole.detail),
  ]);
}
