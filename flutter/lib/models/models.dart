/// Wire models for the Kotlin ↔ Dart bridge.
///
/// Field names mirror `kotlinx.serialization`'s output one-for-one
/// (`docs/BRIDGE_CONTRACT.md` §3–§5). Nothing here does business logic: enum
/// labels, model tier resolution and status text all arrive pre-computed from
/// Kotlin, so a drifting copy in Dart can never disagree with the host.
library;

import 'dart:convert';

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

  ToolBlock copyWith({String? output, bool? isError, bool? running}) =>
      ToolBlock(
        id: id,
        name: name,
        input: input,
        output: output ?? this.output,
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
  });

  final String id;
  final Role role;
  final List<Block> blocks;
  final int ts;
  final double? costUsd;
  final int? durationMs;
  final bool streaming;

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
  });

  final String id;
  final String title;
  final int createdAt;
  final int updatedAt;
  final String? claudeSessionId;
  final String? model;

  factory ChatSession.fromJson(Map<String, dynamic> json) => ChatSession(
        id: json['id'] as String? ?? '',
        title: json['title'] as String? ?? '',
        createdAt: _asInt(json['createdAt']) ?? 0,
        updatedAt: _asInt(json['updatedAt']) ?? 0,
        claudeSessionId: json['claudeSessionId'] as String?,
        model: json['model'] as String?,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'title': title,
        'createdAt': createdAt,
        'updatedAt': updatedAt,
        'claudeSessionId': claudeSessionId,
        'model': model,
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

  /// Tier name (`OPUS`, `FABLE`, `SONNET`, `HAIKU`) → label.
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
