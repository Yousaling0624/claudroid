/// Wire models for the backup bridge (`docs/BACKUP.md` §4/§6).
///
/// Every value crosses the channel as a JSON string, and decoding never
/// throws: a malformed or partial payload falls back to the documented
/// defaults so an older host or a half-written config cannot blank the
/// settings page.
library;

import 'dart:convert';

// ---------------------------------------------------------------------------
// Target config
// ---------------------------------------------------------------------------

enum BackupTargetKind {
  local,
  webdav,
  s3,
  github;

  static BackupTargetKind fromWire(String? value) => switch (value) {
        'WEBDAV' => BackupTargetKind.webdav,
        'S3' => BackupTargetKind.s3,
        'GITHUB' => BackupTargetKind.github,
        _ => BackupTargetKind.local,
      };

  String get wire => switch (this) {
        BackupTargetKind.local => 'LOCAL',
        BackupTargetKind.webdav => 'WEBDAV',
        BackupTargetKind.s3 => 'S3',
        BackupTargetKind.github => 'GITHUB',
      };

  String get label => switch (this) {
        BackupTargetKind.local => '仅本地',
        BackupTargetKind.webdav => 'WebDAV',
        BackupTargetKind.s3 => 'S3',
        BackupTargetKind.github => 'GitHub 仓库',
      };
}

class WebDavConfig {
  const WebDavConfig({this.url = '', this.username = '', this.password = ''});

  /// Full URL of the ZIP, not the collection root.
  final String url;
  final String username;
  final String password;

  factory WebDavConfig.fromJson(Map<String, dynamic> json) => WebDavConfig(
        url: _string(json['url']),
        username: _string(json['username']),
        password: _string(json['password']),
      );

  WebDavConfig copyWith({String? url, String? username, String? password}) =>
      WebDavConfig(
        url: url ?? this.url,
        username: username ?? this.username,
        password: password ?? this.password,
      );

  Map<String, dynamic> toJson() => {
        'url': url,
        'username': username,
        'password': password,
      };
}

class S3Config {
  const S3Config({
    this.endpoint = '',
    this.region = 'us-east-1',
    this.bucket = '',
    this.accessKey = '',
    this.secretKey = '',
    this.prefix = 'claudroid',
    this.pathStyle = true,
  });

  /// Empty means Amazon S3, built from [region].
  final String endpoint;
  final String region;
  final String bucket;
  final String accessKey;
  final String secretKey;
  final String prefix;

  /// Path-style (`endpoint/bucket/key`) instead of virtual-host style.
  final bool pathStyle;

  factory S3Config.fromJson(Map<String, dynamic> json) => S3Config(
        endpoint: _string(json['endpoint']),
        region: _string(json['region'], 'us-east-1'),
        bucket: _string(json['bucket']),
        accessKey: _string(json['accessKey']),
        secretKey: _string(json['secretKey']),
        prefix: _string(json['prefix'], 'claudroid'),
        pathStyle: _bool(json['pathStyle'], true),
      );

  S3Config copyWith({
    String? endpoint,
    String? region,
    String? bucket,
    String? accessKey,
    String? secretKey,
    String? prefix,
    bool? pathStyle,
  }) =>
      S3Config(
        endpoint: endpoint ?? this.endpoint,
        region: region ?? this.region,
        bucket: bucket ?? this.bucket,
        accessKey: accessKey ?? this.accessKey,
        secretKey: secretKey ?? this.secretKey,
        prefix: prefix ?? this.prefix,
        pathStyle: pathStyle ?? this.pathStyle,
      );

  Map<String, dynamic> toJson() => {
        'endpoint': endpoint,
        'region': region,
        'bucket': bucket,
        'accessKey': accessKey,
        'secretKey': secretKey,
        'prefix': prefix,
        'pathStyle': pathStyle,
      };
}

class GitHubConfig {
  const GitHubConfig({
    this.repo = '',
    this.branch = 'main',
    this.path = 'claudroid-backup.zip',
    this.token = '',
  });

  /// `owner/name`.
  final String repo;
  final String branch;

  /// Path inside the repository.
  final String path;
  final String token;

  factory GitHubConfig.fromJson(Map<String, dynamic> json) => GitHubConfig(
        repo: _string(json['repo']),
        branch: _string(json['branch'], 'main'),
        path: _string(json['path'], 'claudroid-backup.zip'),
        token: _string(json['token']),
      );

