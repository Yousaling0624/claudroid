# Claudroid 设计系统

> 本文件是 UI 迭代的唯一视觉依据。改配色、圆角、图标、动效、工具调用或代码块展示前先读这里；
> 与 `prototype/index.html`、`docs/screenshots/` 一起看。实现落在 `ui/theme/`（token）、
> `ui/components/`（通用控件与动效）、`ui/chat/`、`ui/md/`、`ui/settings/`。

## 1. 设计语言

暖纸色 + 陶土橙。参考 Claude 官方 App 的安静、克制：平面纸张、1dp 发丝描边代替阴影、
衬线体承载助手的声音、无纯黑与蓝灰。橙色是**焦点色，不是主题色**——大面积铺橙会毁掉纸张质感。

内容卡片保持「平面纸张 + 发丝描边」；只有悬浮 chrome（顶栏四胶囊、底部输入胶囊）使用
半透明玻璃、柔和阴影和亮边，形成前后层次。

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
| `sm` | 16dp | 普通行、工具卡、代码块 |
| `md` | 20dp | 普通按钮、主按钮、折叠披露、代码块头部 |
| `menu` | 22dp | 顶栏模型 / 标题下拉浮层（`FloatingMenu`） |
| `lg` | 24dp | 分区卡、用户气泡、设置分类卡、底部弹层、侧边栏会话行 / 引擎胶囊 / 提示行 |
| `xl` | 28dp | 对话框、抽屉右缘 |
| `xxl` | 32dp | 大弹层 / M3 extraLarge |
| 胶囊 | `CircleShape` | 顶栏四个气泡、底部输入框、侧边栏新对话 / 搜索、建议 chip |

`Shapes` 把 extraSmall…extraLarge 一一对应上去。卡片比按钮圆一点、按钮比小 chip 圆一点，
形成"越大的容器越圆"的稳定节奏；需要胶囊时直接用 `CircleShape`，不新增硬编码半径。
侧边栏的会话行、引擎胶囊、搜索框、新对话按钮都使用 `lg` 或胶囊，不再使用 `sm` 小圆角。
工作区的文件块、缩略图、小按钮也一律取 `Radii` 档位（10/8/14dp 这类散落值已并入 `xs`/`sm`）。

### 3.1 按钮、胶囊与输入框

- **悬浮 chrome**：46dp 正圆图标键、46–54dp 胶囊；图标 18–20dp。玻璃只用于操作入口
  （顶栏、输入框附件/发送、抽屉 X/新对话/工作区/设置、设置返回键、斜杠卡片），标题不裹气泡。
  抽屉底部固定为一条行：引擎状态胶囊（weight 1）+ 工作区圆钮 + 设置圆钮，三个控件均 46dp，间距 8dp。
- **主/次操作**：50dp 高、`CircleShape`。主按钮陶土渐变 + 顶部白色高光 + 橙色 glow；
  次按钮纸面 + 白→沙 rim + 2dp 阴影。disabled 用 `surface2`/`bone`，destructive 用 error 渐变。
- **设置 / 抽屉大胶囊入口**：52–54dp 高；左侧图标放进 36–42dp 圆容器；标题 15.5sp SemiBold；
  入口之间 8–10dp 间距，不要退回普通列表行。
- **chip / 列表行**：chip 触控高度至少 44dp（视觉内边距可更紧），列表行至少 56dp；
  选中态用 ink/sand/paper，不用橙色实心铺底。
- **输入胶囊**用 `Radii.mdShape`（20dp），不用 `CircleShape`：`CircleShape` 的半径恒等于高度的一半，
  多行增高后左右两个 40dp 按钮（距边缘 6dp）会落进底部圆角的内切区域被裁掉。20dp 是单行高度下
  仍能完整包住按钮的最大 `Radii` 档位；单行看起来仍接近胶囊，多行只保留正常圆角。

## 4. 图标规范（`AppIcons.kt`）

- 24dp 网格，`viewport 24×24`。
- 线宽统一 **2dp**（个别强调图标 2.2dp），`StrokeCap.Round` + `StrokeJoin.Round`，圆角半径约 **2.4dp**。
- 参考 Material Symbols Rounded / M3 Expressive 的 2026 方向：先画清楚的轮廓，再决定要不要加内部细节；
  不再用三条横线加圆点、折角纸、经纬线、扳手齿这类偏拟物的细碎线条。
