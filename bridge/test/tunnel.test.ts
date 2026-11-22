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

function setup(mode: "quick" | "named" | "off", extra: Record<string, string> = {}) {
  const child = new FakeChild();
  const statuses: TunnelStatus[] = [];
  let args: string[] = [];
  const manager = new TunnelManager(
    { mode, ...extra },
    8787,
    (status) => statuses.push(status),
    (_command, spawnArgs) => {
      args = spawnArgs;
      return child as unknown as TunnelChild;
    },
  );
  return {
    manager,
    child,
    statuses,
    get args() {
      return args;
    },
  };
}

describe("TunnelManager", () => {
  it("parses a quick tunnel URL from cloudflared output", () => {
    const harness = setup("quick");
    harness.manager.start();
    expect(harness.manager.current().state).toBe("starting");
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

  it("uses the configured hostname for a named tunnel", () => {
    const { manager, child } = setup("named", {
      token: "test-tunnel-token",
      hostname: "claude.example.com",
    });
    manager.start();
    expect(manager.current().state).toBe("starting");
    expect(manager.current().url).toBe("https://claude.example.com");
    child.stderr.emit("data", Buffer.from("Registered tunnel connection connIndex=0\n"));
    expect(manager.current().state).toBe("up");
    manager.stop();
  });

  it("stops the child and reports off", () => {
    const { manager, child } = setup("quick");
    manager.start();
    manager.stop();
    expect(child.killed).toBe(true);
    expect(manager.current().state).toBe("off");
  });

  it("surfaces a crash and schedules a restart", () => {
    const { manager, child } = setup("quick");
    manager.start();
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
});
