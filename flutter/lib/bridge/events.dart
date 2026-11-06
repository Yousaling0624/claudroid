/// Event channels: `app.claudroid/events` (global) and
/// `app.claudroid/chat/<sessionId>` (per session).
///
/// The global channel's `ready` message is turned into a full [AppState] so
/// listeners can start from it; `settings` / `engine` / `sessions` then arrive
/// as patches. The per-session channel is decoded into [ChatEvent]s, which the
/// chat page folds into its own [ChatState].
library;

import 'package:flutter/services.dart';

import '../models/models.dart';
import 'api.dart';

// ---------------------------------------------------------------------------
// Global events
// ---------------------------------------------------------------------------

class AppState {
  const AppState({
    this.settings = const AppSettings(),
    this.labels = const ModelLabels(),
    this.engine = const EngineState(),
    this.sessions = const [],
    this.ready = false,
  });

  final AppSettings settings;
  final ModelLabels labels;
  final EngineState engine;
  final List<ChatSession> sessions;

  /// True once the host has sent its initial `ready` payload.
  final bool ready;

  AppState copyWith({
    AppSettings? settings,
    ModelLabels? labels,
    EngineState? engine,
    List<ChatSession>? sessions,
    bool? ready,
  }) =>
      AppState(
        settings: settings ?? this.settings,
        labels: labels ?? this.labels,
        engine: engine ?? this.engine,
        sessions: sessions ?? this.sessions,
        ready: ready ?? this.ready,
      );
}

/// A single global event. [AppEvent] is a patch over the previous [AppState].
sealed class AppEvent {
  const AppEvent();

  /// Takes `Object?` so it can be used directly as a `Stream.map` callback:
  /// `receiveBroadcastStream()` is dynamically typed, and a `Map<Object?, Object?>`
  /// parameter would not accept it.
  static AppEvent? fromMap(Object? raw) {
    if (raw is! Map) return null;
    final map = raw;
    switch (map['type']) {
      case 'ready':
        return AppReadyEvent(
          AppState(
            settings:
                AppSettings.fromJsonString(map['settingsJson'] as String?),
            labels: ModelLabels.fromJson(
              decodeJsonMap(map['labelsJson'] as String?),
            ),
            engine: EngineState.fromJson(
              decodeJsonMap(map['engineJson'] as String?),
            ),
            sessions: decodeJsonList(map['sessionsJson'] as String?)
                .map(ChatSession.fromJson)
                .toList(growable: false),
            ready: true,
          ),
        );
      case 'settings':
        return AppSettingsEvent(
          settings: AppSettings.fromJsonString(map['settingsJson'] as String?),
          labels: ModelLabels.fromJson(
            decodeJsonMap(map['labelsJson'] as String?),
          ),
        );
      case 'engine':
        return AppEngineEvent(
          EngineState.fromJson(decodeJsonMap(map['engineJson'] as String?)),
        );
      case 'sessions':
        return AppSessionsEvent(
          decodeJsonList(map['sessionsJson'] as String?)
              .map(ChatSession.fromJson)
              .toList(growable: false),
        );
      default:
        // A newer host may emit event types this build does not know yet;
        // ignoring them keeps the stream alive.
        return null;
    }
  }

  AppState apply(AppState previous);
}

class AppReadyEvent extends AppEvent {
  const AppReadyEvent(this.state);

  final AppState state;

  @override
  AppState apply(AppState previous) => state;
}

class AppSettingsEvent extends AppEvent {
  const AppSettingsEvent({required this.settings, required this.labels});

  final AppSettings settings;
  final ModelLabels labels;

  @override
  AppState apply(AppState previous) =>
      previous.copyWith(settings: settings, labels: labels);
}

class AppEngineEvent extends AppEvent {
  const AppEngineEvent(this.engine);

  final EngineState engine;

  @override
  AppState apply(AppState previous) => previous.copyWith(engine: engine);
}

class AppSessionsEvent extends AppEvent {
  const AppSessionsEvent(this.sessions);

  final List<ChatSession> sessions;

  @override
  AppState apply(AppState previous) =>
      previous.copyWith(sessions: sessions);
}

// ---------------------------------------------------------------------------
// Per-session events
// ---------------------------------------------------------------------------

sealed class ChatEvent {
  const ChatEvent();

