import { randomUUID } from "node:crypto";
import type { BridgeConfig } from "./config.js";
import { HttpError } from "./errors.js";
import type { EventBus } from "./events.js";
import type { FileService } from "./files.js";
import type { PermissionBroker } from "./permissions.js";
import type { ClaudeRunner } from "./runner.js";
import {
  SAFE_PERMISSION_MODES,
  type PermissionDecision,
  type PermissionMode,
  type TurnSnapshot,
  type TurnStartRequest,
} from "./types.js";

const MAX_PROMPT_CHARS = 1_000_000;
const FINISHED_TURN_TTL_MS = 30 * 60_000;

interface TurnState {
  id: string;
  deviceId: string;
  request: TurnStartRequest;
  cwd: string;
  status: TurnSnapshot["status"];
  exitCode?: number;
  sessionId?: string;
  error?: string;
  startedAt: number;
  finishedAt?: number;
  controller: AbortController;
}

export interface TurnManagerDeps {
  runner: ClaudeRunner;
  bus: EventBus;
  permissions: PermissionBroker;
  files: FileService;
  config: BridgeConfig;
}

/**
 * A bare "不支持的权限模式" leaves the phone with no clue that the host merely needs
 * an upgrade. Name the modes this daemon accepts (and keep 400, not 500).
 */
export function unsupportedPermissionModeError(
  mode: string,
  allowed: readonly PermissionMode[],
): HttpError {
  const raw = String(mode).slice(0, 40);
  return new HttpError(
    `不支持的权限模式「${raw}」。当前 claudroid-bridge 支持：${allowed.join("、")}。` +
      "请升级 claudroid-bridge 到最新版本（npm install -g claudroid-bridge），" +
      "或改用上面列出的权限模式。",
    400,
  );
}

export class TurnManager {
  private readonly turns = new Map<string, TurnState>();

  constructor(private readonly deps: TurnManagerDeps) {}

  async start(deviceId: string, request: TurnStartRequest): Promise<TurnSnapshot> {
    this.prune();
    if (request === null || typeof request !== "object") {
      throw new HttpError("请求格式无效。", 400);
    }
    if (typeof request.cwd !== "string" || !request.cwd) {
      throw new HttpError("缺少工作目录 cwd。", 400);
    }
    if (typeof request.prompt !== "string" || request.prompt.trim().length === 0) {
      throw new HttpError("消息不能为空。", 400);
    }
    if (request.prompt.length > MAX_PROMPT_CHARS) {
      throw new HttpError("消息过长。", 413);
    }
    const permissionMode: PermissionMode = request.permissionMode ?? "default";
    const allowed: PermissionMode[] = this.deps.config.allowBypassPermissions
      ? [...SAFE_PERMISSION_MODES, "bypassPermissions"]
      : SAFE_PERMISSION_MODES;
    if (!allowed.includes(permissionMode)) {
      if (permissionMode === "bypassPermissions") {
        throw new HttpError("主机没有允许 bypassPermissions。", 403);
      }
      throw unsupportedPermissionModeError(String(permissionMode), allowed);
    }
    for (const turn of this.turns.values()) {
      if (turn.deviceId === deviceId && turn.status === "running") {
        throw new HttpError("该设备已有任务正在执行。", 409);
      }
    }
    const { path: cwd, stats } = await this.deps.files.resolveExisting(request.cwd).catch((error) => {
      if (error && typeof error === "object" && "status" in error) throw error;
      throw new HttpError("工作目录无效。", 400);
    });
    if (!stats.isDirectory()) throw new HttpError("cwd 不是目录。", 400);
    if (request.resumeSessionId !== undefined && typeof request.resumeSessionId !== "string") {
      throw new HttpError("resumeSessionId 格式无效。", 400);
    }
    if (request.model !== undefined && (typeof request.model !== "string" || request.model.length > 200)) {
      throw new HttpError("model 格式无效。", 400);
    }

    const state: TurnState = {
      id: randomUUID(),
      deviceId,
      request: { ...request, cwd, permissionMode },
      cwd,
      status: "running",
      startedAt: Date.now(),
      controller: new AbortController(),
    };
    this.turns.set(state.id, state);
    this.deps.bus.publish({
      kind: "turn",
      turnId: state.id,
      owner: deviceId,
      phase: "started",
    });
    void this.execute(state);
    return this.toSnapshot(state);
  }

