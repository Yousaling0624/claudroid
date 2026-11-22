import { spawn } from "node:child_process";
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

function quickTunnelUrl(line: string): string | undefined {
  return line.match(/https:\/\/[a-z0-9-]+\.trycloudflare\.com/i)?.[0];
}

/**
 * Supervises a cloudflared process. The connector must run on the host because it
 * is what exposes the host's loopback bridge; the phone only stores the public URL.
 */
export class TunnelManager {
  private status: TunnelStatus;
  private child: TunnelChild | null = null;
  private stopped = true;
  private attempt = 0;
  private restartTimer: NodeJS.Timeout | null = null;
  private readonly spawnFn: SpawnFn;

  constructor(
    private readonly config: TunnelConfig,
    private readonly port: number,
    private readonly onChange: (status: TunnelStatus) => void,
    spawnFn: SpawnFn = spawn as unknown as SpawnFn,
  ) {
    this.spawnFn = spawnFn;
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
    if (this.config.mode === "named" && (!this.config.token || !this.config.hostname)) {
      this.set({
        state: "error",
        error: "named 模式需要 tunnel.token 与 tunnel.hostname。",
      });
      return;
    }
    if (this.child) return;
    this.stopped = false;
    this.launch();
  }

  stop(): void {
    this.stopped = true;
    if (this.restartTimer) {
      clearTimeout(this.restartTimer);
      this.restartTimer = null;
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

  private launch(): void {
    if (this.stopped) return;
    const binary = this.config.cloudflaredPath || "cloudflared";
    const args =
      this.config.mode === "quick"
        ? ["tunnel", "--no-autoupdate", "--url", `http://127.0.0.1:${this.port}`]
        : ["tunnel", "--no-autoupdate", "run", "--token", this.config.token ?? ""];
    this.set({
      state: "starting",
      url:
        this.config.mode === "named" && this.config.hostname
          ? `https://${this.config.hostname}`
          : undefined,
      error: undefined,
    });

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
          ? "未找到 cloudflared。请先安装：https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/"
          : error instanceof Error
            ? error.message
            : String(error);
      this.fail(message);
    });

    child.on("exit", (code, signal) => {
      this.child = null;
      if (this.stopped) return;
      this.fail(`cloudflared 已退出（code=${String(code)}, signal=${String(signal)}）。`);
    });
  }

  private consume(line: string): void {
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

  private fail(message: string): void {
    this.set({ state: "error", error: message });
    this.scheduleRestart();
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
