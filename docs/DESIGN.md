# Claudroid 设计系统

> 本文件是 UI 迭代的唯一视觉依据。改配色、圆角、图标、动效、工具调用或代码块展示前先读这里；
> 与 `prototype/index.html`、`docs/screenshots/` 一起看。实现落在 `ui/theme/`（token）、
> `ui/components/`（通用控件与动效）、`ui/chat/`、`ui/md/`、`ui/settings/`。

## 1. 设计语言

暖纸色 + 陶土橙。参考 Claude 官方 App 的安静、克制：平面纸张、1dp 发丝描边代替阴影、
衬线体承载助手的声音、无纯黑与蓝灰。橙色是**焦点色，不是主题色**——大面积铺橙会毁掉纸张质感。

字体（`Theme.kt` 的 `AppType` / `AppTypography`）：

| 用途 | 字体 | 关键尺寸 |
| --- | --- | --- |
| 助手正文、标题、问候 | Serif（系统 CJK 回退 Noto Serif） | 正文 16/25，问候 32/38 |
| 设置、抽屉、按钮、标签 | Sans | body 14/21，title 15/20，label 14/18 |
| 代码、日志、命令 | Monospace | code 14/21，log 11.5/18 |

`includeFontPadding = false`：默认字体 padding 会让衬线标题在线框里偏低。

间距（`Spacing`）：页面 20dp，控件间隙 12dp，分区 20dp。卡片内边距 16–18dp，
行高不小于 56–60dp，保证触摸目标。

## 2. 颜色 token 与橙色使用规则

`AppColors`（`ui/theme/Theme.kt`），同时映射到 M3 `ColorScheme` 让原生组件（菜单、对话框、
开关）继承同一套色。

| token | 浅色 | 用途 |
| --- | --- | --- |
| `canvas` | `#F8F4ED` | 页面背景，纯平不渐变 |
| `paper` | `#FBF9F4` | 卡片、输入框、抽屉 |
| `surface1` | `#F0EAE0` | 用户气泡、次级填充、搜索框 |
| `surface2` | `#E8E0D2` | 按下态、禁用按钮 |
| `sand` | `#DDD2BD` | 1dp 发丝描边（可 0.6–0.7 透明度） |
| `ink` | `#2D2520` | 主文本、选中态填充 |
| `graphite` | `#5A4F44` | 次要文本 |
| `stone` | `#74695E` | 三级文本、时间戳、说明 |
| `bone` | `#B5AB9E` | 仅禁用态文本，绝不用于内容 |
| `orange` | `#D97757` | 焦点/关键动作 |
| `accentInk` | `#A64C2F` | 文本安全的陶土色（链接、小标签） |
| `orangeSoft` | `#F2DDD0` | 极淡的思考/提示底（尽量少用） |
| `codeBg / codeFg` | `#1F1B16 / #E8E0D2` | 代码块（深色，跟随主题） |
| `error / success` | 红 / 绿 | 状态 |

深色主题在 `DarkAppColors` 里做对应映射，保持同样的层级关系。

**橙色使用规则（重要）：**

- ✅ 允许：主要动作按钮（`PrimaryButton`）、发送/停止、输入框聚焦边框与光标、流式 caret、
  运行中的脉冲点/进度、少量品牌星标、`ClaudeMark`、选中态的细强调线。
- ❌ 禁止：普通卡片的填充、选中 chip 的实心填充、开关轨道、思考胶囊的整块底、列表行的背景、
  大面积 `orangeSoft` 铺底。
- 选中态与次要状态用**墨色 / 沙色 / 纸色**：`PillChip` 选中是 `ink` 填充 + `paper` 文字；
  `WarmSwitch` 轨道用 `ink`；思考胶囊用 `surface1`。
- 目标：一屏里橙色填充面积 < 5%，只出现在用户要点的那个动作上。

## 3. 圆角层级（`Radii`）

`ui/theme/Theme.kt` 定义唯一尺度，禁止散落 `RoundedCornerShape(硬编码)`：

| token | 值 | 用途 |
| --- | --- | --- |
| `xs` | 12dp | 小标签、复制按钮、小 chip、图标块 |
| `sm` | 16dp | 输入框、普通行、工具卡、代码块 |
| `md` | 20dp | 普通按钮、主按钮、折叠披露、代码块头部 |
| `lg` | 24dp | 分区卡、用户气泡、设置分类卡、底部弹层 |
| `xl` | 28dp | 对话框 |
| `xxl` | 32dp | 大弹层 / M3 extraLarge |

`Shapes` 把 extraSmall…extraLarge 一一对应上去。卡片比按钮圆一点、按钮比小 chip 圆一点，
形成"越大的容器越圆"的稳定节奏；但都不做全屏 pill。

