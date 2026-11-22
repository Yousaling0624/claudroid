/// QR pairing payload printed by `claudroid-bridge start`
/// (`docs/REMOTE_HOST.md` §3 and `docs/BRIDGE_CONTRACT.md` §6.9).
library;

/// The daemon prints a QR containing
/// `claudroid://pair?v=1&url=…&code=…&name=…`. The code is the same
/// one-time secret as the text shown in the terminal, so a malformed or
/// foreign QR must be rejected before it costs a failed pairing attempt.
class PairingPayload {
  const PairingPayload({
    required this.baseUrl,
    required this.code,
    this.name = '',
  });

  final String baseUrl;
  final String code;

  /// Suggested display name (the host's `hostname`); empty when absent.
  final String name;

  static final RegExp _codePattern = RegExp(r'^[A-Z2-9]{8}$');
  static final RegExp _trailingSlash = RegExp(r'/+$');

  /// Parses a scanned QR. Returns null for anything that is not a v1
  /// `claudroid://pair` URI with an http(s) base URL and an 8-char code.
  static PairingPayload? tryParse(String raw) {
    final text = raw.trim();
    if (text.isEmpty || text.length > 4096) return null;
    try {
      return _parse(text);
    } on FormatException {
      // Untrusted input: a broken percent escape must not escape the parser.
      return null;
    }
  }

  static PairingPayload? _parse(String text) {
    final uri = Uri.tryParse(text);
    if (uri == null || uri.scheme != 'claudroid' || uri.host != 'pair') {
      return null;
    }
    final version = uri.queryParameters['v'];
    if (version != null && version != '1') return null;

    final baseUrl = (uri.queryParameters['url'] ?? '').trim();
    final target = Uri.tryParse(baseUrl);
    if (target == null ||
        (target.scheme != 'http' && target.scheme != 'https') ||
        target.host.isEmpty ||
        target.hasQuery ||
        target.hasFragment) {
      return null;
    }

    final code = (uri.queryParameters['code'] ?? '').trim().toUpperCase();
    if (!_codePattern.hasMatch(code)) return null;

    final name = (uri.queryParameters['name'] ?? '').trim();
    return PairingPayload(
      baseUrl: baseUrl.replaceFirst(_trailingSlash, ''),
      code: code,
      name: name.length > 40 ? name.substring(0, 40) : name,
    );
  }
}
