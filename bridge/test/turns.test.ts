import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { defaultConfig, type BridgeConfig } from "../src/config.js";
import { EventBus } from "../src/events.js";
import { FileService } from "../src/files.js";
import { PermissionBroker } from "../src/permissions.js";
import type { ClaudeRunner } from "../src/runner.js";
import { TurnManager, unsupportedPermissionModeError } from "../src/turns.js";
import type { BridgeEvent } from "../src/types.js";
import { cleanup, FakeRunner, tempDir, waitFor } from "./helpers.js";

const dirs: string[] = [];

interface Harness {
  turns: TurnManager;
  events: BridgeEvent[];
  root: string;
  config: BridgeConfig;
}

async function makeHarness(
  runner: ClaudeRunner,
  overrides: Partial<BridgeConfig> = {},
): Promise<Harness> {
  const root = tempDir();
  dirs.push(root);
  const config: BridgeConfig = {
    ...defaultConfig(),
    roots: [root],
    defaultCwd: root,
    permissionTimeoutMs: 500,
    ...overrides,
  };
  const files = new FileService(config.roots, config.maxUploadBytes);
  await files.init();
  const bus = new EventBus();
  const events: BridgeEvent[] = [];
  bus.subscribe("device-1", (event) => events.push(event));
  const permissions = new PermissionBroker(bus, config.permissionTimeoutMs);
  return {
    turns: new TurnManager({ runner, bus, permissions, files, config }),
    events,
    root,
    config,
  };
}

afterEach(() => {
  while (dirs.length > 0) cleanup(dirs.pop()!);
});

async function waitForFinished(harness: Harness, turnId: string) {
  return waitFor(() => {
    const snapshot = harness.turns.snapshot(turnId, "device-1");
    return snapshot.status === "running" ? null : snapshot;
  });
}

