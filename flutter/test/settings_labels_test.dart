import 'package:claudroid_ui/ui/settings/settings_labels.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('bypass mode spells out that commands run directly', () {
    final footnote = permissionFootnote('BYPASS');
    expect(footnote, contains('完全放行'));
    expect(footnote, contains('直接执行'));
  });

  test('other modes use the standard caution', () {
    final footnote = permissionFootnote('AUTO');
    expect(footnote, contains('请确认'));
    expect(footnote, isNot(contains('完全放行')));
  });

  group('networkProxyLabel', () {
    test('empty proxy means automatic', () {
      expect(networkProxyLabel(''), '自动（跟随系统）');
    });

    test('plain proxy is shown as-is', () {
      expect(
        networkProxyLabel('http://127.0.0.1:7890'),
        'http://127.0.0.1:7890',
      );
    });

    test('proxy with credentials is masked', () {
      expect(
        networkProxyLabel('http://user:pas' 's@host:8080'),
        '已设置（含凭据）',
      );
    });
  });

  group('validateNetworkProxy', () {
    test('empty value is allowed', () {
      expect(validateNetworkProxy(''), isNull);
      expect(validateNetworkProxy('   '), isNull);
      expect(validateNetworkProxy(null), isNull);
    });

    test('host and port without scheme gets http:// prepended', () {
      expect(validateNetworkProxy('127.0.0.1:7890'), isNull);
    });

    test('http URL with trailing slash is valid', () {
      expect(validateNetworkProxy('http://host:8080/'), isNull);
    });

    test('https URL is valid', () {
      expect(validateNetworkProxy('https://proxy:8443'), isNull);
    });

    test('socks proxy is rejected with an explanatory message', () {
      expect(validateNetworkProxy('socks5://127.0.0.1:7891'), contains('只支持'));
    });

    test('socks scheme check is case-insensitive', () {
      expect(
        validateNetworkProxy('Socks5://127.0.0.1:7891'),
        contains('只支持'),
      );
    });

    test('scheme without host is rejected', () {
      expect(validateNetworkProxy('http://'), isNotNull);
    });

    test('port out of range is rejected', () {
      expect(validateNetworkProxy('http://host:70000'), isNotNull);
    });
  });
}
