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
| `app.claudroid/terminal` | EventChannel，Kotlin → Dart | 工作区终端的流式输出与结束事件 |
| `app.claudroid/backup` | EventChannel，Kotlin → Dart | 备份/恢复进度与上次结果 |

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
  "streaming": false,
  "model": "Opus · claude-opus-4-5"|null  // 产出这条回答时的模型签名
}
```

`Block` 的判别字段是 `"type"`，取值 `text|thinking|tool|error`。

助手消息的 `model` 是**产出该回合时**的展示签名，由 `ChatController` 在开回合时用
`chatModelLabel(settings)` 冻结后写入；之后切换档位/模型只影响新回合，不会改写旧回答的
署名。该字段是后加的，旧会话文件里没有它，解码为 `null`，前端回退到当前的 `labelsJson.chat`。

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

`labelsJson` 是**派生展示文案**，由 Kotlin 用 `data/ModelLabels.kt` 算好后附上。
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

安装/升级期间 `fraction` **最多每 120ms 推一次**，`stage` / `detail` 变化仍立即推送：
下载回调按 256 KiB 触发、解包回调按条目触发，原样转发会让设置页每秒重建几十次，
表现为界面反复停顿零点几秒。限流在 `EngineManager.install` 里做，桥接层不再二次缓冲。

`sessionsJson` 是 `ChatSession` 数组（不含 `messages`，会话列表不需要）：

```json
[{"id":"uuid","title":"...","createdAt":0,"updatedAt":0,
  "claudeSessionId":"..."|null,"model":"..."|null,
  "hasContent":true,"preview":"最后一条消息的摘要",
  "searchText":"标题之外的可搜索文本（截断）"}]
