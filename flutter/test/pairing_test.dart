import 'package:claudroid_ui/models/pairing.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('PairingPayload.tryParse', () {
    test('parses the daemon QR payload', () {
      final payload = PairingPayload.tryParse(
        'claudroid://pair?v=1&url=https%3A%2F%2Fclaude.example.com'
        '&code=abcdefgh&name=dev%20box',
      );
      expect(payload, isNotNull);
      expect(payload!.baseUrl, 'https://claude.example.com');
      expect(payload.code, 'ABCDEFGH');
      expect(payload.name, 'dev box');
    });

    test('accepts a missing version and an optional name', () {
      final payload = PairingPayload.tryParse(
        'claudroid://pair?url=http://192.168.1.5:8787&code=ABCDEFGH',
      );
      expect(payload, isNotNull);
      expect(payload!.baseUrl, 'http://192.168.1.5:8787');
      expect(payload.name, isEmpty);
    });

    test('decodes the + used for spaces by URLSearchParams', () {
      final payload = PairingPayload.tryParse(
        'claudroid://pair?v=1&url=http%3A%2F%2F10.0.0.8%3A8787'
        '&code=ABCDEFGH&name=dev+box',
      );
      expect(payload?.name, 'dev box');
    });

    test('decodes percent-encoded UTF-8 host names', () {
      final payload = PairingPayload.tryParse(
        'claudroid://pair?v=1&url=http%3A%2F%2F10.0.0.8%3A8787'
        '&code=ABCDEFGH&name=%E5%BC%80%E5%8F%91%E6%9C%BA',
      );
      expect(payload?.name, '开发机');
    });

    test('normalizes the code and strips trailing slashes', () {
      final payload = PairingPayload.tryParse(
        'claudroid://pair?v=1&url=https%3A%2F%2Fclaude.example.com%2F'
        '&code=abcdefgh',
      );
      expect(payload?.baseUrl, 'https://claude.example.com');
      expect(payload?.code, 'ABCDEFGH');
    });

    test('rejects foreign schemes, hosts and versions', () {
      expect(
        PairingPayload.tryParse(
          'https://pair?url=https%3A%2F%2Fclaude.example.com&code=ABCDEFGH',
        ),
        isNull,
      );
      expect(
        PairingPayload.tryParse(
          'claudroid://other?url=https%3A%2F%2Fclaude.example.com&code=ABCDEFGH',
        ),
        isNull,
      );
      expect(
        PairingPayload.tryParse(
          'claudroid://pair?v=2&url=https%3A%2F%2Fclaude.example.com'
          '&code=ABCDEFGH',
        ),
        isNull,
      );
    });

    test('rejects malformed codes and bad addresses', () {
      expect(
        PairingPayload.tryParse(
          'claudroid://pair?v=1&url=https%3A%2F%2Fclaude.example.com&code=SHORT',
        ),
        isNull,
      );
      expect(
        PairingPayload.tryParse(
          'claudroid://pair?v=1&url=https%3A%2F%2Fclaude.example.com'
          '&code=ABCDEFG1',
        ),
        isNull,
      );
      expect(
        PairingPayload.tryParse(
          'claudroid://pair?v=1&url=ftp%3A%2F%2Fclaude.example.com'
          '&code=ABCDEFGH',
        ),
        isNull,
      );
      expect(
        PairingPayload.tryParse(
          'claudroid://pair?v=1&url=https%3A%2F%2Fclaude.example.com%2F'
          '%3Fx%3D1&code=ABCDEFGH',
        ),
        isNull,
      );
      expect(PairingPayload.tryParse(''), isNull);
      expect(PairingPayload.tryParse('not a QR'), isNull);
      expect(PairingPayload.tryParse('x' * 5000), isNull);
      // A broken escape in the optional label must not break the pairing data.
      final malformedName = PairingPayload.tryParse(
        'claudroid://pair?v=1&url=http%3A%2F%2F192.168.1.5%3A8787'
        '&code=ABCDEFGH&name=%ZZ',
      );
      expect(malformedName?.code, 'ABCDEFGH');
      expect(malformedName?.baseUrl, 'http://192.168.1.5:8787');
    });

    test('caps the suggested name at the display-name limit', () {
      final payload = PairingPayload.tryParse(
        'claudroid://pair?v=1&url=https%3A%2F%2Fclaude.example.com'
        '&code=ABCDEFGH&name=${'x' * 80}',
      );
      expect(payload?.name.length, 40);
    });
  });
}
