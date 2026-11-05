# 架构与取舍

> 本文记录的是实现过程中真实测量过的结论，包含四次推翻原设计的地方（原生二进制 / W^X / 不需要 MCP / 免 root 用 PRoot）。

## 1. 目标与候选方案

要做「RikkaHub 那样的界面 + 真能操作设备的 Agent」，只有三条路：

| 方案 | 优点 | 问题 |
| --- | --- | --- |
| A. 自己实现 Agent 循环，直连 Messages API | 完全可控 | 要重造工具系统、上下文管理、权限、压缩……等于重写 Claude Code |
| B. Termux / proot 里装 CLI，App 只做 UI | 最省事 | 用户必须另装 Termux 并手动配环境，体验断裂 |
| C. 把官方 CLI 内置进 APK，App 负责启动与解析 | 一条命令装完，升级跟官方 | 要解决"官方二进制怎么在 Android 上跑" |

选 C。难点全部集中在下面三节。

## 2. 关键发现：2.x 已经不是 Node 程序

```
$ curl -s https://registry.npmjs.org/@anthropic-ai/claude-code/latest
bin   = {"claude":"bin/claude.exe"}
deps  = {}
optionalDependencies = {
  @anthropic-ai/claude-code-linux-arm64-musl: 2.1.318,
  @anthropic-ai/claude-code-linux-arm64:      2.1.318,
  @anthropic-ai/claude-code-linux-x64-musl:   … }
unpackedSize = 190570        ← 本体只有 190 KB
```

真正的程序在平台包里。下载 `@anthropic-ai/claude-code-linux-arm64-musl` 并解析 ELF 头：

```
ELF64: true   type: EXEC   machine: AARCH64
PT_INTERP: /lib/ld-musl-aarch64.so.1        ← 动态链接 musl，不是静态
DT_NEEDED: （只有 libc.musl-aarch64.so.1，由加载器自己提供）
size: 306.8 MB
```

两个结论：

1. **不需要 Node**。所谓"内置 Node + npm 包"针对的是 1.x 时代（纯 `cli.js`）；
   2.x 之后要跟官方最新版，只需要"平台二进制 + musl 加载器"。
2. **必须让加载器出现在 `/lib/ld-musl-aarch64.so.1` 这个绝对路径上**。Android 的 `/lib`
   属于系统分区（多数机型只是 `/system/lib64` 的软链），既不可写也不保证存在，
   于是用 **chroot** 把私有目录树变成 `/` 是最干净的解法。

## 3. 关键发现：Android 10 的 W^X，以及 targetSdk 28 + PRoot 的解法

Android 10 起，`targetSdk ≥ 29` 的应用**不允许 exec() 自己 home 目录里的文件**：

- sepolicy 变更：<https://android-review.googlesource.com/c/platform/system/sepolicy/+/804149>
- Google 明确答复（"working-as-intended"）：<https://issuetracker.google.com/issues/128554619>
- 官方文档：<https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission>
- 现实案例：Termux 至今仍 `targetSdk 28`，否则无法启动 shell

我们的 CLI 恰好就是"运行时下载、放进应用目录的可执行文件"。解法与 Termux / dsh-Go 一致：

1. **targetSdk 固定 28**（`app/build.gradle.kts` 里有注释）。这是有意为之，不是忘了升级；
   升到 29+ 后 PRoot 和 jail 内二进制都无法执行。
2. **PRoot 本体放在 jniLibs**。Android 安装时会把 `lib*.so` 解到 `nativeLibraryDir`
   （应用自有、可执行）。`tools/fetch-proot.py` 下载 Termux 的 `proot` / `loader` /
   `libtalloc` / `libandroid-shmem`，把 `libtalloc.so.2` 改名成 `libtalloc.so` 并原地
   改写 `DT_NEEDED`（等长、NUL 填充，不移动 ELF 偏移）。
3. **guest rootfs 仍在 filesDir**：`filesDir/engine/jail`。targetSdk 28 允许执行这里的文件。
4. **有 root 时**：jail 改放 `/data/adb/claudroid`（Magisk 地盘，可执行、可持久），
   由 root shell 组装，用 busybox `chroot` 启动；写失败退回 `/data/local/tmp/claudroid`。

运行时关键点：

```kotlin
// ProotRunner：libproot.so 依赖同目录的 libtalloc.so / libandroid-shmem.so
env["LD_LIBRARY_PATH"] = proot.parentFile.absolutePath
env["PROOT_LOADER"] = loader.absolutePath
env["PROOT_TMP_DIR"] = cacheDir/proot-tmp
env["PROOT_NO_SECCOMP"] = "1"     // 部分 Android 10+ 内核过滤 ptrace/seccomp
argv = [proot, "-0", "-r", jail, "-w", "/work", ...binds, "--link2symlink", "--kill-on-exit",
        "/opt/claude/<ver>/claude", ...]
```