```

`hasContent` 与 `preview` 由 Kotlin 的 `ChatSession.hasContent/preview` 计算；`searchText`
是全部消息文本块/工具输出的截断拼接（每会话最多 2000 字符），供抽屉做内容搜索。Dart 不读
磁盘，也不自己拼这些字段。

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

`ChatController` 目前把整个 `messages: StateFlow<List<ChatMessage>>` 当作唯一真相。进程内的
旧前端订阅它是零成本的（直接重组）。**跨平台通道不一样**：一轮长对话的 JSON 可达数百 KB，
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
| `engine.updateCli` | — | `{ok}` |
| `engine.checkUpdate` | `quiet: bool` | `{ok}` |
| `engine.probeRoot` | — | `{ok, granted: bool}` |
| `engine.selfTest` | — | `{ok, report}` |
| `engine.rootProbeReport` | — | `{ok, report}` |
| `engine.refreshNetwork` | — | `{ok, changed: bool, skipped: bool, dns, message}` |
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
| `chat.fork` | `sessionId`, `messageId` | `{ok, sessionId}` | 以该消息为终点复制到新会话；消息不存在时 `ok:false` |
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
`app.claudroid.chat` 里的 `internal fun`，同属 `:app` 模块，桥接层可直接调用，**不要**在
Dart 里重写（它过滤以 `[` 或 `{` 开头的诊断输出，规则将来还会变）。

`chat.fork` 的语义：新会话的 `messages` 是源会话到 `messageId`（含）为止的副本，
`claudeSessionId` 为 `null`，标题是源标题加 ` · 分支`。新会话首次 `chat.send` 时，
`ChatController` 用 `ConversationPrompt.build` 把可见历史重放进 prompt——官方 CLI
只能 resume 它自己记录的完整会话，无法在任意消息处截断，所以分支必须从本地历史起步。
`chat.fork` 只写一个会话文件，不启动 turn；Dart 收到新 id 后走正常的 `chat.attach`。

### 6.4 应用扩展（首页 / 关于 / 附件 / 日志）

| 方法 | 参数 | 返回 | Kotlin 实现 |
| --- | --- | --- | --- |
| `app.suggestions` | `count: int`, `context: String?` | `{ok, itemsJson}` | `SuggestionGenerator.generate`；失败返回空数组（不伪造文案） |
| `app.info` | — | `{ok, versionName, versionCode}` | `BuildConfig` |
| `app.release` | — | `{ok, releaseJson}` | `ReleaseCheck.fetch()` + `ReleaseCheck.status`；网络失败 `ok:false` |
| `app.openUrl` | `url` | `{ok}` | 只接受 http/https，`Intent.ACTION_VIEW` |
| `share.text` | `text: String` | `{ok}` | `ACTION_SEND` + `text/plain` 系统分享面板；空白或超过 20 万字符返回中文错误 |
| `share.image` | `bytes: ByteArray` | `{ok}` | 写入 `cacheDir/share/` 后经 FileProvider 以 `image/png` 分享；空或超过 24 MiB 返回中文错误 |
| `attachments.pick` | — | `{ok, relativePathsJson, cancelled}` | 系统 `OpenDocument` 选择器 + `Attachments.import`，复制进**当前活动工作区**的 `attachments/<name>` |
| `logs.read` | — | `{ok, linesJson}` | `Logx.lines.value` |
| `logs.clear` | — | `{ok}` | `Logx.clear()` |

`share.image` 的 PNG 由 Flutter 生成，Kotlin 在 `Dispatchers.IO` 写入
`cacheDir/share/claudroid-<timestamp>.png`，经现有 `${applicationId}.files` FileProvider 的
`<cache-path name="share" path="share/" />` 授权读取；每次分享前清理 24 小时前或最近 10 个之外的文件。
上限为图片 24 MiB、文字 20 万字符，分享内容本身绝不写入日志。

`releaseJson`：

```json
{"tag":"0.1.42","url":"https://github.com/.../releases/tag/v0.1.42",
 "publishedAt":"...","status":"UP_TO_DATE|UPDATE_AVAILABLE|DEV_BUILD",
 "currentVersion":"0.1.40"}
```

`attachments.pick` 必须在当前 Activity 上启动选择器；用户取消时返回
`{"ok":true,"cancelled":true,"relativePathsJson":"[]"}`。Dart 把每个路径当作待发送附件，
在提交时用 `composeChatPrompt` 把 `@attachments/<name>` 追加到 prompt；界面只显示预览图，
不再把路径插进输入框。预览图直接复用 `workspace.preview` 读取图片字节，不新增通道方法。

### 6.5 服务商与模型

| 方法 | 参数 | 返回 | Kotlin 实现 |
| --- | --- | --- | --- |
| `providers.list` | — | `{ok, presetsJson, activeId}` | `Providers.all` + `activeProviderPreset` |
| `providers.apply` | `presetId` | `{ok, settingsJson, labelsJson}` | `Providers.apply` 后写回 DataStore（保留 API Key） |
| `models.list` | — | `{ok, modelsJson, modelDetailsJson, endpoint}` | `ModelCatalog.fetch` |
| `models.test` | — | `{ok, endpoint, count}` | `ModelCatalog.fetch`；HTTP/解析失败转 `ok:false` |

`presetsJson`：

```json
[{"id":"anthropic","label":"Anthropic 官方","flavor":"ANTHROPIC",
  "baseUrl":"https://api.anthropic.com","authStyle":"API_KEY",
  "model":"claude-sonnet-5-5","hint":"..."}]
```

`activeId` 为 `null` 表示自定义端点。`modelsJson` 是模型 ID 的字符串数组，保留给旧前端
兼容；Flutter 只用 `modelDetailsJson`。`modelDetailsJson` 是同一批模型的元数据数组：

```json
[{"id":"auto/dfmodel","displayName":"DeepSeek-Flash",
  "contextWindow":1000000,"maxOutputTokens":128000,
  "maxInputTokens":1000000,"ownedBy":"qoder-cn","reasoning":true,
  "reasoningLevels":["low","high"],"label":"routing",
  "modalities":["text","image"],"supportedParameters":["tools"]}]
```

所有元数据字段可为空；只返回 `modelsJson` 的实现仍可用（Dart 会合成 ID-only 条目）。
`engine.refreshNetwork` 强制按当前网络重写 jail 的 `resolv.conf`/`gai.conf`；无安装或
root 未授权时 `skipped=true`，`message` 可直接显示给用户。

### 6.6 工作区

工作区是 jail 映射到 `/work` 的目录。默认工作区是 `files/workspace`；用户可以在
「工作区」页新建多个工作区（`files/workspaces/<id>`）并切换。任意时刻只有**一个**工作区被
挂载到 `/work`，所有 `workspace.*` 路径、附件导入与终端命令都作用于当前活动工作区。
Kotlin 负责路径校验、读取与系统 Intent；Dart 只渲染。所有 `path` 都是**工作区相对路径**
（`/` 分隔，`""` 表示根目录），`..` 逃逸、绝对路径与指向工作区外的符号链接一律拒绝。

| 方法 | 参数 | 返回 | Kotlin 实现 |
| --- | --- | --- | --- |
| `workspace.list` | `path` | `{ok, path, entriesJson, totalBytes}` | `WorkspaceFiles.list` |
| `workspace.preview` | `path` | `{ok, path, name, kind, mime, size, modifiedAt, text?, truncated, bytes?, tooLarge}` | `WorkspaceFiles.readPreview`；图片读原始字节 |
| `workspace.delete` | `path` | `{ok}` | 仅允许删除文件，目录拒绝 |
| `workspace.open` | `path` | `{ok}` | `FileProvider` + `ACTION_VIEW` |
| `workspace.share` | `path` | `{ok}` | `FileProvider` + `ACTION_SEND` chooser |
| `workspace.spaces` | — | `{ok, workspacesJson}` | `WorkspaceStore.state` |
| `workspace.create` | `name` | `{ok, workspacesJson}` | 新建目录并立即切换 |
| `workspace.select` | `id` | `{ok, workspacesJson}` | 切换活动工作区 |
| `workspace.rename` | `id`, `name` | `{ok, workspacesJson}` | 仅自定义工作区；默认工作区拒绝 |
| `workspace.remove` | `id` | `{ok, workspacesJson}` | 仅自定义工作区；递归删除目录 |

`workspacesJson`：

```json
{"activeId":"default",
 "spaces":[{"id":"default","name":"默认工作区","createdAt":0},
           {"id":"ws-abc123","name":"项目 A","createdAt":1700000000000}]}
```

`workspace.create` / `select` / `remove` 在聊天 turn 或终端命令运行时会返回 `ok:false`
（`任务正在运行，等这一轮结束后再切换工作区`）：运行中的进程已经持有旧的 `/work` bind，
切换会让同一命令看到两棵不同的树。删除是递归的，Dart 必须在确认框里明确告知。

`entriesJson`：

```json
[{"name":"report.md","path":"docs/report.md","kind":"MARKDOWN",
  "size":1234,"modifiedAt":1700000000000,"isDirectory":false}]
```

`kind` 是 `DIRECTORY | IMAGE | MARKDOWN | HTML | TEXT | ARCHIVE | OTHER`。目录在前按名称排序，
文件按 `modifiedAt` 倒序。`totalBytes` 是当前目录直接子文件大小之和（不含子目录递归）。

`workspace.preview` 的规则：

- 文本/Markdown/HTML/代码：`text` 为 UTF-8 预览（最多 512 KiB），`truncated` 表示文件更长；
  含 NUL 字节或无法按文本读取时不返回 `text`，Dart 落到二进制卡片。
- 图片：`bytes` 是原始字节（`Uint8List`，**不经过 JSON**），最多 6 MiB；超过时 `tooLarge:true`
  且不带 `bytes`，Dart 只显示打开/分享入口。`text` 一定为空。
- `mime` 来自 `WorkspaceFiles.mimeOf`，供打开/分享与图标选择。

### 6.7 终端

终端一次只允许一条命令。Kotlin 复用 `engine/TerminalRunner`（PRoot/root/直启与 CLI 一致），
不保留交互式 PTY；命令之间只保留工作目录，命令始终在**当前活动工作区**的 `/work` 下执行。

| 方法 | 参数 | 返回 | 说明 |
| --- | --- | --- | --- |
| `terminal.info` | — | `{ok, label, issue}` | `EngineManager.shellPlan` 的标签；`issue` 非空表示不可用 |
| `terminal.run` | `command`, `cwdRel` | `{ok}` | 立即返回；有命令在跑时 `ok:false` |
| `terminal.stop` | — | `{ok}` | 取消当前命令，子进程由 `TerminalRunner` 终止 |

运行结果通过 `app.claudroid/terminal` 发送：

```json
{"type":"chunk","text":"...部分输出..."}
{"type":"done","exitCode":0,"cwdRel":"docs","error":null,"truncated":false}
{"type":"stopped"}
{"type":"error","message":"执行失败：..."}
```

`chunk` 是 stdout+stderr 合并后的增量，最多累计 1 MiB（超出后继续排空但不追加）；Dart 侧
再按自己的显示上限裁剪。`done.cwdRel` 是下一条命令的起始目录；`stopped` 不改目录。
`terminal.stop` 与页面关闭都只影响终端，不影响聊天 turn。

### 6.8 备份与恢复

方法、归档格式、恢复语义与目标配置见 [`BACKUP.md`](BACKUP.md)。要点：

- 归档只含**会话、工作区文件与脱敏设置**；`apiKey` / `extraEnv` / `networkProxy` 不入包。
- 恢复是**安全合并**：不删除、不覆盖本机已有文件；会话仅在备份较新时覆盖。
- 支持 `WEBDAV` / `S3`（含兼容服务）/ `GITHUB` 仓库三种云端目标，以及 SAF 本地导入导出。
- 进度通过 `app.claudroid/backup` 推送；同一时刻只允许一个任务，任务或终端运行时拒绝执行。

## 7. 线程与错误

- MethodChannel handler 在主线程回调。凡是 `suspend` 的 Kotlin 方法一律
  `scope.launch { ...; result.success(map) }`，**不要**在 handler 里 `runBlocking`。
- `ChatController.send/resendFrom` 是**非 suspend**（内部已 `scope.launch`），直接调用即可；
  返回值表示「是否被接受」，不代表「已完成」。
- 任何异常：`result.error("bridge", message, null)` 之外，优先用 `result.success(mapOf("ok" to false, "error" to ...))`，
  便于 Dart 侧统一处理。
- 日志：桥接层只记 `Logx.i("bridge: <method>")` 级别，**绝不**打印 `apiKey`、`extraEnv`、
  `networkProxy`（可能含代理账号密码）、完整 prompt。`settingsJson` 在日志里必须先抹掉
  `apiKey`。

## 8. Dart 侧对应要求

- `lib/bridge/api.dart`：`Bridge` 单例，封装 `MethodChannel`，每个方法一个强类型签名，
  内部 `jsonDecode` 成 `AppSettings` / `ChatMessage` 等模型。
- `lib/bridge/events.dart`：把全局、单会话与终端三类 EventChannel 转成
  `Stream<AppState>` / `Stream<ChatState>` / `Stream<TerminalEvent>`。
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
