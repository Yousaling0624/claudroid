# Kotlin ↔ Dart 桥接契约（Flutter 前端）

> 本文件是 Flutter 前端的**接口契约**，冻结后两端独立实现：
> Kotlin 侧（`app/src/main/java/app/claudroid/bridge/`）与 Dart 侧（`flutter/lib/bridge/`）。
> 改字段名、改事件类型、改语义前必须先改这里，否则两端会静默错位。

## 1. 总体结构

Flutter 只做**界面**。业务逻辑、持久化、引擎进程、CLI 生命周期全部留在 Kotlin 现有实现里，
桥接层是一个**薄适配器**：把 `AppContainer` 暴露成 MethodChannel + EventChannel，不做任何业务判断。

```
Dart (Flutter)                    Kotlin (host)
──────────────────────────────────────────────────────────────
lib/bridge/api.dart   ──Method──►  BridgePlugin.kt ──► AppContainer
lib/bridge/events.dart ◄─Event───  BridgePlugin.kt ◄── StateFlow / SharedFlow
```

**不迁移**：`engine/`、`data/`、`core/` 的任何逻辑都不进 Dart。
Dart 侧不直接读文件、不直接起进程、不持有 API Key 以外的任何持久状态。

## 2. 通道

| 通道名 | 方向 | 用途 |
| --- | --- | --- |
| `app.claudroid/bridge` | MethodChannel，Dart → Kotlin | 所有命令，均有返回值 |
| `app.claudroid/events` | EventChannel，Kotlin → Dart | 全局状态：设置、引擎、会话列表 |
| `app.claudroid/chat/<sessionId>` | EventChannel，Kotlin → Dart | 单会话状态与流式增量 |

`<sessionId>` 是 `ChatSession.id`（UUID）。通道名里的 UUID 含 `-`，符合 EventChannel 命名要求。

### 生命周期

- Flutter engine 用 `FlutterEngineCache` **只创建一次**（key `claudroid`），在
  `ClaudroidApp.onCreate()` 里预热并注册通道。Activity 重建不重建 engine，通道不丢。
- `app.claudroid/events` 在 `onListen` 时开始收集，`onCancel` 时取消。多个 Dart 订阅者共用一个
  Kotlin 侧订阅（EventChannel 保证只有一个 `StreamHandler`）。
- `app.claudroid/chat/<id>` 的 `onListen` 里调 `AppContainer.chat(id)` 并 `load()`；
  `onCancel` **不**停 turn、**不**丢控制器（用户可能只是切页）。真正释放由 `chat.close` 决定。

## 3. 数据格式

**所有复杂值都用 JSON 字符串传，不用嵌套 Map。**

理由：平台通道会把 `Int` 变 `Long`、把 `List<Any?>` 变 `List<Object>`，Dart 侧再猜类型很容易
出静默错误；而 `kotlinx.serialization` 本来就能把 `AppSettings` / `ChatMessage` 直接编码成
与磁盘格式**完全一致**的 JSON，Dart 用同一套 `fromJson` 即可。

Kotlin 侧统一使用与 `SessionStore` 相同的 `Json` 配置：

```kotlin
val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
    // 默认 classDiscriminator 就是 "type"，与磁盘格式一致，不要改
}
```

因此 `ChatMessage` 的线上格式即磁盘格式：

```json
{
  "id": "uuid",
  "role": "USER",                       // USER | ASSISTANT | SYSTEM
  "blocks": [
    {"type": "text", "text": "..."},
    {"type": "thinking", "text": "..."},
    {"type": "tool", "id": "...", "name": "Bash", "input": "{...}",
     "output": "..."|null, "isError": false, "running": true},
    {"type": "error", "text": "..."}
  ],
  "ts": 1700000000000,
  "costUsd": 0.01|null,
  "durationMs": 1234|null,
  "streaming": false
}
```

`Block` 的判别字段是 `"type"`，取值 `text|thinking|tool|error`。

方法调用的参数一律是 `Map<String, Any?>`，值只允许 `String` / `bool` / `int` / `double` / `null`
以及**复杂结构的 JSON 字符串**（字段名带 `Json` 后缀，如 `settingsJson`）。

