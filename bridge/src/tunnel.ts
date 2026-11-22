import { spawn } from "node:child_process";
import { readFileSync } from "node:fs";
import { ensureCloudflared } from "./cloudflared.js";
import type { TunnelConfig } from "./config.js";
import type { TunnelStatus } from "./types.js";

export interface TunnelChild {
  stdout?: NodeJS.ReadableStream | null;
  stderr?: NodeJS.ReadableStream | null;
  kill(signal?: NodeJS.Signals | string): boolean;
  on(event: string, listener: (...args: unknown[]) => void): unknown;
}

export type SpawnFn = (
  command: string,
  args: string[],
  options: { stdio: ["ignore", "pipe", "pipe"] },
) => TunnelChild;

export type EnsureBinaryFn = () => Promise<string>;

/**
 * A quick tunnel URL is a single-label subdomain. `api.trycloudflare.com` shows
 * up in cloudflared's own error output when provisioning fails, and must not be
 * mistaken for a working tunnel.
 */
const QUICK_TUNNEL_URL = /https:\/\/(?!api\.)[a-z0-9-]+\.trycloudflare\.com/i;

function quickTunnelUrl(line: string): string | undefined {
  return line.match(QUICK_TUNNEL_URL)?.[0];
}

/** How often `external` mode re-reads the URL published by the connector. */
export const EXTERNAL_URL_POLL_MS = 3_000;

/**
 * Supervises a cloudflared process. The connector must run on the host because it
 * is what exposes the host's loopback bridge; the phone only stores the public URL.
 * The binary is resolved (and, when missing, downloaded from the pinned official
 * release) on demand, so enabling a tunnel needs no manual cloudflared install.
 */
export class TunnelManager {
  private status: TunnelStatus;
  private child: TunnelChild | null = null;
  private stopped = true;
  private launching = false;
  private generation = 0;
  private resolvedBinary: string | null = null;
  private attempt = 0;
  private restartTimer: NodeJS.Timeout | null = null;
  private externalTimer: NodeJS.Timeout | null = null;
  private lastOutput = "";
  private readonly spawnFn: SpawnFn;
  private readonly ensureBinary: EnsureBinaryFn;

  constructor(
    private readonly config: TunnelConfig,
    private readonly port: number,
    private readonly onChange: (status: TunnelStatus) => void,
    spawnFn: SpawnFn = spawn as unknown as SpawnFn,
    ensureBinary?: EnsureBinaryFn,
    log: (message: string) => void = () => undefined,
  ) {
    this.spawnFn = spawnFn;
    this.ensureBinary = ensureBinary ?? (() => ensureCloudflared(this.config, log));
    this.status = { mode: config.mode, state: "off", changedAt: Date.now() };
  }

  current(): TunnelStatus {
    return { ...this.status };
  }

  start(): void {
    if (this.config.mode === "off") {
      this.set({ state: "off", url: undefined, error: undefined });
      return;
    }
    if (this.config.mode === "external") {
      // The connector lives outside this process; only its published URL is
      // owned here. Polling keeps the advertised address fresh across daemon
      // upgrades and connector restarts.
      this.stopped = false;
      if (!this.externalTimer) this.refreshExternal();
      return;
    }
    if (this.config.mode === "named" && (!this.config.token || !this.config.hostname)) {
      this.set({
        state: "error",
        error: "named 模式需要 tunnel.token 与 tunnel.hostname。",
      });
      return;
    }
    if (this.child || this.launching) return;
    this.stopped = false;
    this.launch();
  }

  stop(): void {
    this.stopped = true;
    this.generation += 1;
    this.launching = false;
    if (this.restartTimer) {
      clearTimeout(this.restartTimer);
      this.restartTimer = null;
    }
    if (this.externalTimer) {
      clearTimeout(this.externalTimer);
      this.externalTimer = null;
    }
    if (this.child) {
      try {
        this.child.kill("SIGTERM");
      } catch {
        // already gone
      }
      this.child = null;
    }
    this.set({ state: "off", url: undefined, error: undefined });
  }

  restart(): void {
    this.stop();
    this.start();
  }