  GitHubConfig copyWith({
    String? repo,
    String? branch,
    String? path,
    String? token,
  }) =>
      GitHubConfig(
        repo: repo ?? this.repo,
        branch: branch ?? this.branch,
        path: path ?? this.path,
        token: token ?? this.token,
      );

  Map<String, dynamic> toJson() => {
        'repo': repo,
        'branch': branch,
        'path': path,
        'token': token,
      };
}

class BackupConfig {
  const BackupConfig({
    this.kind = BackupTargetKind.local,
    this.webdav = const WebDavConfig(),
    this.s3 = const S3Config(),
    this.github = const GitHubConfig(),
  });

  final BackupTargetKind kind;
  final WebDavConfig webdav;
  final S3Config s3;
  final GitHubConfig github;

  factory BackupConfig.fromJson(Map<String, dynamic> json) {
    final webdav = _section(json['webdav']);
    final s3 = _section(json['s3']);
    final github = _section(json['github']);
    return BackupConfig(
      kind: BackupTargetKind.fromWire(_string(json['kind'])),
      webdav: WebDavConfig.fromJson(webdav),
      s3: S3Config.fromJson(s3),
      github: GitHubConfig.fromJson(github),
    );
  }

  factory BackupConfig.fromJsonString(String? source) {
    if (source == null || source.isEmpty) return const BackupConfig();
    try {
      final decoded = jsonDecode(source);
      if (decoded is Map<String, dynamic>) return BackupConfig.fromJson(decoded);
      if (decoded is Map) return BackupConfig.fromJson(_stringKeyed(decoded));
    } on FormatException {
      // A half-written target file must not take the settings page down.
    }
    return const BackupConfig();
  }

  BackupConfig copyWith({
    BackupTargetKind? kind,
    WebDavConfig? webdav,
    S3Config? s3,
    GitHubConfig? github,
  }) =>
      BackupConfig(
        kind: kind ?? this.kind,
        webdav: webdav ?? this.webdav,
        s3: s3 ?? this.s3,
        github: github ?? this.github,
      );

  Map<String, dynamic> toJson() => {
        'kind': kind.wire,
        'webdav': webdav.toJson(),
        's3': s3.toJson(),
        'github': github.toJson(),
      };

  String toJsonString() => jsonEncode(toJson());

  /// What is still missing before a cloud action can run. Empty for
  /// [BackupTargetKind.local] because local export/import has no target config.
  List<String> get issues => backupConfigIssues(this);

  bool get cloudReady =>
      kind != BackupTargetKind.local && issues.isEmpty;
}

// ---------------------------------------------------------------------------
// Status and results
// ---------------------------------------------------------------------------

/// `statusJson` from `app.claudroid/backup` and `backup.status`.
class BackupStatus {
  const BackupStatus({
    this.phase = 'Idle',
    this.stage = '',
    this.detail = '',
    this.fraction = 0,
    this.action = '',
    this.lastAt = 0,
    this.lastBytes = 0,
    this.lastAction = '',
    this.lastMessage = '',
    this.lastOk,
    this.error,
  });

  /// `Idle | Working | Error`.
  final String phase;
  final String stage;
  final String detail;
  final double fraction;
  final String action;
  final int lastAt;
  final int lastBytes;
  final String lastAction;
  final String lastMessage;

  /// Null when this host has never recorded a result.
  final bool? lastOk;
  final String? error;

  bool get working => phase == 'Working';

  factory BackupStatus.fromJson(Map<String, dynamic> json) => BackupStatus(
        phase: _string(json['phase'], 'Idle'),
        stage: _string(json['stage']),
        detail: _string(json['detail']),
        fraction: _fraction(json['fraction']),
        action: _string(json['action']),
        lastAt: _int(json['lastAt']) ?? 0,
        lastBytes: _int(json['lastBytes']) ?? 0,
        lastAction: _string(json['lastAction']),
        lastMessage: _string(json['lastMessage']),
        lastOk: _boolOrNull(json['lastOk']),
        error: _stringOrNull(json['error']),
      );

  factory BackupStatus.fromJsonString(String? source) {
    if (source == null || source.isEmpty) return const BackupStatus();
    try {
      final decoded = jsonDecode(source);
      if (decoded is Map<String, dynamic>) return BackupStatus.fromJson(decoded);
      if (decoded is Map) return BackupStatus.fromJson(_stringKeyed(decoded));
    } on FormatException {
      // Ignore a malformed status; the page still renders its defaults.
    }
    return const BackupStatus();
  }
}

