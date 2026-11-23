# claudroid-bridge

主机侧守护进程：让 Claudroid 手机的 Claude Code 会话在**这台主机**上执行。
手机负责界面、会话与逐条权限确认；守护进程负责运行 Claude Code、转发事件、文件访问与
Cloudflare Tunnel。

- 协议：[`docs/BRIDGE_CONTRACT.md`](../docs/BRIDGE_CONTRACT.md) §6.9（v1）
- 部署与安全模型：[`docs/REMOTE_HOST.md`](../docs/REMOTE_HOST.md)
- 运行 Claude Code 的是主机自己的登录态 / API Key / Base URL / 模型配置；手机不会发送
  任何供应商凭据。

## 环境要求

- Node.js **20 或更高**（`engines` 已约束）。
- 主机上已能运行 Claude Code：执行过 `claude login`，或环境里配置了
  `ANTHROPIC_API_KEY` 等。默认使用 Agent SDK 自带的 CLI；也可用
  `claudeExecutable` / `--config` 指向系统安装的 `claude`。
- 使用隧道时无需预先安装 `cloudflared`：首次启用会自动下载官方版本并校验 sha256（见下文）。

## 安装

```bash
# 从 npm
npm install -g claudroid-bridge

# 从源码（开发/未发布版本）
git clone <repo> && cd claudroid/bridge
npm ci
npm run build
npm link            # 把 claudroid-bridge 命令链到当前构建
```

## 快速开始

```bash
claudroid-bridge init      # 写入 ~/.config/claudroid-bridge/config.json
$EDITOR ~/.config/claudroid-bridge/config.json   # 至少改 roots / defaultCwd
claudroid-bridge doctor    # 检查 Node、配置、工作区与 cloudflared
claudroid-bridge start     # 启动并打印配对码、二维码与主机地址
```

`start` 打印的 8 位配对码 10 分钟内有效、只能使用一次；配对成功后 daemon 会轮换下一个
配对码并重新打印。终端还会打印二维码（`claudroid://pair?v=1&url=…&code=…`），手机在
**设置 → 远程主机 → 添加远程主机 → 扫描二维码** 一扫即完成配对；也可以手动填入地址和
配对码。二维码只在存在手机可达地址（隧道 URL 或局域网 IP）时打印，等同配对码，请勿截图外传。
配对码过期后不需要重启 daemon：`claudroid-bridge pair` 会通过本机管理通道向运行中的守护进程
取码，过期（或剩余不足 1 分钟）时自动轮换并打印新二维码；`pair --new` 可强制立即轮换。

常用命令：

```bash
claudroid-bridge pair                 # 显示有效配对码、二维码与主机地址（过期自动刷新）
claudroid-bridge pair --new           # 强制轮换一个新配对码
claudroid-bridge devices              # 列出已配对设备
claudroid-bridge devices revoke <id>  # 吊销设备令牌
```

## 配置

默认 `~/.config/claudroid-bridge/config.json`；`CLAUDROID_BRIDGE_HOME` 改变根目录
（`config.json` / `devices.json` / `pair.json` / `admin-token` 都放这里），`--config <path>`
单独指定文件。所有字段都有安全默认值，配置损坏时按默认值启动。

```json
{
  "bind": "127.0.0.1",
  "port": 8787,
  "roots": ["/home/me", "/srv/projects"],
  "defaultCwd": "/home/me",
  "permissionTimeoutMs": 120000,
  "maxUploadBytes": 104857600,
  "allowBypassPermissions": false,
  "claudeExecutable": "/usr/local/bin/claude",
  "tunnel": {
    "mode": "off",
    "token": "",
    "hostname": "",
    "cloudflaredPath": "/usr/local/bin/cloudflared"
  }
}
```

| 字段 | 说明 |
| --- | --- |
| `bind` / `port` | 默认 `127.0.0.1:8787`。**不要直接暴露到公网**；公网访问走隧道或自己的反代 |
| `roots` | App 可浏览、可执行 Claude Code 的绝对路径白名单 |
| `defaultCwd` | 手机未指定目录时的工作目录，必须落在 `roots` 内 |
| `permissionTimeoutMs` | 权限确认等待上限（1000 毫秒 – 1 小时），超时即拒绝 |
| `maxUploadBytes` | 单次上传 / 下载与请求体的大小上限 |
| `allowBypassPermissions` | 默认 `false`；只有信任 App 侧操作时才允许 `bypassPermissions` |
| `claudeExecutable` | 可选；不填时使用 Agent SDK 随包的 CLI |
| `tunnel.mode` | `off` / `quick` / `named` / `external`。`external` 表示 cloudflared 由 daemon 之外的服务监督，daemon 只播报公网地址 |
| `tunnel.token` / `hostname` | named 隧道必填 |
| `tunnel.publicUrlFile` | external 模式必填；外部隧道把公网 URL 写入该文件，daemon 每 3 秒读取一次 |
| `tunnel.cloudflaredPath` | 可选；强制指定 cloudflared。留空时依次尝试 WSL/Windows 侧连接器 → 托管副本 → `PATH`，都没有才自动下载 |

