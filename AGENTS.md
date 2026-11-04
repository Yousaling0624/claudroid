# Claudroid 开发规范

> 本文件是仓库唯一的开发规范，适用于人类和所有 AI Agent。
> `CLAUDE.md`、`.github/copilot-instructions.md` 只是各工具的入口；规则冲突时以本文件为准。

## 1. 项目范围

- `app/`：Android 客户端（Kotlin + Jetpack Compose）。引擎、UI、数据层都在这里。
- `tools/`：构建期工具，尤其是 `fetch-proot.py`（把 Termux PRoot 打成 `lib*.so`）。
- `.github/`：PR 检查与 push main 自动发版。
- `prototype/`、`docs/`：设计原型与文档。改 UI 前先看 `prototype/index.html` 与截图。
- `dist/`、`app/build/`、`app/src/main/jniLibs/` 是生成物，不要提交。

## 2. 标准工作流

1. 读相关代码、`docs/ARCHITECTURE.md` 与本文，确认现有实现方式。
2. 只改必要文件，保持现有风格，不夹带无关重构。
3. 可测试的纯逻辑（协议翻译、URL/参数拼装、解析、设置迁移）必须补单元测试。
4. 本地验证：

   ```bash
   python3 tools/fetch-proot.py --all      # 首次或脚本变更后
   ./gradlew testDebugUnitTest
   ./gradlew assembleDebug
   ```

   改了发版脚本时再跑：

   ```bash
   bash -n .github/scripts/release-notes.sh
   ```

5. 提交前 `git status` 确认只有预期文件。
6. 提交/推送遵循用户当次指示；push `main` 后 CI 会自动构建并发布 Release。

**验证失败时不要提交。** 先修复；修不好就如实说明失败项和原因。不要用 `--no-verify`、跳过测试、删测试或改 CI 阈值来「通过」。

## 3. 版本与发布（重要）

- **不要手动升版本号、打 tag、发 Release。** `app/build.gradle.kts` 里的 `1.1.0` 只是本地基线。
- `.github/workflows/release.yml` 在每次 push `main`（命中 `app/**`、`tools/**`、Gradle 或工作流路径）时：
  1. 生成 `versionName = 0.1.<run_number>`、`versionCode = 1000 + run_number`；
  2. 跑单元测试、`assembleRelease`；
  3. 上传 `claudroid-<version>.apk` + `.sha256`；
  4. 用 commit 生成 release notes，并创建/更新 GitHub Release。
