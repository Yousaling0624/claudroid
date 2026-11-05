# Claudroid

完整开发规范在仓库根目录 [`AGENTS.md`](AGENTS.md)，动手前必须先阅读。

最低限度必须遵守：

- 主要改 `app/`；`tools/` 是构建期工具；不要提交 `app/src/main/jniLibs/`、APK、keystore。
- 改完先验证：

  ```bash
  ./gradlew testDebugUnitTest assembleDebug
  ```

- `targetSdk = 28` 是**有意为之**（PRoot / jail 需要 exec 应用目录里的文件）；不要随手升。
- 不要手动升版本号、打 tag、发 Release；CI 自动生成 `v0.1.<run_number>`。
- 签名密钥只放 GitHub Secrets；仓库和日志里绝不能出现 API Key / 密码。
- commit 标题使用 `Android：一句话总结`，正文用列表写具体改动（Release notes 会直接使用）。
- 提交/推送遵循用户当次指示；push `main` 后 CI 自动构建并发布 APK。

## Agent team

`.claude/agents/` 定义了按模块划分的 subagent。**默认所有开发任务都由主会话作为 team lead 分派给对应 subagent**，用户不需要点名：

- 按改动范围路由：`engine/`（非 `openai/`）→ `engine-dev`；`ui/`、`res/` → `compose-ui`（设计）或 `ui-impl`（按冻结 spec 实现）；`engine/openai/` → `proxy-dev`；Gradle、CI、`tools/` → `build-release`。
- 验证分层：例行构建/单测/工作树检查 → `qa-build`（Haiku，省成本）；协议、持久化、并发等高风险 diff 的深度审查 → `qa-reviewer`（Sonnet）。
- 主会话只做需求拆解、模块路由、并行调度、结果汇总、验证与提交，不直接写实现。
- 跨领域任务并行分派互不重叠的部分，由主会话汇总；纯问答、纯文档或一行以内的紧急修复可直接处理，但需在回复里说明。
- commit/push 仍由主会话按用户当次指示执行，subagent 不自行提交，也不能再 spawn 子 agent。
- 成本分层的交接规则与 brief 模板见 [`docs/AGENT_WORKFLOW.md`](docs/AGENT_WORKFLOW.md)。