- 具体轮廓：
  - `Settings`：圆润齿轮——一个外圈、8 个短齿、中心圆孔；不再复用滑块。
  - `Sliders`：三条竖向推子 + 圆钮，与齿轮明确区分（设置根页用齿轮，「高级」用推子）。
  - `File` / `TextFile` / `ReadFile` / `WriteFile`：圆角文档矩形 + 2–3 条短线，去掉折角；
    `ReadFile` 内部用眼睛，`WriteFile` 在右下角叠一支铅笔，靠语义而不是细节区分。
  - `Folder`：柔和圆角轮廓 + 左上小页签，没有尖锐折角或外翻的纸边。
  - `Image` / `Code` / `Terminal`：只保留 1–2 个内部特征（太阳点 + 单条山线、`< >` + 斜杠、
    提示符 + 光标线），在 17–20dp 下仍然清楚。
- 纯线稿为主；`Stop`、`Sparkle`、`More`、状态点等用实心，作为语义强调，实心面积保持很小。
- 所有图标在深浅主题都用 `Icon` 的 `tint` 上色，令牌见第 2 节。
- `ClaudeMark`（`ui/components/Brand.kt`）是例外：八条带弧肩的实心星芒，品牌专用，跟随主题 accent。
- 消息操作新增 `Edit`（编辑）与 `Regenerate`（复用 `Refresh` 的语义别名）。
- 设置分类图标：ROOT 用 `Settings` 齿轮，API「接口与模型」用 `Plug`，ADVANCED「高级」用 `Sliders`，
  不再让 ROOT / ADVANCED 共用同一个图标。
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

**触摸物理层级**（`PressFeedback.kt`）：可复用 `Modifier.pressFeedback(shape = ...)`，加在
`clickable` **之前**，本身只观察不消费事件。一共三层，不要每个按钮都加戏：

1. **悬浮 chrome（`dragMorph = true`）**：顶栏胶囊、输入框附件/发送、抽屉 X / 新对话 /
   工作区 / 设置、设置返回胶囊。
   - 按下 90ms 内到 `0.96`；拖动后沿主轴拉伸、垂直轴压缩：
     `stretch ≤ 0.15`，`squash = stretch × 0.65`，以拖动反方向的边为锚点（anchor stretch）；
   - 中心向手指阻尼位移 `0.12 × 拖动距离`，不 1:1 跟手；
   - 松手用 `spring(dampingRatio = 0.55f, stiffness = 380f)`，并把 `VelocityTracker` 的
     速度换算成形变速度作为 `initialVelocity`，避免固定时长曲线的顿挫；
   - 按下时叠一层 `alpha ≤ 0.07` 的白色高光，只裁剪高光本身（阴影和拉伸仍可越出原边界），
     拖动越远高光越淡；抽屉 / 首页没有 Haze 源时退化为半透明 paper，但物理参数不变。
2. **主 / 次操作按钮**：`pressFeedback(pressedScale = 0.97f)`，补按下态颜色/亮度/rim：
   按下时叠 4–5% 墨色 wash（深色主题用白色 wash），Primary 的白色 rim 变亮，Ghost 的填充过渡到
   `surface1`、rim 过渡到 ink 30%；主按钮保留长按触感。只用短 tween，不做形变。
3. **列表行 / chip / 开关 / 代码复制**：默认 `pressedScale = 0.98f`、`translationY = 0.5dp`，
   `Motion.PressQuiet` 短 tween + 同一条 4–5% wash。无弹簧、无形变；滚动超过 touch slop 立即取消。

**滚动防误触**：位移超过 `LocalViewConfiguration.touchSlop` 立即取消按压态，把手势让给父滚动容器。
消息操作、建议 chip、模型列表行、工具 / 思考折叠、会话行都不启用 `dragMorph`。

**无障碍**：系统"移除动画"（`ANIMATOR_DURATION_SCALE == 0`）时 `LocalReduceMotion` 为 true，
按压、颜色、页面、折叠动画全部降级为瞬时；`ClaudroidTheme` 注入该 CompositionLocal。
不添加 ripple，纸张不该有涟漪扩散；配合 `Modifier.quietClickable`（`indication = null`）
使用，不要直接叠默认带 ripple 的 `clickable`。

原则：安静、不浮夸。纸张不该像橡胶，也不该有涟漪扩张；只给"按下去"一点物理反馈。

## 6. 设置页 IA（`ui/settings/SettingsScreen.kt`）

三级结构，**不平铺全部控件**：

