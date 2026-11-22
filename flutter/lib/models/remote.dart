/// Wire models for remote hosts (`docs/BRIDGE_CONTRACT.md` §6.9).
///
/// Host tokens never cross the bridge, so nothing in this file can hold one.
/// The list and its runtime phases arrive as one `hostsJson` document; the
/// chat permission cards arrive on the per-session channel.
library;

import 'dart:convert';

// ---------------------------------------------------------------------------
// Hosts
// ---------------------------------------------------------------------------

class RemoteTunnelStatus {
  const RemoteTunnelStatus({
    this.mode = 'off',
    this.state = 'off',
    this.url,
    this.error,
    this.changedAt = 0,
  });

  /// `off`, `quick`, `named` or `external`.
  final String mode;

  /// `off`, `starting`, `up`, `error` or `stopped`.
  final String state;
  final String? url;
  final String? error;
  final int changedAt;

  bool get up => state == 'up' && (url ?? '').isNotEmpty;
  bool get starting => state == 'starting';

  String get modeLabel => switch (mode) {
        'quick' => '临时隧道',
        'named' => '固定隧道',
        'external' => '外部隧道',
        _ => '未开启',
      };

  String get stateLabel => switch (state) {
        'starting' => '正在启动',
        'up' => '已开启',
        'error' => '隧道异常',
        'stopped' => '已停止',
        _ => '未开启',
      };

  factory RemoteTunnelStatus.fromJson(Map<String, dynamic> json) =>
      RemoteTunnelStatus(
        mode: json['mode'] as String? ?? 'off',
        state: json['state'] as String? ?? 'off',
        url: json['url'] as String?,
        error: json['error'] as String?,
        changedAt: (json['changedAt'] as num?)?.toInt() ?? 0,
      );

  Map<String, dynamic> toJson() => {
        'mode': mode,
        'state': state,
        'url': url,
        'error': error,
        'changedAt': changedAt,
      };
}

class RemoteHost {
  const RemoteHost({
    required this.id,
    required this.name,
    required this.baseUrl,
    this.hostName = '',
    this.platform = '',
    this.protocol = 0,
    this.defaultCwd = '',
    this.workspaceRoots = const [],
    this.lastSeenAt = 0,
    this.phase = 'offline',
    this.error,
    this.hasAccessToken = false,
    this.tunnel = const RemoteTunnelStatus(),
  });

  final String id;

  /// The user's label; falls back to the daemon's host name when empty.
  final String name;
  final String baseUrl;
  final String hostName;
  final String platform;
  final int protocol;
  final String defaultCwd;
  final List<String> workspaceRoots;
  final int lastSeenAt;

  /// `offline`, `connecting`, `online` or `error`.
  final String phase;
  final String? error;
  final bool hasAccessToken;
  final RemoteTunnelStatus tunnel;

  bool get online => phase == 'online';
  bool get connecting => phase == 'connecting';
  bool get failed => phase == 'error';

  String get displayName {
    if (name.trim().isNotEmpty) return name.trim();
    if (hostName.trim().isNotEmpty) return hostName.trim();
    return baseUrl;
  }

  String get phaseLabel => switch (phase) {
        'online' => '在线',
        'connecting' => '连接中',
        'error' => '连接失败',
        _ => '离线',
      };

