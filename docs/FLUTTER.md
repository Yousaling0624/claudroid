# Flutter 前端

**发布的 APK 用 Flutter 前端**：`release.yml` 以 `-PflutterUi=true` 构建，所以 GitHub Release
里的包启动后直接进 `FlutterHostActivity`。`flutter/` 仍是一个 **add-to-app 模块**，由
`settings.gradle.kts` 按 flag 决定是否 `include(":flutter")`——本地不带 flag 的
`./gradlew assembleDebug` 依旧是 Compose 前端，产物与引入 Flutter 之前完全一致，
也不需要在 WSL 里装 Flutter SDK。

Compose 前端保留为对照实现与本地回退路径；两边功能追平后可以彻底删掉。

## 目录

```
flutter/
├── pubspec.yaml          # package claudroid_ui；module: 段标记 add-to-app
├── .metadata             # project_type: module（pub get 生成 .android/ 的触发条件）
├── lib/
│   ├── main.dart         # 入口：runApp(const ClaudroidApp())，并 export ui/app.dart 供测试引用
│   ├── bridge/           # 与 Kotlin 的通道封装
│   │   ├── api.dart      #   Bridge：MethodChannel 调用（app/sessions/settings/engine/chat/…）
│   │   └── events.dart   #   GlobalEvents + 每会话 EventChannel，折叠成 AppState
│   ├── models/models.dart #  Dart 侧镜像 Kotlin 的序列化结构（AppState/ChatSession/Settings/Labels…）
│   └── ui/               # 界面，只用 prism_ui
│       ├── app.dart      #   ClaudroidApp：主题、事件订阅、home / chat:<id> 路由、设置/抽屉导航
│       ├── home_page.dart      # 首页：品牌问候、AI 建议、引擎提示、输入胶囊、模型胶囊
│       ├── workspace_page.dart # 工作区：文件浏览、文本/Markdown/HTML/图片预览、终端
│       ├── chat_page.dart      # 聊天页：气泡、工具卡、思考折叠、会话内搜索、回到底部
│       ├── composer.dart       # 共用输入胶囊：玻璃外层、附件、斜杠面板、发送/停止
│       ├── glass_controls.dart # 主操作按钮的液态玻璃封装
│       ├── left_side_sheet.dart # 左缘锚定的会话抽屉路由
│       ├── scaffold_bar.dart   # 静态页面色顶栏（无玻璃胶囊），标题/模型键/动作同排
│       ├── fold.dart           # 思考/工具折叠的动效与旋转 chevron（180ms 展开 / 120ms 收起）
│       ├── model_menu.dart     # 模型档位菜单与 /model 弹层
│       ├── press_guard.dart    # DeferredDisable：按下后自我禁用的按钮等按压动画结束再置灰
│       ├── drafts.dart         # 按会话保存的输入草稿（内存）
│       ├── session_drawer.dart # 会话抽屉：搜索、分组、行内菜单、底部引擎/设置
│       ├── settings_page.dart  # 设置根页：三组带图标的入口行，进入二级页
│       ├── palette.dart        # kSeedColor 与 claudroidColorScheme（暖纸角色覆盖）
│       ├── typography.dart     # claudroidSerif：首页问候语与空对话标题的衬线
│       ├── layout.dart         # ContentWidth 与聊天/首页/设置/工作区的阅读宽度
│       ├── organic_halo.dart   # 首页有机光晕：5.6s 呼吸 + 42s 慢旋转
│       ├── time_format.dart    # sessionTimeLabel：今天/昨天/日期
│       ├── chat_search.dart    # 会话内搜索：messagePlainText、searchMessages
│       ├── conversation_export.dart # 整段对话导出为纯文本
│       ├── workspace_logic.dart # 工作区 breadcrumb、名称过滤与排序
│       ├── settings/           # 设置二级页与共享状态
│       │   ├── settings_controller.dart # ChangeNotifier：设置/标签/引擎与所有异步动作
│       │   ├── settings_destination.dart # 深链目标：根页 / 服务与模型 / 引擎 / …
│       │   ├── settings_widgets.dart    # SettingsScaffold、入口行、动作行、picker/value/switch/info
│       │   ├── settings_dialogs.dart    # 文本、数字输入与卸载确认
│       │   ├── model_picker.dart        # 模型元数据选择器：搜索、分组、chips、手动输入
│       │   ├── settings_labels.dart     # 枚举标签、档位字段、字节格式、permissionFootnote
│       │   ├── settings_summary.dart    # 根页副标题的纯函数
│       │   ├── log_filter.dart          # 日志过滤纯函数
│       │   ├── service_models_page.dart
│       │   ├── engine_page.dart
│       │   ├── features_page.dart
│       │   ├── appearance_page.dart
│       │   ├── advanced_page.dart
│       │   ├── about_page.dart
│       │   └── logs_page.dart
│       ├── tool_presentation.dart # 工具调用的动词/图标映射 + toolCallDetail 输入摘要
│       ├── web_sources.dart    # 尾部来源剥离与来源卡解析
│       ├── markdown.dart # Markdown 渲染与可点击链接
│       ├── glyphs.dart   # 业务图标（prism_ui 的 Glyphs 之外的自定义 glyph）
│       └── localizations.dart  # ZhUiLocalizations：prism_ui 组件的中文文案
├── test/                 # 无平台通道的组件/纯函数测试（smoke、搜索、导出、排序、时间格式等）
└── .android/             # flutter pub get 生成，已 gitignore
```

