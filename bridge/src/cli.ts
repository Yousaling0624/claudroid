#!/usr/bin/env node
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import type { AddressInfo } from "node:net";
import { networkInterfaces } from "node:os";
import { dirname, join, resolve } from "node:path";
import { AuthService, DeviceStore, type PairingInfo } from "./auth.js";
import { managedCloudflaredPath } from "./cloudflared.js";
import {
  configPath,
  defaultConfig,
  loadConfig,
  pairingPath,
  publicConfig,
  saveConfig,
  writeFilePrivate,
  type BridgeConfig,
} from "./config.js";
import { EventBus } from "./events.js";
import { FileService } from "./files.js";
import { PermissionBroker } from "./permissions.js";
import { SdkClaudeRunner } from "./sdk-runner.js";
import { createBridgeServer } from "./server.js";
import { TunnelManager } from "./tunnel.js";
import { TurnManager } from "./turns.js";
import type { TunnelMode } from "./types.js";
import { DAEMON_VERSION } from "./version.js";

interface CliOptions {
  command: string;
  positional: string[];
  config?: string;
  bind?: string;
  port?: number;
  roots: string[];
  tunnel?: TunnelMode;
  tunnelToken?: string;
  tunnelHostname?: string;
  allowBypass?: boolean;
  help: boolean;
  version: boolean;
}

function parseArgs(argv: string[]): CliOptions {
  const options: CliOptions = {
    command: "start",
    positional: [],
    roots: [],
    help: false,
    version: false,
  };
  let index = 0;
  if (argv[0] && !argv[0].startsWith("-")) {
    options.command = argv[0];
    index = 1;
  }
  const nextValue = (name: string): string => {
    const value = argv[index + 1];
    if (value === undefined || value.startsWith("--")) {
      throw new Error(`${name} 需要一个值。`);
    }
    index += 2;
    return value;
  };
  while (index < argv.length) {
    const arg = argv[index]!;
    if (arg === "--help" || arg === "-h") {
      options.help = true;
      index += 1;
    } else if (arg === "--version" || arg === "-v") {
      options.version = true;
      index += 1;
    } else if (arg === "--config") {
      options.config = nextValue(arg);
    } else if (arg.startsWith("--config=")) {
      options.config = arg.slice("--config=".length);
      index += 1;
    } else if (arg === "--bind") {
      options.bind = nextValue(arg);
    } else if (arg.startsWith("--bind=")) {
      options.bind = arg.slice("--bind=".length);
      index += 1;
    } else if (arg === "--port") {
      const value = Number(nextValue(arg));
      if (!Number.isInteger(value) || value < 0 || value > 65535) throw new Error("--port 无效。");
      options.port = value;
    } else if (arg.startsWith("--port=")) {
      const value = Number(arg.slice("--port=".length));
      if (!Number.isInteger(value) || value < 0 || value > 65535) throw new Error("--port 无效。");
      options.port = value;
      index += 1;
    } else if (arg === "--root") {
      options.roots.push(resolve(nextValue(arg)));
    } else if (arg.startsWith("--root=")) {
      options.roots.push(resolve(arg.slice("--root=".length)));
      index += 1;
    } else if (arg === "--tunnel") {
      const value = nextValue(arg);
      if (value !== "off" && value !== "quick" && value !== "named") {
        throw new Error("--tunnel 只能是 off/quick/named。");
      }
      options.tunnel = value;
    } else if (arg.startsWith("--tunnel=")) {
      const value = arg.slice("--tunnel=".length);
      if (value !== "off" && value !== "quick" && value !== "named") {
        throw new Error("--tunnel 只能是 off/quick/named。");
      }
      options.tunnel = value;
      index += 1;
    } else if (arg === "--tunnel-token") {
      options.tunnelToken = nextValue(arg);
    } else if (arg.startsWith("--tunnel-token=")) {
      options.tunnelToken = arg.slice("--tunnel-token=".length);
      index += 1;
    } else if (arg === "--tunnel-hostname") {
      options.tunnelHostname = nextValue(arg);
    } else if (arg.startsWith("--tunnel-hostname=")) {
      options.tunnelHostname = arg.slice("--tunnel-hostname=".length);
      index += 1;
    } else if (arg === "--allow-bypass") {
      options.allowBypass = true;
      index += 1;
    } else if (arg.startsWith("-")) {
      throw new Error(`未知参数：${arg}`);
    } else {
      options.positional.push(arg);
      index += 1;
    }
  }
  return options;
}

function applyOverrides(base: BridgeConfig, options: CliOptions): BridgeConfig {
  const config: BridgeConfig = {
    ...base,
    roots: [...base.roots],
    tunnel: { ...base.tunnel },
  };
  if (options.bind) config.bind = options.bind;
  if (options.port !== undefined) config.port = options.port;
  if (options.roots.length > 0) {
    config.roots = [...options.roots];
    config.defaultCwd = options.roots[0]!;
  }
  if (options.tunnel) config.tunnel.mode = options.tunnel;
  if (options.tunnelToken) config.tunnel.token = options.tunnelToken;
  if (options.tunnelHostname) config.tunnel.hostname = options.tunnelHostname;
  if (options.allowBypass) config.allowBypassPermissions = true;
  return config;
}