  factory RemoteHost.fromJson(Map<String, dynamic> json) => RemoteHost(
        id: json['id'] as String? ?? '',
        name: json['name'] as String? ?? '',
        baseUrl: json['baseUrl'] as String? ?? '',
        hostName: json['hostName'] as String? ?? '',
        platform: json['platform'] as String? ?? '',
        protocol: (json['protocol'] as num?)?.toInt() ?? 0,
        defaultCwd: json['defaultCwd'] as String? ?? '',
        workspaceRoots: (json['workspaceRoots'] as List<dynamic>? ?? const [])
            .map((entry) => '$entry')
            .toList(growable: false),
        lastSeenAt: (json['lastSeenAt'] as num?)?.toInt() ?? 0,
        phase: json['phase'] as String? ?? 'offline',
        error: json['error'] as String?,
        hasAccessToken: json['hasAccessToken'] as bool? ?? false,
        tunnel: RemoteTunnelStatus.fromJson(
          (json['tunnel'] as Map<String, dynamic>?) ?? const {},
        ),
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'baseUrl': baseUrl,
        'hostName': hostName,
        'platform': platform,
        'protocol': protocol,
        'defaultCwd': defaultCwd,
        'workspaceRoots': workspaceRoots,
        'lastSeenAt': lastSeenAt,
        'phase': phase,
        'error': error,
        'hasAccessToken': hasAccessToken,
        'tunnel': tunnel.toJson(),
      };
}

class RemoteHostsState {
  const RemoteHostsState({this.defaultHostId, this.hosts = const []});

  final String? defaultHostId;
  final List<RemoteHost> hosts;

  bool get isEmpty => hosts.isEmpty;

  RemoteHost? byId(String? id) {
    if (id == null || id.isEmpty) return null;
    for (final host in hosts) {
      if (host.id == id) return host;
    }
    return null;
  }

  RemoteHost? get defaultHost => byId(defaultHostId);

  factory RemoteHostsState.fromJson(Map<String, dynamic> json) =>
      RemoteHostsState(
        defaultHostId: json['defaultHostId'] as String?,
        hosts: (json['hosts'] as List<dynamic>? ?? const [])
            .whereType<Map<String, dynamic>>()
            .map(RemoteHost.fromJson)
            .toList(growable: false),
      );

  factory RemoteHostsState.fromJsonString(String? source) {
    if (source == null || source.isEmpty) return const RemoteHostsState();
    final Object? decoded;
    try {
      decoded = jsonDecode(source);
    } on FormatException {
      return const RemoteHostsState();
    }
    return decoded is Map<String, dynamic>
        ? RemoteHostsState.fromJson(decoded)
        : const RemoteHostsState();
  }

  Map<String, dynamic> toJson() => {
        'defaultHostId': defaultHostId,
        'hosts': [for (final host in hosts) host.toJson()],
      };
}

/// One remote file-system entry. Unlike [WorkspaceEntry], `path` is absolute
/// on the host and is fed straight back into `hosts.browse`.
class RemoteFileEntry {
  const RemoteFileEntry({
    required this.name,
    required this.path,
    required this.isDir,
    this.size = 0,
    this.mtimeMs = 0,
  });

  final String name;
  final String path;
  final bool isDir;
  final int size;
  final int mtimeMs;

  factory RemoteFileEntry.fromJson(Map<String, dynamic> json) =>
      RemoteFileEntry(
        name: json['name'] as String? ?? '',
        path: json['path'] as String? ?? '',
        isDir: json['isDir'] as bool? ?? false,
        size: (json['size'] as num?)?.toInt() ?? 0,
        mtimeMs: (json['mtimeMs'] as num?)?.toInt() ?? 0,
      );
}

class RemoteFileListing {
  const RemoteFileListing({required this.path, required this.entries});

  final String path;
  final List<RemoteFileEntry> entries;

  /// Matches the daemon's POSIX paths; never goes above `/`.
  String? get parent {
    if (path.isEmpty || path == '/') return null;
    final withoutTrailing = path.endsWith('/')
        ? path.substring(0, path.length - 1)
        : path;
    final slash = withoutTrailing.lastIndexOf('/');
    if (slash < 0) return null;
    return slash == 0 ? '/' : withoutTrailing.substring(0, slash);
  }
}

class RemoteHealth {
  const RemoteHealth({
    this.protocol = 0,
    this.daemonVersion = '',
    this.claudeVersion = '',
    this.hostName = '',
    this.platform = '',
    this.arch = '',
    this.workspaceRoots = const [],
    this.defaultCwd = '',
    this.defaultModel,
    this.permissionModes = const [],
    this.allowBypassPermissions = false,
    this.tunnel = const RemoteTunnelStatus(),
  });