没有 root 时，PRoot 把工作区、HOME、`/dev`、`/proc`、`/sys`、`/system`、`/apex`、
`/vendor`、`/odm`、`/product`（存在才 bind）和可读的 `/sdcard` 映射进 guest。


## 4. jail 里有什么

有 root 时在 `/data/adb/claudroid/`；没有 root 时同一套布局在
`filesDir/engine/jail/`，由 PRoot 当 rootfs 使用。

```
/data/adb/claudroid/                    （或 filesDir/engine/jail/）
├── lib/ld-musl-aarch64.so.1          Alpine `musl` 包（~723 KB）
├── lib/libc.musl-aarch64.so.1        软链
├── bin/busybox                       Alpine `busybox` 包（~919 KB）+ 60 个 applet 软链
├── bin/bash                          Alpine `bash` 包（GNU bash）
├── usr/lib/libreadline.so.8          Alpine `readline`
├── usr/lib/libncursesw.so.6          Alpine `libncursesw`
├── opt/claude/<version>/claude       官方二进制（~306 MB，只保留当前版本）
├── etc/resolv.conf                   当前网络的 DNS（ConnectivityManager 优先）
├── etc/ssl/certs/ca-certificates.crt curl.se 的 CA bundle（保险）
├── etc/passwd, group, hosts
├── root/                             HOME，`~/.claude` 会话记录落在这里
├── work/                             工作区 bind mount
└── dev/ proc/ sys/ system/ data/ sdcard/   挂载点
```

GNU bash 是刻意补的：Claude Code 的 Bash 工具按 Unix 习惯调用 `/bin/bash`，
busybox 只有 ash。没有 bash 时部分命令会失败或退化。bash 的下载失败不会让安装失败，
jail 会退回 `/bin/sh`，`Installation.hasBash` 记录实际状态。

组装由一个 root shell 脚本一次完成（`Toolchain.assembleAsRoot`）：`mkdir` → `cp` 文件 →
`chmod 0755` → 软链 → 写配置文件 → 删掉旧版本目录。无 root 时
`assembleAsApp` 做同样的事，只是用 Java NIO。两者幂等，可反复执行。

启动脚本（`engine/turn.sh`，每个 turn 生成一次）大致是：

```sh
#!/system/bin/sh
export ANTHROPIC_BASE_URL='…' ANTHROPIC_AUTH_TOKEN='…' …
echo $$ > '<filesDir>/engine/turn-<n>.pid'   # exec 之后仍然指向 CLI 本身
export HOME=/root TMPDIR=/tmp
R=/data/adb/claudroid
bind_mount() { grep -qs " $2 " /proc/mounts || mount --bind "$1" "$2" 2>/dev/null; }
bind_mount /proc   "$R/proc"
bind_mount /dev    "$R/dev"
bind_mount /sys    "$R/sys"
bind_mount /system "$R/system"
bind_mount /data   "$R/data"
bind_mount <workspace> "$R/work"
cd "$R/work"
exec /data/adb/magisk/busybox chroot "$R" \
     /lib/ld-musl-aarch64.so.1 /opt/claude/<version>/claude -p '…' --output-format stream-json …
```

刻意的设计：

- **`/system`、`/data` 一起 bind 进去**：Agent 因此能读真实系统、能写 `/data/adb`，
  `magisk --install-module` 才能工作。挂了 `/system` 之后，Android 自己的 `sh`/`toybox`
  （bionic，`PT_INTERP=/system/bin/linker64`，在 jail 里也存在）同样可用，等于白拿一套工具。
- **用 `grep /proc/mounts` 而不是 `mountpoint`**：toybox 不保证有 `mountpoint`。
- **每个 turn 一个进程 + `--resume <session_id>`**：CLI 自己保存会话，我们不维护 PTY；
  崩溃或切后台都不会污染下一次对话，代价是每轮约 100 ms 启动。
- **脚本先写 pid 再 `exec`**：我们持有的直接子进程只是 `su`（或 proot），杀掉它并不会
  带走真正写 stdout 的 CLI。停止时按 pid 精确结束 CLI，否则读循环会一直等不到 EOF，
  UI 就卡在「正在停止…」。proot 模式同理，通过 jail 的 `/tmp` 里的包装脚本记录 pid。
- **只给 `--permission-mode`，不给 `--dangerously-skip-permissions`**：语义更明确，
  也让"默认（只读）/ 只读规划"这些模式真的可用（headless 下需要批准的工具会被直接拒绝）。

