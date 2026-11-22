# 远程主机

远程主机让 Claude Code 在**另一台机器**上执行，手机继续做完整界面：流式对话、工具卡片、
逐条权限确认、远端文件浏览与附件上传。本地 PRoot 引擎仍完全可用，两种会话可以并存。

- 主机装 [`claudroid-bridge`](../bridge/) 守护进程（Node 20+）。
- 手机通过 `docs/BRIDGE_CONTRACT.md` §6.9 的 `hosts.*` 方法配对、管理主机。
- 网络可达性由守护进程托管的 **Cloudflare Tunnel** 提供，也可以直接用局域网地址或自己的反代。
- **远程会话只使用主机上的 Claude 登录 / API Key / Base URL / 模型配置。** 手机不发送自己的
  Key、Base URL、`extraEnv` 或代理设置。

## 1. 链路

```
Flutter UI
  └─ MethodChannel / EventChannel
Kotlin
  ├─ ChatController → RemoteTurnBackend → RemoteClient（HTTP + SSE）
  └─ RemoteHostManager / RemoteHostStore / SecretBox
主机
  ├─ claudroid-bridge
  │    ├─ 配对、Bearer 鉴权、文件 RPC、权限转发、隧道监督
  │    └─ @anthropic-ai/claude-agent-sdk → 主机的 Claude Code
  └─ cloudflared（可选）
```

命令走普通 POST，事件走 `GET /v1/events` 的 SSE 长连接；daemon 把 CLI 的 `stream-json`
行原样转发，Kotlin 侧继续用同一套 `StreamParser`，所以本地/远程的文本、思考、工具卡片
行为一致。

## 2. 主机准备

### 2.1 安装

```bash
# Node 20+
npm install -g claudroid-bridge

# 写入默认配置（~/.config/claudroid-bridge/config.json）
claudroid-bridge init

# 检查配置、工作目录与 cloudflared
claudroid-bridge doctor
```

开发仓库内：

```bash
cd bridge
npm ci
npm run build
node dist/cli.js start
```

### 2.2 主机上的 Claude

daemon 使用主机自己的 Claude 配置：

- 已执行过 `claude login` 的登录态，或
- 主机环境里的 `ANTHROPIC_API_KEY` / 自定义 Base URL 等。

App 不会读取或转发这些值。`claudeExecutable` 可指定主机上的 `claude` 可执行文件；不填时
由 Agent SDK 使用随包 CLI。

### 2.3 配置

默认路径 `~/.config/claudroid-bridge/config.json`（可用 `CLAUDROID_BRIDGE_HOME` 改根目录，
或用 `--config` 指定文件）：

```json
{
  "bind": "127.0.0.1",
  "port": 8787,
  "roots": ["/home/me", "/srv/projects"],
  "defaultCwd": "/home/me",
  "permissionTimeoutMs": 120000,
  "maxUploadBytes": 104857600,
  "allowBypassPermissions": false,
  "tunnel": { "mode": "off" }
}
```

- `roots`：App 可浏览、可执行 Claude Code 的绝对路径白名单。
- `defaultCwd`：手机未指定目录时使用的工作目录，必须落在 `roots` 内。
- `permissionTimeoutMs`：工具确认等待上限；超时、断连一律拒绝。
- `allowBypassPermissions`：默认 `false`。只有明确信任 App 侧操作时才打开。
- daemon 只绑定 `127.0.0.1`；不要把它直接暴露到公网，公网访问请走隧道。

## 3. 配对

```bash
claudroid-bridge start
```

启动后打印 8 位一次性配对码与主机地址，10 分钟内有效、限速、使用一次即失效；
配对成功后 daemon 会立即轮换并打印新码。配对码过期后用 `claudroid-bridge pair` 查看当前
状态，重启 `claudroid-bridge start` 生成新码。

手机端：

1. 打开 **设置 → 远程主机 → 添加远程主机**。
2. 填主机地址（`https://…` 隧道 URL、局域网 `http://192.168.1.5:8787`，或反向代理地址）。
3. 填配对码，可给主机起一个显示名称。
4. 配对成功后手机保存一个 32 字节设备令牌；设置页显示在线状态、主机名与工作目录。

令牌在 Android Keystore 里用 AES-GCM 加密后保存到 `files/hosts/hosts.json`，不会进入
`hostsJson`、日志、备份归档或 Android 系统备份（应用已关闭 `allowBackup`）。
daemon 只保存令牌的 SHA-256 哈希，并支持 `claudroid-bridge devices revoke <id>` 吊销。

## 4. 使用

- **新会话**：首页输入框上方的胶囊选择「本机 / 主机名」，再点目录胶囊选主机上的工作目录。
  绑定在首次发送时写入会话，之后不可中途切换。
