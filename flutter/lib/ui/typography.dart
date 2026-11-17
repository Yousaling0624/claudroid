import 'package:flutter/widgets.dart';

/// The assistant/greeting voice. Android resolves `serif` to Noto Serif, and
/// the explicit fallbacks keep CJK text on the serif stack as well; if a device
/// lacks a CJK serif the platform falls back to its sans face without breaking
/// layout.
TextStyle claudroidSerif(TextStyle base) => base.copyWith(
      fontFamily: 'serif',
      fontFamilyFallback: const [
        'Noto Serif CJK SC',
        'Noto Serif SC',
        'Source Han Serif SC',
      ],
    );