  final int protocol;
  final String daemonVersion;
  final String claudeVersion;
  final String hostName;
  final String platform;
  final String arch;
  final List<String> workspaceRoots;
  final String defaultCwd;
  final String? defaultModel;
  final List<String> permissionModes;
  final bool allowBypassPermissions;
  final RemoteTunnelStatus tunnel;

  factory RemoteHealth.fromJson(Map<String, dynamic> json) => RemoteHealth(
        protocol: (json['protocol'] as num?)?.toInt() ?? 0,
        daemonVersion: json['daemonVersion'] as String? ?? '',
        claudeVersion: json['claudeVersion'] as String? ?? '',
        hostName: json['hostName'] as String? ?? '',
        platform: json['platform'] as String? ?? '',
        arch: json['arch'] as String? ?? '',
        workspaceRoots: (json['workspaceRoots'] as List<dynamic>? ?? const [])
            .map((entry) => '$entry')
            .toList(growable: false),
        defaultCwd: json['defaultCwd'] as String? ?? '',
        defaultModel: json['defaultModel'] as String?,
        permissionModes:
            (json['permissionModes'] as List<dynamic>? ?? const [])
                .map((entry) => '$entry')
                .toList(growable: false),
        allowBypassPermissions:
            json['allowBypassPermissions'] as bool? ?? false,
        tunnel: RemoteTunnelStatus.fromJson(
          (json['tunnel'] as Map<String, dynamic>?) ?? const {},
        ),
      );
}

// ---------------------------------------------------------------------------
// Permissions
// ---------------------------------------------------------------------------

/// One tool call waiting for the user's decision on the phone. Host wall-clock
/// `expiresAt` drives the countdown only; the daemon still fails closed.
class PendingPermission {
  const PendingPermission({
    required this.requestId,
    required this.toolName,
    this.inputJson = '',
    this.title,
    this.description,
    this.expiresAt = 0,
  });

  final String requestId;
  final String toolName;
  final String inputJson;
  final String? title;
  final String? description;
  final int expiresAt;

  String get heading {
    final custom = title?.trim() ?? '';
    if (custom.isNotEmpty) return custom;
    return '主机请求运行 $toolName';
  }

  /// A short, human-readable digest of the tool input. Commands and paths are
  /// what the user must actually judge; everything else is compacted raw JSON.
  String get summary {
    final raw = inputJson.trim();
    if (raw.isEmpty) return '';
    Object? decoded;
    try {
      decoded = jsonDecode(raw);
    } on FormatException {
      return _clip(raw);
    }
    if (decoded is! Map<String, dynamic>) return _clip(raw);
    for (final key in const [
      'command',
      'file_path',
      'path',
      'pattern',
      'url',
      'query',
      'description',
    ]) {
      final value = decoded[key];
      if (value is String && value.trim().isNotEmpty) {
        return _clip(value.trim());
      }
    }
    final content = decoded['content'] ?? decoded['new_string'];
    if (content is String && content.trim().isNotEmpty) {
      return _clip(content.trim());
    }
    return _clip(raw);
  }

  int remainingMs([DateTime? now]) {
    if (expiresAt <= 0) return 0;
    return expiresAt - (now ?? DateTime.now()).millisecondsSinceEpoch;
  }

  factory PendingPermission.fromJson(Map<String, dynamic> json) =>
      PendingPermission(
        requestId: json['requestId'] as String? ?? '',
        toolName: json['toolName'] as String? ?? '工具',
        inputJson: json['inputJson'] as String? ?? '',
        title: json['title'] as String?,
        description: json['description'] as String?,
        expiresAt: (json['expiresAt'] as num?)?.toInt() ?? 0,
      );
}

String _clip(String value, [int limit = 280]) {
  final collapsed = value.replaceAll(RegExp(r'\s+'), ' ').trim();
  return collapsed.length <= limit
      ? collapsed
      : '${collapsed.substring(0, limit)}…';
}
