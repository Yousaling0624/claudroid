import { EventEmitter } from "node:events";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { EXTERNAL_URL_POLL_MS, TunnelManager, type TunnelChild } from "../src/tunnel.js";
import type { TunnelStatus } from "../src/types.js";

const tempDirs: string[] = [];

function externalUrlFile(content?: string): string {
  const dir = mkdtempSync(join(tmpdir(), "claudroid-bridge-test-"));
  tempDirs.push(dir);
  const file = join(dir, "public-url");
  if (content !== undefined) writeFileSync(file, content);
  return file;
}

afterEach(() => {
  vi.useRealTimers();
  for (const dir of tempDirs.splice(0)) rmSync(dir, { recursive: true, force: true });
});

class FakeChild extends EventEmitter {
  readonly stdout = new EventEmitter();
  readonly stderr = new EventEmitter();
  killed = false;

  kill(): boolean {
    this.killed = true;
    this.emit("exit", 0, "SIGTERM");
    return true;
  }
}

const settle = (): Promise<void> => new Promise((resolve) => setImmediate(resolve));

function setup(
  mode: "quick" | "named" | "off" | "external",
  extra: Record<string, string> = {},
  ensureBinary: () => Promise<string> = async () => "cloudflared",
) {
  const child = new FakeChild();
  const statuses: TunnelStatus[] = [];
  let args: string[] = [];
  let command = "";
  let spawned = 0;
  const manager = new TunnelManager(
    { mode, ...extra },
    8787,
    (status) => statuses.push(status),
    (spawnCommand, spawnArgs) => {
      command = spawnCommand;
      args = spawnArgs;
      spawned += 1;
      return child as unknown as TunnelChild;
    },
    ensureBinary,
  );
  return {
    manager,
    child,
    statuses,
    get args() {
      return args;
    },
    get command() {
      return command;
    },
    get spawned() {
      return spawned;
    },
  };
}

describe("TunnelManager", () => {
  it("parses a quick tunnel URL from cloudflared output", async () => {
    const harness = setup("quick");
    harness.manager.start();
    expect(harness.manager.current().state).toBe("starting");
    await settle();
    harness.child.stderr.emit(
      "data",
      Buffer.from("Your quick Tunnel has been created https://abc-def.trycloudflare.com\n"),
    );
    expect(harness.manager.current()).toMatchObject({
      state: "up",
      url: "https://abc-def.trycloudflare.com",
    });
    expect(harness.args).toContain("--url");
    expect(harness.args).toContain("http://127.0.0.1:8787");
    harness.manager.stop();
  });

  it("does not treat the provisioning API URL as a tunnel URL", async () => {
    const harness = setup("quick");
    harness.manager.start();
    await settle();
    harness.child.stderr.emit(
      "data",
      Buffer.from(
        'failed to request quick Tunnel: Post "https://api.trycloudflare.com/tunnel": context deadline exceeded\n',
      ),
    );
    expect(harness.manager.current().state).toBe("starting");
    expect(harness.manager.current().url).toBeUndefined();
    harness.child.emit("exit", 1, null);
    expect(harness.manager.current().state).toBe("error");
    expect(harness.manager.current().error).toMatch(/api\.trycloudflare\.com/);
    harness.manager.stop();
  });

  it("reports an error for named tunnels without credentials", () => {
    const { manager } = setup("named");
    manager.start();
    expect(manager.current().state).toBe("error");
    expect(manager.current().error).toMatch(/token/);
  });

  it("uses the configured hostname for a named tunnel", async () => {
    const { manager, child } = setup("named", {
      token: "test-tunnel-token",
      hostname: "claude.example.com",
    });
    manager.start();
    expect(manager.current().state).toBe("starting");
    expect(manager.current().url).toBe("https://claude.example.com");
    await settle();
    child.stderr.emit("data", Buffer.from("Registered tunnel connection connIndex=0\n"));
    expect(manager.current().state).toBe("up");
    manager.stop();
  });

  it("stops the child and reports off", async () => {
    const { manager, child } = setup("quick");
    manager.start();
    await settle();
    manager.stop();
    expect(child.killed).toBe(true);
    expect(manager.current().state).toBe("off");
  });

  it("surfaces a crash and schedules a restart", async () => {
    const { manager, child } = setup("quick");
    manager.start();
    await settle();
    child.emit("exit", 1, null);
    expect(manager.current().state).toBe("error");
    expect(manager.current().error).toMatch(/退出/);
    manager.stop();
  });

  it("stays off when mode is off", () => {
    const { manager } = setup("off");
    manager.start();
    expect(manager.current().state).toBe("off");
  });

  it("advertises the URL published by an external connector without spawning", () => {
    const file = externalUrlFile("https://steady-bridge.trycloudflare.com\n");
    const harness = setup("external", { publicUrlFile: file });
    harness.manager.start();
    expect(harness.manager.current()).toMatchObject({
      mode: "external",
      state: "up",
      url: "https://steady-bridge.trycloudflare.com",
    });
    expect(harness.spawned).toBe(0);
    harness.manager.stop();
    expect(harness.manager.current().state).toBe("off");
    expect(harness.manager.current().url).toBeUndefined();
  });

  it("picks up an external URL written after startup", () => {
    vi.useFakeTimers();
    const file = externalUrlFile();
    const harness = setup("external", { publicUrlFile: file });
    harness.manager.start();
    expect(harness.manager.current().state).toBe("starting");
    expect(harness.manager.current().error).toMatch(/尚未就绪/);
    writeFileSync(file, "https://late-bridge.trycloudflare.com\n");
    vi.advanceTimersByTime(EXTERNAL_URL_POLL_MS);
    expect(harness.manager.current()).toMatchObject({
      state: "up",
      url: "https://late-bridge.trycloudflare.com",
    });
    expect(harness.manager.current().error).toBeUndefined();
    harness.manager.stop();
  });

  it("explains that external mode needs a public URL file", () => {
    const harness = setup("external");
    harness.manager.start();
    expect(harness.manager.current().state).toBe("starting");
    expect(harness.manager.current().error).toMatch(/publicUrlFile/);
    harness.manager.stop();
  });

  it("rejects a non-HTTP external URL", () => {
    const file = externalUrlFile("file:///etc/passwd\n");
    const harness = setup("external", { publicUrlFile: file });
    harness.manager.start();
    expect(harness.manager.current().state).toBe("starting");
    expect(harness.manager.current().error).toMatch(/http/);
    harness.manager.stop();
  });

  it("spawns the binary returned by the installer", async () => {
    const harness = setup("quick", {}, async () => "/home/me/.config/claudroid-bridge/bin/cloudflared");
    harness.manager.start();
    await settle();
    expect(harness.command).toBe("/home/me/.config/claudroid-bridge/bin/cloudflared");
    harness.manager.stop();
  });

  it("reports an installation failure without spawning", async () => {
    const harness = setup("quick", {}, async () => {
      throw new Error("cloudflared 校验失败");
    });
    harness.manager.start();
    await settle();
    expect(harness.manager.current().state).toBe("error");
    expect(harness.manager.current().error).toMatch(/校验失败/);
    expect(harness.spawned).toBe(0);
    harness.manager.stop();
  });

  it("does not spawn when stopped while the binary is resolving", async () => {
    let release: (value: string) => void = () => undefined;
    const harness = setup(
      "quick",
      {},
      () =>
        new Promise<string>((resolve) => {
          release = resolve;
        }),
    );
    harness.manager.start();
    harness.manager.stop();
    release("cloudflared");
    await settle();
    expect(harness.spawned).toBe(0);
    expect(harness.manager.current().state).toBe("off");
  });
});