/// `resultJson` from `backup.run`.
class BackupResult {
  const BackupResult({
    this.bytes = 0,
    this.sessions = 0,
    this.workspaces = 0,
    this.files = 0,
    this.target = '',
    this.createdAt = 0,
  });

  final int bytes;
  final int sessions;
  final int workspaces;
  final int files;

  /// Display string computed by the host, e.g. `WebDAV · dav.example.com`.
  final String target;
  final int createdAt;

  factory BackupResult.fromJson(Map<String, dynamic> json) => BackupResult(
        bytes: _int(json['bytes']) ?? 0,
        sessions: _int(json['sessions']) ?? 0,
        workspaces: _int(json['workspaces']) ?? 0,
        files: _int(json['files']) ?? 0,
        target: _string(json['target']),
        createdAt: _int(json['createdAt']) ?? 0,
      );

  factory BackupResult.fromJsonString(String? source) {
    final decoded = _decodeObject(source);
    return decoded == null ? const BackupResult() : BackupResult.fromJson(decoded);
  }
}

/// `resultJson` from `backup.restore` / `backup.import`.
class RestoreResult {
  const RestoreResult({
    this.sessionsAdded = 0,
    this.sessionsSkipped = 0,
    this.filesAdded = 0,
    this.filesSkipped = 0,
    this.workspacesAdded = 0,
    this.settingsApplied = false,
    this.createdAt = 0,
  });

  final int sessionsAdded;
  final int sessionsSkipped;
  final int filesAdded;
  final int filesSkipped;
  final int workspacesAdded;
  final bool settingsApplied;
  final int createdAt;

  factory RestoreResult.fromJson(Map<String, dynamic> json) => RestoreResult(
        sessionsAdded: _int(json['sessionsAdded']) ?? 0,
        sessionsSkipped: _int(json['sessionsSkipped']) ?? 0,
        filesAdded: _int(json['filesAdded']) ?? 0,
        filesSkipped: _int(json['filesSkipped']) ?? 0,
        workspacesAdded: _int(json['workspacesAdded']) ?? 0,
        settingsApplied: _bool(json['settingsApplied'], false),
        createdAt: _int(json['createdAt']) ?? 0,
      );

  factory RestoreResult.fromJsonString(String? source) {
    final decoded = _decodeObject(source);
    return decoded == null
        ? const RestoreResult()
        : RestoreResult.fromJson(decoded);
  }
}

// ---------------------------------------------------------------------------
// Presentation helpers (pure, shared by the page and the root summary)
// ---------------------------------------------------------------------------

/// What cloud actions are still missing, in the order the rows appear.
/// `LOCAL` has no issues: the action is disabled by the kind itself.
List<String> backupConfigIssues(BackupConfig config) {
  switch (config.kind) {
    case BackupTargetKind.local:
      return const [];
    case BackupTargetKind.webdav:
      final url = config.webdav.url.trim();
      if (url.isEmpty) return const ['文件地址'];
      final scheme = Uri.tryParse(url)?.scheme;
      if (scheme != 'http' && scheme != 'https') {
        return const ['文件地址（需以 http:// 或 https:// 开头）'];
      }
      return const [];
    case BackupTargetKind.s3:
      return [
        if (config.s3.bucket.trim().isEmpty) '存储桶',
        if (config.s3.region.trim().isEmpty) '区域',
        if (config.s3.accessKey.trim().isEmpty) 'Access Key',
        if (config.s3.secretKey.trim().isEmpty) 'Secret Key',
      ];
    case BackupTargetKind.github:
      final repo = config.github.repo.trim();
      return [
        if (repo.isEmpty)
          '仓库（owner/name）'
        else if (!_isRepo(repo))
          '仓库格式（owner/name）',
        if (config.github.token.trim().isEmpty) 'Token',
      ];
  }
}

bool _isRepo(String repo) {
  final parts = repo.split('/');
  return parts.length == 2 && parts.every((part) => part.trim().isNotEmpty);
}

/// Why the cloud section is disabled, or an empty string once it is ready.
String backupRequirementText(BackupConfig config) {
  if (config.kind == BackupTargetKind.local) {
    return '当前目标是「仅本地」。选择 WebDAV、S3 或 GitHub 仓库后可云端备份；也可以直接用下方的本地导出。';
  }
  final issues = backupConfigIssues(config);
  if (issues.isEmpty) return '';
  return '还缺：${issues.join('、')}。填好后云端操作才会启用。';
}