  /// Returns `null` for unknown types, including the `delta` hot path when its
  /// payload is malformed — dropping one delta is recoverable, throwing is not.
  static ChatEvent? fromMap(Object? raw) {
    if (raw is! Map) return null;
    final map = raw;
    switch (map['type']) {
      case 'snapshot':
        return ChatSnapshotEvent(
          ChatState.fromJson(decodeJsonMap(map['chatJson'] as String?)),
        );
      case 'messages':
        return ChatMessagesEvent(
          decodeJsonList(map['messagesJson'] as String?)
              .map(ChatMessage.fromJson)
              .toList(growable: false),
        );
      case 'delta':
        final messageId = map['messageId'] as String?;
        final block = map['block'] as String?;
        final append = map['append'] as String?;
        if (messageId == null || block == null || append == null) return null;
        return ChatDeltaEvent(
          messageId: messageId,
          block: block,
          append: append,
        );
      case 'tool_use':
        final id = map['id'] as String?;
        if (id == null) return null;
        return ChatToolUseEvent(
          messageId: map['messageId'] as String? ?? '',
          id: id,
          name: map['name'] as String? ?? '',
          input: map['inputJson'] as String? ?? '',
        );
      case 'tool_result':
        final id = map['id'] as String?;
        if (id == null) return null;
        return ChatToolResultEvent(
          messageId: map['messageId'] as String? ?? '',
          id: id,
          output: map['output'] as String? ?? '',
          isError: map['isError'] as bool? ?? false,
        );
      case 'error_block':
        return ChatErrorBlockEvent(
          messageId: map['messageId'] as String? ?? '',
          text: map['text'] as String? ?? '',
        );
      case 'status':
        return ChatStatusEvent(
          status: map['status'] as String? ?? '',
          statusLine: map['statusLine'] as String? ?? '',
        );
      case 'running':
        return ChatRunningEvent(map['running'] as bool? ?? false);
      case 'title':
        return ChatTitleEvent(map['title'] as String? ?? '');
      case 'titleBusy':
        return ChatTitleBusyEvent(map['busy'] as bool? ?? false);
      case 'titleNotice':
        return ChatTitleNoticeEvent(map['notice'] as String?);
      default:
        return null;
    }
  }

  /// Folds this event into [state]. Returns `null` when the delta could not be
  /// applied and the caller should re-sync with `chat.attach`.
  ChatState? apply(ChatState state);
}

class ChatSnapshotEvent extends ChatEvent {
  const ChatSnapshotEvent(this.state);

  final ChatState state;

  @override
  ChatState apply(ChatState previous) => state;
}

class ChatMessagesEvent extends ChatEvent {
  const ChatMessagesEvent(this.messages);

  final List<ChatMessage> messages;

  @override
  ChatState apply(ChatState previous) => previous.copyWith(messages: messages);
}

class ChatDeltaEvent extends ChatEvent {
  const ChatDeltaEvent({
    required this.messageId,
    required this.block,
    required this.append,
  });

  final String messageId;

  /// `text` or `thinking`.
  final String block;
  final String append;

  @override
  ChatState? apply(ChatState previous) {
    final index = previous.messages.indexWhere((m) => m.id == messageId);
    if (index < 0) return null;
    final updated = previous.messages[index].appendDelta(block, append);
    if (updated == null) return null;
    final messages = [...previous.messages]..[index] = updated;
    return previous.copyWith(messages: messages);
  }
}

class ChatToolUseEvent extends ChatEvent {
  const ChatToolUseEvent({
    required this.messageId,
    required this.id,
    required this.name,
    required this.input,
  });

  final String messageId;
  final String id;
  final String name;
  final String input;

  @override
  ChatState? apply(ChatState previous) {
    final index = previous.messages.indexWhere((m) => m.id == messageId);
    if (index < 0) return null;
    final message = previous.messages[index];
    // A tool call always starts a new block; the preceding thinking segment
    // is done and any later text will start its own block too.
    final blocks = [...message.blocks, ToolBlock(id: id, name: name, input: input)];
    final messages = [...previous.messages]..[index] =
        message.copyWith(blocks: blocks);
    return previous.copyWith(messages: messages);
  }
}

class ChatToolResultEvent extends ChatEvent {
  const ChatToolResultEvent({
    required this.messageId,
    required this.id,
    required this.output,
    required this.isError,
  });

