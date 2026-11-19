/// Wire models for the Kotlin ↔ Dart bridge.
///
/// Field names mirror `kotlinx.serialization`'s output one-for-one
/// (`docs/BRIDGE_CONTRACT.md` §3–§5). Nothing here does business logic: enum
/// labels, model tier resolution and status text all arrive pre-computed from
/// Kotlin, so a drifting copy in Dart can never disagree with the host.
library;

import 'dart:convert';
import 'dart:typed_data';

// ---------------------------------------------------------------------------
// Blocks
// ---------------------------------------------------------------------------

/// One segment of a message. The wire discriminator is `"type"`, with the four
/// values `text | thinking | tool | error`.
sealed class Block {
  const Block();

  /// Unknown types (a newer Kotlin adding a block kind) collapse to a text
  /// block so an old frontend still renders the turn instead of dropping it.
  static Block fromJson(Map<String, dynamic> json) {
    switch (json['type']) {
      case 'thinking':
        return ThinkingBlock.fromJson(json);
      case 'tool':
        return ToolBlock.fromJson(json);
      case 'error':
        return ErrorBlock.fromJson(json);
      case 'text':
      default:
        return TextBlock.fromJson(json);
    }
  }

  Map<String, dynamic> toJson();
}

class TextBlock extends Block {
  const TextBlock(this.text);

  final String text;

  factory TextBlock.fromJson(Map<String, dynamic> json) =>
      TextBlock(json['text'] as String? ?? '');

  @override
  Map<String, dynamic> toJson() => {'type': 'text', 'text': text};
}

class ThinkingBlock extends Block {
  const ThinkingBlock(this.text);

  final String text;

  factory ThinkingBlock.fromJson(Map<String, dynamic> json) =>
      ThinkingBlock(json['text'] as String? ?? '');

  @override
  Map<String, dynamic> toJson() => {'type': 'thinking', 'text': text};
}

class ToolBlock extends Block {
  const ToolBlock({
    required this.id,
    required this.name,
    required this.input,
    this.output,
    this.isError = false,
    this.running = true,
  });

  final String id;
  final String name;

  /// Raw JSON string of the tool input, exactly as the model produced it.
  final String input;
  final String? output;
  final bool isError;
  final bool running;

  factory ToolBlock.fromJson(Map<String, dynamic> json) => ToolBlock(
        id: json['id'] as String? ?? '',
        name: json['name'] as String? ?? '',
        input: json['input'] as String? ?? '',
        output: json['output'] as String?,
        isError: json['isError'] as bool? ?? false,
        running: json['running'] as bool? ?? false,
      );

  ToolBlock copyWith({
    String? output,
    bool clearOutput = false,
    bool? isError,
    bool? running,
  }) =>
      ToolBlock(
        id: id,
        name: name,
        input: input,
        output: clearOutput ? null : (output ?? this.output),
        isError: isError ?? this.isError,
        running: running ?? this.running,
      );

  @override
  Map<String, dynamic> toJson() => {
        'type': 'tool',
        'id': id,
        'name': name,
        'input': input,
        'output': output,
        'isError': isError,
        'running': running,
      };
}

class ErrorBlock extends Block {
  const ErrorBlock(this.text);

  final String text;

  factory ErrorBlock.fromJson(Map<String, dynamic> json) =>
      ErrorBlock(json['text'] as String? ?? '');

  @override
  Map<String, dynamic> toJson() => {'type': 'error', 'text': text};
}

// ---------------------------------------------------------------------------
// Messages
// ---------------------------------------------------------------------------

enum Role {
  user,
  assistant,
  system;

  static Role fromWire(String? value) => switch (value) {
        'USER' => Role.user,
        'ASSISTANT' => Role.assistant,
        'SYSTEM' => Role.system,
        _ => Role.system,
      };
}

class ChatMessage {
  const ChatMessage({
    required this.id,
    required this.role,
    required this.blocks,
    this.ts = 0,
    this.costUsd,
    this.durationMs,
    this.streaming = false,
    this.model,
  });