function localUrls(bind: string, port: number): string[] {
  const urls: string[] = [];
  const wildcard = bind === "0.0.0.0" || bind === "::" || bind === "0:0:0:0:0:0:0:0";
  if (wildcard) {
    urls.push(`http://127.0.0.1:${port}`);
    for (const addresses of Object.values(networkInterfaces())) {
      for (const address of addresses ?? []) {
        if (address.family === "IPv4" && !address.internal) urls.push(`http://${address.address}:${port}`);
      }
    }
  } else {
    urls.push(`http://${bind.includes(":") ? `[${bind}]` : bind}:${port}`);
  }
  return [...new Set(urls)];
}

function printPairing(code: string, expiresAt: number, urls: string[]): void {
  console.log("");
  console.log(`配对码: ${code}（${new Date(expiresAt).toLocaleString()} 前有效）`);
  if (urls.length > 0) {
    console.log("主机地址:");
    for (const url of urls) console.log(`  ${url}`);
  }
  console.log("在 App 里进入「设置 → 远程主机 → 添加主机」，填入地址和配对码。");
  console.log("");
}

interface RunningState {
  pairing: PairingInfo;
  urls: string[];
  pairFile: string;
}

function writePairFile(state: RunningState): void {
  writeFilePrivate(
    state.pairFile,
    `${JSON.stringify(
      {
        code: state.pairing.code,
        expiresAt: state.pairing.expiresAt,
        urls: state.urls,
        updatedAt: Date.now(),
      },
      null,
      2,
    )}\n`,
  );
}

async function start(options: CliOptions): Promise<void> {
  const configFile = options.config ? resolve(options.config) : configPath();
  const config = applyOverrides(loadConfig(configFile), options);
  if (config.tunnel.mode !== "off" && config.port === 0) {
    throw new Error("启用隧道时 --port 不能为 0。");
  }
  const files = new FileService(config.roots, config.maxUploadBytes);
  await files.init();
  const configDir = dirname(configFile);
  const devices = new DeviceStore(join(configDir, "devices.json"));
  const pairFile = join(configDir, "pair.json");
  const auth = new AuthService(devices);
  const bus = new EventBus();
  const permissions = new PermissionBroker(bus, config.permissionTimeoutMs);
  const turns = new TurnManager({
    runner: new SdkClaudeRunner(),
    bus,
    permissions,
    files,
    config,
  });

  const state: RunningState = {
    pairing: auth.issuePairingCode(),
    urls: [],
    pairFile,
  };

  let server: ReturnType<typeof createBridgeServer> | null = null;
  const tunnel = new TunnelManager(
    config.tunnel,
    config.port,
    (status) => {
      bus.publish({ kind: "tunnel", tunnel: status });
      if (server && server.listening) {
        const address = server.address() as AddressInfo;
        state.urls = [
          ...localUrls(config.bind, address.port),
          ...(status.url ? [status.url] : []),
        ];
        writePairFile(state);
      }
      if (status.state === "up" && status.url) console.log(`隧道已就绪: ${status.url}`);
      if (status.state === "error" && status.error) console.error(`隧道错误: ${status.error}`);
    },
    undefined,
    undefined,
    (message) => console.log(message),
  );

  server = createBridgeServer({
    config,
    auth,
    bus,
    turns,
    files,
    tunnel,
    version: DAEMON_VERSION,
    onPairingChanged: (info) => {
      state.pairing = info;
      writePairFile(state);
      printPairing(info.code, info.expiresAt, state.urls);
    },
  });

  await new Promise<void>((resolveListen, rejectListen) => {
    server!.once("error", rejectListen);
    server!.listen(config.port, config.bind, () => {
      server!.off("error", rejectListen);
      resolveListen();
    });
  });
  const address = server.address() as AddressInfo;
  state.urls = localUrls(config.bind, address.port);
  writePairFile(state);
  console.log(`claudroid-bridge ${DAEMON_VERSION} 已启动`);
  console.log(`监听: ${config.bind}:${address.port}`);
  console.log(`工作区: ${files.workspaceRoots.join(", ")}`);
  printPairing(state.pairing.code, state.pairing.expiresAt, state.urls);
  if (config.tunnel.mode !== "off") tunnel.start();

  const shutdown = (): void => {
    console.log("\n正在停止…");
    turns.close();
    tunnel.stop();
    server?.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 2_000).unref();
  };
  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);
}