  /** Reads and validates the URL an external connector published. */
  private refreshExternal(): void {
    if (this.stopped) return;
    const file = this.config.publicUrlFile;
    let url: string | undefined;
    let error: string | undefined;
    if (!file) {
      error = "external 模式需要 tunnel.publicUrlFile，由外部隧道写入公网地址。";
    } else {
      try {
        const candidate = readFileSync(file, "utf8").trim().split(/\s+/)[0] ?? "";
        const parsed = new URL(candidate);
        if (parsed.protocol !== "https:" && parsed.protocol !== "http:") {
          error = `公网地址不是 http(s) URL：${candidate.slice(0, 200)}`;
        } else {
          url = candidate;
        }
      } catch {
        error = `公网地址尚未就绪：${file}。外部隧道写入后会自动生效。`;
      }
    }
    const state: TunnelStatus["state"] = url ? "up" : "starting";
    if (
      this.status.state !== state ||
      this.status.url !== url ||
      (this.status.error ?? "") !== (error ?? "")
    ) {
      this.set({ state, url, error });
    }
    this.externalTimer = setTimeout(() => {
      this.externalTimer = null;
      this.refreshExternal();
    }, EXTERNAL_URL_POLL_MS);
    this.externalTimer.unref();
  }

  private launch(): void {
    if (this.stopped) return;
    const generation = ++this.generation;
    this.launching = true;
    this.set({
      state: "starting",
      url:
        this.config.mode === "named" && this.config.hostname
          ? `https://${this.config.hostname}`
          : undefined,
      error: undefined,
    });
    void this.resolveBinary()
      .then((binary) => {
        if (this.stopped || generation !== this.generation) return;
        this.launching = false;
        this.spawnChild(binary);
      })
      .catch((error: unknown) => {
        if (this.stopped || generation !== this.generation) return;
        this.launching = false;
        // A failed download/installation is permanent until the user retries;
        // do not hammer the network with the crash-restart backoff.
        this.fail(error instanceof Error ? error.message : String(error), false);
      });
  }

  private async resolveBinary(): Promise<string> {
    if (this.resolvedBinary) return this.resolvedBinary;
    const binary = this.config.cloudflaredPath || (await this.ensureBinary());
    this.resolvedBinary = binary;
    return binary;
  }

  private spawnChild(binary: string): void {
    if (this.stopped) return;
    const args =
      this.config.mode === "quick"
        ? ["tunnel", "--no-autoupdate", "--url", `http://127.0.0.1:${this.port}`]
        : ["tunnel", "--no-autoupdate", "run", "--token", this.config.token ?? ""];

    let child: TunnelChild;
    try {
      child = this.spawnFn(binary, args, { stdio: ["ignore", "pipe", "pipe"] });
    } catch (error) {
      this.fail(error instanceof Error ? error.message : String(error));
      return;
    }
    this.child = child;

    const onData = (chunk: unknown): void => {
      const text = typeof chunk === "string" ? chunk : Buffer.isBuffer(chunk) ? chunk.toString("utf8") : String(chunk);
      for (const line of text.split(/\r?\n/)) this.consume(line);
    };
    child.stdout?.on("data", onData);
    child.stderr?.on("data", onData);

    child.on("error", (error) => {
      this.child = null;
      const message =
        error && typeof error === "object" && "code" in error && error.code === "ENOENT"
          ? "未找到 cloudflared。请检查 tunnel.cloudflaredPath，或删除该配置让守护进程自动下载。"
          : error instanceof Error
            ? error.message
            : String(error);
      this.fail(message);
    });

    child.on("exit", (code, signal) => {
      this.child = null;
      if (this.stopped) return;
      const detail = this.lastOutput ? `：${this.lastOutput.slice(0, 300)}` : "";
      this.fail(`cloudflared 已退出（code=${String(code)}, signal=${String(signal)}）${detail}`);
    });
  }

  private consume(line: string): void {
    const trimmed = line.trim();
    if (trimmed) this.lastOutput = trimmed;
    const quick = quickTunnelUrl(line);
    if (quick) {
      this.attempt = 0;
      this.set({ state: "up", url: quick, error: undefined });
      return;
    }
    if (this.config.mode === "named" && /Registered tunnel connection/i.test(line)) {
      this.attempt = 0;
      this.set({
        state: "up",
        url: this.config.hostname ? `https://${this.config.hostname}` : undefined,
        error: undefined,
      });
    }
  }

  private fail(message: string, retry = true): void {
    this.set({ state: "error", error: message });
    if (retry) this.scheduleRestart();
  }

  private scheduleRestart(): void {
    if (this.stopped || this.restartTimer) return;
    const delay = Math.min(60_000, 5_000 * 2 ** Math.min(this.attempt, 4));
    this.attempt += 1;
    this.restartTimer = setTimeout(() => {
      this.restartTimer = null;
      this.launch();
    }, delay);
    this.restartTimer.unref();
  }

  private set(patch: Partial<TunnelStatus>): void {
    this.status = { ...this.status, ...patch, changedAt: Date.now() };
    this.onChange(this.current());
  }
}