## 界面结构

- **导航**：`ui/app.dart` 只有 `home` 与 `chat:<sessionId>` 两个常驻目的地，左上角用
  `showLeftSideSheet` 从左缘展开会话抽屉，设置页用 `BaseNavigator.push` 叠加。冷启动与「新对话」都落在首页；从首页发送第一条消息时才调用
  `sessions.create` 并切到聊天页（与 Compose `MainActivity.AppRoot` 一致）。
- **首页**：`home_page.dart` 的衬线问候语来自本地时间，左侧是缓慢呼吸/旋转的有机光晕；
  AI 建议走 Kotlin 的 `SuggestionGenerator`（`app.suggestions`），加载骨架与 chip 都可点，
  切换新对话时重新生成。最近一条有内容的会话显示为「继续上次对话」卡，点击直接进入。
  引擎未就绪时顶部出现可点进设置的提示条；输入胶囊与聊天页共用 `composer.dart`，下方脚注
  随 `permissionMode` 变化。
- **聊天页**：按 blocks 原始顺序渲染。输出前只显示实时思考胶囊与活动行（动词 + 输入摘要）；
  开始输出或结束后汇总成默认折叠的 `WorkDisclosure`，展开后仍是按时间的思考折叠 + 工具卡。
  正文前的尾部
  `Sources:` 列表会被剥离并显示成来源卡，Markdown 链接与裸 URL 经 `app.openUrl` 打开。
  用户消息空闲时显示「编辑」动作；顶栏可搜索当前会话或复制整段对话。流式状态按帧合并，
  Markdown 解析结果按输入缓存，正文限宽 760。助手正文用无衬线，每条回答的署名读
  `ChatMessage.model`（开回合时由 Kotlin 冻结），切换模型或档位不会改写旧回答的签名。
- **设置页**：根页按「模型与引擎 / 应用 / 其他」分三组，每行是带线条拟物图标的入口，
  用 `OpenContainer` 打开对应二级页：服务与模型、Claude Code 引擎、功能与实验、外观、
  高级、关于与诊断；工作区单独复用 `workspace_page.dart`。从外部快捷入口进入时，
  `SettingsPage(destination: …)` 直接渲染目标二级页：模型菜单的「接口与模型…」到服务与模型，
  首页引擎提示与抽屉引擎胶囊到引擎页；只有 `/settings` 与抽屉齿轮按钮进根页。所有页面共享一个
  `SettingsController`，因此安装进度、`settings` 事件和异步操作结果会在根页与当前二级页
  同时刷新。模型字段共用带元数据的搜索选择器；引擎页把安装/升级做成主操作，更新/自检/
  Root 探测/网络配置是独立动作行，卸载单独归入危险操作分区；服务商分区展示选中预设的
  hint；日志页支持过滤。外观页提供跟随系统/浅色/深色的实时预览卡，关于页只放版本、发布、
  日志与存储。