`start` 的等价参数：`--bind`、`--port`、`--root`（可重复）、`--tunnel`、
`--tunnel-token`、`--tunnel-hostname`、`--tunnel-public-url-file`、`--allow-bypass`、`--config`。

## Cloudflare Tunnel

```bash
# quick：每次启动生成新的 *.trycloudflare.com 地址，适合临时试用
claudroid-bridge start --tunnel quick

# named：在 Cloudflare 创建隧道后使用固定域名
claudroid-bridge start --tunnel named \
  --tunnel-token <token> \
  --tunnel-hostname claude.example.com

# external：cloudflared 由外部服务（如 systemd）监督，daemon 只读取公网地址
claudroid-bridge start --tunnel external \
  --tunnel-public-url-file ~/.config/claudroid-bridge/public-url
```

**cloudflared 无需手动安装。** daemon 按以下顺序选择连接器：`tunnel.cloudflaredPath` →
WSL 下 Windows 侧安装的 `cloudflared.exe` → 托管副本
`~/.config/claudroid-bridge/bin/cloudflared`（Windows 为 `cloudflared.exe`）→ `PATH`。
都没有时从 Cloudflare 官方 GitHub Release 下载固定版本，按内置 SHA-256 校验后安装到
托管路径，之后复用。下载失败或校验不符时隧道进入错误状态并在 App 中显示原因，不会带病启动。

在 WSL 里，DNS 经常由 Windows 侧代理接管（fake-ip），Linux 版 cloudflared 因此连不上
`api.trycloudflare.com`；此时 daemon 会优先使用 Windows 侧安装的 cloudflared，让流量走
Windows 网络（Windows 与 WSL 默认互通 localhost）。若想强制指定，仍可设置
`tunnel.cloudflaredPath`。

daemon 负责启动与监督 `cloudflared`，异常退出按指数退避重启；隧道状态与公网地址通过
`/v1/tunnel` 和 SSE 推给 App，也可以在手机的主机详情页开关。quick 地址会变化，长期使用
请配置 named tunnel。

`external` 模式不启动 cloudflared：外部服务把 `https://…` 写入 `tunnel.publicUrlFile`，
daemon 轮询并把它并入配对地址与 `/v1/tunnel`。这样升级 daemon（npm 重装 + 重启）不会
改变公网地址，手机不需要重新配对；外部连接器自身重启时地址仍会变化，需要重新配对或改用
named tunnel。`bridge/deploy/` 的 `claudroid-bridge-tunnel.service` 就是这样一个外部服务。

Quick Tunnel **不支持 SSE**（Cloudflare 边缘会缓冲 `text/event-stream`）。App 在回合开始后
收不到事件时会自动改用 `GET /v1/events/batch?since=<seq>&timeout=<ms>` 长轮询同一事件总线；
named tunnel 与局域网直连不受影响。

## 作为系统服务运行

```ini
# /etc/systemd/system/claudroid-bridge.service
[Unit]
Description=Claudroid Bridge
After=network-online.target

[Service]
Type=simple
User=me
Environment=HOME=/home/me
ExecStart=/usr/local/bin/claudroid-bridge start
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
```

`claudroid-bridge start` 在前台运行并处理 `SIGINT` / `SIGTERM`。配对码写入
`~/.config/claudroid-bridge/pair.json`（0600），需要时用 `claudroid-bridge pair` 查看。

systemd / launchd 启动的 daemon **不会继承登录 shell 的环境变量**。如果主机靠
`HTTPS_PROXY` 才能访问供应商端点，请在单元里显式写 `Environment=HTTPS_PROXY=…`
（以及需要的 `NO_PROXY`）。WebFetch 的域名预检是例外：`sdk-runner` 会通过 SDK 的 flag
settings 设置 `skipWebFetchPreflight`，因此即使没有代理，抓取目标网页也不会先卡在
`api.anthropic.com` 的预检上。

### 自动部署（CD）

[`deploy/`](deploy/) 提供可重复安装的 systemd **用户**单元：

| 单元 | 作用 |
| --- | --- |
| `claudroid-bridge.service` | 前台 daemon，失败自动重启 |
| `claudroid-bridge-tunnel.service` | 在 daemon 之外运行 quick tunnel，地址写入 `publicUrlFile` |
| `claudroid-bridge-update.timer` | 每 5 分钟检查 npm `latest`；有新版本就安装并重启 daemon |

```bash
loginctl enable-linger "$USER"     # 注销后仍保持服务运行（只需一次）
bash bridge/deploy/install.sh      # 复制单元与脚本、设置 tunnel.mode=external、启动服务
```

安装脚本会把 `update.sh` / `quick-tunnel.mjs` 放到
`~/.local/lib/claudroid-bridge-deploy/`；修改 `deploy/` 后重新运行 `install.sh` 即可。
`npm test` 等工作流不受影响：CI 推到 npm 的版本会在一个轮询周期内自动落到装了这套单元的
主机上。日志：