- **根页**：H1「设置」+ 一句说明；当前配置 hero 卡（模型 headline、协议/权限/主题/引擎摘要、
  右侧陶土星标）；分类是 8 个间距 10dp 的大胶囊行（图标块 + 标题 + 摘要 + chevron），
  不再塞进一张大卡。
- **详情页**：H1 26sp SemiBold + 一句 intro；H2 17.5sp 在卡片外，卡片内只放控件；
  按钮是 50dp 大胶囊。所有二级页共享同一个 `AnimatedContent` 横向进入/返回。
- **三级页**：权限模式、引擎日志、环境变量。同样 H1/H2，顶部返回胶囊；`rememberSaveable`
  保存子页，`BackHandler` 顺序为：模态 → 三级页 → 详情页 → 根页 → 离开设置。
- **顶栏**：只有悬浮的 46dp 圆形玻璃返回胶囊。**页面标题是滚动内容里的 H1 文字，不做标题气泡、
  不加玻璃胶囊**；玻璃胶囊只留给返回键、模型卡关闭键、大按钮与 chip 等操作控件。
- **玻璃模型选择面板**（`ModelListSheet`）：同窗口浮层，背景 `GlassScrim` 真实模糊 + 低透明压暗；
  面板高约屏高 88%，顶部拖拽 handle、H1 标题/说明、模型计数、46dp 玻璃关闭键；搜索框 48dp；
  每行 58dp 大胶囊（左圆形 provider 标记、14.5sp Medium 模型 ID、右侧圆形勾选），行距 7dp；
  选中行 `surface1` + ink 细边，未选中 paper + sand 发丝边；列表占满剩余高度并滚动；
  底部固定手动输入 + 全宽 50dp 主按钮；`imePadding` 保证键盘弹出时按钮不被遮挡。
  点击整行选择并关闭，遮罩 / 系统返回也会关闭；空列表与无匹配都有下一步提示。
- 所有异步状态（模型拉取、自检、日志、发布检查、sheet/对话框）留在顶层 `SettingsScreen`，
  切分类或进三级页都不会取消进行中的任务。
- 保留全部既有设置项与行为。「功能与实验」二级页包含 agent teams（诚实标注 headless `-p`
  下队友无法真正启动，属实验）、workflows、auto memory、background tasks、extended thinking；
  `EffortLevel`（AUTO 不传 `--effort`）；`subagentModel`；`autoModeModel`（仅供官方 auto
  权限分类器）。权限模式含「自动（官方 Auto）」并说明网关兼容性；模型档位区分
  「默认 / 路由组」（把原始模型 ID 原样传给 `--model`，可填 Magpie 的 `auto/dfmodel[1m]`）
  与「CLI 默认（不指定模型）」（完全不传 `--model`）。

## 7. 思考与工具折叠规则（`ui/chat/ChatScreen.kt`）

**输出前实时，输出后合并。** 助手回合还没有非空 `Block.Text` 且仍在流式时，思考显示为
一个「思考中…」胶囊（可点开看实时推理），工具调用显示为紧凑「活动行」——`中文动词 + 工具图标`，
如「执行」「读取」「搜索」「查看网页」「调用代理」。活动行不显示命令、文件路径、输入/输出 JSON、
结果细节；运行中用小脉冲点，失败/完成用小标记。`ChatController` 的 `_status` 同样只写中文动词。

**间距节奏**：标题行下方 10dp spacer + 思考胶囊顶部 2dp = 12dp；`WorkDisclosure` 顶部 2dp、
底部 12dp，折叠与展开都不改变正文上下的 12dp 呼吸。

**正式输出开始或回合结束后**（已有正文，或流式已结束），思考胶囊与活动行一起收起，在**正式
输出正文上方**换成一个统一的 `WorkDisclosure`：

- 标签由 `workLabel(thinkingCount, toolCount, errorCount)` 生成，形如
  「思考过程 2 段 · 工具调用 3」，有错误时追加「· 1 个错误」；只有思考或只有工具时只显示
  对应的一半，两者都没有时不渲染。标题图标用 `Sparkle`，有错误时转 error 色。
- **外层默认折叠**，点标题行展开成一个小列表；展开后严格按 `message.blocks` 的原始顺序回放。
- **嵌套折叠**：外层展开后，每段 `Block.Thinking` 和每个 `Block.Tool` 仍是各自默认收起的小折叠：
  - 思考段是一行 `思考过程 i/n · N 字` + `RotatingChevron`，没有额外边框或底色；点整行才展开
    正文，正文保留左侧 2dp 沙色竖线与 `SelectionContainer`；
  - 工具调用复用 `ToolCard(embedded = true)`（本身默认收起，保留入参/输出/来源卡片）；
  - 小折叠状态分别用 `rememberSaveable(messageId)` / `rememberSaveable(messageId, blockIndex)` /
    `rememberSaveable(block.id)` 保存，流式重组不会重置用户选择；
  - 项之间用沙色分隔线。全部收起时高度很紧凑，用户点哪一段才看哪一段。