## 4. 图标规范（`AppIcons.kt`）

- 24dp 网格，`viewport 24×24`。
- 线宽统一 **1.7dp**，`StrokeCap.Round` + `StrokeJoin.Round`，`fill = null`（纯线稿）。
- 少量必要的实心点用 `solid {}`，圆半径 0.9–2.3（如 `More`、信息点）。
- 轮廓是克制的拟物线稿（终端框、折角纸、地球经纬线、插头、扳手），不是 Material 的填充图标，
  也不是几何抽象符号。所有图标在深浅主题都用 `Icon` 的 `tint` 上色，令牌见第 2 节。
- `ClaudeMark`（`ui/components/Brand.kt`）是例外：八条带弧肩的实心星芒，品牌专用，跟随主题 accent。
- 工具图标映射在 `ui/chat/ToolPresentation.kt`（纯函数，可单测）：

| 工具名 | 中文动词 | 图标 |
| --- | --- | --- |
| Bash / shell / command | 执行 | `Terminal` |
| Read / view / file | 读取 | `ReadFile` |
| Write / Edit / create | 写入 | `WriteFile` |
| WebFetch / browser | 查看网页 | `Globe` |
| WebSearch / Grep / Glob | 搜索 | `Search` |
| Task / Agent / team / workflow | 调用代理 | `Agents` |
| Todo / plan | 整理清单 | `Checklist` |
| MCP / plugin / connector | 连接服务 | `Plug` |
| 通用/配置 | 处理 | `Wrench` |
| 生成/思考 | 生成 | `Sparkle` |
| 未知 | 调用工具 | `Tool` |

## 5. 动效与触摸物理（`Motion.kt` / `PressFeedback.kt`）

时长：`FAST=120`、`MEDIUM=180`、`SLOW=240` ms。曲线：`EaseChrome`（标准减速）、`EaseOut`、
`EaseInOut`。页面进入/退出用横向 1/20~1/40 位移 + 淡入淡出；列表项用 `animateItem`；
折叠展开用 `expandVertically`/`shrinkVertically` + `fadeIn/Out`；状态切换用 `animateColorAsState`。

**触摸物理**：可复用 `Modifier.pressFeedback(shape = ...)`，加在 `clickable` **之前**，本身只观察不消费事件。

- 按下：`scale 0.96` + `translationY 1dp`；
- 松手：`spring(dampingRatio = 0.72f, stiffness = 340f)`（`Motion.Release`）回弹，软 Q 弹但不过分晃；
- 滚动防误触：位移超过 `LocalViewConfiguration.touchSlop` 立即取消按压态，把手势让给父滚动容器；
- 尊重无障碍：系统"移除动画"（`ANIMATOR_DURATION_SCALE == 0`）时 `LocalReduceMotion` 为 true，
  所有按压/页面/折叠动画降级为瞬时；`ClaudroidTheme` 注入该 CompositionLocal。

原则：安静、不浮夸。纸张不该像橡胶，也不该有涟漪扩散；只给"按下去"一点物理反馈。

## 6. 设置页 IA（`ui/settings/SettingsScreen.kt`）

两级结构，**不平铺全部控件**：

- 根页：一句当前配置摘要 + 8 个带图标/摘要的分类行
  （接口与模型、模型档位、引擎与权限、功能与实验、外观、工作区、高级、关于）。
- 二级页：返回箭头 + 标题，`AnimatedContent` 横向进入/返回；`BackHandler` 先退二级页，
  根页的返回交给 `MainActivity` 的路由栈（顺序：二级 → 根 → 离开设置）。
- 所有异步状态（模型拉取、自检、日志、发布检查、sheet/对话框）留在顶层 `SettingsScreen`，
  切分类不会取消进行中的任务。
- 保留全部既有设置项与行为。「功能与实验」二级页新增开关与说明：
  agent teams（诚实标注 headless `-p` 下队友无法真正启动，属实验）、workflows、auto memory、
  background tasks、extended thinking；`EffortLevel`（AUTO 不传 `--effort`）；
  `subagentModel`；`autoModeModel`（注明仅供官方 auto 权限分类器）。
  权限模式含「自动（官方 Auto）」并说明网关兼容性；模型档位区分
  「默认 / 路由组」（把原始模型 ID 原样传给 `--model`，可填 Magpie 的 `auto/dfmodel[1m]`）
  与「CLI 默认（不指定模型）」（完全不传 `--model`）。

## 7. 工具调用折叠规则（`ui/chat/ChatScreen.kt`）

按 `ChatController` 的 `Block.Tool` 模型，分两阶段：