  final String messageId;
  final String id;
  final String output;
  final bool isError;

  @override
  ChatState? apply(ChatState previous) {
    final index = previous.messages.indexWhere((m) => m.id == messageId);
    if (index < 0) return null;
    final message = previous.messages[index];
    final blockIndex = message.blocks.indexWhere(
      (b) => b is ToolBlock && b.id == id,
    );
    if (blockIndex < 0) return null;
    final blocks = [...message.blocks];
    final tool = blocks[blockIndex] as ToolBlock;
    blocks[blockIndex] = tool.copyWith(
      output: output,
      isError: isError,
      running: false,
    );
    final messages = [...previous.messages]..[index] =
        message.copyWith(blocks: blocks);
    return previous.copyWith(messages: messages);
  }
}

class ChatErrorBlockEvent extends ChatEvent {
  const ChatErrorBlockEvent({required this.messageId, required this.text});

  final String messageId;
  final String text;

  @override
  ChatState? apply(ChatState previous) {
    final index = previous.messages.indexWhere((m) => m.id == messageId);
    if (index < 0) return null;
    final message = previous.messages[index];
    final messages = [...previous.messages]..[index] = message.copyWith(
        blocks: [...message.blocks, ErrorBlock(text)],
      );
    return previous.copyWith(messages: messages);
  }
}

class ChatStatusEvent extends ChatEvent {
  const ChatStatusEvent({required this.status, required this.statusLine});

  final String status;
  final String statusLine;

  @override
  ChatState apply(ChatState previous) =>
      previous.copyWith(status: status, statusLine: statusLine);
}

class ChatRunningEvent extends ChatEvent {
  const ChatRunningEvent(this.running);

  final bool running;

  @override
  ChatState apply(ChatState previous) => previous.copyWith(running: running);
}

class ChatTitleEvent extends ChatEvent {
  const ChatTitleEvent(this.title);

  final String title;

  @override
  ChatState apply(ChatState previous) => previous.copyWith(title: title);
}

class ChatTitleBusyEvent extends ChatEvent {
  const ChatTitleBusyEvent(this.busy);

  final bool busy;

  @override
  ChatState apply(ChatState previous) => previous.copyWith(titleBusy: busy);
}

class ChatTitleNoticeEvent extends ChatEvent {
  const ChatTitleNoticeEvent(this.notice);

  final String? notice;

  @override
  ChatState apply(ChatState previous) =>
      previous.copyWith(titleNotice: notice);
}

// ---------------------------------------------------------------------------
// Channels
// ---------------------------------------------------------------------------

class GlobalEvents {
  GlobalEvents();

  final EventChannel _channel = const EventChannel('app.claudroid/events');

  /// Decoded events. Listeners normally fold them into an [AppState].
  Stream<AppEvent> get events => _channel
      .receiveBroadcastStream()
      .map(AppEvent.fromMap)
      .where((event) => event != null)
      .cast<AppEvent>();

  /// Folds events into a running [AppState] for simple `StreamBuilder` use.
  Stream<AppState> get states =>
      events.scan(const AppState(), (state, event) => event.apply(state));
}

class ChatEvents {
  ChatEvents(this.sessionId);

  final String sessionId;

  late final EventChannel _channel =
      EventChannel('app.claudroid/chat/$sessionId');

  Stream<ChatEvent> get events => _channel
      .receiveBroadcastStream()
      .map(ChatEvent.fromMap)
      .where((event) => event != null)
      .cast<ChatEvent>();

  /// Folds events into [ChatState]. Events that cannot be applied (a delta
  /// whose target is missing) are dropped; the caller re-syncs via
  /// `chat.attach` and pushes a fresh snapshot through this same stream.
  Stream<ChatState> get states =>
      events.scan(const ChatState(), (state, event) => event.apply(state) ?? state);
}

// ---------------------------------------------------------------------------
// scan
// ---------------------------------------------------------------------------

extension _Scan<T> on Stream<T> {
  Stream<S> scan<S>(S seed, S Function(S previous, T event) combine) async* {
    var accumulator = seed;
    yield accumulator;
    await for (final event in this) {
      accumulator = combine(accumulator, event);
      yield accumulator;
    }
  }
}
