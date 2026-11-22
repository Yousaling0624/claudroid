# 备份与恢复

> 本文件是备份功能的**冻结契约**：归档格式、恢复语义与桥接方法以这里为准。
> 修改任何字段名或语义前，先改本文，再改 Kotlin 与 Dart 两端。

## 1. 范围

备份内容：

- **所有会话历史**：`filesDir/sessions/*.json`（每个会话一个文件，磁盘格式即
  `docs/BRIDGE_CONTRACT.md` §3 的 `ChatMessage` 格式）。
- **所有工作区文件**：默认工作区 `filesDir/workspace`，以及 `filesDir/workspaces/<id>`
  下的每个自定义工作区；工作区索引（`workspaces.json`）的元数据也会备份。
- **设置**：`AppSettings` 中除密钥外的字段。**不包含** `apiKey`、`extraEnv` 与
  `networkProxy`（后两者可能含自定义 token、环境密钥或代理账号密码），备份里这三个
  字段一律写空。

不备份：

- 引擎 jail / rootfs / CLI 二进制（可重装）。
- **远程主机配置与设备令牌**：`filesDir/hosts/`（`hosts.json` 里的令牌经 Android Keystore
  加密，Keystore 密钥不可导出，归档到别的设备也无法解密）不进入备份、导出或自动备份。
  恢复后需要重新配对远程主机。
- 附件缓存之外的任何运行期文件、日志、`install.json`、备份目标配置本身。
- 工作区里的符号链接与特殊文件（socket/FIFO）：ZIP 无法可靠表达，跳过并在
  `manifest.json` 里记 `skipped` 数量。

## 2. 归档格式

单个 ZIP（文件名固定 `claudroid-backup.zip`）：

```
manifest.json                 # 最后写入；见下
settings.json                 # AppSettings（apiKey/extraEnv/networkProxy 已置空）
workspaces/index.json         # {activeId, spaces:[{id,name,createdAt}]}
workspaces/default/**         # 默认工作区文件（相对路径）
workspaces/<id>/**            # 自定义工作区文件；<id> 必须通过 WorkspaceStore.isValidId
sessions/<id>.json            # 会话；<id> 必须匹配 [A-Za-z0-9_-]{1,64}
```

`manifest.json`：

```json
{"schema":1,"createdAt":1700000000000,"appVersion":"0.1.42","versionCode":1042,
 "sessions":4,"workspaces":2,"files":38,"bytes":1234567,"skipped":0}
```

- `schema` 不匹配时拒绝恢复，错误信息提示「备份版本不兼容」。
- `createdAt` 用于展示；恢复不判断新旧，按 §3 的合并规则执行。
- ZIP 条目名必须是相对路径，拒绝绝对路径、`..`、反斜杠、NUL；解压目标必须落在
  对应工作区/会话目录的 canonical path 内（防 Zip Slip）。
- 解压上限：条目数 ≤ 200000，解压总字节 ≤ 4 GiB；超过即中止并保留已恢复的内容。
- 会话条目必须能按 `ChatSession` 解码，坏条目跳过并计数，不中断整次恢复。

## 3. 恢复语义（安全合并）

**恢复永远不删除、不覆盖本机已有内容。**

- 会话：目标文件不存在 → 写入；已存在 → 比较 `updatedAt`，仅当备份更新时覆盖。
  备份里不存在的本机会话保持不动。
- 工作区文件：目标路径不存在 → 写入；已存在 → 跳过（不比较时间，避免覆盖用户
  正在编辑的文件），计入 `filesSkipped`。
- 工作区索引：只补充本机缺失的自定义工作区；同名 id 保留本机名称；**不切换**
  `activeId`。
- 设置：仅当调用方显式要求（`restoreSettings=true`）时应用；应用时保留本机
  `apiKey`、`extraEnv` 与 `networkProxy`，其余字段采用备份值。
- 恢复完成后由桥接层调用 `sessions.load()` 与 `workspaces.load()` 刷新列表。
- 聊天 turn 或终端正在运行时拒绝备份/恢复/导入（读到的文件可能半写，恢复会与
  运行中的进程冲突），错误信息：`任务或终端正在运行，结束后再备份或恢复`。

## 4. 目标配置

存放于 `filesDir/backup/target.json`（应用私有目录，权限随应用沙箱）：

```json
{"kind":"WEBDAV",
 "auto":{"enabled":false,"intervalHours":24},
 "webdav":{"url":"","username":"","password":""},
 "s3":{"endpoint":"","region":"us-east-1","bucket":"","accessKey":"","secretKey":"",
       "prefix":"claudroid","pathStyle":true},
 "github":{"repo":"owner/name","branch":"main","path":"claudroid-backup.zip","token":""}}
```

