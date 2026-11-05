# Agent 工作流

本文是 `AGENTS.md` §2.1–2.2 的展开说明：怎么把一次需求拆给 subagent、按什么模型分层、
交接时给什么信息、什么情况升级回主会话。规范冲突时以 `AGENTS.md` 为准。

## 1. 目标

- **判断与成本分离**：审美、协议语义、架构取舍由强模型负责；机械实现与例行验证交给低成本模型。
- **一次需求一份 brief**：先冻结范围再开工，避免代理反复读上下文、边做边改需求。
- **主会话是 team lead**：负责拆解、路由、并行调度、汇总、验证与提交；不直接写实现。
- **子代理边界**：不 commit / push，不再 spawn 子代理，不跨模块顺手重构。

## 2. 角色与模型

| 代理 | 模型 | 职责 | 不负责 |
| --- | --- | --- | --- |
| `engine-dev` | Sonnet | PRoot、chroot、jail、CLI 进程、stream-json、安装升级 | OpenAI 协议、UI |
| `proxy-dev` | Sonnet | `OpenAiProxy` / `OpenAiMapping` / `ModelCatalog`、协议单测 | 引擎启动、UI |
| `compose-ui` | Sonnet | 设计负责人：token、信息层级、材质、动效、最终截图评审 | 全量构建与例行验证 |
| `ui-impl` | Sonnet | 按冻结 spec 做文件级实现、组件替换、样式统一 | 审美决策、完整构建 |
| `build-release` | Sonnet | Gradle/AGP、`tools/`、CI、签名、release notes | 业务代码 |
| `qa-build` | **Haiku** | 例行 `testDebugUnitTest assembleDebug`、`git status`/diff、AGENTS 硬性检查 | 视觉评审、架构判断 |
| `qa-reviewer` | Sonnet | 高风险 diff 深度评审（协议、持久化兼容、并发/生命周期） | 修改代码 |

路由规则见 `AGENTS.md` §2.1。跨领域任务拆成互不重叠的文件范围并行分派，由主会话合并。

## 3. 任务 brief 模板

主会话分派前先写 brief；代理开工后不再追加需求。需要追加时先更新 brief 再让代理继续。

```markdown
## 目标
（用户可见的结果，一两句话）

## 非目标
（明确不做什么，防止范围膨胀）

## 文件范围
- 可改：`app/src/main/java/app/claudroid/ui/...`
- 只读参考：`docs/DESIGN.md` §5、`Theme.kt`
- 不要动：`engine/`、Gradle、CI

## 设计/实现约束
（引用的 spec、token、现有组件；新逻辑的边界）

## 验收标准
- [ ] 可见效果 / 行为
- [ ] 纯逻辑有 `app/src/test/**` 单测
- [ ] `compileDebugKotlin` 通过（实现代理）；全量验证由 qa-build 跑

## 验证命令
（本批次只需要跑的命令；完整构建不要写进实现代理的 brief）
```

## 4. 交接节奏

1. **主会话**：拆解需求 → 写 brief → 选择代理与模型 → 并行分派互不重叠的部分。
2. **实现代理**：按 brief 改代码，每批次只跑 `compileDebugKotlin`；纯逻辑补单测；输出改动清单与偏差。
3. **主会话**：汇总各代理结果，冲突处自己裁决（必要时请 `compose-ui` 定夺视觉）。
4. **`qa-build`**：跑全量测试与 debug 构建、工作树与 AGENTS 硬性检查，只回传失败尾部。
5. **`compose-ui`**：仅在最终验收看 2–3 张关键截图（浅/深色），确认视觉与动效。
6. **主会话**：通过后按用户当次指示 commit / push；CI 自动发版。

失败回流：`qa-build` 失败 → 主会话把具体失败信息发回对应实现代理 → 修复后再跑 `qa-build`；
通过后才提交。不要带病提交，也不要让验证代理顺手改代码。

## 5. 成本规则

- 实现代理只在批次结束跑 `compileDebugKotlin`，不跑完整 `assembleDebug`。
- 完整构建、单测、`git diff --check` 只在 `qa-build` 跑一次；有 Kotlin 改动但命中缓存时用
  `--rerun-tasks --no-build-cache` 复核。
- 截图、安装、模拟器点按只在最终视觉验收做；不要每改一步就截图。
- 定位问题用 `Grep` / 定向 `Read`，避免整文件读取 1000 行以上的文件。
- 需求变化先更新 brief，避免代理因上下文错位返工。

## 6. 升级与例外

- 审美或交互取舍不明确：升级给 `compose-ui`，仍不明确再由主会话问用户。
- 协议语义、持久化兼容、并发/生命周期风险：升级给 `qa-reviewer` 深度评审。
- 需要新增运行时依赖、升级 Gradle/AGP/Kotlin、改 `targetSdk`：停下，由主会话征求用户同意。
- 代理发现 brief 缺验收标准、文件范围冲突或与现有 token 冲突：停止实现并报告，不自行发挥。
- 纯问答、纯文档或一行以内的紧急修复可由主会话直接处理，但要在回复里说明。
