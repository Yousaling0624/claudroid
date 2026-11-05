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

`.claude/agents/` 定义了按模块划分的 subagent。主会话作为 team lead 自动分派，用户不需要点名：

- 按改动范围路由：`engine/`（非 `openai/`）→ `engine-dev`；`ui/`、`res/` → `compose-ui`；`engine/openai/` → `proxy-dev`；Gradle、CI、`tools/` → `build-release`；验证与提交前审查 → `qa-reviewer`。
- 跨领域任务并行分派互不重叠的部分，由主会话汇总；小改动或纯问答直接处理，不必强开 subagent。
- commit/push 仍由主会话按用户当次指示执行，subagent 不自行提交。