- `kind`：`LOCAL | WEBDAV | S3 | GITHUB`；`LOCAL` 表示只用本地导出/导入。
- `auto.enabled`：是否开启后台自动备份，默认 `false`。
- `auto.intervalHours`：自动备份间隔，只接受 `24` / `72` / `168`（每天 / 每 3 天 /
  每周）；其他值按 `24` 处理。
- 旧版本保存的 `target.json` 没有 `auto` 字段时按默认值读取；未知字段忽略。
- 配置允许保存不完整的内容（用户边填边存）；执行动作时再校验并给出具体缺项。
- 密钥只写入本文件，不写日志、不进归档、不在错误信息里回显。

## 5. 同步方式

### 5.1 WebDAV

- `webdav.url` 是**完整的备份文件地址**（如
  `https://dav.example.com/remote.php/dav/files/me/claudroid-backup.zip`）。
- 用户名非空时发 `Authorization: Basic base64(user:pass)`。
- 上传前对父路径逐级 `MKCOL`（已存在返回 405 视为成功；失败不致命，PUT 仍尝试）。
- `PUT` 流式写入并带 `Content-Length`，接受 200/201/204；`GET` 200 成功，
  404 表示「云端还没有备份」。
- 连接测试：`HEAD` 目标地址；200/204/404/405 都算可达，401/403 报认证失败。

### 5.2 S3（含 S3 兼容服务）

- 空 `endpoint` → `https://s3.<region>.amazonaws.com`；非空时按用户给的协议/主机
  （可带端口）。
- `pathStyle=true`（默认）→ `<endpoint>/<bucket>/<key>`；
  `pathStyle=false` → `<bucket>.<endpoint-host>/<key>`。
- key = `prefix.trim('/') + "/claudroid-backup.zip"`，`prefix` 为空时只用文件名。
- SigV4：`AWS4-HMAC-SHA256`，服务名 `s3`，签 `host;x-amz-content-sha256;x-amz-date`。
  - `x-amz-content-sha256`：上传为归档的 SHA-256 十六进制；GET/HEAD 用空串摘要
    `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`。
  - Canonical URI 按 RFC 3986 逐段编码（只保留 `A-Za-z0-9-_.~` 原样，其余 `%XX`
    大写）；canonical query 为空。
  - 签名密钥：`HMAC("AWS4"+secret, date) → region → s3 → aws4_request`。
- 连接测试：`HEAD` 对象；200/404 可达，403 报权限/密钥问题。
- 请求失败时错误信息只带 HTTP 状态与截断后的响应体，绝不带 `Authorization`。

### 5.3 GitHub 仓库

- `github.repo` 为 `owner/name`，`branch` 分支，`path` 仓库内文件路径
  （默认 `claudroid-backup.zip`）。
- 认证：`Authorization: Bearer <token>`、`Accept: application/vnd.github+json`、
  `X-GitHub-Api-Version: 2022-11-28`。
- 上传：`GET /repos/{repo}/contents/{path}?ref={branch}` 取现有 `sha`（404 表示首次），
  再 `PUT /repos/{repo}/contents/{path}`，body
  `{"message":"Claudroid backup <yyyy-MM-dd HH:mm>","content":"<base64>","branch":"...","sha":"..."}`。
- 下载：GET 同一路径，响应 `content` 为 base64（可能带换行），解码后校验 ZIP。
- GitHub Contents API 单文件上限 100 MB；归档超过 **95 MB** 时直接失败并提示改用
  WebDAV / S3。
- 连接测试：`GET /repos/{repo}`；401 报 Token 无效，404 报仓库不存在或 Token 无权访问。

### 5.4 本地文件（SAF）

- 导出：创建归档到 `cacheDir/backup/`，用 `ACTION_CREATE_DOCUMENT`
  （`application/zip`，建议名 `claudroid-backup-yyyyMMdd-HHmm.zip`）写入选定位置，
  完成后删除缓存。
- 导入：`ACTION_OPEN_DOCUMENT`（`*/*`，部分网盘把 zip 标成 octet-stream），复制到
  `cacheDir/backup/import.zip`（上限 4 GiB），按 §3 恢复。
- 用户取消返回 `{ok:true, cancelled:true}`，不产生错误提示。

### 5.5 自动备份（后台定时）