## 4. 全局事件 `app.claudroid/events`

每条消息是 `Map`，`type` 字段区分：

| type | 附带字段 | 触发 |
| --- | --- | --- |
| `settings` | `settingsJson`, `labelsJson` | 设置变化（DataStore 流） |
| `engine` | `engineJson` | `EngineManager.state` 变化 |
| `sessions` | `sessionsJson` | 会话列表变化（增删改序） |
| `ready` | `settingsJson`, `labelsJson`, `engineJson`, `sessionsJson` | `onListen` 时立即发一次，让 Dart 无需额外请求 |

`labelsJson` 是**派生展示文案**，由 Kotlin 用现有 `ui/components/ModelLabels.kt` 算好后附上。
模型档位（`ModelTier`）的解析规则（`usesAutoModel` / `resolvedActiveTier` / `tierModel`）留在
Kotlin，Dart 不复制：

```json
{
  "composer": "Opus · claude-opus-5-5",   // composerModelLabel
  "chat": "Opus · claude-opus-5-5",       // chatModelLabel
  "short": "Opus",                        // shortModelLabel（模型气泡用）
  "tierSummary": "Opus · 官方默认",        // activeTierSummary
  "tierMapping": {"OPUS": "...", "FABLE": "...", "SONNET": "...", "HAIKU": "..."}  // tierMappingLabel
}
```

`engineJson` 结构（对应 `EngineManager.State`）：

```json
{
  "phase": "Idle|...",           // Phase 的 name
  "stage": "...", "detail": "...", "fraction": 0.0,
  "installedVersion": "..."|null, "latestVersion": "..."|null,
  "arch": "..."|null, "jailPath": "..."|null,
  "rootMode": false, "rootGranted": false,
  "chrootAvailable": false, "prootAvailable": false,
  "error": "..."|null, "selfTest": "..."|null,
  "ready": false,
  "engineLabel": "PRoot（免 root）"   // 由 state.engineLabel(settings.engineMode) 计算后附上
}
```

`engineLabel` 是派生值：桥接层用当前 `settings.engineMode` 调 `state.engineLabel(mode)`，
省掉 Dart 侧复制那套判定逻辑（那段逻辑有注释说明过「用户选择的模式优先于探测到的能力」，
复制一份必然漂移）。

`sessionsJson` 是 `ChatSession` 数组（不含 `messages`，会话列表不需要）：

```json
[{"id":"uuid","title":"...","createdAt":0,"updatedAt":0,
  "claudeSessionId":"..."|null,"model":"..."|null}]
```

## 5. 单会话事件 `app.claudroid/chat/<sessionId>`

`onListen` 时先发一条 `snapshot`，之后发增量。

| type | 附带字段 | 说明 |
| --- | --- | --- |
| `snapshot` | `chatJson` | 全量：`{messages, title, titleBusy, titleNotice, running, status}` |
| `messages` | `messagesJson` | 结构变化时的全量消息数组（新增消息、重排、收尾） |
| `delta` | `messageId`, `block`(`text`\|`thinking`), `append` | **流式热路径**，只追加文本 |
| `tool_use` | `messageId`, `id`, `name`, `inputJson` | 工具卡片出现（`running: true`） |
| `tool_result` | `messageId`, `id`, `output`, `isError` | 工具卡片收尾 |
| `error_block` | `messageId`, `text` | 追加一个 `Block.Error` |
| `status` | `status`, `statusLine` | 状态行：`status` 是原文，`statusLine` 是 `chatStatusLine(running, raw, model)` 的结果。Dart **只显示 `statusLine`** |
| `running` | `running` | 本轮开始/结束 |
| `title` | `title` | 标题变化 |
| `titleBusy` | `busy` | 标题摘要进行中 |
| `titleNotice` | `notice`(`String?`) | 一次性提示；Dart 显示后调 `chat.consumeTitleNotice` |

### 为什么需要 `delta` 而不是全量 `messages`