describe("TurnManager", () => {
  it("runs a turn, forwards CLI lines and finishes", async () => {
    const harness = await makeHarness(new FakeRunner());
    const started = await harness.turns.start("device-1", {
      cwd: harness.root,
      prompt: "hello",
      permissionMode: "default",
    });
    expect(started.status).toBe("running");
    const done = await waitForFinished(harness, started.turnId);
    expect(done).toMatchObject({ status: "done", exitCode: 0, sessionId: "session-1" });
    const cliLines = harness.events.filter((e) => e.kind === "cli");
    expect(cliLines.length).toBeGreaterThan(0);
    expect(harness.events.some((e) => e.kind === "turn" && e.phase === "done")).toBe(true);
  });

  it("forwards a permission request and applies the answer", async () => {
    const runner = new FakeRunner({
      onRun: async (_request, callbacks) => {
        const decision = await callbacks.onPermission({
          requestId: "perm-1",
          toolUseID: "tool-1",
          toolName: "Bash",
          input: { command: "ls" },
        });
        callbacks.onMessage({
          type: "result",
          subtype: "success",
          is_error: false,
          result: decision.behavior,
          session_id: "session-1",
        });
        return { exitCode: 0, sessionId: "session-1" };
      },
    });
    const harness = await makeHarness(runner);
    const started = await harness.turns.start("device-1", {
      cwd: harness.root,
      prompt: "run ls",
    });
    await waitFor(() => harness.events.find((e) => e.kind === "permission"));
    harness.turns.respondPermission(started.turnId, "device-1", "perm-1", {
      behavior: "allow",
      always: true,
    });
    const done = await waitForFinished(harness, started.turnId);
    expect(done.status).toBe("done");
    const resultLine = harness.events
      .filter((e) => e.kind === "cli")
      .map((e) => (e.kind === "cli" ? e.line : ""))
      .find((line) => line.includes('"type":"result"'));
    expect(resultLine).toContain('"result":"allow"');
  });

  it("cancels a running turn with exit code -2", async () => {
    const runner = new FakeRunner({
      onRun: async (request) => {
        await new Promise<void>((resolve) => {
          if (request.signal.aborted) resolve();
          else request.signal.addEventListener("abort", () => resolve(), { once: true });
        });
        return { exitCode: -2 };
      },
    });
    const harness = await makeHarness(runner);
    const started = await harness.turns.start("device-1", {
      cwd: harness.root,
      prompt: "stop me",
    });
    harness.turns.cancel(started.turnId, "device-1");
    const done = await waitForFinished(harness, started.turnId);
    expect(done).toMatchObject({ status: "cancelled", exitCode: -2 });
  });

  it("keeps turns private to the device that started them", async () => {
    const runner = new FakeRunner({
      onRun: async () => {
        await new Promise((resolve) => setTimeout(resolve, 100));
        return { exitCode: 0 };
      },
    });
    const harness = await makeHarness(runner);
    const started = await harness.turns.start("device-1", {
      cwd: harness.root,
      prompt: "private",
    });
    expect(() => harness.turns.snapshot(started.turnId, "device-2")).toThrow(/无权/);
    expect(() =>
      harness.turns.respondPermission(started.turnId, "device-2", "x", { behavior: "deny" }),
    ).toThrow(/无权/);
  });

  it("rejects a second concurrent turn for the same device", async () => {
    const runner = new FakeRunner({
      onRun: async () => {
        await new Promise((resolve) => setTimeout(resolve, 150));
        return { exitCode: 0 };
      },
    });
    const harness = await makeHarness(runner);
    await harness.turns.start("device-1", { cwd: harness.root, prompt: "one" });
    await expect(
      harness.turns.start("device-1", { cwd: harness.root, prompt: "two" }),
    ).rejects.toMatchObject({ status: 409 });
  });

  it("accepts auto and forwards it to the runner", async () => {
    const runner = new FakeRunner();
    const harness = await makeHarness(runner);
    const started = await harness.turns.start("device-1", {
      cwd: harness.root,
      prompt: "auto",
      permissionMode: "auto",
    });
    expect(started.status).toBe("running");
    const done = await waitForFinished(harness, started.turnId);
    expect(done.status).toBe("done");
    expect(runner.requests[0].permissionMode).toBe("auto");
  });

  it("explains an unsupported mode with the supported list and an upgrade hint", async () => {
    // A mode added to the app after this daemon was installed (e.g. auto on 0.1.6).
    const error = unsupportedPermissionModeError("auto", [
      "default",
      "acceptEdits",
      "plan",
    ]);
    expect(error.status).toBe(400);
    expect(error.message).toContain("auto");
    expect(error.message).toContain("default、acceptEdits、plan");
    expect(error.message).toContain("请升级 claudroid-bridge");

    const harness = await makeHarness(new FakeRunner());
    const rejected = await harness.turns
      .start("device-1", {
        cwd: harness.root,
        prompt: "x",
        permissionMode: "dontAsk" as never,
      })
      .catch((e: unknown) => e);
    expect(rejected).toMatchObject({ status: 400 });
    expect((rejected as Error).message).toContain("请升级 claudroid-bridge");
    expect((rejected as Error).message).toContain("default");
  });

  it("gates bypassPermissions behind host configuration", async () => {
    const blocked = await makeHarness(new FakeRunner());
    await expect(
      blocked.turns.start("device-1", {
        cwd: blocked.root,
        prompt: "x",
        permissionMode: "bypassPermissions",
      }),
    ).rejects.toMatchObject({ status: 403 });

    const allowed = await makeHarness(new FakeRunner(), { allowBypassPermissions: true });
    const started = await allowed.turns.start("device-1", {
      cwd: allowed.root,
      prompt: "x",
      permissionMode: "bypassPermissions",
    });
    expect(started.status).toBe("running");
  });

  it("rejects cwd outside the configured roots", async () => {
    const harness = await makeHarness(new FakeRunner());
    const outside = tempDir();
    dirs.push(outside);
    await expect(
      harness.turns.start("device-1", { cwd: outside, prompt: "x" }),
    ).rejects.toMatchObject({ status: 403 });
  });

  it("rejects empty prompts", async () => {
    const harness = await makeHarness(new FakeRunner());
    await expect(
      harness.turns.start("device-1", { cwd: harness.root, prompt: "   " }),
    ).rejects.toMatchObject({ status: 400 });
  });

  it("rejects responses for unknown permission requests", async () => {
    const runner = new FakeRunner({
      onRun: async () => {
        await new Promise((resolve) => setTimeout(resolve, 100));
        return { exitCode: 0 };
      },
    });
    const harness = await makeHarness(runner);
    const started = await harness.turns.start("device-1", { cwd: harness.root, prompt: "x" });
    expect(() =>
      harness.turns.respondPermission(started.turnId, "device-1", "missing", { behavior: "allow" }),
    ).toThrow(/过期|不存在/);
  });
});