- 使用系统 `JobScheduler`（不引入新的运行时依赖）注册唯一任务，约束
  `NETWORK_TYPE_ANY`，周期取 `auto.intervalHours`，`setPersisted(true)` 以便重启后
  继续；需要 `RECEIVE_BOOT_COMPLETED` 权限。
- 调度同步时机：应用启动时、以及 `backup.saveConfig` 保存后。仅当
  `auto.enabled=true` 且 `kind != LOCAL` 时保留任务，否则取消。间隔或目标未变化时
  不重排，避免每次改字段都把下一次执行时间往后推。
- 每次触发时重新读取 `target.json`，上传内容与手动备份完全一致；成功时
  `lastMessage` 为「已自动备份到 <target>」。
- 以下情况跳过本轮且不写状态，等下个周期：配置未开启、`kind=LOCAL`、聊天 turn
  或终端正在运行（读到的文件可能半写）、已有备份任务在进行。
- 所有失败都写入 `status.json`（`lastOk=false` + `error`），不发系统通知；下个
  周期继续尝试。
- 周期任务受系统省电策略影响可能延后；应用被强行停止后不会运行，下次启动时
  重新注册。单次执行受系统后台任务约 10 分钟限制，超大归档请手动备份或改用
  支持分片的方式。

## 6. 桥接契约

新增 EventChannel `app.claudroid/backup`，`onListen` 先推一条当前状态，之后每次状态
变化推一条：

```json
{"type":"status","statusJson":"{...}"}
```

`statusJson`：

```json
{"phase":"Idle|Working|Error","stage":"打包","detail":"正在读取工作区文件",
 "fraction":0.42,"action":"backup|restore|export|import|test",
 "lastAt":1700000000000,"lastBytes":123456,"lastAction":"backup",
 "lastMessage":"已备份到 WebDAV · dav.example.com","lastOk":true,"error":null}
```

`last*` 持久化在 `filesDir/backup/status.json`，重启后仍展示上次结果。

| 方法 | 参数 | 返回 |
| --- | --- | --- |
| `backup.config` | — | `{ok, configJson}` |
| `backup.saveConfig` | `configJson` | `{ok, configJson}` |
| `backup.status` | — | `{ok, statusJson}` |
| `backup.test` | `configJson?`（缺省用已保存配置） | `{ok, message}` |
| `backup.run` | — | `{ok, resultJson}` |
| `backup.restore` | `restoreSettings: bool`（默认 false） | `{ok, resultJson}` |
| `backup.export` | — | `{ok, cancelled: bool, bytes}` |
| `backup.import` | — | `{ok, cancelled: bool, resultJson}` |
| `backup.cancel` | — | `{ok}` |

`resultJson`（run）：

```json
{"bytes":123456,"sessions":4,"workspaces":2,"files":38,
 "target":"WebDAV · dav.example.com","createdAt":1700000000000}
```

`resultJson`（restore / import）：

```json
{"sessionsAdded":2,"sessionsSkipped":1,"filesAdded":10,"filesSkipped":3,
 "workspacesAdded":1,"settingsApplied":false,"createdAt":1700000000000}
```

- `backup.run` / `backup.restore` 在 `kind=LOCAL` 时返回 `ok:false`，提示改用本地
  导出/导入。
- `backup.saveConfig` 保存后同步自动备份调度（§5.5），不需要额外方法；`configJson`
  原样带回 `auto` 字段。
- 同一时刻只允许一个备份任务；重复调用返回 `ok:false`「已有备份任务在进行」。
- 所有方法都不打印配置内容；错误信息不得包含密码、Access Key、Token。

## 7. 测试要求

纯逻辑必须有 JVM 单测（`app/src/test/java/app/claudroid/backup/`）：

- 归档写入/读取：会话、默认工作区、自定义工作区、manifest 必填校验。
- Zip Slip：绝对路径、`..`、反斜杠条目被拒绝。
- 恢复合并：会话较新才覆盖、工作区文件已存在则跳过、索引只补缺失、设置恢复保留本机密钥。
- SigV4：签名密钥派生与官方示例向量；canonical URI/query 编码。
- 目标 URL 拼装：WebDAV 父路径 MKCOL 列表、S3 path-style/virtual-host、GitHub 路径编码。
- GitHub Contents 响应解析（真实响应样例）与 PUT body 构造。
- manifest 解码：schema 不符、缺字段、超大条目。
- 自动备份配置：缺少 `auto` 的旧配置取默认值；间隔只接受 24/72/168，其余回落 24；
  调度判定只在 `enabled=true` 且目标不是 `LOCAL` 时成立。