1. **正式输出前**（助手回合里还没有非空 `Block.Text`）：只显示紧凑「活动行」——
   `中文动词 + 工具图标`，如「执行」「读取」「搜索」「查看网页」「调用代理」。
   不显示命令、文件路径、输入/输出 JSON、结果细节；运行中用小脉冲点，失败/完成用小标记。
   `ChatController` 的 `_status` 同样只写中文动词。
2. **正式输出开始后**：活动行收起，正文不再出现工具卡；在助手回合末尾提供低调可折叠
   「工具调用 N」披露入口，**默认折叠**，展开才显示原始 `ToolCard` 细节（入参/输出/来源）。
   标签由 `toolCallLabel(count, errorCount)` 生成，有错误时显示「工具调用 N · M 个错误」，
   但错误详情只在展开后可见，不在正文铺大段错误卡。

折叠状态用 `rememberSaveable(messageId)` 稳定保存，流式重组不会重置用户选择。

## 8. 代码块折叠规则（`ui/md/Markdown.kt`）

`CodeFold`（纯函数，可单测）：`>8 行` **或** `>480 字符` 判为长代码。

- 长代码默认折叠到约 6 行（`6 × 21sp + 24dp`），底部渐隐遮罩提示被截断；
- 头部显示语言 + 「N 行 · M 字」，并有「展开 / 收起」+ 旋转箭头；**整个头部行可点**切换折叠
  （复制按钮单独拦截自己的点击），点击折叠态的正文区也直接展开；
- 展开/收起用 `animateContentSize` + `spring(dampingRatio = 0.72, stiffness = 340)`，动画到
  **完整文本高度**，没有固定 4000dp 上限，超长代码也不会被裁切；
- **复制按钮始终可用**，与折叠状态无关；
- 短代码完整显示，不折叠；
- 折叠状态用**无内容 key** 的 `rememberSaveable` 按块槽位保存：流式追加不会重置用户选择，
  内容派生的 key（如首 48 字或长度分桶）会在每个 chunk 变化时把展开态打回折叠，禁止使用。

## 9. 会话标题（`ui/chat/ChatController.kt` + `engine/TitleGenerator.kt`）

- 首轮回答完成后，若标题仍是临时标题（默认「新对话」或首条消息截断的 28 字），
  在后台调 `TitleGenerator.generate(...)`；成功更新 `_title` 并 persist，失败静默保留临时标题。
- 不阻塞 UI、不影响流式；用 `scope.launch` 独立协程。
- 并发保护：`titleGeneration` 计数器 + `titleIsTemporary` + 提交前比对 `expectedTitle`，
  用户已改名 / 已切换会话 / 已清空时绝不覆盖。
- 聊天页菜单「重新生成标题」：运行中禁用，有 Toast 进度/失败反馈；`markTitleUserEdited()` 供未来改名接入。

## 10. 流式滚动（不可回退的约束）

`ChatScreen` 的 `followBottom` 是闩锁：用户拖动即关闭，滚到真正底部才重新打开。
内容增长本身**不得**重新启用跟随，否则回复中上翻会被每个 token 拉回底部。
`scrollToBottom` 处理"最后一项比视口还高"的尾部溢出。

## 11. 毛玻璃与有机形变（Haze / graphics-shapes）

两个"重"库只用在两个明确的地方，不做无意义的炫技：

- **聊天顶栏毛玻璃**（`ui/components/Frosted.kt`）：滚动内容挂 `Modifier.hazeSource(state)`，
  顶栏覆盖层挂 `Modifier.frosted(state, canvas)`。`HazeStyle` 用
  `blurRadius = 26dp`、canvas tint `alpha = 0.86`、`noiseFactor = 0.05`；Android 11 及以下
  自动退化为近乎不透明的 `fallbackTint`，保证文字对比度。标题行不再占布局高度，消息从下方
  滚入并穿过毛玻璃，`LazyColumn` 的 `contentPadding.top = 状态栏 + 56dp + 10dp`。
- **品牌呼吸光环**（`ui/components/OrganicHalo.kt`）：`graphics-shapes` 的
  `Morph(circle → 圆角五角星)` 用 `asCubics(progress)` 生成真实曲线，慢速呼吸 + 42s 自转，
  放在 `ClaudeMark` 后面的低透明度陶土色圆斑里。只在首页问候与空对话页出现，替掉静态装饰；
  `LocalReduceMotion` 为 true 时停在固定形态。
- 依赖版本锁定 `dev.chrisbanes.haze:haze:1.0.1` 与 `androidx.graphics:graphics-shapes:1.1.0`：
  两者都兼容 Kotlin 2.0.21 / Compose 1.7.0；不要顺手升级（新版本分别要求 Kotlin 2.1+
  与 Compose 1.12+）。