/// A target line for the status and the root row, e.g. `WebDAV · dav.example.com`.
String backupTargetLabel(BackupConfig config) {
  switch (config.kind) {
    case BackupTargetKind.local:
      return '仅本地';
    case BackupTargetKind.webdav:
      final host = Uri.tryParse(config.webdav.url.trim())?.host ?? '';
      return host.isEmpty ? 'WebDAV' : 'WebDAV · $host';
    case BackupTargetKind.s3:
      final bucket = config.s3.bucket.trim();
      return bucket.isEmpty ? 'S3' : 'S3 · $bucket';
    case BackupTargetKind.github:
      final repo = config.github.repo.trim();
      return repo.isEmpty ? 'GitHub 仓库' : 'GitHub · $repo';
  }
}

String backupActionLabel(String action) => switch (action) {
      'restore' => '恢复',
      'export' => '导出',
      'import' => '导入',
      'test' => '连接测试',
      '' => '备份',
      _ => action == 'backup' ? '备份' : action,
    };

/// `刚刚 / N 分钟前 / N 小时前 / 昨天 / N 天前 / M月D日 / yyyy年M月D日`.
/// Empty for a missing timestamp so callers can skip the row. [now] is
/// injectable for tests.
String backupRelativeTime(int millis, {DateTime? now}) {
  if (millis <= 0) return '';
  final current = now ?? DateTime.now();
  final time = DateTime.fromMillisecondsSinceEpoch(millis);
  final diff = current.difference(time);
  if (diff.isNegative || diff.inMinutes < 1) return '刚刚';
  if (diff.inHours < 1) return '${diff.inMinutes} 分钟前';
  if (diff.inHours < 24) return '${diff.inHours} 小时前';
  if (diff.inDays == 1) return '昨天';
  if (diff.inDays < 7) return '${diff.inDays} 天前';
  if (time.year == current.year) return '${time.month}月${time.day}日';
  return '${time.year}年${time.month}月${time.day}日';
}

/// The toast after a restore/import, naming what was added and skipped.
String restoreResultSummary(RestoreResult result) {
  final added = <String>[
    '${result.sessionsAdded} 个会话',
    '${result.filesAdded} 个文件',
    if (result.workspacesAdded > 0) '${result.workspacesAdded} 个工作区',
  ];
  final skipped = result.sessionsSkipped + result.filesSkipped;
  return [
    '恢复完成：新增 ${added.join('、')}',
    if (skipped > 0) '跳过 $skipped 项本机已有内容',
    if (result.settingsApplied) '设置已应用',
  ].join('；');
}

/// The next step offered after a failed cloud action.
String backupRetryHint(BackupConfig config) => switch (config.kind) {
      BackupTargetKind.local => '可以改用下方的本地导出。',
      BackupTargetKind.webdav => '检查文件地址、用户名和密码后重试。',
      BackupTargetKind.s3 => '检查存储桶、区域与密钥后重试。',
      BackupTargetKind.github => '检查仓库、分支与 Token 后重试。',
    };

// ---------------------------------------------------------------------------
// Parsing helpers
// ---------------------------------------------------------------------------

Map<String, dynamic> _section(Object? value) {
  if (value is Map<String, dynamic>) return value;
  if (value is Map) return _stringKeyed(value);
  return const {};
}

Map<String, dynamic> _stringKeyed(Map<dynamic, dynamic> source) => {
      for (final entry in source.entries) '${entry.key}': entry.value,
    };

Map<String, dynamic>? _decodeObject(String? source) {
  if (source == null || source.isEmpty) return null;
  try {
    final decoded = jsonDecode(source);
    if (decoded is Map<String, dynamic>) return decoded;
    if (decoded is Map) return _stringKeyed(decoded);
  } on FormatException {
    return null;
  }
  return null;
}

String _string(Object? value, [String fallback = '']) =>
    value is String ? value : fallback;

String? _stringOrNull(Object? value) => value is String ? value : null;

bool _bool(Object? value, bool fallback) =>
    value is bool ? value : fallback;

bool? _boolOrNull(Object? value) => value is bool ? value : null;

int? _int(Object? value) => switch (value) {
      int v => v,
      double v => v.round(),
      String v => int.tryParse(v),
      _ => null,
    };

double _fraction(Object? value) {
  final number = switch (value) {
    num v => v.toDouble(),
    String v => double.tryParse(v),
    _ => null,
  };
  if (number == null || number.isNaN) return 0;
  if (number <= 0) return 0;
  if (number >= 1) return 1;
  return number;
}
