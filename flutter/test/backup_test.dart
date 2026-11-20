import 'dart:convert';

import 'package:claudroid_ui/bridge/events.dart';
import 'package:claudroid_ui/models/backup.dart';
import 'package:claudroid_ui/ui/glyphs.dart';
import 'package:claudroid_ui/ui/settings/settings_summary.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:prism_ui/icons/icons.dart';

const _webDav = BackupConfig(
  kind: BackupTargetKind.webdav,
  webdav: WebDavConfig(
    url: 'https://dav.example.com/remote.php/dav/files/me/claudroid-backup.zip',
    username: 'me',
    password: 'secret',
  ),
);

const _s3 = BackupConfig(
  kind: BackupTargetKind.s3,
  s3: S3Config(bucket: 'backups', accessKey: 'AKIA', secretKey: 's3cr3t'),
);

const _github = BackupConfig(
  kind: BackupTargetKind.github,
  github: GitHubConfig(repo: 'owner/name', token: 'ghp_x'),
);

void main() {
  group('target kind', () {
    test('decodes the wire names and falls back to local', () {
      expect(
        BackupTargetKind.fromWire('WEBDAV'),
        BackupTargetKind.webdav,
      );
      expect(BackupTargetKind.fromWire('S3'), BackupTargetKind.s3);
      expect(BackupTargetKind.fromWire('GITHUB'), BackupTargetKind.github);
      expect(BackupTargetKind.fromWire('LOCAL'), BackupTargetKind.local);
      expect(BackupTargetKind.fromWire('unknown'), BackupTargetKind.local);
      expect(BackupTargetKind.fromWire(null), BackupTargetKind.local);
    });

    test('round-trips to the contract names and labels', () {
      expect(BackupTargetKind.local.wire, 'LOCAL');
      expect(BackupTargetKind.webdav.wire, 'WEBDAV');
      expect(BackupTargetKind.s3.wire, 'S3');
      expect(BackupTargetKind.github.wire, 'GITHUB');
      expect(BackupTargetKind.github.label, 'GitHub 仓库');
      expect(BackupTargetKind.local.label, '仅本地');
    });
  });

  group('config parsing', () {
    test('reads every section of the documented payload', () {
      final config = BackupConfig.fromJsonString(jsonEncode({
        'kind': 'GITHUB',
        'webdav': {
          'url': 'https://dav.example.com/a.zip',
          'username': 'me',
          'password': 'pw',
        },
        's3': {
          'endpoint': 'https://minio.local:9000',
          'region': 'eu-west-1',
          'bucket': 'b',
          'accessKey': 'ak',
          'secretKey': 'sk',
          'prefix': 'nightly',
          'pathStyle': false,
        },
        'github': {
          'repo': 'owner/name',
          'branch': 'dev',
          'path': 'backups/x.zip',
          'token': 't',
        },
      }));
      expect(config.kind, BackupTargetKind.github);
      expect(config.webdav.url, 'https://dav.example.com/a.zip');
      expect(config.webdav.username, 'me');
      expect(config.webdav.password, 'pw');
      expect(config.s3.endpoint, 'https://minio.local:9000');
      expect(config.s3.region, 'eu-west-1');
      expect(config.s3.bucket, 'b');
      expect(config.s3.accessKey, 'ak');
      expect(config.s3.secretKey, 'sk');
      expect(config.s3.prefix, 'nightly');
      expect(config.s3.pathStyle, isFalse);
      expect(config.github.repo, 'owner/name');
      expect(config.github.branch, 'dev');
      expect(config.github.path, 'backups/x.zip');
      expect(config.github.token, 't');
    });

    test('applies the documented defaults for missing sections', () {
      final config = BackupConfig.fromJsonString(null);
      expect(config.kind, BackupTargetKind.local);
      expect(config.s3.region, 'us-east-1');
      expect(config.s3.prefix, 'claudroid');
      expect(config.s3.pathStyle, isTrue);
      expect(config.github.branch, 'main');
      expect(config.github.path, 'claudroid-backup.zip');
      expect(config.webdav.url, '');
    });

    test('keeps an explicitly cleared field instead of defaulting it', () {
      final config = BackupConfig.fromJson({
        'kind': 'S3',
        's3': {'region': '', 'prefix': ''},
      });
      expect(config.s3.region, '');
      expect(config.s3.prefix, '');
    });

    test('a malformed payload falls back instead of throwing', () {
      expect(
        BackupConfig.fromJsonString('{not json').kind,
        BackupTargetKind.local,
      );
      expect(BackupConfig.fromJsonString('[]').kind, BackupTargetKind.local);
      expect(
        BackupConfig.fromJsonString('{"kind":42}').kind,
        BackupTargetKind.local,
      );
    });

    test('toJson round-trips and copyWith preserves other sections', () {
      final config = BackupConfig.fromJsonString(_github.toJsonString());
      expect(config.github.repo, 'owner/name');
      expect(config.github.token, 'ghp_x');

      final renamed = _github.copyWith(
        github: _github.github.copyWith(repo: 'other/repo'),
      );
      expect(renamed.github.repo, 'other/repo');
      expect(renamed.github.branch, 'main');
      expect(renamed.kind, BackupTargetKind.github);

      final switched = _github.copyWith(kind: BackupTargetKind.local);
      expect(switched.github.repo, 'owner/name');
    });
  });

  group('auto backup config', () {
    test('defaults to off and daily, including legacy JSON without auto', () {
      const defaults = BackupConfig();
      expect(defaults.auto.enabled, isFalse);
      expect(defaults.auto.intervalHours, 24);

      final legacy = BackupConfig.fromJsonString(jsonEncode({
        'kind': 'WEBDAV',
        'webdav': {'url': 'https://dav.example.com/a.zip'},
      }));
      expect(legacy.kind, BackupTargetKind.webdav);
      expect(legacy.auto.enabled, isFalse);
      expect(legacy.auto.intervalHours, 24);
    });

    test('reads and round-trips the documented auto block', () {
      final config = BackupConfig.fromJson({
        'kind': 'S3',
        'auto': {'enabled': true, 'intervalHours': 168},
        's3': {'bucket': 'b'},
      });
      expect(config.auto.enabled, isTrue);
      expect(config.auto.intervalHours, 168);
      expect(config.auto.toJson(), {'enabled': true, 'intervalHours': 168});

      final decoded = BackupConfig.fromJsonString(config.toJsonString());
      expect(decoded.auto.enabled, isTrue);
      expect(decoded.auto.intervalHours, 168);
      expect(decoded.s3.bucket, 'b');
    });

    test('copyWith keeps the auto block and other sections', () {
      final config = _webDav.copyWith(
        auto: const AutoBackupConfig(enabled: true, intervalHours: 72),
      );
      expect(config.auto.enabled, isTrue);
      expect(config.auto.intervalHours, 72);
      expect(config.webdav.url, _webDav.webdav.url);

      final switched = config.copyWith(kind: BackupTargetKind.local);
      expect(switched.auto.intervalHours, 72);
      expect(switched.webdav.username, 'me');
    });

    test('malformed auto blocks fall back without throwing', () {
      expect(
        BackupConfig.fromJson({'auto': 'nope'}).auto.intervalHours,
        24,
      );
      expect(
        BackupConfig.fromJson({
          'auto': {'enabled': 'yes', 'intervalHours': 36},
        }).auto.enabled,
        isFalse,
      );
      expect(
        BackupConfig.fromJson({
          'auto': {'intervalHours': 36},
        }).auto.intervalHours,
        24,
      );
      expect(
        BackupConfig.fromJson({
          'auto': {'intervalHours': '168'},
        }).auto.intervalHours,
        168,
      );
      expect(
        BackupConfig.fromJsonString('{"auto": []}').auto.enabled,
        isFalse,
      );
    });
  });

  group('auto interval', () {
    test('normalizes to the three accepted values', () {
      for (final hours in autoBackupIntervalHours) {
        expect(normalizeAutoIntervalHours(hours), hours);
      }
      for (final hours in <int?>[null, 0, 1, 12, 25, 48, 167, 336, -24]) {
        expect(normalizeAutoIntervalHours(hours), 24);
      }
    });

    test('labels the cadence and treats unknown values as daily', () {
      expect(autoIntervalLabel(24), '每天');
      expect(autoIntervalLabel(72), '每 3 天');
      expect(autoIntervalLabel(168), '每周');
      expect(autoIntervalLabel(48), '每天');
    });
  });

  group('missing config', () {
    test('local has no missing fields but is not cloud-ready', () {
      expect(const BackupConfig().issues, isEmpty);
      expect(const BackupConfig().cloudReady, isFalse);
    });

    test('WebDAV needs a complete http(s) file address', () {
      expect(
        const BackupConfig(kind: BackupTargetKind.webdav).issues,
        ['文件地址'],
      );
      expect(
        const BackupConfig(
          kind: BackupTargetKind.webdav,
          webdav: WebDavConfig(url: 'dav.example.com/a.zip'),
        ).issues,
        ['文件地址（需以 http:// 或 https:// 开头）'],
      );
      expect(_webDav.issues, isEmpty);
      expect(_webDav.cloudReady, isTrue);
    });

    test('S3 needs bucket, region, and both keys', () {
      expect(
        const BackupConfig(kind: BackupTargetKind.s3).issues,
        ['存储桶', 'Access Key', 'Secret Key'],
      );
      expect(
        const BackupConfig(
          kind: BackupTargetKind.s3,
          s3: S3Config(region: ''),
        ).issues,
        ['存储桶', '区域', 'Access Key', 'Secret Key'],
      );
      expect(_s3.issues, isEmpty);
      expect(_s3.cloudReady, isTrue);
    });

    test('GitHub needs an owner/name repo and a token', () {
      expect(
        const BackupConfig(kind: BackupTargetKind.github).issues,
        ['仓库（owner/name）', 'Token'],
      );
      expect(
        const BackupConfig(
          kind: BackupTargetKind.github,
          github: GitHubConfig(repo: 'owner', token: 't'),
        ).issues,
        ['仓库格式（owner/name）'],
      );
      expect(
        const BackupConfig(
          kind: BackupTargetKind.github,
          github: GitHubConfig(repo: 'owner/name'),
        ).issues,
        ['Token'],
      );
      expect(_github.issues, isEmpty);
      expect(_github.cloudReady, isTrue);
    });

    test('requirement text explains local and missing fields', () {
      expect(backupRequirementText(const BackupConfig()), contains('仅本地'));
      expect(
        backupRequirementText(const BackupConfig(kind: BackupTargetKind.s3)),
        '还缺：存储桶、Access Key、Secret Key。填好后云端操作才会启用。',
      );
      expect(backupRequirementText(_s3), isEmpty);
    });
  });

  group('auto backup warning', () {
    test('stays empty while disabled or once the target is ready', () {
      expect(autoBackupWarning(const BackupConfig()), isEmpty);
      expect(autoBackupWarning(_webDav), isEmpty);
      expect(
        autoBackupWarning(
          _webDav.copyWith(auto: const AutoBackupConfig(enabled: true)),
        ),
        isEmpty,
      );
    });

    test('asks for a cloud target when enabled on local', () {
      final warning = autoBackupWarning(
        const BackupConfig(auto: AutoBackupConfig(enabled: true)),
      );
      expect(warning, contains('云目标'));
      expect(warning, contains('WebDAV'));
      expect(warning, contains('本地导出'));
    });

    test('names the missing fields when enabled but incomplete', () {
      final warning = autoBackupWarning(
        const BackupConfig(
          kind: BackupTargetKind.s3,
          auto: AutoBackupConfig(enabled: true),
        ),
      );
      expect(warning, contains('存储桶'));
      expect(warning, contains('Access Key'));
      expect(warning, contains('下个周期'));
    });
  });

  group('labels', () {
    test('target label names the host, bucket or repo', () {
      expect(backupTargetLabel(const BackupConfig()), '仅本地');
      expect(backupTargetLabel(_webDav), 'WebDAV · dav.example.com');
      expect(backupTargetLabel(_s3), 'S3 · backups');
      expect(backupTargetLabel(_github), 'GitHub · owner/name');
      expect(
        backupTargetLabel(
          const BackupConfig(kind: BackupTargetKind.webdav),
        ),
        'WebDAV',
      );
    });

    test('action labels cover every documented action', () {
      expect(backupActionLabel('backup'), '备份');
      expect(backupActionLabel('restore'), '恢复');
      expect(backupActionLabel('export'), '导出');
      expect(backupActionLabel('import'), '导入');
      expect(backupActionLabel('test'), '连接测试');
      expect(backupActionLabel(''), '备份');
      expect(backupActionLabel('unknown'), 'unknown');
    });

    test('retry hints point at the fields that failed', () {
      expect(backupRetryHint(_webDav), contains('文件地址'));
      expect(backupRetryHint(_s3), contains('存储桶'));
      expect(backupRetryHint(_github), contains('Token'));
      expect(backupRetryHint(const BackupConfig()), contains('本地导出'));
    });
  });

  group('time formatting', () {
    final now = DateTime(2026, 11, 20, 12);
    int at(DateTime time) => time.millisecondsSinceEpoch;

    test('missing timestamps stay empty', () {
      expect(backupRelativeTime(0, now: now), '');
    });

    test('recent results read as just now, minutes, then hours', () {
      expect(
        backupRelativeTime(at(now.subtract(const Duration(seconds: 20))), now: now),
        '刚刚',
      );
      expect(
        backupRelativeTime(at(now.subtract(const Duration(minutes: 5))), now: now),
        '5 分钟前',
      );
      expect(
        backupRelativeTime(at(now.subtract(const Duration(hours: 2))), now: now),
        '2 小时前',
      );
      expect(
        backupRelativeTime(at(now.add(const Duration(hours: 1))), now: now),
        '刚刚',
      );
    });

    test('older results use yesterday, days, then a date', () {
      expect(
        backupRelativeTime(at(now.subtract(const Duration(hours: 25))), now: now),
        '昨天',
      );
      expect(
        backupRelativeTime(at(now.subtract(const Duration(days: 3))), now: now),
        '3 天前',
      );
      expect(
        backupRelativeTime(at(DateTime(2026, 1, 2)), now: now),
        '1月2日',
      );
      expect(
        backupRelativeTime(at(DateTime(2025, 12, 31)), now: now),
        '2025年12月31日',
      );
    });
  });

  group('status parsing', () {
    test('reads the documented status payload', () {
      final status = BackupStatus.fromJsonString(jsonEncode({
        'phase': 'Working',
        'stage': '打包',
        'detail': '正在读取工作区文件',
        'fraction': 0.42,
        'action': 'backup',
        'lastAt': 1700000000000,
        'lastBytes': 123456,
        'lastAction': 'backup',
        'lastMessage': '已备份到 WebDAV · dav.example.com',
        'lastOk': true,
        'error': null,
      }));
      expect(status.working, isTrue);
      expect(status.stage, '打包');
      expect(status.detail, '正在读取工作区文件');
      expect(status.fraction, 0.42);
      expect(status.action, 'backup');
      expect(status.lastAt, 1700000000000);
      expect(status.lastBytes, 123456);
      expect(status.lastAction, 'backup');
      expect(status.lastMessage, '已备份到 WebDAV · dav.example.com');
      expect(status.lastOk, isTrue);
      expect(status.error, isNull);
    });

    test('missing and malformed values fall back safely', () {
      const idle = BackupStatus();
      expect(idle.phase, 'Idle');
      expect(idle.working, isFalse);
      expect(idle.fraction, 0);
      expect(idle.lastOk, isNull);

      expect(
        BackupStatus.fromJson({'fraction': 3}).fraction,
        1.0,
      );
      expect(
        BackupStatus.fromJson({'fraction': -0.5}).fraction,
        0.0,
      );
      expect(
        BackupStatus.fromJson({'fraction': '0.25'}).fraction,
        0.25,
      );
      expect(
        BackupStatus.fromJson({'fraction': 'oops'}).fraction,
        0.0,
      );
      expect(BackupStatus.fromJsonString('{nope').phase, 'Idle');
    });
  });

  group('results', () {
    test('run result decodes bytes and target', () {
      final result = BackupResult.fromJsonString(jsonEncode({
        'bytes': 123456,
        'sessions': 4,
        'workspaces': 2,
        'files': 38,
        'target': 'WebDAV · dav.example.com',
        'createdAt': 1700000000000,
      }));
      expect(result.bytes, 123456);
      expect(result.sessions, 4);
      expect(result.workspaces, 2);
      expect(result.files, 38);
      expect(result.target, 'WebDAV · dav.example.com');
      expect(result.createdAt, 1700000000000);
    });

    test('restore summary names additions, skips and settings', () {
      final result = RestoreResult.fromJsonString(jsonEncode({
        'sessionsAdded': 2,
        'sessionsSkipped': 1,
        'filesAdded': 10,
        'filesSkipped': 3,
        'workspacesAdded': 1,
        'settingsApplied': true,
        'createdAt': 1700000000000,
      }));
      expect(result.sessionsAdded, 2);
      expect(result.sessionsSkipped, 1);
      expect(result.filesAdded, 10);
      expect(result.filesSkipped, 3);
      expect(result.workspacesAdded, 1);
      expect(result.settingsApplied, isTrue);
      expect(
        restoreResultSummary(result),
        '恢复完成：新增 2 个会话、10 个文件、1 个工作区；跳过 4 项本机已有内容；设置已应用',
      );
    });

    test('an empty restore summary still reads as a sentence', () {
      expect(
        restoreResultSummary(const RestoreResult()),
        '恢复完成：新增 0 个会话、0 个文件',
      );
      expect(
        restoreResultSummary(RestoreResult.fromJsonString('not json')),
        '恢复完成：新增 0 个会话、0 个文件',
      );
    });
  });

  group('root summary', () {
    final now = DateTime(2026, 11, 20, 12);
    int at(DateTime time) => time.millisecondsSinceEpoch;

    test('unconfigured and never-backed-up states are distinct', () {
      expect(
        settingsBackupSummary(const BackupStatus(), const BackupConfig()),
        '未配置',
      );
      expect(
        settingsBackupSummary(const BackupStatus(), _webDav),
        'WebDAV · 未备份',
      );
    });

    test('names the target and the last run time', () {
      final status = BackupStatus(
        lastAt: at(now.subtract(const Duration(hours: 2))),
        lastAction: 'backup',
      );
      expect(
        settingsBackupSummary(status, _webDav, now: now),
        'WebDAV · 2 小时前',
      );
      expect(
        settingsBackupSummary(status, _s3, now: now),
        'S3 · 2 小时前',
      );
      expect(
        settingsBackupSummary(status, _github, now: now),
        'GitHub 仓库 · 2 小时前',
      );
      expect(
        settingsBackupSummary(status, const BackupConfig(), now: now),
        '上次备份 · 2 小时前',
      );
    });

    test('working shows the stage and percent, errors stay short', () {
      expect(
        settingsBackupSummary(
          const BackupStatus(
            phase: 'Working',
            stage: '打包',
            fraction: 0.42,
            action: 'backup',
          ),
          _webDav,
        ),
        '打包 42%',
      );
      expect(
        settingsBackupSummary(
          const BackupStatus(
            phase: 'Working',
            fraction: 0.5,
            action: 'restore',
          ),
          _webDav,
        ),
        '正在恢复 50%',
      );
      expect(
        settingsBackupSummary(
          const BackupStatus(error: '连接超时'),
          _webDav,
        ),
        '备份异常，点击查看',
      );
    });

    test('auto backup names the cadence for a ready cloud target', () {
      final auto = _webDav.copyWith(
        auto: const AutoBackupConfig(enabled: true),
      );
      expect(
        settingsBackupSummary(const BackupStatus(), auto),
        '自动备份 · 每天',
      );
      expect(
        settingsBackupSummary(
          const BackupStatus(),
          auto.copyWith(
            auto: const AutoBackupConfig(enabled: true, intervalHours: 72),
          ),
        ),
        '自动备份 · 每 3 天',
      );
    });

    test('auto backup keeps the last backup time but not other actions', () {
      final auto = _webDav.copyWith(
        auto: const AutoBackupConfig(enabled: true),
      );
      expect(
        settingsBackupSummary(
          BackupStatus(
            lastAt: at(now.subtract(const Duration(hours: 2))),
            lastAction: 'backup',
            lastOk: true,
          ),
          auto,
          now: now,
        ),
        '自动备份 · 每天 · 2 小时前',
      );
      expect(
        settingsBackupSummary(
          BackupStatus(
            lastAt: at(now.subtract(const Duration(hours: 2))),
            lastAction: 'restore',
            lastOk: true,
          ),
          auto,
          now: now,
        ),
        '自动备份 · 每天',
      );
    });

    test('auto backup stays actionable when failed or incomplete', () {
      final auto = _webDav.copyWith(
        auto: const AutoBackupConfig(enabled: true),
      );
      expect(
        settingsBackupSummary(
          BackupStatus(
            lastAt: at(now.subtract(const Duration(hours: 1))),
            lastAction: 'backup',
            lastOk: false,
          ),
          auto,
          now: now,
        ),
        '自动备份异常，点击查看',
      );
      expect(
        settingsBackupSummary(
          const BackupStatus(),
          const BackupConfig(
            kind: BackupTargetKind.s3,
            auto: AutoBackupConfig(enabled: true),
          ),
        ),
        '自动备份待配置',
      );
      expect(
        settingsBackupSummary(const BackupStatus(error: '连接超时'), auto),
        '备份异常，点击查看',
      );
    });

    test('auto backup on a local target keeps the old summary', () {
      const localAuto = BackupConfig(
        auto: AutoBackupConfig(enabled: true),
      );
      expect(
        settingsBackupSummary(const BackupStatus(), localAuto),
        '未配置',
      );
      expect(
        settingsBackupSummary(
          BackupStatus(
            lastAt: at(now.subtract(const Duration(hours: 2))),
            lastAction: 'backup',
            lastOk: true,
          ),
          localAuto,
          now: now,
        ),
        '上次备份 · 2 小时前',
      );
    });
  });

  test('backup events decode status messages and ignore the rest', () {
    final event = BackupEvent.fromMap({
      'type': 'status',
      'statusJson': jsonEncode({'phase': 'Working', 'fraction': 0.5}),
    });
    expect(event, isA<BackupStatusEvent>());
    expect((event! as BackupStatusEvent).status.working, isTrue);
    expect((event as BackupStatusEvent).status.fraction, 0.5);

    expect(BackupEvent.fromMap({'type': 'unknown'}), isNull);
    expect(BackupEvent.fromMap('nope'), isNull);
  });

  test('the backup glyph stays stroke-only for filled icon buttons', () {
    for (final shape in AppGlyphs.backup.shapes) {
      expect(shape.role, GlyphRole.line);
    }
  });
}