- 错误详情只在展开后可见，不在正文铺大段错误卡。

### 7.1 消息操作与来源隐藏

- 用户消息在空闲时可「编辑」：文本回填输入胶囊，发送后从该消息处截断并重放更早的对话
  （`ChatController.resendFrom` + `ConversationPrompt`）。官方 CLI 只能 resume、不能 rewind，
  所以编辑会开新会话，但更早的本地历史会作为紧凑前言带给模型。
- 最后一条助手回答在空闲时可「重新回答」：定位到它前面的用户消息，同样截断并重放。
  `prepareTurn` 在截断消息之前完成 API/引擎预检，配置错误不会毁掉已有记录。
- 助手回答提供「复制」，复制内容同样剔除尾部来源列表。
- WebSearch 回答尾部的 `Sources: …` / `Links: …` / `来源：…` 列表由
  `WebSources.stripTrailingSources` 在渲染前剥离；代码块内的同名标题、以及只是提到
  "Sources:" 的正文不受影响。可点击的来源卡片仍保留在工具调用的折叠区里。

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

两个"重"库只用在明确的浮层与玻璃节点上，不做无意义的炫技。所有玻璃都只能在**同一个 window**
内采样；跨 window 的 `Dialog` / `ModalBottomSheet` 会失去真实模糊，改用同窗口 overlay。
Haze 固定 1.0.1，Android 11 及以下退化为 `fallbackTint` 近不透明表面，文字必须仍然可读。

- **共享玻璃材质**（`ui/components/Glass.kt`）：`Modifier.glass` 默认 `blurRadius = 30dp`；
  图标型胶囊 tint `0.58–0.66`，文字型胶囊 `0.68–0.74`（保证对比度），共享实现再抬 0.04、
  上限 0.84；`noiseFactor = 0.06`。阴影 12dp 柔光；亮边为白色→沙色的竖向渐变
  （浅色 0.92/0.80，深色 0.28/0.48）；内部再叠一层顶部高光→中部透明→底部微墨/微纸的
  sheen，让浅色模式下的胶囊和 canvas 拉开层次。无 Haze 源时退化为 paper 竖向渐变
  （浅色 0.94→0.86，深色 0.95→0.90），仍保留同样的阴影和 rim，不能变成一块死白。
- **悬浮顶栏玻璃**（`ui/components/FloatingTopBar.kt` + `Frosted.kt`）：聊天/首页顶栏由四个
  独立胶囊组成——圆形侧栏、标题长胶囊（点按打开菜单）、模型切换胶囊、圆形新会话。聊天页
  滚动内容挂 `Modifier.hazeSource(state)`，每个胶囊挂 `Modifier.frosted(...)`；
  `HazeStyle` 用 `blurRadius = 28–30dp`、canvas tint、`noiseFactor = 0.06`。首页没有滚动源，
  胶囊退化为半透明 `paper` + 柔和阴影。顶栏总高 **62dp（不含状态栏）**，
  `LazyColumn.contentPadding.top = 状态栏 + 62dp + 10dp`，消息从胶囊之间和下方穿过。
- **设置悬浮返回键**：46dp 正圆，直接复用同一个 `GlassIconButton`（阴影 / Haze / rim /
  dragMorph / 长按触感）。设置页顶栏只有这个操作胶囊，**标题是 H1 纸面文字，不做标题气泡**。
- **模糊模型面板**（`ModelListSheet`）：同窗口浮层。全屏 `GlassScrim` 用 26dp 模糊 +
  低透明 canvas tint 与压暗；面板本身保持 paper 以保证长模型 ID 的可读性，顶部圆角 28dp。
  面板高度约 88%，关闭键用共享玻璃轮廓的 fallback paper 形态（面板不透明，若用 Haze
  会在卡片上开一个透出背景的小窗）。
- **侧边栏玻璃入口**（`SessionDrawer.kt`）：抽屉是 paper Surface，没有自己的 Haze 源，
  因此 X 收起键、新对话、底部三个入口统一用半透明 paper fallback + 12dp 阴影 + rim。
  底部固定为一条行：引擎状态胶囊（weight 1）+ 工作区圆钮 + 设置圆钮，三个控件均 46dp，间距 8dp；
  会话行、搜索框仍是安静按压，不加 dragMorph。