- 纯文档改动不命中路径，不会发版；不要为了发版去改无关文件。
- 签名密钥只放在 GitHub Secrets：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、
  `ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。没有密钥时 CI 用 debug key 签名，仍可侧载。
  仓库里绝不能出现 keystore / 密码。
- PR 只跑 `ci.yml`（测试 + debug APK 构建），不发版。

## 4. Commit message 规范

Release notes 直接从 commit 生成，所以 commit 就是发布说明：

- 标题格式：`Android：一句话总结`。CI/文档改动可用 `CI：`、`文档：`。
- 标题写用户能看懂的变化，不要写「修改代码」「update」。
- 正文用 `-` 列表写具体改动；单 commit 发版时正文会原样进入 Release notes。
- 多 commit 时脚本按「新增/添加/支持」「修复/修正/解决」「优化/调整/重构」等关键词归类。
- 不要写版本号；不要提交生成物（APK、`release-notes.md`、`jniLibs/` 已忽略）。
- Agent 如果支持署名，Claude Code 固定用 `Co-Authored-By: Claude Code <noreply@anthropic.com>`。

## 5. 引擎与运行环境（必须理解）

- 官方 Claude Code 2.x 是 **musl 原生二进制**，不需要 Node。
- Android 10+ 对 `targetSdk >= 29` 禁止 exec 应用数据目录里的文件（W^X）。本项目
  **故意保持 `targetSdk 28`**，和 Termux / dsh-Go 同一策略。不要随手升 targetSdk；
  升之前必须先证明 PRoot、jail 内二进制仍能执行。
- 免 root：`ProotRunner` 用 jniLibs 里的 Termux PRoot + loader + libtalloc +
  libandroid-shmem，把 `filesDir/engine/jail` 当 rootfs 跑。
  `LD_LIBRARY_PATH` 必须指向 `nativeLibraryDir`，否则 `libproot.so` 找不到
  `libtalloc.so` / `libandroid-shmem.so`。
- 有 root：`Toolchain` 用 `su` 把 jail 组装到 `/data/adb/claudroid`，`ClaudeEngine`
  用 Magisk busybox `chroot` 启动；没有 `chroot(1)` 时降级为加载器直启。
- jail 里必须有 Alpine musl loader、busybox、GNU bash（bash + readline + ncursesw）。
  缺少 bash 时 Claude Code 的 Bash 工具会退化甚至不可用。
- 每个 turn 一个进程，靠 `--resume <session-id>` 续上下文。不要改成常驻 PTY。
- PRoot 组件由 `tools/fetch-proot.py` 在构建期下载并改写 `libtalloc.so.2` 的
  DT_NEEDED；不要把它们提交进仓库。

## 6. 接口与代理

- Anthropic 兼容：CLI 直接请求用户配置的 Base URL（不要带 `/v1`）。
- OpenAI 兼容：`OpenAiProxy` 在本机 loopback 起 NanoHTTPD，做 Messages
  <-> Chat Completions 的双向翻译（文本、图片、tool_calls、stream、reasoning）。
  - 请求的 `stream` 值必须原样转发；非流式请求不能强制 `stream=true`。
  - 上游 HTTP 状态要映射回 Anthropic 错误语义（401/429/400…），不要一律 500。
  - 本地/内网端点（Ollama、LM Studio）允许空 API Key；不要发送
    `Authorization: Bearer ` 这种空头。
  - 真实 Key 只留在代理进程；CLI 只拿占位 token。
- 任何协议字段变化都要在 `OpenAiMappingTest` 里补纯函数测试。

## 7. 代码约定

- Kotlin official style；Compose Material3；保持现有暖纸色 + 陶土橙设计。
- 注释解释「为什么」，不复述代码；模块顶部保留用途说明。
- 不引入新的运行时依赖 / UI 框架；确有必要先征求用户同意。
- 设置持久化 key 修改必须向后兼容（例如 `root_enabled` -> `engine_mode` 的迁移），
  读取失败要能安全回退。
- 流式 UI 必须自动滚动到底部；用户手动上翻后不要强行拉回。
- 错误提示要给下一步操作，而不只是报错码。
- 会话 JSON 读取失败不能让整个列表崩掉；崩溃遗留的 `streaming` / `running` 状态要在
  打开会话时修复。

## 8. 安全与隐私

- 仓库里绝不能出现 API Key、Cookie、keystore、`.env`、签名密码。示例用占位符。
- 不记录用户 Key；日志里只出现模型名、版本号、退出码。
- root + `完全放行` 等于把 root shell 交给模型。默认保持 `AUTO`，只在用户明确需要时
  使用 Root 模式；不要默认把 `/data` 暴露给不可信模型。
- 下载的 npm / Alpine / Termux 产物必须做完整性校验（sha512 / APKINDEX 版本）。
- 外部响应视为不可信输入；解析前判空、限长。

## 9. 测试

- 新增/修改纯逻辑必须补 `app/src/test/**` 单测，并保证全量 `testDebugUnitTest` 通过。
- 不 mock 网络「凑覆盖率」；把真实响应样例喂给纯解析函数。
- 不删除、不跳过已有测试。
- 真机验证项（PRoot 启动、chroot、DNS）无法在 CI 覆盖，改到相关路径时要在设置页
  自检并记录结果。

## 10. 提交前自查清单

- [ ] 只改了必要文件；没有提交 `jniLibs/`、APK、keystore
- [ ] `./gradlew testDebugUnitTest assembleDebug` 通过
- [ ] 新纯逻辑有测试；没有删/跳过测试
- [ ] 没有手改版本号、tag、Release
- [ ] commit 标题/正文可直接作为 Release notes
- [ ] 没有把 API Key / 签名密码写进代码或日志
