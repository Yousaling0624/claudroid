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
claudroid-bridge start     # 启动并打印配对码与主机地址
```

`start` 打印的 8 位配对码 10 分钟内有效、只能使用一次；配对成功后 daemon 会轮换下一个
配对码并重新打印。配对码过期后重启 `start` 生成新码。手机端在
**设置 → 远程主机 → 添加远程主机** 填入主机地址和配对码。

常用命令：

```bash
claudroid-bridge pair                 # 查看当前配对码、有效期与主机地址
claudroid-bridge devices              # 列出已配对设备
claudroid-bridge devices revoke <id>  # 吊销设备令牌
```

## 配置

默认 `~/.config/claudroid-bridge/config.json`；`CLAUDROID_BRIDGE_HOME` 改变根目录
（`config.json` / `devices.json` / `pair.json` 都放这里），`--config <path>` 单独指定文件。
所有字段都有安全默认值，配置损坏时按默认值启动。

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
| `tunnel.mode` | `off` / `quick` / `named` |
| `tunnel.token` / `hostname` | named 隧道必填 |
| `tunnel.cloudflaredPath` | 可选；指定自定义 cloudflared。留空时先复用 `PATH`，缺失则自动下载到 `~/.config/claudroid-bridge/bin/` |

`start` 的等价参数：`--bind`、`--port`、`--root`（可重复）、`--tunnel`、
`--tunnel-token`、`--tunnel-hostname`、`--allow-bypass`、`--config`。

## Cloudflare Tunnel

```bash
# quick：每次启动生成新的 *.trycloudflare.com 地址，适合临时试用
claudroid-bridge start --tunnel quick

# named：在 Cloudflare 创建隧道后使用固定域名
claudroid-bridge start --tunnel named \
  --tunnel-token <token> \
  --tunnel-hostname claude.example.com
```

**cloudflared 无需手动安装。** 首次启用隧道时，daemon 会从 Cloudflare 官方 GitHub
Release 下载固定版本的连接器，按内置 SHA-256 校验后安装到
`~/.config/claudroid-bridge/bin/cloudflared`（Windows 为 `cloudflared.exe`），之后复用。
若 `PATH` 里已有 cloudflared，会优先使用；`tunnel.cloudflaredPath` 可强制指定。
下载失败或校验不符时隧道进入错误状态并在 App 中显示原因，不会带病启动。

daemon 负责启动与监督 `cloudflared`，异常退出按指数退避重启；隧道状态与公网地址通过
`/v1/tunnel` 和 SSE 推给 App，也可以在手机的主机详情页开关。quick 地址会变化，长期使用
请配置 named tunnel。

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

## 安全模型

- 默认只绑定 `127.0.0.1`；公网暴露必须经 Cloudflare Tunnel 或你自己控制的反代。
- 配对码一次性、10 分钟有效、限速；设备令牌 32 字节随机，daemon 只保存 SHA-256，
  常量时间比较。
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
src/server.ts       HTTP + SSE 路由
src/auth.ts         配对码、设备令牌哈希、设备存储
src/turns.ts        turn 生命周期与有界事件缓冲
src/sdk-runner.ts   Agent SDK query() 封装（stream-json 透传、resume、abort）
src/permissions.ts  canUseTool → SSE → 等待手机决定
src/files.ts        roots 校验与文件 RPC
src/tunnel.ts       cloudflared 启动、URL 解析与监督
src/cloudflared.ts  固定版本下载、sha256 校验与托管路径
src/config.ts       配置文件读写与归一化
src/events.ts       SSE 事件总线
test/               vitest；用假 runner 覆盖协议、权限、重连与路径逃逸
```

## 发布新版本

1. `npm version 0.1.1 --no-git-tag-version`，并同步 `src/version.ts` 的 `DAEMON_VERSION`。
2. `npm test && npm run build`。
3. `npx npm@11 stage publish --access public`（当前 granular token 为 stage-only）。
4. 在 npmjs.com 的 **Account settings → Staged Packages** 用 2FA 批准。