## 5. 三种执行路径与降级

`EngineManager.probeRoot()` 决定 `rootGranted` / `chrootAvailable`，`ClaudeEngine` 据此选路径：

1. **PRoot**（无 root，默认）
   `exec libproot.so -0 -r <filesDir jail> -w /work … /bin/sh /tmp/.claudroid-turn.sh <args>`

   包装脚本只做两件事：把 `$$` 写到 `/root/turn-<n>.pid`，然后 `exec` CLI。PRoot 的
   `--kill-on-exit` 负责正常清场，pid 文件则保证「停止」在极端情况下也能击中真正的 CLI。

   直接 exec CLI，不要让 musl loader 作为程序参数：PRoot 已经用 `PROOT_LOADER`
   解析 `PT_INTERP`；再手动启动 loader 会与它抢 `0x200000` 固定映射，报
   `Not a valid dynamic program`（x86_64 的 ET_EXEC CLI 必现）。
2. **root + chroot**（有 root，能力最全）
   `su -c "sh turn.sh"` → `busybox chroot` → `ld-musl → claude`
3. **root，无 chroot**（设备上找不到 `chroot`）
   `su -c "exec ld-musl-aarch64.so.1 …/claude …"`——直接把加载器当解释器用，绕开 `PT_INTERP`，
   路径保持真实。代价：`/etc/resolv.conf` 缺失，DNS 可能不通。
4. **应用目录直启**（最后的兜底）
   targetSdk 28 下允许执行，但部分 ROM 仍可能拒绝；保留它用于诊断。

设置页会显示实际运行方式（PRoot / root + chroot / root 直启 / 应用目录直启）、
PRoot 组件是否打包、root 授权与 chroot 状态，不静默降级。
`EngineManager.startIssue()` 会在每轮开始前给出可操作的错误信息，而不是让子进程直接崩掉。

## 6. 升级链路

```
registry.npmjs.org/@anthropic-ai/claude-code/latest                    → 版本号
registry.npmjs.org/@anthropic-ai/claude-code-linux-arm64-musl/<ver>   → tarball + integrity
        ↓ 下载（进度回调到 UI）
sha512 与 integrity 比对，不一致立即删除缓存并报错
        ↓ 自研 TarGz 抽取 package/claude（有单测 + 真实 150MB 包集成测试）
重新组装 jail（旧版本目录在脚本最后被删除）
        ↓ 写入 app 侧 install.json（版本 / jail 路径 / 模式）
下次 turn 即生效
```

上游发版即生效，没有硬编码版本号。

## 7. stream-json → UI 事件

用真 CLI（2.1.318）跑本地假端点（`tools/mock-anthropic.mjs`）抓到的帧：

```json
{"type":"system","subtype":"init","session_id":"…","tools":[…],"model":"…"}
{"type":"stream_event","event":{"type":"message_start","message":{"id":"msg_1"}}}
{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"Hello …"}}}
{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_01","name":"Bash","input":{…}}]}}
{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_01","content":"…","is_error":false}]}}
{"type":"result","subtype":"success","total_cost_usd":0.000688,"duration_ms":106,"num_turns":2,"result":"…"}
```

两个容易踩的点，单测都覆盖了：

- `assistant` 事件在流式模式下**会再发一次整条消息**。只认 `text_delta`、只从 `assistant`
  取 `tool_use`，否则文本会重复两遍。
- 若某版本没给 partial 消息（没有 `stream_event`），必须用 `assistant` 里的 text 兜底，
  否则界面空白。解析器用 `sawDelta` 标记处理。

UI 侧不做逐 token 的整表替换：`ChatController` 用 `MutableStateFlow<List<ChatMessage>>` +
按消息 id 的局部更新，块级合并（连续 `TextDelta` 追加到同一个 `Block.Text`）。

## 8. 真机上第一次跑不起来时怎么查

设置页有**自检**（跑 `claude --version`，走完全相同的启动路径）和**引擎日志**。
若自检失败，按顺序手工确认（`adb shell` → `su`）：

```sh
R=/data/adb/claudroid

# 1. jail 齐了吗
ls -l $R/lib $R/bin $R/opt/claude/*/

# 2. chroot 能不能起
/data/adb/magisk/busybox chroot $R /bin/busybox echo ok

# 3. 加载器 + 二进制
/data/adb/magisk/busybox chroot $R /lib/ld-musl-aarch64.so.1 /opt/claude/*/claude --version

# 4. 不经 chroot 的加载器直启
$R/lib/ld-musl-aarch64.so.1 $R/opt/claude/*/claude --version

# 5. 设备上有哪些 su / busybox
ls -l /data/adb/magisk/busybox /system/bin/su /debug_ramdisk/su 2>/dev/null
```

