import type { AddressInfo } from "node:net";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { AuthService, DeviceStore } from "../src/auth.js";
import { defaultConfig, type BridgeConfig } from "../src/config.js";
import { EventBus } from "../src/events.js";
import { FileService } from "../src/files.js";
import { PermissionBroker } from "../src/permissions.js";
import type { ClaudeRunner } from "../src/runner.js";
import { createBridgeServer } from "../src/server.js";
import { TunnelManager } from "../src/tunnel.js";
import { TurnManager } from "../src/turns.js";
import { cleanup, FakeRunner, tempDir } from "./helpers.js";

interface Harness {
  baseUrl: string;
  root: string;
  token: string;
  turns: TurnManager;
  bus: EventBus;
  close: () => Promise<void>;
}

const cleanups: Array<() => Promise<void>> = [];

async function startServer(runner: ClaudeRunner): Promise<Harness> {
  const root = tempDir();
  const configDir = tempDir();
  const config: BridgeConfig = {
    ...defaultConfig(),
    roots: [root],
    defaultCwd: root,
    port: 0,
    permissionTimeoutMs: 1_000,
    tunnel: { mode: "off" },
  };
  const files = new FileService(config.roots, config.maxUploadBytes);
  await files.init();
  const auth = new AuthService(new DeviceStore(join(configDir, "devices.json")), {
    pairingTtlMs: 60_000,
  });
  const code = auth.issuePairingCode().code;
  const bus = new EventBus();
  const permissions = new PermissionBroker(bus, config.permissionTimeoutMs);
  const turns = new TurnManager({ runner, bus, permissions, files, config });
  const tunnel = new TunnelManager(config.tunnel, 0, () => {});
  const server = createBridgeServer({
    config,
    auth,
    bus,
    turns,
    files,
    tunnel,
    version: "test",
  });
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => resolve());
  });
  const port = (server.address() as AddressInfo).port;
  const baseUrl = `http://127.0.0.1:${port}`;

  const pairResponse = await fetch(`${baseUrl}/v1/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code, deviceName: "Test Phone" }),
  });
  expect(pairResponse.status).toBe(200);
  const pairBody = (await pairResponse.json()) as { token: string };

  const close = async (): Promise<void> => {
    turns.close();
    tunnel.stop();
    await new Promise<void>((resolve) => server.close(() => resolve()));
    cleanup(root);
    cleanup(configDir);
  };
  cleanups.push(close);
  return { baseUrl, root, token: pairBody.token, turns, bus, close };
}

afterEach(async () => {
  while (cleanups.length > 0) await cleanups.pop()!();
});

function authHeaders(token: string): Record<string, string> {
  return { authorization: `Bearer ${token}`, "content-type": "application/json" };
}

async function json<T>(response: Response): Promise<T> {
  return (await response.json()) as T;
}

class SseReader {
  private readonly reader: ReadableStreamDefaultReader<Uint8Array>;
  private readonly decoder = new TextDecoder();
  private buffer = "";

  constructor(response: Response) {
    if (!response.body) throw new Error("no body");
    this.reader = response.body.getReader();
  }

  async next(timeoutMs = 3_000): Promise<{ event: string; data: Record<string, unknown> }> {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const boundary = this.buffer.indexOf("\n\n");
      if (boundary >= 0) {
        const block = this.buffer.slice(0, boundary);
        this.buffer = this.buffer.slice(boundary + 2);
        const parsed = this.parse(block);
        if (parsed) return parsed;
        continue;
      }
      const remaining = deadline - Date.now();
      if (remaining <= 0) throw new Error("SSE timeout");
      const read = this.reader.read();
      const timeout = new Promise<never>((_, reject) =>
        setTimeout(() => reject(new Error("SSE timeout")), remaining),
      );
      const { value, done } = await Promise.race([read, timeout]);
      if (done) throw new Error("SSE closed");
      this.buffer += this.decoder.decode(value, { stream: true });
    }
  }

  private parse(block: string): { event: string; data: Record<string, unknown> } | null {
    let event = "message";
    const data: string[] = [];
    for (const line of block.split("\n")) {
      if (line.startsWith("event:")) event = line.slice(6).trim();
      else if (line.startsWith("data:")) data.push(line.slice(5).trimStart());
    }
    if (data.length === 0) return null;
    return { event, data: JSON.parse(data.join("\n")) as Record<string, unknown> };
  }
}

describe("Bridge HTTP server", () => {
  it("pairs a device and serves authenticated health", async () => {
    const harness = await startServer(new FakeRunner());
    const unauthorized = await fetch(`${harness.baseUrl}/v1/health`);
    expect(unauthorized.status).toBe(401);
    const response = await fetch(`${harness.baseUrl}/v1/health`, {
      headers: authHeaders(harness.token),
    });
    expect(response.status).toBe(200);
    const health = await json<{ protocol: number; workspaceRoots: string[]; eventPoll: boolean }>(response);
    expect(health.protocol).toBe(1);
    expect(health.workspaceRoots).toContain(harness.root);
    expect(health.eventPoll).toBe(true);
  });

  it("streams CLI events and resolves permission requests", async () => {
    const runner = new FakeRunner({
      onRun: async (_request, callbacks) => {
        callbacks.onMessage({
          type: "system",
          subtype: "init",
          session_id: "s-1",
          model: "test",
          tools: [],
        });
        const decision = await callbacks.onPermission({
          requestId: "req-1",
          toolUseID: "tool-1",
          toolName: "Bash",
          input: { command: "ls" },
        });
        callbacks.onMessage({
          type: "result",
          subtype: "success",
          is_error: false,
          result: decision.behavior,
          session_id: "s-1",
        });
        return { exitCode: 0, sessionId: "s-1" };
      },
    });
    const harness = await startServer(runner);
    const controller = new AbortController();
    const eventsResponse = await fetch(`${harness.baseUrl}/v1/events`, {
      headers: authHeaders(harness.token),
      signal: controller.signal,
    });
    expect(eventsResponse.status).toBe(200);
    const sse = new SseReader(eventsResponse);

    const turnResponse = await fetch(`${harness.baseUrl}/v1/turns`, {
      method: "POST",
      headers: authHeaders(harness.token),
      body: JSON.stringify({ cwd: harness.root, prompt: "run ls" }),
    });
    expect(turnResponse.status).toBe(202);
    const turn = await json<{ turnId: string }>(turnResponse);

    let permission:
      | { event: string; data: Record<string, unknown> }
      | undefined;
    let resultLine = "";
    let finished = false;
    while (!finished) {
      const event = await sse.next();
      if (event.event === "permission") permission = event;
      if (event.event === "cli") resultLine += String(event.data.line ?? "");
      if (event.event === "turn" && event.data.phase === "done") finished = true;
      if (permission && event.event === "permission") {
        const decision = await fetch(
          `${harness.baseUrl}/v1/turns/${encodeURIComponent(turn.turnId)}/permission`,
          {
            method: "POST",
            headers: authHeaders(harness.token),
            body: JSON.stringify({ requestId: "req-1", behavior: "allow" }),
          },
        );
        expect(decision.status).toBe(200);
      }
    }
    expect(permission).toBeDefined();
    expect(resultLine).toContain('"result":"allow"');
    controller.abort();
  });

  it("browses, uploads, downloads and deletes remote files", async () => {
    const harness = await startServer(new FakeRunner());
    const target = join(harness.root, "note.txt");
    const upload = await fetch(`${harness.baseUrl}/v1/fs?path=${encodeURIComponent(target)}`, {
      method: "PUT",
      headers: authHeaders(harness.token),
      body: "hello remote",
    });
    expect(upload.status).toBe(200);

    const listing = await fetch(
      `${harness.baseUrl}/v1/fs?path=${encodeURIComponent(harness.root)}`,
      { headers: authHeaders(harness.token) },
    );
    const entries = await json<{ entries: Array<{ name: string }> }>(listing);
    expect(entries.entries.map((e) => e.name)).toContain("note.txt");

    const download = await fetch(
      `${harness.baseUrl}/v1/fs?op=download&path=${encodeURIComponent(target)}`,
      { headers: authHeaders(harness.token) },
    );
    expect(await download.text()).toBe("hello remote");

    const removed = await fetch(`${harness.baseUrl}/v1/fs?path=${encodeURIComponent(target)}`, {
      method: "DELETE",
      headers: authHeaders(harness.token),
    });
    expect(removed.status).toBe(200);
  });

  it("rejects file access outside the configured roots", async () => {
    const harness = await startServer(new FakeRunner());
    const outside = tempDir();
    try {
      const response = await fetch(
        `${harness.baseUrl}/v1/fs?path=${encodeURIComponent(outside)}`,
        { headers: authHeaders(harness.token) },
      );
      expect(response.status).toBe(403);
      const upload = await fetch(
        `${harness.baseUrl}/v1/fs?path=${encodeURIComponent(join(outside, "x"))}`,
        { method: "PUT", headers: authHeaders(harness.token), body: "x" },
      );
      expect(upload.status).toBe(403);
    } finally {
      cleanup(outside);
    }
  });

  it("reports tunnel state and refuses to start an unconfigured tunnel", async () => {
    const harness = await startServer(new FakeRunner());
    const status = await fetch(`${harness.baseUrl}/v1/tunnel`, {
      headers: authHeaders(harness.token),
    });
    expect(await json<{ state: string }>(status)).toMatchObject({ state: "off" });
    const start = await fetch(`${harness.baseUrl}/v1/tunnel`, {
      method: "POST",
      headers: authHeaders(harness.token),
      body: JSON.stringify({ action: "start" }),
    });
    expect(start.status).toBe(409);
  });
});

describe("event polling fallback", () => {
  it("replays buffered events as one bounded JSON batch", async () => {
    const harness = await startServer(new FakeRunner());
    const started = await fetch(`${harness.baseUrl}/v1/turns`, {
      method: "POST",
      headers: authHeaders(harness.token),
      body: JSON.stringify({ cwd: harness.root, prompt: "你好" }),
    });
    expect(started.status).toBe(202);

    const response = await fetch(`${harness.baseUrl}/v1/events/batch?since=0&timeout=1000`, {
      headers: authHeaders(harness.token),
    });
    expect(response.status).toBe(200);
    const body = await json<{
      events: Array<{ kind: string }>;
      latestSeq: number;
    }>(response);
    expect(body.events[0]?.kind).toBe("turn");
    expect(body.events.some((event) => event.kind === "cli")).toBe(true);
    expect(body.latestSeq).toBeGreaterThan(0);
  });

  it("long-polls for a new event and reports the daemon sequence head", async () => {
    const harness = await startServer(new FakeRunner());
    const pending = fetch(
      `${harness.baseUrl}/v1/events/batch?since=${harness.bus.latestSeq()}&timeout=5_000`,
      { headers: authHeaders(harness.token) },
    );
    const published = harness.bus.publish({
      kind: "tunnel",
      tunnel: { mode: "off", state: "off", changedAt: 1 },
    });
    const body = await json<{
      events: Array<{ kind: string; seq: number }>;
      latestSeq: number;
    }>(await pending);
    expect(body.events.map((event) => event.seq)).toContain(published.seq);
    expect(body.latestSeq).toBe(published.seq);

    const ahead = await fetch(`${harness.baseUrl}/v1/events/batch?since=999999&timeout=20`, {
      headers: authHeaders(harness.token),
    });
    const empty = await json<{ events: unknown[]; latestSeq: number }>(ahead);
    expect(empty.events).toEqual([]);
    // A seq ahead of the daemon's head means the app survived a daemon restart.
    expect(empty.latestSeq).toBeLessThan(999_999);
  });
});