function printDoctor(options: CliOptions): void {
  const config = options.config ? loadConfig(resolve(options.config)) : loadConfig();
  console.log(`Node: ${process.version}`);
  console.log(`配置文件: ${options.config ? resolve(options.config) : configPath()}`);
  console.log(`配置: ${JSON.stringify(publicConfig(config), null, 2)}`);
  let failed = false;
  for (const root of config.roots) {
    if (!existsSync(root)) {
      console.error(`✗ 工作区根目录不存在: ${root}`);
      failed = true;
    } else {
      console.log(`✓ 工作区: ${root}`);
    }
  }
  const managed = managedCloudflaredPath();
  const candidate = config.tunnel.cloudflaredPath ?? (existsSync(managed) ? managed : "cloudflared");
  const cloudflared = spawnSync(candidate, ["--version"], {
    encoding: "utf8",
  });
  if (cloudflared.error || cloudflared.status !== 0) {
    console.log("! 未找到 cloudflared；启用隧道时会自动下载官方版本并校验 sha256");
    console.log("  也可以手动安装，或用 tunnel.cloudflaredPath 指定可执行文件");
  } else {
    console.log(`✓ cloudflared: ${(cloudflared.stdout || "").trim()}${candidate === managed ? "（托管）" : ""}`);
  }
  if (config.tunnel.mode === "named" && (!config.tunnel.token || !config.tunnel.hostname)) {
    console.error("✗ named 隧道需要 tunnel.token 与 tunnel.hostname");
    failed = true;
  }
  if (failed) process.exitCode = 1;
}

function printPair(options: CliOptions): void {
  const file = options.config
    ? join(dirname(resolve(options.config)), "pair.json")
    : pairingPath();
  if (!existsSync(file)) {
    console.error(`还没有配对信息：${file}。先运行 claudroid-bridge start。`);
    process.exitCode = 1;
    return;
  }
  const info = JSON.parse(readFileSync(file, "utf8")) as {
    code: string;
    expiresAt: number;
    urls: string[];
  };
  console.log(JSON.stringify(info, null, 2));
  printPairing(info.code, info.expiresAt, info.urls);
}

function deviceCommand(options: CliOptions): void {
  const dir = options.config ? dirname(resolve(options.config)) : undefined;
  const store = new DeviceStore(dir ? join(dir, "devices.json") : join(dirname(configPath()), "devices.json"));
  const action = options.positional[0] ?? "list";
  if (action === "list") {
    const devices = store.list();
    if (devices.length === 0) {
      console.log("还没有已配对的设备。");
      return;
    }
    for (const device of devices) {
      console.log(
        `${device.id}  ${device.name}  创建=${new Date(device.createdAt).toLocaleString()}  最近=${new Date(device.lastSeenAt).toLocaleString()}`,
      );
    }
    return;
  }
  if (action === "revoke") {
    const id = options.positional[1];
    if (!id) throw new Error("用法: claudroid-bridge devices revoke <id>");
    console.log(store.remove(id) ? "已吊销。" : "没有找到该设备。");
    return;
  }
  throw new Error("用法: claudroid-bridge devices [list|revoke <id>]");
}

function help(): void {
  console.log(`claudroid-bridge ${DAEMON_VERSION}

用法:
  claudroid-bridge start [选项]      启动守护进程并打印配对码
  claudroid-bridge init              写入默认配置
  claudroid-bridge pair              查看当前配对码与主机地址
  claudroid-bridge doctor            检查配置、工作区与 cloudflared
  claudroid-bridge devices [list|revoke <id>]

start 选项:
  --config <path>            配置文件路径（默认 ~/.config/claudroid-bridge/config.json）
  --bind <addr>              监听地址（默认 127.0.0.1）
  --port <n>                 监听端口（默认 8787）
  --root <dir>               工作区根目录，可重复
  --tunnel off|quick|named   Cloudflare Tunnel 模式
  --tunnel-token <token>     named 隧道 token
  --tunnel-hostname <host>   named 隧道的公网域名
  --allow-bypass             允许 App 请求 bypassPermissions（默认关闭）

隧道首次启用时会自动下载并校验 cloudflared；tunnel.cloudflaredPath 可覆盖。

配置文件示例见 bridge/README.md。`);
}

async function main(): Promise<void> {
  const options = parseArgs(process.argv.slice(2));
  if (options.version) {
    console.log(DAEMON_VERSION);
    return;
  }
  if (options.help || options.command === "help") {
    help();
    return;
  }
  switch (options.command) {
    case "start":
      await start(options);
      return;
    case "init": {
      const path = options.config ? resolve(options.config) : configPath();
      if (existsSync(path)) {
        console.log(`配置已存在：${path}`);
        return;
      }
      saveConfig(defaultConfig(), path);
      console.log(`已写入默认配置：${path}`);
      return;
    }
    case "doctor":
      printDoctor(options);
      return;
    case "pair":
      printPair(options);
      return;
    case "devices":
      deviceCommand(options);
      return;
    default:
      help();
      process.exitCode = 1;
  }
}

main().catch((error: unknown) => {
  console.error(`claudroid-bridge: ${error instanceof Error ? error.message : String(error)}`);
  process.exitCode = 1;
});