  private async execute(state: TurnState): Promise<void> {
    try {
      const outcome = await this.deps.runner.run(
        {
          cwd: state.cwd,
          prompt: state.request.prompt,
          resumeSessionId: state.request.resumeSessionId,
          permissionMode: state.request.permissionMode ?? "default",
          model: state.request.model,
          executablePath: this.deps.config.claudeExecutable,
          signal: state.controller.signal,
        },
        {
          onMessage: (message) => {
            if (state.status !== "running") return;
            let line: string;
            try {
              line = JSON.stringify(message);
            } catch {
              line = JSON.stringify({
                type: "system",
                subtype: "error",
                message: "无法序列化 CLI 消息。",
              });
            }
            this.deps.bus.publish({
              kind: "cli",
              turnId: state.id,
              owner: state.deviceId,
              line,
            });
          },
          onPermission: (ask, signal) =>
            this.deps.permissions.ask(
              state.id,
              state.deviceId,
              ask,
              signal ?? state.controller.signal,
            ),
        },
      );
      this.finish(state, outcome.exitCode, outcome.sessionId, outcome.error);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      this.finish(state, 1, undefined, message);
    }
  }

  private finish(
    state: TurnState,
    exitCode: number,
    sessionId?: string,
    error?: string,
  ): void {
    if (state.status !== "running") return;
    state.finishedAt = Date.now();
    if (sessionId) state.sessionId = sessionId;
    if (exitCode === -2) {
      state.status = "cancelled";
      state.exitCode = -2;
    } else if (exitCode === 0) {
      state.status = "done";
      state.exitCode = 0;
    } else {
      state.status = "error";
      state.exitCode = exitCode;
      state.error = error ?? "任务执行失败。";
    }
    this.deps.permissions.cancelTurn(state.id);
    this.deps.bus.publish({
      kind: "turn",
      turnId: state.id,
      owner: state.deviceId,
      phase: state.status === "error" ? "error" : "done",
      exitCode: state.exitCode,
      error: state.error,
    });
  }

  cancel(turnId: string, deviceId: string): TurnSnapshot {
    const state = this.requireTurn(turnId, deviceId);
    if (state.status !== "running") return this.toSnapshot(state);
    state.controller.abort();
    this.deps.permissions.cancelTurn(state.id);
    // The SDK normally settles immediately; this guard keeps a wedged process from
    // leaving the phone stuck in "running" forever.
    const fallback = setTimeout(() => this.finish(state, -2), 15_000);
    fallback.unref();
    return this.toSnapshot(state);
  }

  respondPermission(
    turnId: string,
    deviceId: string,
    requestId: string,
    decision: PermissionDecision,
  ): void {
    const state = this.requireTurn(turnId, deviceId);
    if (state.status !== "running") throw new HttpError("任务已结束。", 409);
    if (typeof requestId !== "string" || !requestId) {
      throw new HttpError("缺少 requestId。", 400);
    }
    if (decision === null || typeof decision !== "object") {
      throw new HttpError("决定格式无效。", 400);
    }
    if (decision.behavior !== "allow" && decision.behavior !== "deny") {
      throw new HttpError("决定必须是 allow 或 deny。", 400);
    }
    const delivered = this.deps.permissions.respond(turnId, requestId, decision);
    if (!delivered) throw new HttpError("确认请求已过期或不存在。", 404);
  }

  snapshot(turnId: string, deviceId: string): TurnSnapshot {
    return this.toSnapshot(this.requireTurn(turnId, deviceId));
  }

  runningCount(): number {
    let count = 0;
    for (const turn of this.turns.values()) if (turn.status === "running") count += 1;
    return count;
  }

  close(): void {
    for (const state of this.turns.values()) {
      if (state.status === "running") {
        state.controller.abort();
        this.deps.permissions.cancelTurn(state.id);
        this.finish(state, -2);
      }
    }
  }

  private requireTurn(turnId: string, deviceId: string): TurnState {
    const state = this.turns.get(turnId);
    if (!state) throw new HttpError("任务不存在。", 404);
    if (state.deviceId !== deviceId) throw new HttpError("无权访问该任务。", 403);
    return state;
  }

  private toSnapshot(state: TurnState): TurnSnapshot {
    return {
      turnId: state.id,
      status: state.status,
      exitCode: state.exitCode,
      sessionId: state.sessionId,
      error: state.error,
      startedAt: state.startedAt,
      finishedAt: state.finishedAt,
    };
  }

  private prune(): void {
    const cutoff = Date.now() - FINISHED_TURN_TTL_MS;
    for (const [id, state] of this.turns) {
      if (state.status !== "running" && (state.finishedAt ?? 0) < cutoff) {
        this.turns.delete(id);
      }
    }
  }
}