无 root / PRoot 模式（debug 包可用 `run-as` 进入应用目录）：

```sh
adb shell run-as app.claudroid ls -l files/engine/jail/lib files/engine/jail/bin
adb shell run-as app.claudroid sh -c 'LD_LIBRARY_PATH=$PWD/../lib \
  PROOT_LOADER=$PWD/../lib/libproot-loader.so PROOT_TMP_DIR=$PWD/../cache/proot-tmp \
  files/engine/jail/../lib/libproot.so ...'   # 直接用 App 内自检更省事
```

| 现象 | 原因 / 处理 |
| --- | --- |
| `proot 组件缺失` | APK 构建时跳过了 `tools/fetch-proot.py`（或 `-PskipProot`），用正式产物 |
| `libtalloc.so not found` | `LD_LIBRARY_PATH` 没有指向 `nativeLibraryDir`；`ProotRunner` 会设置 |
| PRoot 下命令全部 `Permission denied` | SELinux 拦截 ptrace：确认 `PROOT_NO_SECCOMP=1`，或改用 root 模式 |
| PRoot 启动后立刻 `killed` / 无输出 | 检查 nativeLibraryDir 里的 `libproot.so` 是否被 strip/压缩坏，重装 APK |
| `Permission denied` 执行 CLI | SELinux/W^X：确认 `targetSdk=28`；root 模式确认 jail 在 `/data/adb` |
| `not executable: 64-bit ELF` / `Exec format error` | 架构不符或文件解坏了，重装 |
| `No such file or directory`（文件确实在） | 加载器路径不对：必须是 `/lib/ld-musl-aarch64.so.1`（jail 内） |
| `chroot: can't change root` | 设备缺 `chroot(1)`，会自动降级为路径 3 |
| 请求超时 | jail 里 DNS 不通：PRoot 写入当前网络 DNS；root 模式检查 `$R/etc/resolv.conf`，或把 Base URL 改成 IP |
| TLS 证书错误 | 换 `$R/etc/ssl/certs/ca-certificates.crt`，或网关走 http |
| `[claude-code:unrecognized_model]` 警告 | 正常：网关的模型名不在官方白名单里，不影响使用 |

## 9. 为什么没有 MCP 桥接 root

最初设计是"CLI 以普通 uid 跑，通过本地 MCP server 把 `root_sh` 工具转给 App 执行"。
实现过程中发现不必要：既然用户本来就给 root，让**整个 CLI 进程就是 root** 更简单，
CLI 自带的 Bash 工具直接拥有权限，刷模块用标准 `magisk --install-module`，
少一层协议、少一个 Node 依赖（而 2.x 根本没有 Node 可用）。

代价是没有"工具调用逐条批准"的界面。需要控制感时用 `--permission-mode manual`，
CLI 会自己拒绝未批准的工具并把结果回给模型。

## 9.1 root 与 bypassPermissions 的官方限制

Claude Code 在 Linux 上有一条硬规则：**uid 0 时拒绝「完全放行」模式**，报
`--dangerously-skip-permissions cannot be used with root/sudo privileges`。

我们的设计是"整个 CLI 以 root 跑"，正好撞上这条。上游给的开关是环境变量 `IS_SANDBOX=1`
（见 claude-code issue #3490 / #9184，维护者确认但未写进文档），App 里无条件注入。
这不是绕过安全模型，而是把它对齐到"容器内 root"这个官方认可的场景。

如果哪天上游删掉这个开关，备选方案是：CLI 以普通 uid 运行，只在 Bash 工具的 PATH 前面放一个
`bash` 包装脚本，内部 `exec su -c "$*"` —— 这样 CLI 拿不到 root 身份，但每条命令仍然是 root。

## 9.2 Auto 模型、Auto 权限与功能开关

Claude Code 官方没有 `--model auto`。设置里的「CLI 默认（不指定模型）」是 App 层语义：
**不传 `--model`、也不写 `ANTHROPIC_MODEL`**，让 CLI 用自身版本/账号的默认模型。这样新版本
默认模型变化时 App 不需要跟着改；具体档位仍然通过 `--model <tier>` + `ANTHROPIC_DEFAULT_*_MODEL` 映射。
Magpie 路由组（如 `auto/dfmodel[1m]`）走另一条路：「默认 / 路由组」把模型字段原样透传给
`--model`，由网关混合调度，App 不做自动路由。