  final String id;
  final Role role;
  final List<Block> blocks;
  final int ts;
  final double? costUsd;
  final int? durationMs;
  final bool streaming;

  /// Display label of the model that produced this answer, frozen by Kotlin when
  /// the turn started. Null for records written before the field existed, and for
  /// user/system messages; the assistant header then falls back to the live label.
  final String? model;

  factory ChatMessage.fromJson(Map<String, dynamic> json) => ChatMessage(
        id: json['id'] as String? ?? '',
        role: Role.fromWire(json['role'] as String?),
        blocks: (json['blocks'] as List<dynamic>? ?? const [])
            .whereType<Map<String, dynamic>>()
            .map(Block.fromJson)
            .toList(growable: false),
        ts: _asInt(json['ts']) ?? 0,
        costUsd: _asDouble(json['costUsd']),
        durationMs: _asInt(json['durationMs']),
        streaming: json['streaming'] as bool? ?? false,
        model: json['model'] as String?,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'role': switch (role) {
          Role.user => 'USER',
          Role.assistant => 'ASSISTANT',
          Role.system => 'SYSTEM',
        },
        'blocks': blocks.map((b) => b.toJson()).toList(growable: false),
        'ts': ts,
        'costUsd': costUsd,
        'durationMs': durationMs,
        'streaming': streaming,
        'model': model,
      };

  /// Appends streaming text to the block of [kind] that is currently being
  /// written. Returns `null` when the message no longer has such a block —
  /// the caller then drops the delta and re-syncs with `chat.attach`.
  ChatMessage? appendDelta(String kind, String append) {
    if (blocks.isEmpty) return null;
    final last = blocks.last;
    if (kind == 'thinking' && last is ThinkingBlock) {
      return _replaceLast(ThinkingBlock(last.text + append));
    }
    if (kind == 'text' && last is TextBlock) {
      return _replaceLast(TextBlock(last.text + append));
    }
    // A delta for a kind whose block is not the tail means blocks arrived out
    // of order; the host will resend the full list.
    if ((kind == 'text' && last is ThinkingBlock) ||
        (kind == 'thinking' && last is TextBlock)) {
      return _replaceLast(
        kind == 'text' ? TextBlock(append) : ThinkingBlock(append),
      );
    }
    return null;
  }

  ChatMessage _replaceLast(Block block) => ChatMessage(
        id: id,
        role: role,
        blocks: [...blocks.take(blocks.length - 1), block],
        ts: ts,
        costUsd: costUsd,
        durationMs: durationMs,
        streaming: streaming,
        model: model,
      );

  ChatMessage copyWith({
    List<Block>? blocks,
    double? costUsd,
    int? durationMs,
    bool? streaming,
  }) =>
      ChatMessage(
        id: id,
        role: role,
        blocks: blocks ?? this.blocks,
        ts: ts,
        costUsd: costUsd ?? this.costUsd,
        durationMs: durationMs ?? this.durationMs,
        streaming: streaming ?? this.streaming,
        model: model,
      );
}

// ---------------------------------------------------------------------------
// Sessions
// ---------------------------------------------------------------------------

class ChatSession {
  const ChatSession({
    required this.id,
    required this.title,
    this.createdAt = 0,
    this.updatedAt = 0,
    this.claudeSessionId,
    this.model,
    this.hasContent = true,
    this.preview = '',
    this.searchText = '',
  });

  final String id;
  final String title;
  final int createdAt;
  final int updatedAt;
  final String? claudeSessionId;
  final String? model;

  /// False for a session that was created but never got a message; the drawer
  /// hides those instead of filling itself with empty rows.
  final bool hasContent;

  /// Last message preview, computed by Kotlin.
  final String preview;

  /// Truncated transcript text used for drawer search (the transcript itself
  /// never crosses the bridge).
  final String searchText;