`ChatController` 目前把整个 `messages: StateFlow<List<ChatMessage>>` 当作唯一真相。Compose 侧
订阅它是零成本的（进程内重组）。**跨平台通道不一样**：一轮长对话的 JSON 可达数百 KB，
按 token 频率全量序列化会直接把主线程拖垮。所以桥接层必须转发增量。

实现要求（Kotlin 侧）：

- `ChatController` 增加一个 `val events: SharedFlow<ChatEvent>`（`extraBufferCapacity = 64`,
  `onBufferOverflow = DROP_OLDEST`），在**现有** `executeTurn` 的 `runTurn` 回调里同步发出，
  与 `_messages.update` 并列。**不新增第二套状态**，`_messages` 仍是唯一真相。
- 发出点就在 `attempt()` 里那个 `when (ev)` 分支（`ChatController.kt` 的
  `is EngineEvent.TextDelta ->` 等）。每个分支已有 `mutateAssistant(...)`，紧挨着它 `emit` 对应
  的 `ChatEvent`；**不要**改 `mutateAssistant` 本身去反推类型，那个函数拿不到 `EngineEvent`，
  反推只会退化成全量 diff。
- 结构变化（新消息、`clear()`、`load()`、`resume` 重试清空 blocks）由控制器**显式**发
  `Messages` 全量；不要靠轮询 `_messages` 猜。
- `_status` / `_running` / `_title` / `_titleBusy` / `_titleNotice` 的赋值同样在赋值处
  `emit`。这些是低频值，直接发即可。
- 桥接层只在 `messages` 的结构性改动（`_messages` 的元素个数/顺序/id 变化）时发全量，
  其余时候发增量。
- 增量与全量的边界必须自洽：Dart 收到 `messages` 后**整体替换**本地列表，收到 `delta` 后
  **按 `messageId` 找到消息、按 `block` 类型追加文本**。找不到目标时（例如乱序）Dart 侧
  丢弃该增量并请求一次 `chat.attach` 重新同步——这是兜底，不是常规路径。

## 6. 方法清单

所有方法返回 `Map`，成功时至少含 `"ok": true`；失败时 `"ok": false, "error": "<中文消息>"`。
抛异常也由桥接层捕获并转成 `ok:false`，**不让 Dart 见 `PlatformException` 的原始栈**。

### 6.1 应用级

| 方法 | 参数 | 返回 | Kotlin 实现 |
| --- | --- | --- | --- |
| `app.init` | — | `{ok, settingsJson, labelsJson, engineJson, sessionsJson}` | 读当前三份状态 + 派生标签 |
| `settings.update` | `settingsJson` | `{ok, settingsJson, labelsJson}` | `settings.edit { decoded }` |
| `sessions.list` | — | `{ok, sessionsJson}` | `sessions.sessions.value` |
| `sessions.new` | `model: String?` | `{ok, sessionJson}` | `sessions.newSession(model)` |
| `sessions.delete` | `id` | `{ok}` | `sessions.delete(id)` + `AppContainer.closeChat(id)` |
| `sessions.rename` | `id`, `title` | `{ok}` | `sessions.rename(id, title)` |

### 6.2 引擎

全部 `suspend`，在 `AppContainer.scope` 里执行后回复。UI 需要进度时靠 `events` 的 `engine` 推送，
**不要**为这些方法做同步阻塞。

| 方法 | 参数 | 返回 |
| --- | --- | --- |
| `engine.bootstrap` | — | `{ok}` |
| `engine.install` | `force: bool` | `{ok}` |
| `engine.checkUpdate` | `quiet: bool` | `{ok}` |
| `engine.probeRoot` | — | `{ok, granted: bool}` |
| `engine.selfTest` | — | `{ok, report}` |
| `engine.rootProbeReport` | — | `{ok, report}` |
| `engine.storageUsage` | — | `{ok, jailBytes, workspaceBytes, sessionBytes}` |
| `engine.uninstall` | `deleteWorkspace: bool` | `{ok}` |
| `engine.cancel` | — | `{ok}` |

### 6.3 会话（聊天）

