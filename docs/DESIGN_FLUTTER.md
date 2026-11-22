# Flutter 前端视觉规范

`flutter/` 是应用唯一的前端；Compose 实现已移除，[`DESIGN.md`](DESIGN.md) 只作历史参考。
视觉方向是 **FlClash 结构 + Claude 质感**：材质、图标、动画与设置页排版沿用
[FlClash](https://github.com/chen08209/FlClash)，页面底色、文字气质与克制的赤陶色改用
`DESIGN.md` 的暖纸方案：

> 材质、图标、动画效果照抄 flclash，设置页面的排版也要借鉴一下，聊天页面保持当前布局。

## 1. 依赖与组件映射

下表的左列是已移除的 Compose 实现，保留作对照；右列是当前唯一实现。

| Compose（`ui/`） | Flutter（`flutter/lib/ui/`） |
| --- | --- |
| `Theme.kt`（暖纸 + 赤陶） | `palette.dart` 的 `claudroidColorScheme`（seeded 主色 + 暖纸角色覆盖） |
| `HomeScreen` | `home_page.dart` |
| `AppDrawer` | `session_drawer.dart` 的 `SessionDrawer` |
| `ChatScreen` / 气泡 / 工具卡 / 工作折叠 | `chat_page.dart`（**布局保持不变**） |
| `ChatInput` / 斜杠面板 | `composer.dart` 的 `ChatComposer` |
| `ModelTierMenu` | `model_menu.dart` |
| `ToolPresentation` | `tool_presentation.dart` |
| `WebSources` | `web_sources.dart` |
| `WorkspaceScreen` / `WorkspaceTerminal` | `workspace_page.dart` |
| `SettingsScreen` | `settings_page.dart`（按 FlClash 分组列表排版重做） |
| `AppIcons` | `glyphs.dart` 的 `AppGlyphs` + prism_ui 的 `Glyphs` |
| Material 组件 | prism_ui 同名替代（见下表） |

| 需要什么 | 用 prism_ui 的 |
| --- | --- |
| 页面骨架、顶栏 | `PrismScaffold`；`bar` 用 `scaffold_bar.dart` 的 `ClaudroidBar`（静态页面色背板，图标键由内部 `TonalButtonTheme` 给玻璃胶囊），**不要**直接用 `ScaffoldBar` |
| 容器 | `Surface`（`SurfaceLevel.page` / `.card` 取色） |
| 设置项列表 | `generateSection` / `generateGroup` / `ListRow` / `DecorationListItem`；根页用 `SettingsGroup` + `SettingsEntryRow` |
| 分组行间距 | `ItemPosition.get(index, length)` 或 `ItemPositionProvider` |
| 搜索框 | `SearchField` |
| 弹出菜单 | `PopupAnchor` + `PopupMenu` + `PopupMenuItem` |
| 对话框 | `AlertDialog` + `showClaudroidDialog` |
| 输入框 | prism_ui 的 `TextField`（镜像 Flutter 构造器）；聊天输入框外层是单个 `LiquidGlass` 胶囊 |
| 可点击卡片 | `Card`（`onPressed`）/ `PressSurface` |
| 按钮 | `TextButton` / `GlassTonalButton`（主操作）/ `TonalButton`（危险操作） |
| 长列表 | `PrismListView` |
| 侧边抽屉 | `showLeftSideSheet` + prism_ui 的 `SideSheet`（左缘锚定） |
| 图标 | `GlyphIcon(Glyphs.x)`，**必须** `import 'package:prism_ui/icons/icons.dart';` |
| Markdown（`ui/md/Markdown.kt`） | `markdown.dart` 的 `MarkdownText`（Dart 移植，无外部依赖） |

**不要 import `package:flutter/material.dart`**：`prism_ui` 自带 `AppBar`、`ThemeMode`、
`TextField`、`Card` 等同名符号，同时引入会产生 `ambiguous_import`。
`ValueListenable` / `ChangeNotifier` 从 `package:flutter/foundation.dart` 取。

## 2. 色板

`palette.dart` 先取 `seededColorScheme(seedColor: kSeedColor)` 生成主色、容器与玻璃底纹，
再用 `copyWith` 把表面/文字/描边角色覆盖成固定暖色（`claudroidColorScheme(Brightness)`）。
`PrismApp` 与外观页的三张预览卡都只走这一个入口，不各自建 scheme。

```dart
const kSeedColor = Color(0xFFD97757); // 与 ui/theme/Theme.kt 的 orange 同值
```

浅色：

| 角色 | 值 | 用途 |
| --- | --- | --- |
| `surface` / `surfaceContainer` | `#F8F4ED` | 页面背景 |
| `surfaceBright` | `#FBF9F4` | 卡片 |
| `surfaceContainerLowest` | `#FFFDF9` | 最亮层 |
| `surfaceContainerHigh` | `#F0EAE0` | 用户气泡、次级填充、搜索框 |
| `surfaceContainerHighest` | `#E8E0D2` | 按下态、内嵌块 |
| `onSurface` | `#2D2520` | 主文本 |
| `onSurfaceVariant` | `#5A4F44` | 次要文本 |
| `outline` | `#B5AB9E` | 发丝边 |
| `outlineVariant` | `#DDD2BD` | 更淡分隔线 |
| `primary` | `#D97757` | 焦点、发送/停止 |
| `primaryContainer` | `#F2DDD0` | 选中底 |
| `onPrimaryContainer` | `#A64C2F` | 选中底上的文字 |

深色：`surface #1B1815`、`surfaceBright #24201C`、`surfaceContainerHigh #2A251F`、
`surfaceContainerHighest #332D26`、`onSurface #F2EAE0`、`onSurfaceVariant #C9BFB3`、
`outline #8A7D6E`、`outlineVariant #443C34`、`primary #D97757`、`onPrimary #33170E`、
`primaryContainer #5A2E1E`、`onPrimaryContainer #F6D8C9`。

暗色的 `primary` 直接复用品牌 clay `#D97757`（claude.ai 暗色主题同样沿用这个值），
不再用提亮版：旧值 `#E08B6C` 在暖黑纸面上会发粉、显得不够鲜艳。

两条取色规则：

- **取色只用语义角色**（`context.colorScheme.primary`、`.onSurface`、`.onSurfaceVariant`、
  `.surface`），不要在组件里写十六进制字面量 —— 唯一例外是 `palette.dart` 本身。卡片参照
  prism_ui 的 `SurfaceLevel`：`.page` = `surfaceContainer`、`.card` = `surfaceBright`、
  `.block` = `surfaceContainer`、`.inset` = `surfaceContainerHighest`。
- 橙色 `primary` 只做**填充与图标**（发送/停止、当前会话标记、聚焦描边），单屏填充面积
  不超过 5%；正文里的链接、裸 URL、来源标题等**文字**场景用 `linkColor(scheme)`（浅色
  `#A64C2F`、深色直接用品牌色 `#D97757`），保证对比度。
- 设置根页的图标徽章用 `palette.dart` 的 `SettingsTone`（clay / honey / sage）：clay 用
  品牌 clay（暗色即 `#D97757`），honey / sage 用去饱和的暖色；`fill` 是圆形底、`ink` 是
  描边图标色，浅深色各有一套。不要在页面里另写十六进制色值。

## 3. 材质与动画

- `PrismApp(liquidGlass: LiquidGlassQuality.standard)`：液态玻璃统一在这个层级开，
  组件侧不单独配。**顶栏背板保持静态页面色**（整条 bar 覆盖，末尾约 16% 高度淡出），
  滚动内容不会透过标题；但顶栏里的图标按钮由 `ClaudroidBar` 统一包在
  `TonalButtonTheme(glass: LiquidGlassScope.of(context), size: TonalButtonSize.bar)`
  里，菜单、搜索、返回、新建等键都是液态玻璃胶囊（与 FlClash 一致）。文本类动作（模型键）
  不受这个主题影响，仍是纯文字。玻璃同时用于聊天输入框（单个胶囊，内部按钮不叠 pane）
  和页面主操作按钮（`GlassTonalButton`）。无 `LiquidGlassScope` 时这些按钮降级为
  `raisedSurface` 填充。
- 顶栏 `preferredSize` 必须保持 72dp（compact sheet 48dp），否则 `contentTopPadding`
  会算错；静态背板让滚动内容从标题下方干净地滑过，就是设置页标题不跟着列表移动的
  实现方式。
- **动作材质词汇**（同类操作必须同一个组件）：

  | 角色 | 组件 | 材质 |
  | --- | --- | --- |
  | 页面主操作 | `GlassTonalButton` | 液态玻璃 |
  | 危险操作 | `TonalButton` | 红色实色 |
  | 行内次要动作 | `TextButton` | 无填充 |
  | 行尾值/编辑 | `SettingsValueButton` | 文本 + glyph |
  | 顶栏图标 | `IconButton` + `ClaudroidBar` 的 `TonalButtonTheme` | 液态玻璃 |
  | 对话框动作 | `AlertDialog` actions | 末位自动填色 |

- 弹层（菜单、对话框）用 prism_ui 自带的转场与玻璃模糊；左侧抽屉是
  `showLeftSideSheet` 的 `PopupRoute`，复用 `SideSheet` 与 `SheetCoverRouteMixin`。
  对话框统一走 `dialog_motion.dart` 的 `showClaudroidDialog`：280ms 从 +10dp 上浮到 0、
  0.96→1 微缩放并玻璃淡入，退出 180ms；prism 的玻璃遮罩与 `AlertDialog` 表面不变。
  **不要手写** `AnimatedOpacity` / `BackdropFilter`。
- 动效时长统一走 `context.motionDuration`，无障碍「减少动效」打开时它自动归零；
  `context.disableAnimations` 可作显式判断。**不要**写死 `Duration(milliseconds: …)`。
- 液态玻璃按钮的长按拖动形变由 `glass_controls.dart` 的 `LiquidGlassMorph` 提供，
  顶栏图标键与 `GlassTonalButton` 都包在它里面：按住 `kLongPressTimeout` 后按钮轻微
  膨胀，拖动时沿拖动方向拉伸并跟手，松手弹回。手指在长按计时前移动超过 `kTouchSlop`
  就取消形变，把滚动/点击留给子组件；减少动效时完全不启动。形变只作用于绘制与命中
  变换，不改变顶栏布局。
- **按钮不能在自己的点击回调里把自己置为 `onPressed: null`**：prism_ui 的
  `PressSurface` 会在「按下 → 禁用」的这次更新里取消按压动画并经 states controller
  回调按钮自己的 `setState`，debug 构建直接触发框架断言（红屏）。主操作按钮用
  `DeferredDisable`（`press_guard.dart`）包一层，把置灰推迟到 250ms 按压动画结束之后；
  发送/附件这类高频切换的按钮则始终保留非空回调，在回调内判断是否能执行。
- 思考/工具折叠用 `fold.dart` 的 `AnimatedFold`：展开 180ms、收起 120ms，
  曲线 `Cubic(0,0,0.2,1)`，淡入先于高度完成；chevron 用 `FoldChevron` 旋转 90°，
  不换图标。展开状态由聊天页按 `work:<messageId>` / `think:<messageId>:<block>`
  / `tool:<blockId>` 记忆，列表回收后不丢。折叠头不允许再次手写 `if (open)`。
- 滚动与浮层同一套弹簧语言：Android 列表用 `scroll_behavior.dart` 的
  `BouncingScrollPhysics + RangeMaintainingScrollPhysics`（不再用 Clamping）；
  输入区的编辑提示与附件条用 `AnimatedFold(slide: 6)` 从下方浮起、斜杠面板用
  `slide: 8`，收起时用 `RetainedFold` 把最后一份内容留到高度折完（`composer.dart`）；
  对话框列表行用
  `dialog_motion.dart` 的 `DialogRowReveal` 在窗口落位后逐行浮现，节奏与 `PopupMenu` 一致。
- 「实时思考/活动行 → 最终 WorkDisclosure」是状态驱动的结构性切换，用
  `AnimatedFold(fade: false)` 只做高度过渡，不做整块淡入淡出；用户手动展开折叠时仍保留淡入。
  流式结束时这些结构折叠传 `animate: false`（`AnimatedFold.animate`），直接落到终态：
  否则最后一帧还会再播一次高度动画，看上去像整页刷新。**不要**在 `streaming` 结束时
  引入任何新的淡入淡出。
- 图标一律矢量 glyph，不用 emoji、不用位图，不随字号放大而变糊。
- 自定义 `Glyph` 的开放线条（弧线、折线）必须标 `GlyphRole.line`。顶栏图标键以
  `fill: 1` 绘制：`body` 角色的开放弧线会被当成闭合面填充成一块实心扇面，`detail`
  则会被挖成透明；只有 `line` 在两种填充下都保持描边。

## 4. 字体与排版

用 prism_ui 的文本样式类，不要 `TextStyle(fontSize: …)` 硬编码：

| 场景 | 样式 |
| --- | --- |
| 页面标题、品牌 | `AppTextStyle.headline` |
| 区块小标题 | `context.listTitleStyle` |
| 设置项主文本 | `AppTextStyle.callout`（强调加 `emphasized: true`） |
| 设置项副文本 | `context.listSubtitleStyle` |
| 说明/脚注 | `AppTextStyle.footnote` |
| 卡片头 | `context.cardHeaderStyle` |

列表行的高度、内边距交给 `ListRow` / `DecorationListItem` 的默认值，不要逐行覆写 ——
FlClash 的观感来自这些统一的行高。

**衬线**只用于单行展示型文字：首页问候语（约 30/38、字重 500）与空对话标题「开始对话」。
用 `typography.dart` 的 `claudroidSerif(style)`（`fontFamily: 'serif'`，以
`Noto Serif CJK SC` / `Noto Serif SC` 兜底）。**助手正文不用衬线**：Android 的系统级中文
衬线只登记了 regular 字重，标题与 `**粗体**` 会回退到黑体，正文出现「细宋体 + 粗黑体」
混排；正文改用主题的无衬线栈后，中英文与 400/600 字重都来自同一族。用户气泡、设置、
工具卡、行内代码、代码块与表格同样保持无衬线或 mono，不引入字体包。

**阅读宽度**用 `layout.dart` 的 `ContentWidth`：聊天 `760`、首页 `640`、设置 `840`、
工作区 `820`。顶栏保持通栏，正文、输入胶囊与预览在宽屏上居中收窄，不随窗口无限拉伸。

## 5. 各页面约束

### 首页

- 问候语衬线、副标题无衬线；问候语左侧的有机光晕（`organic_halo.dart`）做 5.6s 呼吸与
  42s 缓慢旋转，主色约 13% 透明度，减少动效时固定形状且不旋转。
- 最近一条有内容的会话显示为「继续上次对话」卡（标题、预览、相对时间、chevron），
  没有会话时不占位；时间格式由 `time_format.dart` 的 `sessionTimeLabel` 统一生成。
- AI 建议加载中用 `SkeletonPill`（自带呼吸并尊重减少动效），chip 高度 40dp；没有建议就
  整条隐藏，不伪造。
- 输入胶囊与聊天页共用 `composer.dart`，下方脚注由 `permissionFootnote` 生成 ——
  `BYPASS` 时写明「命令会直接执行」。

### 聊天页 —— 保留信息层级，精修交互

信息层级与 `ChatScreen.kt` 一致（气泡、工具卡、思考折叠、流式光标、错误条、回到底部），
只把 Compose 组件换成 prism_ui 等价物，并补上可见交互：

- 用户气泡用 `surfaceContainerHigh` 填充、`onSurface` 文字，最大宽度为**内容宽度的 80%**，
  不铺橙色底。空闲时气泡下方有「编辑」动作（编辑 glyph + 文案），运行中隐藏；点气泡编辑的
  隐藏手势保留。动作行与来源行触控目标 ≥ 44dp。
- 助手正文用无衬线（见 §4，避免中文衬线粗体回退造成混排），由 `MarkdownText` 渲染
  （标题/列表/引用/表格/代码块/行内样式）；标题是 22/20/17/16 的四级半粗梯度，列表项间距
  5dp、段落 9dp，行内代码底比代码块浅一档。列表符号用 `onSurfaceVariant`，赤陶色只留给
  链接与裸 URL；用户气泡仍是纯文本。Markdown 链接经 `app.openUrl`（仅 http/https）交给
  系统浏览器。
- 一条回答的全部正文共用一个 `SelectionArea`：`MarkdownText` 内部只用 `Text.rich`
  （表格、代码块也一样，代码块不再自建选择域），长按后的「全选」覆盖整条回复而不是
  单个段落。标题行、思考过程与工具卡留在这个区域外，避免嵌套选择域把全选截断。
  选择菜单由 `selection_menu.dart` 接管：按框架给出的顺序显示「复制 / 分享 / 全选」，
  点「全选」后菜单会带着整段选区重新弹出，可以继续分享或复制，不会直接消失。
  工作区的 Markdown 预览与文本预览同样由外层 `SelectionArea` 提供选择，并共用这个菜单。
- 消息正文必须支持 emoji：`Markdown.inline` 的普通文字按整段缓冲后再建 `TextSpan`，
  不能逐 `codeUnit` 建 span——代理对（😀、👨‍👩‍👧‍👦 等）一旦被拆进两个 span，排版器
  会把两个半个字符都画成 U+FFFD。加行内样式时先 `flushPlain()`，保证标记两侧的
  普通文字仍是连续的 span。
- 助手输出前的实时思考胶囊与活动行、输出后的 `WorkDisclosure` 嵌套折叠，语义照
  `ChatScreen.kt`：「输出前实时，输出后合并」；展开后仍按 blocks 原始顺序排列。
  实时胶囊与折叠内容都靠 `AnimatedFold` 对齐到起始边，窄胶囊不会被 `SizeTransition`
  居中。活动行在本地化动词后显示工具输入摘要（`toolCallDetail`，command / file_path /
  query / url 等首个可用字段），思考阶段也能看出正在做什么。
- 工具卡标题用 `tool_presentation.dart` 的本地化动词 + 工具名，完成/失败用勾叉 glyph；
  收起时只显示标题与一行摘要。来源列表、输入与输出都属于展开后的详情，默认不占卡片
  高度；展开后来源按 `web_sources.dart` 的结果显示可点卡片。失败工具用错误色描边，
  错误条用 `errorContainer` 淡底 + `error` 描边。
- 顶栏只保留模型键与新对话键，不再放搜索键；会话内搜索（`chat_search.dart`）从标题菜单的
  「搜索当前对话 / 退出搜索」进入：只保留命中正文/错误的消息并显示匹配数，搜索时暂停
  自动跟随与回到底部。顶栏菜单的「复制整段对话」用
  `conversation_export.dart` 导出纯文本（剥掉尾部 `Sources:` 与附件引用，跳过思考/工具）。
- 每条回答结束后的动作行**只用图标键**，不显示文字：重新回答（仅最后一条）、复制（复制后
  变勾）、分享（`AppGlyphs.share`，见下）、分出新对话（`AppGlyphs.fork`，调 `chat.fork`）。
  tooltip 负责解释图标。分享键先弹出「分享文字 / 分享图片」：
  - **分享文字**把可见正文（剥掉尾部 `Sources:` 与附件引用）交给系统分享面板，可存进便签
    或聊天应用；
  - **分享图片**把整条回复（标题行、思考/工具折叠与正文，不含动作行）渲染成一张四周留
    20dp 页面底色背景的 PNG 长图，再交给系统分享面板。设备像素比上限 2x，任一边不超过
    8192px、总像素不超过 1200 万；过长的回复提示改用分享文字。
- 附件不出现在输入框文本里：`composer.dart` 与用户消息气泡都用 `attachments.dart` 的
  `AttachmentPreviewStrip` 显示缩略图（图片走 `workspace.preview` 字节，其他文件显示
  文件卡片 + 扩展名，可点 × 移除）；提交时才由 `composeChatPrompt` 把
  `@attachments/<name>` 追加到真正发送的 prompt。
- 回到底部是 48dp 可按压按钮，用 `AnimatedVisibility.bottomNavigation` 淡入并轻微缩放，
  列表底部预留 56dp，最后一条消息不会被遮住。
- 流式更新按帧合并：`ChatState` 每帧最多刷新一次，顶栏只在可见签名变化时重建，Markdown
  解析按「文本 + 配色 + 回调 + 衬线」缓存，每条消息包一层 `RepaintBoundary`。
- 橙色只用于焦点、发送/停止与当前会话标记，占屏不超过 5%。

### 设置页 —— 分组卡片 + 图标徽章

设置页借鉴 FlClash 的「分组标题 + 一组行 + 行尾值/箭头」结构，但根页改为一屏可读完的
入口卡片，不再用 Material 的卡片堆叠：

- **根页**只放入口行：三组「模型与引擎 / 应用 / 其他」，每组是 `SettingsGroup`
  （组标题 + 圆角 22dp 的 `surfaceBright` 卡片 + 发丝分隔线）。每行是
  `SettingsEntryRow(glyph, title, value, tone, pageBuilder)`，左侧 42dp 圆形图标徽章，
  行尾只放一行实时值（服务商 · 模型、引擎版本/进度、开关数、主题、自动更新/轮数、
  版本/发布状态）与 chevron，长值省略；不写第二行说明。行 → 页仍用 `OpenContainer`
  做展开转场。
- 根页顶栏标题居中、字号放大到 `AppTextStyle.headline`（`ClaudroidBar.centerTitle`），
  与分组卡片一起构成参考稿的层级。二级页也复用同一个居中标题，标题始终固定在顶栏。
- **二级页**沿用 FlClash 的 `ListRow` 语言：`SettingsScaffold` + `generateSection`，
  选择类设置用 `PopupAnchor` 弹出 `PopupMenu`，**行尾只显示当前值**，不写解释性副标题
  （用户要求「去除所有说明文字来降低行长」）。
- 选择框右边缘对齐按钮右边缘，宽度受控（不要撑满整行）。
- 开关用 `SettingsSwitchRow`，纯展示用 `SettingsInfoRow`，提示/错误用 `SettingsNote`。
- 从外部快捷入口进设置时用 `SettingsDestination` 深链，不再先落根页：模型菜单的
  「接口与模型…」直接打开服务与模型页，首页引擎提示与抽屉引擎胶囊直接打开引擎页；
  只有 `/settings` 与抽屉齿轮按钮进根页。
- **行尾语义**：`chevronForward` = 进入下一页，`Glyphs.picker`（上下双箭头）= 打开菜单/
  模型选择器，`AppGlyphs.edit`（铅笔）= 弹出文本输入，`AppGlyphs.refresh` = 原地执行动作。
  `SettingsValueRow` 默认铅笔；模型字段显式传 picker。这样列表行按钮与可输入设置项
  不再用同一个图标。所有刷新、重新拉取、检查更新与重试动作（含按钮图标和
  `SettingsActionRow`）统一用 `AppGlyphs.refresh`，不要另画或借用 `plug` 等图标。
- **输入弹窗**统一用 `SettingsTextPrompt`（会话重命名也用它）：`Form` 校验、错误行内
  显示并带 `AnimatedSize`；API Key 用 `SecretTextFormField` 的 eye/eyeOff 切换，Base URL
  用 URL 键盘并校验 `http(s)://`，数字项用数字键盘与范围校验，只有多行文本才允许换行。
- **模型选择器**在打开时自动（或按按钮强制）拉取，loading / error / empty / 列表
  四种状态用 `AnimatedSwitcher` 过渡固定高度内容区；失败时给出「确认 Base URL、API Key
  与协议后重试」并保留重试按钮；元数据 chip 用 `TonalChip`。
- 模型选择器显示 `displayName`、ID、上下文/输出/推理 chips，按 `owned_by` 分组并支持
  搜索；「手动输入」保留纯文本回退。选默认模型会同时清空 `activeTier`。
- 二级页不再逐行加图标；图标只作为根页的导航语言，避免设置项列表变成图标墙。
- 引擎页把「安装 / 升级」做成分区顶部的整行 `GlassTonalButton`：未安装时是「安装
  Claude Code」，有可用更新时是「仅更新 CLI 到 <版本>」（只换 CLI，不重建容器），
  其余情况是「重装 / 修复容器」；有可用更新时再加一行「重装 / 修复容器（完整）」，
  保留完整重建入口。「检查更新、自检、Root 探测、取消」改成各占一行的动作行（行尾
  glyph，运行中禁用，取消只在 `phase == 'Working'` 时出现）；「网络」分区放「网络代理」
  文本行与「刷新网络配置」动作行；「卸载引擎」单独放「危险操作」分区。安装位置整行可点复制。
  「官方最新」与「已安装」不一致时，状态区显示主色「可更新」chip。
- 服务与模型页在选中预设带有非空 `hint` 时，用该 hint 作为服务商分区的 footer。
- 日志页顶部是过滤框（`filterLogLines`，大小写不敏感），并显示「N / M 行」；复制与清空
  仍作用于全部日志，空日志隐藏过滤框。
- 外观页的三张主题预览卡是唯一例外：预览本身就是内容，用 `claudroidColorScheme` 的
  浅/深 scheme 画迷你界面，选中态用主色描边与勾选徽标。

### 工作区

- 目录与文件行用 `Card`（左侧图标块、名称、大小/时间、chevron），不是 Material ListTile；
  目录在前，文件按修改时间倒序，顺序与 `WorkspaceFiles.list` 一致。
- 路径导航是可横向滚动的 breadcrumb chip（`/work` 到当前层），点任意 chip 跳回该层；
  工具条的搜索按名称过滤，排序菜单提供名称/时间/大小与升降序，目录永远在前
  （纯函数在 `workspace_logic.dart`）。
- 预览按 Kotlin 返回的 `kind` 分派：Markdown 用 `MarkdownText`，代码/文本用等宽
  `SelectableText`，图片用 `Image.memory` + `InteractiveViewer`，HTML 用 Kotlin 注册的
  平台视图（`AndroidView` 承载系统 `WebView`）渲染、顶栏可切「源码」，二进制落到打开/
  分享卡片。`truncated == true` 时顶部提示「内容过大，仅显示前 N 字节」；文本/代码与
  HTML 源码可复制并切换自动换行。
- Claude 工作目录从聊天顶栏标题菜单的「Claude 工作目录 · /work/…」或 `/cwd` 命令进入：
  弹层只列目录（可逐级进入、返回上一级），底部「工作区根目录」直接重置，「选择此目录」
  确认当前层。设置键是工作区相对路径 `claudeWorkDir`，下一轮 turn 生效，无需新建对话
  或重启引擎；当前值同时显示在菜单项里。
- 终端是独立子页，基于**持久 PTY** 的交互式 shell，不是逐条命令执行器：会话常驻，`top`、
  `vim` 等全屏程序靠备用屏与光标定位正常工作；主屏保留最近 2000 行滚动回滚；ANSI 16 色、
  256 色与 truecolor 在暖纸/深色两套主题下都有可读映射。终端画布是 16dp 圆角的
  `surfaceContainerHigh` 内嵌窗格（而不是 Material 控制台），等宽 12.5sp、行高 1.35，
  按行虚拟化渲染；光标是反色块，输入框聚焦且会话运行时 530ms 闪烁，失焦/未连接时保持静止。
  底部是可横向滚动的玻璃按键行（Esc、Tab、Ctrl 粘滞、↑↓←→、^C、^D、^L、粘贴）与
  `surfaceContainerHigh` 的全圆输入胶囊；输入实时写入 PTY（编辑按字符 diff，提交发 `\r`，
  IME 组词期间不发送），本地不回显。用户上翻后新输出不强行拉回，回到底部才恢复跟随。
  离开页面或切走时必须 `terminal.stop`，不能让子进程留在后台；shell 退出后显示退出码与
  「重新连接」，连接失败显示原因与「重试」。

### 会话抽屉

- 结构照 `SessionDrawer.kt`：品牌行 + 收起、新对话胶囊、搜索、按日分组、底部固定引擎状态
  胶囊与工作区/设置圆钮。搜索同时匹配标题与 Kotlin 传来的 `searchText`，没有内容的会话不显示。
- 品牌行是 38dp 主色圆形徽章 + `AppTextStyle.headline` 的「Claudroid」，右侧收起键走
  `TonalButtonTheme(glass: true)` 的玻璃圆钮；「新对话」是整行 `primaryContainer` 胶囊
  （加号 + 文案），不再用白卡片。
- 搜索框是 `surfaceContainerHigh` 的整圆胶囊（高 50dp），内部复用 `SearchField`；下方
  「全部对话」行只显示实时条数，不放假 chevron/筛选菜单。
- 分组语义与 `SessionGrouping.kt` 完全一致：今天 / 昨天 / 过去 7 天 / 更早，`updatedAt` 倒序。
- 会话行只显示标题、时间与 `…`；不再显示 `session.preview` 的第二行。选中行铺
  `surfaceContainerHigh` 圆角胶囊并加粗标题（不再画左侧细条），未选中行透明。
- 底部引擎胶囊是两行结构：左侧圆形 cpu 徽章，中间 `engineLabel` + 状态行（运行中 /
  准备中 / 可更新 / 不可用 / 未就绪），行尾 `Glyphs.picker`；`latestVersion` 存在且不等于
  `installedVersion` 时状态行显示「可更新」并点亮主色圆点。点击仍进设置。
- 工作区与设置是 44dp 玻璃圆钮，与顶栏图标键同材质。
- 每行的 `…` 菜单只有「重命名」「删除对话」两项；删除是**红色且立即执行**，不弹二次确认。
- 左侧抽屉用 `left_side_sheet.dart`：宽 `屏宽 * 0.88` 夹在 300–560dp，300ms 从
  `Offset(-1, 0)` 滑入、200ms 滑出；barrier 点击、返回键与抽屉内按钮均可关闭。
  prism_ui 的 `showModalSideSheet` 写死右缘锚点，不能用于左上角菜单。v1 不做拖拽关闭。

## 6. 图标

`prism_ui` 的 `Glyphs` 只有通用图标（`arrowBack`、`chevronForward`、`close`、`check`、
`more`、`search`、`eye`、`copy`、`paste`、`sort`、`pin`、`dragHandle` 等）。业务图标
（`plus`、`send`、`stop`、`sparkle`、`trash`、`edit`、`refresh`、`fork`、`menu`、
`settings`、`attachment`、`search`、`globe`、`terminal`、`folder`、`checklist`、`agents`、
`plug`、`read`）放在
`flutter/lib/ui/glyphs.dart` 的 `AppGlyphs`，沿用 24 单位网格、`GlyphRole.line` /
`GlyphRole.detail` 的描边角色，与内置图标同视觉重量。新增图标按同样网格画，不要混入
Material Icons。

设置根页的分类图标继续用同一套线条拟物语言（24 单位网格、1.8 主体描边、detail 次级
描边）：`key`（服务与模型）、`cpu`（引擎，也用于抽屉底部状态胶囊）、`flask`（功能与实验）、
`paintbrush`（外观）、`sliders`（高级）、`clipboardPulse`（关于与诊断），工作区复用
`folder`。根页图标一律装进 42dp 的 `SettingsTone` 圆形徽章（clay / honey / sage，按分组），
徽章负责颜色识别，图标本身仍用 `ink` 色描边。其中 `key`、`cpu`、`paintbrush`、`sliders`
移植自 FlClash 的 `AppGlyphs`，来源与 GPLv3 声明见
[`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md)；`flask` 与 `clipboardPulse` 按同一
网格新画。

## 7. 许可与发布

- `prism_ui` 是 **GPL v3**（chen08209），版本 0.1.2。已在
  [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md) 登记。
- 发布 APK 只包含 Flutter 前端，所以 `prism_ui` 也随发布物分发。两边都是 GPLv3，
  本项目自身的 GPLv3 条款已覆盖这一义务。