  factory ChatSession.fromJson(Map<String, dynamic> json) => ChatSession(
        id: json['id'] as String? ?? '',
        title: json['title'] as String? ?? '',
        createdAt: _asInt(json['createdAt']) ?? 0,
        updatedAt: _asInt(json['updatedAt']) ?? 0,
        claudeSessionId: json['claudeSessionId'] as String?,
        model: json['model'] as String?,
        hasContent: json['hasContent'] as bool? ?? true,
        preview: json['preview'] as String? ?? '',
        searchText: json['searchText'] as String? ?? '',
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'title': title,
        'createdAt': createdAt,
        'updatedAt': updatedAt,
        'claudeSessionId': claudeSessionId,
        'model': model,
        'hasContent': hasContent,
        'preview': preview,
        'searchText': searchText,
      };
}

// ---------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------

class EngineState {
  const EngineState({
    this.phase = 'Idle',
    this.stage = '',
    this.detail = '',
    this.fraction = 0,
    this.installedVersion,
    this.latestVersion,
    this.arch,
    this.jailPath,
    this.rootMode = false,
    this.rootGranted = false,
    this.chrootAvailable = false,
    this.prootAvailable = false,
    this.error,
    this.selfTest,
    this.ready = false,
    this.engineLabel = '',
  });

  final String phase;
  final String stage;
  final String detail;
  final double fraction;
  final String? installedVersion;
  final String? latestVersion;
  final String? arch;
  final String? jailPath;
  final bool rootMode;
  final bool rootGranted;
  final bool chrootAvailable;
  final bool prootAvailable;
  final String? error;
  final String? selfTest;
  final bool ready;

  /// Derived on the Kotlin side from `settings.engineMode`, never recomputed.
  final String engineLabel;

  factory EngineState.fromJson(Map<String, dynamic> json) => EngineState(
        phase: json['phase'] as String? ?? 'Idle',
        stage: json['stage'] as String? ?? '',
        detail: json['detail'] as String? ?? '',
        fraction: _asDouble(json['fraction']) ?? 0,
        installedVersion: json['installedVersion'] as String?,
        latestVersion: json['latestVersion'] as String?,
        arch: json['arch'] as String?,
        jailPath: json['jailPath'] as String?,
        rootMode: json['rootMode'] as bool? ?? false,
        rootGranted: json['rootGranted'] as bool? ?? false,
        chrootAvailable: json['chrootAvailable'] as bool? ?? false,
        prootAvailable: json['prootAvailable'] as bool? ?? false,
        error: json['error'] as String?,
        selfTest: json['selfTest'] as String?,
        ready: json['ready'] as bool? ?? false,
        engineLabel: json['engineLabel'] as String? ?? '',
      );

  Map<String, dynamic> toJson() => {
        'phase': phase,
        'stage': stage,
        'detail': detail,
        'fraction': fraction,
        'installedVersion': installedVersion,
        'latestVersion': latestVersion,
        'arch': arch,
        'jailPath': jailPath,
        'rootMode': rootMode,
        'rootGranted': rootGranted,
        'chrootAvailable': chrootAvailable,
        'prootAvailable': prootAvailable,
        'error': error,
        'selfTest': selfTest,
        'ready': ready,
        'engineLabel': engineLabel,
      };
}

// ---------------------------------------------------------------------------
// Derived labels
// ---------------------------------------------------------------------------

/// Display names for the four model tiers, copied from `ModelTier.label` in
/// `data/ModelTier.kt`. The bridge sends only the serialized enum name for
/// these, so the frontend keeps the four strings here and nowhere else.
const modelTierLabels = {
  'OPUS': 'Opus',
  'FABLE': 'Fable',
  'SONNET': 'Sonnet',
  'HAIKU': 'Haiku',
};

/// Display strings computed by Kotlin (`ui/components/ModelLabels.kt`).
class ModelLabels {
  const ModelLabels({
    this.composer = '',
    this.chat = '',
    this.short = '',
    this.tierSummary = '',
    this.tierMapping = const {},
  });