`--permission-mode auto` 是官方权限模式，需要分类器模型；`CLAUDE_CODE_AUTO_MODE_MODEL`
只影响分类器，不是主模型。网关不支持对应协议时，CLI 会退回只读，因此设置页必须写清限制。

实验开关直接映射为环境变量（`extraEnv` 最后合并，用户手写值优先）：

| 设置 | 环境变量 |
| --- | --- |
| Agent teams | `CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1/0` |
| Workflows 关闭 | `CLAUDE_CODE_DISABLE_WORKFLOWS=1` |
| Auto memory 关闭 | `CLAUDE_CODE_DISABLE_AUTO_MEMORY=1` |
| 后台任务关闭 | `CLAUDE_CODE_DISABLE_BACKGROUND_TASKS=1` |
| 扩展思考关闭 | `CLAUDE_CODE_DISABLE_THINKING=1` |
| 子代理模型 | `CLAUDE_CODE_SUBAGENT_MODEL` |
| Auto 权限分类器 | `CLAUDE_CODE_AUTO_MODE_MODEL` |
| 推理强度 | `--effort low/medium/high/xhigh/max`（Auto 不传） |

注意：非交互 `-p` 模式下 agent teams 的队友无法真正启动，开关只能保证 CLI 侧特性开启，
设置页需要如实标注「实验」。

## 10. OpenAI 兼容桥

Claude Code 只会说 Anthropic Messages 协议，所以"支持 OpenAI 接口"不能靠改 base URL，
必须做协议翻译。实现是一个跑在 127.0.0.1 随机端口的小 HTTP 服务（NanoHTTPD）：

- `POST /v1/messages` 进来 -> 转成 `/v1/chat/completions` 发出去；
- 流式返回的 OpenAI chunk 再翻译回 Anthropic 的 SSE 事件；
- `/v1/messages/count_tokens` 用字符数估算，避免 CLI 报错。

三个容易翻车的点，单测都覆盖了：

1. **tool_calls 是增量下发的**：第一个 chunk 才有 `id/name`，`arguments` 分多片到达。
   Anthropic 要求 `content_block_start(tool_use)` 必须带 name，所以状态机要等到 name 出现
   才开块，之后的 arguments 片段转成 `input_json_delta`；如果 arguments 比 name 先到，
   会先缓存再补发。
2. **tool_result 要变成独立的 `role:"tool"` 消息**，不能塞进 user 的 content 里。
3. **块索引必须连续**：文本块占 0 时，工具块就得是 1。

另外几条容易忽略但已修：

- 请求的 `stream` 必须原样转发。非流式请求被强制 `stream=true` 时，上游返回 SSE，
  代理却按 JSON 解析，表现为"非流式模式永远失败"。
- DeepSeek/OpenRouter 的 `reasoning_content` 映射成 Anthropic `thinking` 块，且必须排在
  文本之前；`finish_reason=length` 映射成 `max_tokens`，而不是 `end_turn`。
- 上游 401/403/429/400 要映射成对应的 Anthropic 错误状态，不能一律 500。
- 图片 base64 转成 OpenAI 的 `image_url` data URI；工具结果里的图片退化为文字占位。
- 本地端点没有 Key 时不要发 `Authorization: Bearer `（空 token 会被部分服务拒绝）。

真实 key 只存在代理内存里，CLI 拿到 `claudroid-local-proxy` 这个占位 token。

## 11. 还没做的

- 逐条工具批准卡片（可用 `--permission-prompt-tool` + MCP 回调补上）
- 多助手 persona（"助手" = 一组 API / 模型 / 权限配置）
- 图片直接粘贴预览（目前只做文件引用）
- x86 模拟器上的端到端自测（本机没有可用 AVD）
- PRoot 下的性能优化（大量小文件 I/O 目前受 ptrace 影响）

## 12. CI/CD

```
PR:        ci.yml      → testDebugUnitTest + assembleDebug + debug APK artifact
push main: release.yml → 版本 0.1.<run_number> + versionCode 1000+run
                        → testDebugUnitTest + assembleRelease
                        → claudroid-<ver>.apk + .sha256
                        → release-notes.sh 生成 notes
                        → gh release create/edit --target $GITHUB_SHA --latest
```

- 工作流保留 `cancel-in-progress: false`（每次 push 都要发版，不能互相取消）。
- `versionCode` 用 `1000 + run_number`，保证覆盖安装时单调递增，也避开本地调试版本。
- 签名密钥只从 GitHub Secrets 读取；没有配置时 release APK 用 debug key 签名。
- `tools/fetch-proot.py` 在 CI 与本地构建时都会跑，缓存在 `.cache/proot` 和
  `app/src/main/jniLibs`，不提交仓库。