```bash
journalctl --user -u claudroid-bridge -u claudroid-bridge-tunnel -u claudroid-bridge-update -f
```

`install.sh` 默认把 `off` / `quick` / `external` 配置改为 `external` 并备份原配置；检测到
`named` 固定隧道时会保留（要强制改回外部隧道用 `CLAUDROID_DEPLOY_FORCE_EXTERNAL=1`，完全不动
现有隧道配置用 `CLAUDROID_DEPLOY_KEEP_TUNNEL=1`）。quick tunnel 地址在 daemon 升级时保持
不变，但连接器或主机重启后会变化，届时用 `claudroid-bridge pair` 重新出码；named tunnel
地址完全固定。

## 安全模型

- 默认只绑定 `127.0.0.1`；公网暴露必须经 Cloudflare Tunnel 或你自己控制的反代。
- 配对码一次性、10 分钟有效、限速；设备令牌 32 字节随机，daemon 只保存 SHA-256，
  常量时间比较。
- `pair` 的本机管理通道使用 daemon 启动时生成的 `admin-token`（0600，与配对码同目录），
  只允许同一用户调用；即使经隧道访问，也必须有该 token，否则一律 401。
- 所有文件路径先 `realpath`，必须落在 `roots` 内；拒绝 `..` 与 symlink 逃逸。
- 权限请求默认拒绝：App 断连或超过 `permissionTimeoutMs` 未响应，工具调用即被拒绝。
- token、配对码、prompt 与 Access secret 不写日志。设备令牌可用
  `devices revoke` 吊销。

## 开发

```bash
npm ci
npm run dev        # tsx src/cli.ts，直接跑 TypeScript
npm test           # vitest：鉴权、事件缓冲、权限、文件路径、隧道、HTTP 协议
npm run typecheck
npm run build      # 产出 dist/（CLI 入口 dist/cli.js）
```

源码结构：

```
src/cli.ts          命令解析、启动/配对/doctor/devices
src/server.ts       HTTP + SSE / 事件轮询路由
src/auth.ts         配对码、设备令牌哈希、设备存储
src/pairing.ts      QR 配对载荷（claudroid://pair URI）
src/qr.ts           终端二维码渲染
src/turns.ts        turn 生命周期与有界事件缓冲
src/sdk-runner.ts   Agent SDK query() 封装（stream-json 透传、resume、abort）
src/permissions.ts  canUseTool → SSE → 等待手机决定
src/files.ts        roots 校验与文件 RPC
src/tunnel.ts       cloudflared 启动、URL 解析与监督
src/cloudflared.ts  固定版本下载、sha256 校验与托管路径
src/config.ts       配置文件读写与归一化
src/events.ts       SSE 事件总线
test/               vitest；用假 runner 覆盖协议、权限、重连与路径逃逸
deploy/             systemd 用户单元、quick tunnel 监督脚本与 npm 自动部署
```

## 发布新版本

发布由 [`.github/workflows/bridge-release.yml`](../.github/workflows/bridge-release.yml)
自动完成：`bridge/src/**`、`package.json`、`package-lock.json` 或 `tsconfig.json` 落到
`main` 后，CI 跑测试与构建，再通过 **npm trusted publishing（GitHub OIDC）** 直接
`npm publish`。不需要仓库里的 npm token，也不需要人工批准 staged package。

### 一次性配置（npmjs.com）

1. 打开 `claudroid-bridge` 的 **Settings → Trusted publishing → GitHub Actions**，填：
   - Repository：`Yousaling0624/claudroid`
   - Workflow filename：`bridge-release.yml`
   - Environment：**留空**（填了会在发布时多一道 GitHub 环境审批）
2. **Allowed actions 必须允许直接发布（`npm publish`）**，不能只保留 `npm stage publish`。
   staged publish 的批准只能由维护者用 2FA 完成，OIDC 不能代批；新配置默认只允许 stage，
   而配置创建后不能编辑，选错只能删除重建。
3. 保存后 **2 天内**手动运行一次 **Publish bridge to npm** 工作流（或推一个 `bridge/`
   代码改动）来验证配置；超时未成功发布会失效。
4. 包设置里的 “Require two-factor authentication and disallow tokens” 不影响 trusted
   publisher，OIDC 照常工作。

### 版本号

CI 取 npm 上 `latest` 的下一个 patch（如 `0.1.2` → `0.1.3`）；如果本地 `package.json`
更高（手动改成 `0.2.0`），就用本地版本。构建时会把同一版本写进 `src/version.ts`，
因此**不需要手动改版本号，也不要把 CI 的版本改动提交回仓库**。同一提交重复运行工作流时，
CI 会比对 npm 的 `gitHead` 并跳过，不会重复发布。

用 [`deploy/`](deploy/) 部署的主机会在发布后的一个轮询周期（约 5 分钟）内自动安装新版本并
重启 daemon，无需在该主机上执行任何命令。

### 紧急手动发布

```bash
cd bridge
npm test && npm run build
npm version <version> --no-git-tag-version
# 同步 src/version.ts 的 DAEMON_VERSION
npm publish --access public        # 需要本机 2FA
```