  final String composer;
  final String chat;
  final String short;
  final String tierSummary;

  /// Tier enum name (`OPUS`, `FABLE`, `SONNET`, `HAIKU`) → the model that tier
  /// is mapped to in settings, or `官方默认` when none is set. This is a model
  /// string, not the tier's display name — see [modelTierLabels] for that.
  final Map<String, String> tierMapping;

  factory ModelLabels.fromJson(Map<String, dynamic> json) => ModelLabels(
        composer: json['composer'] as String? ?? '',
        chat: json['chat'] as String? ?? '',
        short: json['short'] as String? ?? '',
        tierSummary: json['tierSummary'] as String? ?? '',
        tierMapping: (json['tierMapping'] as Map<dynamic, dynamic>? ?? const {})
            .map((k, v) => MapEntry('$k', '$v')),
      );

  Map<String, dynamic> toJson() => {
        'composer': composer,
        'chat': chat,
        'short': short,
        'tierSummary': tierSummary,
        'tierMapping': tierMapping,
      };
}

// ---------------------------------------------------------------------------
// Providers / release
// ---------------------------------------------------------------------------

/// One-tap provider setup. The values come from `Providers` in Kotlin; Dart only
/// displays them and asks the host to apply the selected preset.
class ProviderPreset {
  const ProviderPreset({
    required this.id,
    required this.label,
    required this.flavor,
    required this.baseUrl,
    required this.authStyle,
    required this.model,
    this.hint = '',
  });

  final String id;
  final String label;
  final String flavor;
  final String baseUrl;
  final String authStyle;
  final String model;
  final String hint;

  factory ProviderPreset.fromJson(Map<String, dynamic> json) => ProviderPreset(
        id: json['id'] as String? ?? '',
        label: json['label'] as String? ?? '',
        flavor: json['flavor'] as String? ?? 'ANTHROPIC',
        baseUrl: json['baseUrl'] as String? ?? '',
        authStyle: json['authStyle'] as String? ?? 'API_KEY',
        model: json['model'] as String? ?? '',
        hint: json['hint'] as String? ?? '',
      );
}

class AppInfo {
  const AppInfo({this.versionName = '', this.versionCode = 0});

  final String versionName;
  final int versionCode;
}

enum ReleaseStatus {
  upToDate,
  updateAvailable,
  devBuild;

  static ReleaseStatus fromWire(String? value) => switch (value) {
        'UPDATE_AVAILABLE' => ReleaseStatus.updateAvailable,
        'DEV_BUILD' => ReleaseStatus.devBuild,
        _ => ReleaseStatus.upToDate,
      };
}

class ReleaseInfo {
  const ReleaseInfo({
    required this.tag,
    required this.url,
    this.publishedAt,
    this.status = ReleaseStatus.upToDate,
    this.currentVersion = '',
  });

  final String tag;
  final String url;
  final String? publishedAt;
  final ReleaseStatus status;
  final String currentVersion;

  factory ReleaseInfo.fromJson(Map<String, dynamic> json) => ReleaseInfo(
        tag: json['tag'] as String? ?? '',
        url: json['url'] as String? ?? '',
        publishedAt: json['publishedAt'] as String?,
        status: ReleaseStatus.fromWire(json['status'] as String?),
        currentVersion: json['currentVersion'] as String? ?? '',
      );
}

// ---------------------------------------------------------------------------
// Workspace
// ---------------------------------------------------------------------------

enum WorkspaceKind {
  directory,
  image,
  markdown,
  html,
  text,
  archive,
  other;

  static WorkspaceKind fromWire(String? value) => switch (value) {
        'DIRECTORY' => WorkspaceKind.directory,
        'IMAGE' => WorkspaceKind.image,
        'MARKDOWN' => WorkspaceKind.markdown,
        'HTML' => WorkspaceKind.html,
        'TEXT' => WorkspaceKind.text,
        'ARCHIVE' => WorkspaceKind.archive,
        _ => WorkspaceKind.other,
      };
}

