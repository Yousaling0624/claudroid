import type { PermissionAsk, PermissionDecision, PermissionMode } from "./types.js";

export interface RunRequest {
  cwd: string;
  prompt: string;
  resumeSessionId?: string;
  permissionMode: PermissionMode;
  model?: string;
  signal: AbortSignal;
  /** Optional Claude Code executable; the Agent SDK resolves its bundled binary by default. */
  executablePath?: string;
}

export interface RunCallbacks {
  /** Receives SDK messages in the same shapes the local CLI emits as stream-json. */
  onMessage(message: unknown): void;
  onPermission(ask: PermissionAsk, signal?: AbortSignal): Promise<PermissionDecision>;
}

export interface RunOutcome {
  exitCode: number;
  sessionId?: string;
  error?: string;
}

export interface ClaudeRunner {
  run(request: RunRequest, callbacks: RunCallbacks): Promise<RunOutcome>;
}
