import { chmodSync, existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, isAbsolute, join, resolve } from "node:path";
import type { TunnelMode } from "./types.js";

export interface TunnelConfig {
  mode: TunnelMode;
  /** Token for a named Cloudflare Tunnel. */
  token?: string;
  /** Public hostname configured for the named tunnel, e.g. claude.example.com. */
  hostname?: string;
  /** Override the cloudflared executable path. */
  cloudflaredPath?: string;
}

export interface BridgeConfig {
  bind: string;
  port: number;
  /** Absolute directories the app may browse and run Claude Code in. */
  roots: string[];
  defaultCwd: string;
  permissionTimeoutMs: number;
  maxUploadBytes: number;
  /** Bypass permission checks only when the host operator opts in. */
  allowBypassPermissions: boolean;
  /** Optional executable override; the Agent SDK resolves its bundled CLI by default. */
  claudeExecutable?: string;
  tunnel: TunnelConfig;
}

export function bridgeHome(): string {
  return process.env.CLAUDROID_BRIDGE_HOME
    ? resolve(process.env.CLAUDROID_BRIDGE_HOME)
    : join(homedir(), ".config", "claudroid-bridge");
}

export function configPath(): string {
  return join(bridgeHome(), "config.json");
}

export function devicesPath(): string {
  return join(bridgeHome(), "devices.json");
}

export function pairingPath(): string {
  return join(bridgeHome(), "pair.json");
}

export function defaultConfig(): BridgeConfig {
  const root = homedir();
  return {
    bind: "127.0.0.1",
    port: 8787,
    roots: [root],
    defaultCwd: root,
    permissionTimeoutMs: 120_000,
    maxUploadBytes: 100 * 1024 * 1024,
    allowBypassPermissions: false,
    tunnel: { mode: "off" },
  };
}

function normalizeConfig(raw: unknown, base: BridgeConfig): BridgeConfig {
  if (raw === null || typeof raw !== "object") return base;
  const input = raw as Record<string, unknown>;
  const roots = Array.isArray(input.roots)
    ? input.roots
        .filter((r): r is string => typeof r === "string" && isAbsolute(r))
        .map((r) => resolve(r))
    : base.roots;
  const firstRoot = roots[0] ?? base.defaultCwd;
  const rawTunnel =
    input.tunnel !== null && typeof input.tunnel === "object"
      ? (input.tunnel as Record<string, unknown>)
      : {};
  const mode =
    rawTunnel.mode === "quick" || rawTunnel.mode === "named" || rawTunnel.mode === "off"
      ? rawTunnel.mode
      : base.tunnel.mode;
  return {
    bind: typeof input.bind === "string" && input.bind ? input.bind : base.bind,
    port:
      typeof input.port === "number" && Number.isInteger(input.port) && input.port >= 0 && input.port <= 65535
        ? input.port
        : base.port,
    roots,
    defaultCwd:
      typeof input.defaultCwd === "string" && isAbsolute(input.defaultCwd)
        ? resolve(input.defaultCwd)
        : firstRoot,
    permissionTimeoutMs:
      typeof input.permissionTimeoutMs === "number" && input.permissionTimeoutMs >= 1_000
        ? Math.min(input.permissionTimeoutMs, 60 * 60_000)
        : base.permissionTimeoutMs,
    maxUploadBytes:
      typeof input.maxUploadBytes === "number" && input.maxUploadBytes > 0
        ? input.maxUploadBytes
        : base.maxUploadBytes,
    allowBypassPermissions: input.allowBypassPermissions === true,
    claudeExecutable:
      typeof input.claudeExecutable === "string" && input.claudeExecutable
        ? input.claudeExecutable
        : undefined,
    tunnel: {
      mode,
      token: typeof rawTunnel.token === "string" && rawTunnel.token ? rawTunnel.token : undefined,
      hostname:
        typeof rawTunnel.hostname === "string" && rawTunnel.hostname
          ? rawTunnel.hostname
          : undefined,
      cloudflaredPath:
        typeof rawTunnel.cloudflaredPath === "string" && rawTunnel.cloudflaredPath
          ? rawTunnel.cloudflaredPath
          : undefined,
    },
  };
}

export function loadConfig(path = configPath()): BridgeConfig {
  const base = defaultConfig();
  if (!existsSync(path)) return base;
  try {
    return normalizeConfig(JSON.parse(readFileSync(path, "utf8")), base);
  } catch {
    // A broken config must not prevent the daemon from starting with safe defaults.
    return base;
  }
}

export function writeFilePrivate(path: string, content: string): void {
  mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
  const tmp = `${path}.tmp`;
  writeFileSync(tmp, content, { mode: 0o600 });
  renameSync(tmp, path);
  try {
    chmodSync(path, 0o600);
  } catch {
    // best effort on filesystems that do not support chmod
  }
}

export function saveConfig(config: BridgeConfig, path = configPath()): void {
  writeFilePrivate(path, `${JSON.stringify(config, null, 2)}\n`);
}

/** Config fields that are safe to print in logs (never the tunnel token). */
export function publicConfig(config: BridgeConfig): Record<string, unknown> {
  return {
    bind: config.bind,
    port: config.port,
    roots: config.roots,
    defaultCwd: config.defaultCwd,
    permissionTimeoutMs: config.permissionTimeoutMs,
    maxUploadBytes: config.maxUploadBytes,
    allowBypassPermissions: config.allowBypassPermissions,
    tunnel: { mode: config.tunnel.mode, hostname: config.tunnel.hostname ?? null },
  };
}
