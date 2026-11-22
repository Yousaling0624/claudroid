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

  test('remote permission labels stay in picker order', () {
    expect(remotePermissionLabels.keys.toList(), [
      'auto',
      'default',
      'acceptEdits',
      'plan',
      'bypassPermissions',
    ]);
    expect(remotePermissionLabels['auto'], '自动');
    expect(remotePermissionLabels['default'], '每次确认');
    expect(remotePermissionLabels['acceptEdits'], '自动改文件');
    expect(remotePermissionLabels['plan'], '只读规划');
    expect(remotePermissionLabels['bypassPermissions'], '完全放行');
  });

  test('remote footnote names the host and the run location', () {
    final bypass = remotePermissionFootnote('bypassPermissions');
    expect(bypass, contains('远程主机执行'));
    expect(bypass, contains('命令会在主机上直接执行'));

    // Claude Auto reviews on the host; nothing is forwarded for approval.
    final auto = remotePermissionFootnote('auto');
    expect(auto, contains('远程主机执行'));
    expect(auto, contains('Auto'));
    expect(auto, isNot(contains('工具调用会转发到手机确认')));

    final forwarded = remotePermissionFootnote('default');
    expect(forwarded, contains('远程主机执行'));
    expect(forwarded, contains('工具调用会转发到手机确认'));
    expect(forwarded, isNot(contains('直接执行')));

    // acceptEdits auto-approves edits only; the footnote must not imply that
    // every tool call is forwarded.
    final edits = remotePermissionFootnote('acceptEdits');
    expect(edits, contains('文件修改会自动通过'));
    expect(edits, contains('其他工具调用会转发到手机确认'));

    expect(remotePermissionFootnote('plan'), contains('只读规划'));
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