- **工作区**：抽屉底部按钮或 `/workspace` 打开。目录浏览与文件大小/时间来自
  `workspace.list`；breadcrumb 可跳级，工具条支持按名称过滤与排序。文本、Markdown、HTML
  与图片预览走 `workspace.preview`（图片字节以 `Uint8List` 过通道），HTML 由 Kotlin 侧
  注册的平台视图（系统 `WebView`）渲染并可切到源码，内容过大时提示截断；文本可复制并切换
  自动换行；分享/打开/删除由 Kotlin 的 `FileProvider` 与系统 Intent 完成。终端复用
  `TerminalRunner` 的一次性命令模型，输出经 `app.claudroid/terminal` 流式到达，输入框带
  `$` 提示并支持上/下键历史，离开页面会终止子进程。

分层原则：**Flutter 只做界面，业务逻辑全部留在 Kotlin**。Dart 不读文件、不拼命令行、
不解析 stream-json，只把通道事件折叠成状态再渲染。

`.android/` 是生成物，不进版本库；`pubspec.lock` **要**提交。

## 构建

```bash
# 1. 生成 flutter/.android/（每次改 pubspec 后都要跑）
cd flutter && flutter pub get

# 2. 让 Gradle 找到 SDK：local.properties 里加一行
flutter.sdk=C\:\\dev-toolchain\\flutter
#    或者导出 FLUTTER_ROOT

# 3. 带 flag 构建
./gradlew assembleDebug -PflutterUi=true -Pandroid.newDsl=false
```

产物里会多出 `lib/*/libflutter.so` 与 `assets/flutter_assets/`，其余部分不变。

`release.yml` 走的就是这条路径：装 Flutter 3.47.9、写 `local.properties` 的
`flutter.sdk`、跑 `flutter pub get`，再以 `-PflutterUi=true -Pandroid.newDsl=false`
`assembleRelease`，最后用 `unzip -l` 断言 `libapp.so` 与 `assets/flutter_assets/` 真的在包里
——只有 Compose 的 APK 同样能构建成功，不校验就会悄悄发错前端。

### 为什么必须 `-Pandroid.newDsl=false`

Flutter 的 Gradle 插件（`FlutterPluginUtils.getLegacyAndroidExtension`）用
`project.extensions.findByType(BaseExtension::class.java)!!` 取 AGP 的旧扩展，
`forceNdkDownload` 会走到那里；AGP 9 的新 DSL 不再注册 `BaseExtension`，`!!` 直接抛
NPE。**这个 flag 只能在命令行传**：AGP 在 settings 脚本执行之前就读它，所以在
`settings.gradle.kts` 里改 `gradle.startParameter` 无效（已实测）。

不要把它写进 `gradle.properties`——那会让 Compose 的默认构建也退回旧 DSL。等 Flutter
插件支持新 DSL 后，删掉这个 flag 即可。

### 为什么必须 apply `module_plugin_loader.gradle`

`settings.gradle.kts` 里那句 `apply(from = "$flutterSdk/.../module_plugin_loader.gradle")`
不是可选的。Flutter 插件在 `:app` 的 `afterEvaluate` 里注册各 variant 的任务；没有这个
loader，`:app` 会在 `:flutter` 配置之前就求值完成，于是抛出
`Cannot run Project.afterEvaluate(Action) when the project is already evaluated`。
loader 会对除 `:flutter` 以外的所有 project 设置 `evaluationDependsOn(':flutter')`，
同时 include 模块的 native 插件。

