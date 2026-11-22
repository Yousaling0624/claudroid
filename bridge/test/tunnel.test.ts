import { EventEmitter } from "node:events";
import { describe, expect, it } from "vitest";
import { TunnelManager, type TunnelChild } from "../src/tunnel.js";
import type { TunnelStatus } from "../src/types.js";

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
  mode: "quick" | "named" | "off",
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
