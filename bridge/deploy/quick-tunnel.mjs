#!/usr/bin/env node
/**
 * Supervises a Cloudflare quick tunnel for claudroid-bridge.
 *
 * The connector deliberately lives outside the daemon: the CD path reinstalls
 * the npm package and restarts the daemon, and that must not rotate the public
 * URL saved on the phone. The URL is published atomically to
 * `tunnel.publicUrlFile`; the daemon's `external` tunnel mode polls that file.
 */
import { spawn, spawnSync } from "node:child_process";
import {
  existsSync,
  mkdirSync,
  readFileSync,
  renameSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { homedir } from "node:os";
import { dirname, join, resolve } from "node:path";

const QUICK_TUNNEL_URL = /https:\/\/(?!api\.)[a-z0-9-]+\.trycloudflare\.com/i;

const bridgeHome = process.env.CLAUDROID_BRIDGE_HOME
  ? resolve(process.env.CLAUDROID_BRIDGE_HOME)
  : join(homedir(), ".config", "claudroid-bridge");

function readConfig() {
  try {
    const raw = JSON.parse(readFileSync(join(bridgeHome, "config.json"), "utf8"));
    return raw !== null && typeof raw === "object" ? raw : {};
  } catch {
    return {};
  }
}

const config = readConfig();
const port =
  Number.isInteger(config.port) && config.port > 0 && config.port <= 65535 ? config.port : 8787;
const urlFile =
  typeof config.tunnel?.publicUrlFile === "string" && config.tunnel.publicUrlFile
    ? resolve(config.tunnel.publicUrlFile)
    : join(bridgeHome, "public-url");

function log(message) {
  process.stdout.write(`[${new Date().toISOString()}] ${message}\n`);
}

function isWsl() {
  if (process.platform !== "linux") return false;
  if (process.env.WSL_DISTRO_NAME || process.env.WSL_INTEROP) return true;
  try {
    return /microsoft/i.test(readFileSync("/proc/sys/kernel/osrelease", "utf8"));
  } catch {
    return false;
  }
}

function candidates() {
  const list = [];
  if (process.env.CLAUDROID_TUNNEL_BIN) list.push(process.env.CLAUDROID_TUNNEL_BIN);
  if (isWsl()) {
    // Windows-side connectors use the Windows network stack; WSL's DNS is
    // often hijacked by a Windows proxy's fake-ip range.
    list.push(
      "/mnt/c/Program Files/cloudflared/cloudflared.exe",
      "/mnt/c/Program Files (x86)/cloudflared/cloudflared.exe",
    );
  }
  list.push(join(bridgeHome, "bin", process.platform === "win32" ? "cloudflared.exe" : "cloudflared"));
  list.push("cloudflared");
  return list;
}

function usable(binary) {
  if (binary.includes("/") && !existsSync(binary)) return false;
  const result = spawnSync(binary, ["--version"], { stdio: "ignore", timeout: 10_000 });
  return !result.error && result.status === 0;
}

function findBinary() {
  for (const binary of candidates()) {
    if (usable(binary)) return binary;
  }
  return null;
}

let publishedUrl = null;

function publish(url) {
  if (url === publishedUrl) return;
  try {
    mkdirSync(dirname(urlFile), { recursive: true, mode: 0o700 });
    const tmp = `${urlFile}.tmp`;
    writeFileSync(tmp, `${url}\n`, { mode: 0o600 });
    renameSync(tmp, urlFile);
    publishedUrl = url;
    log(`公网地址: ${url}（已写入 ${urlFile}，守护进程会自动采用）`);
  } catch (error) {
    log(`写入公网地址失败：${error instanceof Error ? error.message : String(error)}`);
  }
}

/** The URL is dead once the connector exits; stop advertising it. */
function withdraw() {
  if (!publishedUrl) return;
  try {
    if (readFileSync(urlFile, "utf8").trim() === publishedUrl) {
      rmSync(urlFile, { force: true });
    }
  } catch {
    // Already removed or never written.
  }
  publishedUrl = null;
  log("隧道已中断，已移除公网地址文件");
}

let child = null;
let timer = null;
let attempt = 0;
let stopping = false;

function schedule(delay) {
  if (stopping) return;
  timer = setTimeout(run, delay);
}

function run() {
  timer = null;
  if (stopping) return;
  const binary = findBinary();
  if (!binary) {
    log("未找到 cloudflared（可用 CLAUDROID_TUNNEL_BIN 指定），10 秒后重试");
    schedule(10_000);
    return;
  }
  log(`启动 ${binary} tunnel --no-autoupdate --url http://127.0.0.1:${port}`);
  let current;
  try {
    current = spawn(binary, ["tunnel", "--no-autoupdate", "--url", `http://127.0.0.1:${port}`], {
      stdio: ["ignore", "pipe", "pipe"],
    });
  } catch (error) {
    log(`无法启动 cloudflared：${error instanceof Error ? error.message : String(error)}`);
    schedule(10_000);
    return;
  }
  child = current;

  const onData = (chunk) => {
    for (const line of String(chunk).split(/\r?\n/)) {
      if (line.trim()) process.stdout.write(`[cloudflared] ${line}\n`);
      const match = line.match(QUICK_TUNNEL_URL);
      if (match) {
        attempt = 0;
        publish(match[0]);
      }
    }
  };
  current.stdout?.on("data", onData);
  current.stderr?.on("data", onData);
  current.on("error", (error) => {
    log(`cloudflared 错误：${error instanceof Error ? error.message : String(error)}`);
  });
  current.on("exit", (code, signal) => {
    if (child === current) child = null;
    withdraw();
    if (stopping) return;
    const delay = Math.min(60_000, 5_000 * 2 ** Math.min(attempt, 4));
    attempt += 1;
    log(
      `cloudflared 退出（code=${String(code)}, signal=${String(signal)}），${Math.round(delay / 1000)} 秒后重启`,
    );
    schedule(delay);
  });
}

function shutdown(signal) {
  if (stopping) return;
  stopping = true;
  log(`收到 ${signal}，正在停止`);
  if (timer) {
    clearTimeout(timer);
    timer = null;
  }
  if (child) {
    try {
      child.kill("SIGTERM");
    } catch {
      // Already gone.
    }
    child = null;
  }
  withdraw();
  process.exit(0);
}

process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT", () => shutdown("SIGINT"));

run();