class WorkspaceEntry {
  const WorkspaceEntry({
    required this.name,
    required this.path,
    required this.kind,
    required this.size,
    required this.modifiedAt,
    required this.isDirectory,
  });

  final String name;

  /// Workspace-relative path (`/` separated); feed this back to the bridge.
  final String path;
  final WorkspaceKind kind;
  final int size;
  final int modifiedAt;
  final bool isDirectory;

  factory WorkspaceEntry.fromJson(Map<String, dynamic> json) => WorkspaceEntry(
        name: json['name'] as String? ?? '',
        path: json['path'] as String? ?? '',
        kind: WorkspaceKind.fromWire(json['kind'] as String?),
        size: _asInt(json['size']) ?? 0,
        modifiedAt: _asInt(json['modifiedAt']) ?? 0,
        isDirectory: json['isDirectory'] as bool? ??
            WorkspaceKind.fromWire(json['kind'] as String?) ==
                WorkspaceKind.directory,
      );
}

class WorkspaceListing {
  const WorkspaceListing({
    required this.path,
    required this.entries,
    required this.totalBytes,
  });

  final String path;
  final List<WorkspaceEntry> entries;
  final int totalBytes;
}

/// One switchable workspace. The host mounts exactly one of these over `/work`;
/// [id] is generated by Kotlin and `default` is the original workspace.
class WorkspaceSpace {
  const WorkspaceSpace({
    required this.id,
    required this.name,
    this.createdAt = 0,
  });

  final String id;
  final String name;
  final int createdAt;

  bool get isDefault => id == 'default';

  factory WorkspaceSpace.fromJson(Map<String, dynamic> json) => WorkspaceSpace(
        id: json['id'] as String? ?? 'default',
        name: json['name'] as String? ?? '默认工作区',
        createdAt: _asInt(json['createdAt']) ?? 0,
      );
}

/// `workspace.spaces` payload: every space plus which one is mounted at `/work`.
class WorkspaceSpaces {
  const WorkspaceSpaces({this.activeId = 'default', this.spaces = const []});

  final String activeId;
  final List<WorkspaceSpace> spaces;

  WorkspaceSpace? get active {
    for (final space in spaces) {
      if (space.id == activeId) return space;
    }
    return spaces.isEmpty ? null : spaces.first;
  }

  factory WorkspaceSpaces.fromJsonString(String? source) {
    if (source == null || source.isEmpty) return const WorkspaceSpaces();
    final Object? decoded;
    try {
      decoded = jsonDecode(source);
    } on FormatException {
      return const WorkspaceSpaces();
    }
    if (decoded is! Map<String, dynamic>) return const WorkspaceSpaces();
    final spaces = (decoded['spaces'] as List<dynamic>? ?? const [])
        .whereType<Map<String, dynamic>>()
        .map(WorkspaceSpace.fromJson)
        .toList(growable: false);
    return WorkspaceSpaces(
      activeId: decoded['activeId'] as String? ?? 'default',
      spaces: spaces,
    );
  }
}

class WorkspacePreview {
  const WorkspacePreview({
    required this.path,
    required this.name,
    required this.kind,
    required this.mime,
    required this.size,
    required this.modifiedAt,
    this.text,
    this.bytes,
    this.truncated = false,
    this.tooLarge = false,
  });

  final String path;
  final String name;
  final WorkspaceKind kind;
  final String mime;
  final int size;
  final int modifiedAt;
  final String? text;
  final Uint8List? bytes;
  final bool truncated;
  final bool tooLarge;
}

class TerminalInfo {
  const TerminalInfo({this.label, this.issue});

  final String? label;
  final String? issue;

  bool get available => issue == null && label != null;
}