### 仓库冲突

Flutter 插件在配置期执行 `rootProject.allprojects { repositories.maven { ... } }` 拉引擎
产物（`io.flutter:flutter_embedding_*`、`libflutter.so`），与
`FAIL_ON_PROJECT_REPOS` 冲突。`settings.gradle.kts` 的解法是：flag 打开时在
`dependencyResolutionManagement` 里声明 `https://storage.googleapis.com/download.flutter.io`，
并把 mode 切成 `PREFER_SETTINGS`。构建会打三条 "prefer settings repositories" 警告，属预期。

## 依赖

| 包 | 用途 |
| --- | --- |
| `prism_ui` 0.1.2 | FlClash 的视觉语言：液态玻璃、glyph 图标、`generateSection` 列表分组 |

`prism_ui` 是 GPL v3（chen08209），随发布前端一起分发；见 `THIRD_PARTY_NOTICES.md`。

工作区的 HTML 预览没有引入 `webview_flutter`：它的 Android 实现在 add-to-app + AGP 9 下
无法配置（要么重新 apply 已被 AGP 9 移除的 `kotlin-android`，要么用 Kotlin DSL 访问器读
不到 Flutter 的扩展）。改为在 `BridgePlugin` 里直接注册一个宿主 `WebView` 的平台视图
（`HtmlPreviewView`），Dart 侧只用 `AndroidView`，同时沿用 Compose 的安全策略：null base
URL、关闭文件与 content provider 访问。

`flutter/lib/**` 只用 `package:flutter/widgets.dart`（`ValueListenable` 一类从
`package:flutter/foundation.dart` 取），**不要 import Material**：`prism_ui` 自带
`AppBar` / `ThemeMode` / `TextField`，同时引入会产生 `ambiguous_import`。

图标同理：`package:prism_ui/prism_ui.dart` **不**导出 `icons/`，用 `GlyphIcon` / `Glyphs`
要单独 `import 'package:prism_ui/icons/icons.dart';`。

## 与 Kotlin 侧的契约

桥接协议冻结在 [`BRIDGE_CONTRACT.md`](BRIDGE_CONTRACT.md)。Kotlin 侧的平台通道实现放在
`app/src/main/flutter/java/`（只在 `flutterUi` 打开时进 sourceSet），所以默认构建的
classpath 里不会出现 Flutter embedding。

### 前端切换：`MainActivity` 的 trampoline

`MainActivity.onCreate` 开头做一次运行期探测：

```kotlin
if (flutterHostAvailable()) {
    startActivity(Intent().setClassName(packageName, "app.claudroid.ui.FlutterHostActivity"))
    finish()
    return
}
```

`flutterHostAvailable()` 就是 `Class.forName(...)` 是否成功。于是同一个 APK 两种形态：

- 默认构建：`FlutterHostActivity` 类不存在，探测失败，**行为完全不变**（Compose 前端）。
- `-PflutterUi=true`：类存在，`MainActivity` 立刻转发到 `FlutterHostActivity` 并结束自己。

用 trampoline 而不是改 manifest 的 `android:name`，是为了让 `TurnService` 里硬编码的
`Intent(this, MainActivity::class.java)` 通知跳转继续可用——它仍然先落到 `MainActivity`，
再被转发进聊天页。

`FlutterHostActivity` 的三条约束：

- `BridgeBootstrap.install()` 必须在 `super.onCreate(savedInstanceState)` **之前**调用，否则
  引擎缓存已经建好、通道却没注册。
- `provideFlutterEngine` 复用 `FlutterEngineCache` 里 key 为 `claudroid` 的引擎（先 `get`，
  没有就 `BridgePlugin.attachCachedEngine(context)`，再 `get`）。
- `shouldDestroyEngineWithHost() = false`：Activity 销毁不能带走缓存引擎，否则热重启和
  返回前台会丢会话连接。