- **输入胶囊**（`ui/components/Composer.kt`）：`Radii.mdShape`（20dp）长条包住附件、可增高
  输入框和发送/停止；paper 填充 + 1dp 沙色/橙色边框 + 8dp 柔和阴影。`maxLines = 7` 后内部
  滚动，不再继续撑高。编辑消息时上方出现一条 `orangeSoft` 胶囊提示，不把工具条塞回输入框内部。
- **斜杠功能卡片**（`ui/components/SlashPalette.kt`）：同窗口玻璃卡（共享 30dp 模糊、tint 0.74、
  12dp 阴影），悬在输入胶囊上方，向上展开 + 淡入；行高 54dp，图标圆容器 + 14.5sp SemiBold
  标题 + 12sp 说明 + 右侧 `/key` 等宽小字；最大高度 300dp（键盘弹出时取可用高度的 55%），
  内部滚动。空草稿输入 `/` 才触发，空格 / 清空 / 选中 / 返回即收起；已有草稿不触发。
  动作只做 App 级操作（新建、模型、附件、工作区、设置、停止、重试），不假装支持 CLI 斜杠命令。
- **顶栏下拉菜单**（`FloatingMenu` / `ModelDropdownMenu`）：模型与标题菜单都从胶囊向下展开，
  不再用 `ModalBottomSheet`。两者共用 paper 背景、`Radii.menu = 22dp`、0 tonal elevation、
  10dp 阴影和 6dp 垂直偏移；模型菜单宽 **248dp**，标题菜单宽 **236dp**。
  - 模型菜单右对齐到模型胶囊：用 `onSizeChanged` 量出胶囊实际宽度，
    `offsetX = 胶囊宽 - 248dp`（负值），360dp 屏幕也不会越界；
  - 标题菜单在标题胶囊内居中：`offsetX = (标题宽 - 236dp) / 2`，标题胶囊窄于 236dp 时
    offsetX 为负值也照样居中，由 M3 popup 的 position provider 夹在窗口内；
  - 两个胶囊的 chevron 都随菜单展开旋转；选中项用 `AppIcons.Check` 尾标。
- **拖动形变**（`PressFeedback.kt`）：浮动 chrome 使用 `pressFeedback(dragMorph = true)`：
  顶栏胶囊、输入框附件/发送、抽屉 X / 新对话 / 工作区 / 设置、设置返回键。
  参数与验收见第 5 节；斜杠卡片按行安静按压，列表行、消息操作、建议 chip、模型行都不启用，
  避免滚动时误触。
- **工作区终端**（`ui/workspace/WorkspaceTerminal.kt`）：工作区顶栏右侧的 `Terminal` 入口；
  每次执行一条命令（不是常驻 PTY），`cd` 通过状态文件延续，`clear` 是 UI 内置命令；
  输出用等宽 12.5/18，命令 accentInk、输出 graphite、错误 error；底部输入胶囊沿用
  `Radii.mdShape` + 共享玻璃；运行中显示停止键。输出在 UI 保留最近 300 行 / 20 万字符，
  引擎再以 1 MiB 上限截断并继续 drain，避免 `yes` 之类命令耗尽内存。
- **AI 输入建议**（`ui/components/AiSuggestions.kt`）：首页与空对话页的 chip 全部由
  `SuggestionGenerator` 调小模型生成，不保留固定文案；未配置 / 请求失败时整段隐藏，
  加载中显示 surface1 骨架（呼吸 alpha 0.35–0.65），不伪造建议。
- **品牌呼吸光环**（`ui/components/OrganicHalo.kt`）：`graphics-shapes` 的
  `Morph(circle → 圆角五角星)` 用 `asCubics(progress)` 生成真实曲线，慢速呼吸 + 42s 自转，
  放在 `ClaudeMark` 后面的低透明度陶土色圆斑里。只在首页问候与空对话页出现，替掉静态装饰；
  `LocalReduceMotion` 为 true 时停在固定形态。
- 依赖版本锁定 `dev.chrisbanes.haze:haze:1.0.1` 与 `androidx.graphics:graphics-shapes:1.1.0`：
  两者都兼容 Kotlin 2.0.21 / Compose 1.7.0；不要顺手升级（新版本分别要求 Kotlin 2.1+
  与 Compose 1.12+）。