| 方法 | 参数 | 返回 | 说明 |
| --- | --- | --- | --- |
| `chat.attach` | `sessionId` | `{ok, chatJson}` | 取控制器 + `load()` + 返回全量快照 |
| `chat.send` | `sessionId`, `text` | `{ok, accepted: bool}` | `accepted=false` 表示已有 turn 在跑 |
| `chat.resendFrom` | `sessionId`, `messageId`, `text` | `{ok, accepted: bool}` | 消息不存在时 `accepted=false` |
| `chat.regenerate` | `sessionId`, `messageId` | `{ok}` | 只允许重生成最后一条助手消息 |
| `chat.stop` | `sessionId` | `{ok}` | |
| `chat.clear` | `sessionId` | `{ok}` | 清空上下文 |
| `chat.consumeTitleNotice` | `sessionId` | `{ok}` | |
| `chat.markTitleEdited` | `sessionId`, `title` | `{ok}` | |
| `chat.regenerateTitle` | `sessionId` | `{ok}` | |
| `chat.forget` | `sessionId` | `{ok}` | `AppContainer.forgetChat(id)`：移除控制器，**turn 继续跑** |
| `chat.close` | `sessionId` | `{ok}` | `AppContainer.closeChat(id)`：`dismiss()` 停 turn + 移除 |

`chatJson` 结构：

```json
{"messages": [ ...ChatMessage... ],
 "title": "...", "titleBusy": false, "titleNotice": null,
 "running": false, "status": "", "statusLine": "claude-sonnet-5-5"}
```

`status` 保留原文是为了排查；界面一律用 `statusLine`。`chatStatusLine` 目前是
`app.claudroid.ui.chat` 里的 `internal fun`，同属 `:app` 模块，桥接层可直接调用，**不要**在
Dart 里重写（它过滤以 `[` 或 `{` 开头的诊断输出，规则将来还会变）。

## 7. 线程与错误

- MethodChannel handler 在主线程回调。凡是 `suspend` 的 Kotlin 方法一律
  `scope.launch { ...; result.success(map) }`，**不要**在 handler 里 `runBlocking`。
- `ChatController.send/resendFrom` 是**非 suspend**（内部已 `scope.launch`），直接调用即可；
  返回值表示「是否被接受」，不代表「已完成」。
- 任何异常：`result.error("bridge", message, null)` 之外，优先用 `result.success(mapOf("ok" to false, "error" to ...))`，
  便于 Dart 侧统一处理。
- 日志：桥接层只记 `Logx.i("bridge: <method>")` 级别，**绝不**打印 `apiKey`、`extraEnv`、
  完整 prompt。`settingsJson` 在日志里必须先抹掉 `apiKey`。

## 8. Dart 侧对应要求

- `lib/bridge/api.dart`：`Bridge` 单例，封装 `MethodChannel`，每个方法一个强类型签名，
  内部 `jsonDecode` 成 `AppSettings` / `ChatMessage` 等模型。
- `lib/bridge/events.dart`：把三条 EventChannel 转成 `Stream<AppState>` / `Stream<ChatState>`。
- `lib/models/`：`AppSettings`、`ChatMessage`、`Block`、`ChatSession`、`EngineState` 的
  `fromJson`，**字段名与 Kotlin 序列化输出逐字一致**。枚举用 `name` 字符串，解析失败时回退到默认值
  并记一次 warn，不要抛异常让整个界面白屏。
- Dart 侧不做业务判断：权限模式、模型档位、引擎可用性的文案全部由 Kotlin 的 `label` / `hint`
  字段提供（`AppSettings` 的枚举已带中文 `label`/`hint`），Dart 只负责展示。

## 9. 验收

1. `flutter analyze` 无 error；`dart test` 的 JSON 往返测试覆盖 `ChatMessage`（四种 Block）与 `AppSettings`。
2. 真机：`chat.send` 后逐字上屏（`delta` 生效，不是整段刷新）；长对话（>20 轮）流式不卡顿。
3. 切页/重建 Activity 后 turn 继续，回来能看到进行中的消息。
4. `settings.update` 后 `events` 立刻推 `settings`，界面同步。
5. 断网/后端未安装时，`chat.send` 走 `error_block`，界面显示中文错误而不是白屏或崩溃。