- **远程模型**：只读使用主机的默认模型；设置页的模型按钮不会影响远程会话。
- **附件**：仍用 `@attachments/<name>` 约定。发送前 Kotlin 把本地附件上传到
  `<remoteCwd>/attachments/<name>`，prompt 不变。
- **权限确认**：主机触发 `canUseTool` 时，聊天页出现确认卡片，可「允许一次 / 本会话总是允许 /
  拒绝」，并显示倒计时。手机断连或超时，主机自动拒绝。
- **文件**：设置 → 远程主机 → 浏览主机文件可查看 roots 下的目录，打开/分享会先下载到本机缓存。
- **重连**：daemon 保留有界事件缓冲；网络恢复后 App 从最后的 `seq` 重放并收敛回合状态，
  聊天页顶部显示主机离线/重连提示。
- **隧道**：主机详情页可开启 / 重启 / 停止隧道并复制公网地址。

同一时刻只允许一个 turn（与本地引擎共用全局 busy 锁），避免和备份、终端、工作区切换冲突。

## 5. Cloudflare Tunnel

APK **不含** cloudflared：连接器必须运行在主机上，手机里的二进制无法反向连到别人的主机。
daemon 负责检测、启动与监督 cloudflared。

```bash
# 临时隧道：每次启动生成新的 *.trycloudflare.com 地址
claudroid-bridge start --tunnel quick

# 固定隧道：在 Cloudflare 创建 named tunnel 后填入 token 与域名
claudroid-bridge start --tunnel named \
  --tunnel-token <token> \
  --tunnel-hostname claude.example.com

# 或在配置文件中写:
# "tunnel": { "mode": "named", "token": "<token>", "hostname": "claude.example.com" }
```

也可以从 App 的主机详情页开关隧道。quick 地址会变化，长期使用请配置 named tunnel；
Cloudflare Access 场景下，在主机详情页填写 Access Client ID / Secret，App 会在请求头里带上
`CF-Access-Client-Id` / `CF-Access-Client-Secret`。

## 6. 安全模型

| 项 | 做法 |
| --- | --- |
| 配对 | 8 位一次性码，10 分钟 TTL、限速、单次使用 |
| 设备令牌 | 32 字节随机；daemon 只存 SHA-256，常量时间比较；手机端 Keystore 加密 |
| 传输 | daemon 默认只监听 `127.0.0.1`；公网经 Tunnel 或用户自己的反代 |
| 工具确认 | 默认拒绝；SSE 断开或超时即拒绝；`bypassPermissions` 需主机显式允许 |
| 路径 | 所有 `fs` 请求先 `realpath`，必须落在 `roots` 内，拒绝 `..` 与 symlink 逃逸 |
| 大小 | 单文件下载/上传与请求体限长（`maxUploadBytes`） |
| 凭据 | 手机绝不发送本机 API Key / Base URL / extraEnv / 代理；主机凭据只存在主机 |
| 日志 | 不记录 token、配对码、prompt 或 Access secret |

**手机与主机的信任边界**：配对成功等于允许这台手机在 `roots` 内执行 Claude Code 并确认工具
调用。只给自己信任的手机配对；丢失手机时在主机上执行 `devices revoke`。

## 7. systemd 示例

```ini
[Unit]
Description=Claudroid Bridge
After=network-online.target

[Service]
Type=simple
User=me
ExecStart=/usr/bin/claudroid-bridge start
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
```

## 8. 排障

| 现象 | 处理 |
| --- | --- |
| 配对失败 401 | 配对码过期或已用过；重启 `claudroid-bridge start` 生成新码（`pair` 只查看当前码） |
| 设置页一直离线 | 检查地址能否在手机浏览器打开 `/v1/health`；确认 daemon 在跑、端口/隧道正确 |
| 提示协议版本不兼容 | 升级主机上的 `claudroid-bridge` 到与 App 相同的协议版本（v1） |
| 隧道启动失败 | `claudroid-bridge doctor` 检查 cloudflared 是否安装、token/域名是否正确 |
| 工具确认总是被拒 | 手机不在线或超过 `permissionTimeoutMs`；确认 App 在前台且 SSE 已连接 |
| 目录选择为空 | `defaultCwd` / `paths` 不在 `roots` 内，或主机的 `roots` 配错 |
| 远程会话报“主机没有允许完全放行” | 改用其他权限模式，或在主机配置开启 `allowBypassPermissions` |

## 9. 当前限制

- 只支持聊天 + 文件；没有远程终端、git 面板或端口转发。
- 同一时刻一个 turn；多主机并发留到后续版本。
- 远程模型只读；模型切换在主机侧配置。
- npm 安装是当前推荐方式。单文件二进制分发（Node SEA / Bun compile）在真机闭环后继续，
  发布流程会使用独立的 `bridge-release.yml`，不影响 App 的自动发版。
- quick tunnel URL 会变化；长期使用请用 named tunnel。
