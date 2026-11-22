import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { ClaudeRunner, RunCallbacks, RunOutcome, RunRequest } from "../src/runner.js";

export function tempDir(prefix = "claudroid-bridge-test-"): string {
  return mkdtempSync(join(tmpdir(), prefix));
}

export function cleanup(path: string): void {
  rmSync(path, { recursive: true, force: true });
}

export async function waitFor<T>(
  fn: () => T | Promise<T>,
  timeoutMs = 3_000,
  intervalMs = 20,
): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  let lastError: unknown;
  for (;;) {
    try {
      const value = await fn();
      if (value) return value;
    } catch (error) {
      lastError = error;
    }
    if (Date.now() >= deadline) {
      throw new Error(`waitFor 超时${lastError ? `: ${String(lastError)}` : ""}`);
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
}

export interface FakeRunnerOptions {
  onRun?: (request: RunRequest, callbacks: RunCallbacks) => Promise<RunOutcome> | RunOutcome;
}

export class FakeRunner implements ClaudeRunner {
  readonly requests: RunRequest[] = [];
  private readonly onRun?: FakeRunnerOptions["onRun"];

  constructor(options: FakeRunnerOptions = {}) {
    this.onRun = options.onRun;
  }

  async run(request: RunRequest, callbacks: RunCallbacks): Promise<RunOutcome> {
    this.requests.push(request);
    if (this.onRun) return this.onRun(request, callbacks);
    callbacks.onMessage({
      type: "system",
      subtype: "init",
      session_id: "session-1",
      model: "test-model",
      tools: [],
    });
    callbacks.onMessage({
      type: "result",
      subtype: "success",
      is_error: false,
      result: "ok",
      session_id: "session-1",
      total_cost_usd: 0,
      duration_ms: 1,
      num_turns: 1,
    });
    return { exitCode: 0, sessionId: "session-1" };
  }
}
