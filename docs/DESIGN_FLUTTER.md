# Flutter 前端视觉规范

Compose 前端的设计系统在 [`DESIGN.md`](DESIGN.md)。本文只覆盖 **Flutter 前端**，它是发布
APK 实际使用的前端（`release.yml` 传 `-PflutterUi=true`），刻意**不**沿用 `DESIGN.md` 的
暖纸色 + 赤陶色，而是照搬
[FlClash](https://github.com/chen08209/FlClash) 的视觉语言 —— 这是产品需求，不是疏漏：

> 材质、图标、动画效果照抄 flclash，设置页面的排版也要借鉴一下，聊天页面保持当前布局。

## 1. 依赖与组件映射

| Compose（`ui/`） | Flutter（`flutter/lib/ui/`） |
| --- | --- |
| `Theme.kt`（暖纸 + 赤陶） | `app.dart` 的 `seededColorScheme(seedColor: kSeedColor)` |
| `AppDrawer` | `session_drawer.dart` 的 `SessionDrawer` |
| `ChatScreen` / 气泡 / 工具卡 | `chat_page.dart`（**布局保持不变**） |
| `SettingsScreen` | `settings_page.dart`（按 FlClash 分组列表排版重做） |
| `AppIcons` | `glyphs.dart` 的 `AppGlyphs` + prism_ui 的 `Glyphs` |
| Material 组件 | prism_ui 同名替代（见下表） |

| 需要什么 | 用 prism_ui 的 |
| --- | --- |
| 页面骨架、顶栏 | `PrismScaffold`（需要 `ViewScope`，由 `PrismApp.builder` 提供）；`bar` 用 `scaffold_bar.dart` 的 `ClaudroidBar`，**不要**直接用 `ScaffoldBar` |
| 容器 | `Surface`（`SurfaceLevel.page` / `.card` 取色） |
| 设置项列表 | `generateSection` / `generateGroup` / `ListRow` / `DecorationListItem` |
| 分组行间距 | `ItemPosition.get(index, length)` 或 `ItemPositionProvider` |
| 搜索框 | `SearchField` |
| 弹出菜单 | `PopupAnchor` + `PopupMenu` + `PopupMenuItem` |
| 对话框 | `AlertDialog` + `showFloatingDialog` |
| 输入框 | prism_ui 的 `TextField`（镜像 Flutter 构造器） |
| 可点击卡片 | `Card`（`onPressed`）/ `PressSurface` |
| 按钮 | `TextButton` / `TonalButton` |
| 长列表 | `PrismListView` |
| 侧边抽屉 | `showModalSideSheet` + `SideSheet` |
| 图标 | `GlyphIcon(Glyphs.x)`，**必须** `import 'package:prism_ui/icons/icons.dart';` |
| Markdown（`ui/md/Markdown.kt`） | `markdown.dart` 的 `MarkdownText`（Dart 移植，无外部依赖） |

**不要 import `package:flutter/material.dart`**：`prism_ui` 自带 `AppBar`、`ThemeMode`、
`TextField`、`Card` 等同名符号，同时引入会产生 `ambiguous_import`。
`ValueListenable` / `ChangeNotifier` 从 `package:flutter/foundation.dart` 取。

## 2. 色板

固定 seed：

```dart
const kSeedColor = Color(0xFFD97757); // 与 ui/theme/Theme.kt 的 orange 同值
```

`PrismApp` 里 light / dark 各建一份 `seededColorScheme`，跟随 `settings.themeMode`
（0 跟随系统 / 1 亮 / 2 暗）。**取色只用语义角色**（`context.colorScheme.primary`、
`.onSurface`、`.onSurfaceVariant`、`.surface`），不要在组件里写十六进制字面量 ——
prism_ui 的主题是按 seed 生成的，写死颜色会在暗色模式下失去对比度。

> 注：种子沿用 Compose 的赤陶 `0xFFD97757`，这样两套前端在系统栏、通知、启动图上
> 不打架；prism_ui 由它派生出的面板、描边、玻璃底纹则是 FlClash 的观感。

## 3. 材质与动画

- `PrismApp(liquidGlass: LiquidGlassQuality.standard)`：液态玻璃统一在这个层级开，
  组件侧不单独配。高端机可以按需上调，低端机下调 —— 这是唯一允许按设备分档的视觉参数。
- 弹层（菜单、对话框、侧边抽屉）用 prism_ui 自带的转场与玻璃模糊，不要手写
  `AnimatedOpacity` / `BackdropFilter`。
- 动效时长统一走 `context.motionDuration`，无障碍「减少动效」打开时它自动归零；
  `context.disableAnimations` 可作显式判断。**不要**写死 `Duration(milliseconds: …)`。
- 图标一律矢量 glyph，不用 emoji、不用位图，不随字号放大而变糊。

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

## 5. 各页面约束

### 聊天页 —— 保持当前布局

用户明确要求聊天页**不跟着改**。因此：

- 消息气泡、助手正文、工具调用卡、思考折叠、流式光标、错误条、回到底部按钮，
  位置与信息层级照 `ChatScreen.kt` 的现有结构，只把 Compose 组件换成 prism_ui 等价物。
- 助手气泡仍用**衬线**，与用户气泡的区分保留。
- 助手正文由 `MarkdownText` 渲染（标题/列表/引用/表格/代码块/行内样式），用户气泡仍是纯文本；
  链接只做样式（accent + 下划线），Flutter 侧没有打开 URL 的桥接方法。
- 橙色（`colorScheme.primary`）只用于焦点与当前会话标记，占屏不超过 5%。

### 设置页 —— 借鉴 FlClash 排版

FlClash 的设置页是「分组标题 + 一组行 + 行尾值/箭头」的列表，不是 Material 的卡片堆叠。
落点：

- 每个分组一个 `generateSection(title: …)`，组内用 `ListRow` 或 `DecorationListItem`。
- 选择类设置用 `PopupAnchor` 弹出 `PopupMenu`，**行尾只显示当前值**，不写解释性副标题
  （用户要求「去除所有说明文字来降低行长」）。
- 选择框右边缘对齐按钮右边缘，宽度受控（不要撑满整行）。
- 开关用 `_SwitchRow`，数值用 `_ValueRow`，纯展示用 `_InfoRow`。

### 会话抽屉

- 结构照 `SessionDrawer.kt`：品牌行 + 收起、新对话胶囊、搜索、按日分组、底部引擎状态。
- 分组语义与 `SessionGrouping.kt` 完全一致：今天 / 昨天 / 过去 7 天 / 更早，`updatedAt` 倒序。
- 每行的 `…` 菜单只有「重命名」「删除对话」两项；删除是**红色且立即执行**，不弹二次确认。
- 已知差异：`showModalSideSheet` 的宽度是 `(屏宽 * 0.4).clamp(360, 560)`，而 Compose 抽屉
  是屏宽的 88%。在 720px 宽的机型上 Flutter 抽屉约 360px，比 Compose 版窄。若需要
  对齐 88%，要在 prism_ui 外面包一层自定义 sheet，而不是改包内常量。

## 6. 图标

`prism_ui` 的 `Glyphs` 只有通用图标（`arrowBack`、`chevronForward`、`close`、`check`、
`more`、`search`、`eye`、`copy`、`paste`、`sort`、`pin`、`dragHandle` 等）。业务图标
（`plus`、`send`、`stop`、`sparkle`、`trash`、`edit`、`refresh`、`menu`）放在
`flutter/lib/ui/glyphs.dart` 的 `AppGlyphs`，沿用 24 单位网格、`GlyphRole.line` /
`GlyphRole.detail` 的描边角色，与内置图标同视觉重量。新增图标按同样网格画，不要混入
Material Icons。

## 7. 许可与发布

- `prism_ui` 是 **GPL v3**（chen08209），版本 0.1.2。已在
  [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md) 登记。
- 发布 APK 用 Flutter 前端（`release.yml` 以 `-PflutterUi=true` 构建），所以 `prism_ui`
  也随发布物分发。两边都是 GPLv3，本项目自身的 GPLv3 条款已覆盖这一义务。