// ---------------------------------------------------------------------------
// Per-session state
// ---------------------------------------------------------------------------

/// Full snapshot of one chat, as sent by `snapshot` / `chat.attach`.
class ChatState {
  const ChatState({
    this.messages = const [],
    this.title = '',
    this.titleBusy = false,
    this.titleNotice,
    this.running = false,
    this.status = '',
    this.statusLine = '',
  });

  final List<ChatMessage> messages;
  final String title;
  final bool titleBusy;
  final String? titleNotice;
  final bool running;

  /// Raw status, kept for diagnostics. The UI only shows [statusLine].
  final String status;
  final String statusLine;

  factory ChatState.fromJson(Map<String, dynamic> json) => ChatState(
        messages: (json['messages'] as List<dynamic>? ?? const [])
            .whereType<Map<String, dynamic>>()
            .map(ChatMessage.fromJson)
            .toList(growable: false),
        title: json['title'] as String? ?? '',
        titleBusy: json['titleBusy'] as bool? ?? false,
        titleNotice: json['titleNotice'] as String?,
        running: json['running'] as bool? ?? false,
        status: json['status'] as String? ?? '',
        statusLine: json['statusLine'] as String? ?? '',
      );

  ChatState copyWith({
    List<ChatMessage>? messages,
    String? title,
    bool? titleBusy,
    String? titleNotice,
    bool clearTitleNotice = false,
    bool? running,
    String? status,
    String? statusLine,
  }) =>
      ChatState(
        messages: messages ?? this.messages,
        title: title ?? this.title,
        titleBusy: titleBusy ?? this.titleBusy,
        titleNotice:
            clearTitleNotice ? null : (titleNotice ?? this.titleNotice),
        running: running ?? this.running,
        status: status ?? this.status,
        statusLine: statusLine ?? this.statusLine,
      );
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

/// `AppSettings` is a wide, still-moving Kotlin data class. Rather than mirror
/// every field (and silently drop unknown ones on the round-trip back to the
/// host), this keeps the decoded map intact and exposes typed accessors for the
/// keys the UI reads. `settings.update` therefore sends back the host's own
/// JSON with only the edited keys replaced — no field can be lost in transit.
class AppSettings {
  const AppSettings([this.raw = const {}]);

  final Map<String, dynamic> raw;

  factory AppSettings.fromJson(Map<String, dynamic> json) =>
      AppSettings(Map<String, dynamic>.from(json));

  factory AppSettings.fromJsonString(String? source) {
    if (source == null || source.isEmpty) return AppSettings();
    final decoded = jsonDecode(source);
    return decoded is Map<String, dynamic>
        ? AppSettings.fromJson(decoded)
        : AppSettings();
  }

  String toJsonString() => jsonEncode(raw);

  /// Returns a copy with [patch] merged over the existing keys.
  AppSettings merge(Map<String, dynamic> patch) =>
      AppSettings({...raw, ...patch});

  String str(String key, [String fallback = '']) =>
      raw[key] as String? ?? fallback;

  bool flag(String key, [bool fallback = false]) =>
      raw[key] as bool? ?? fallback;

  int intOr(String key, [int fallback = 0]) => _asInt(raw[key]) ?? fallback;

  double doubleOr(String key, [double fallback = 0]) =>
      _asDouble(raw[key]) ?? fallback;

  String? strOrNull(String key) => raw[key] as String?;

  List<String> stringList(String key) =>
      (raw[key] as List<dynamic>? ?? const []).map((e) => '$e').toList();

  Map<String, dynamic> map(String key) {
    final value = raw[key];
    return value is Map<String, dynamic> ? value : const {};
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

int? _asInt(Object? value) => switch (value) {
      int v => v,
      double v => v.round(),
      String v => int.tryParse(v),
      _ => null,
    };

double? _asDouble(Object? value) => switch (value) {
      double v => v,
      int v => v.toDouble(),
      String v => double.tryParse(v),
      _ => null,
    };
