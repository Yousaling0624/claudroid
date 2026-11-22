import type { EventBus } from "./events.js";
import type { PermissionAsk, PermissionDecision } from "./types.js";

interface Pending {
  finish: (decision: PermissionDecision) => void;
  timer: NodeJS.Timeout;
  signal: AbortSignal;
  onAbort: () => void;
}

function keyOf(turnId: string, requestId: string): string {
  return `${turnId}\u0000${requestId}`;
}

/**
 * Bridges the Agent SDK's canUseTool callback to the phone. Every request fails
 * closed: a timeout, turn cancellation or disconnect denies the tool call.
 */
export class PermissionBroker {
  private readonly pending = new Map<string, Pending>();

  constructor(
    private readonly bus: EventBus,
    private readonly timeoutMs: number,
  ) {}

  ask(
    turnId: string,
    owner: string,
    ask: PermissionAsk,
    signal: AbortSignal,
  ): Promise<PermissionDecision> {
    const key = keyOf(turnId, ask.requestId);
    const expiresAt = Date.now() + this.timeoutMs;
    this.bus.publish({
      kind: "permission",
      turnId,
      owner,
      requestId: ask.requestId,
      toolUseID: ask.toolUseID,
      toolName: ask.toolName,
      input: ask.input,
      suggestions: ask.suggestions,
      title: ask.title,
      displayName: ask.displayName,
      description: ask.description,
      decisionReason: ask.decisionReason,
      expiresAt,
    });

    return new Promise<PermissionDecision>((resolve) => {
      let settled = false;
      const finish = (decision: PermissionDecision): void => {
        if (settled) return;
        settled = true;
        this.pending.delete(key);
        clearTimeout(timer);
        signal.removeEventListener("abort", onAbort);
        resolve(decision);
      };
      const onAbort = (): void => {
        finish({ behavior: "deny", message: "任务已取消，未执行该工具。" });
      };
      const timer = setTimeout(() => {
        finish({ behavior: "deny", message: "主机等待手机确认超时，已拒绝。" });
      }, this.timeoutMs);
      this.pending.set(key, { finish, timer, signal, onAbort });
      if (signal.aborted) onAbort();
      else signal.addEventListener("abort", onAbort, { once: true });
    });
  }

  respond(turnId: string, requestId: string, decision: PermissionDecision): boolean {
    const current = this.pending.get(keyOf(turnId, requestId));
    if (!current) return false;
    current.finish(decision);
    return true;
  }

  cancelTurn(turnId: string): void {
    const prefix = `${turnId}\u0000`;
    for (const [key, pending] of this.pending) {
      if (key.startsWith(prefix)) {
        pending.finish({ behavior: "deny", message: "任务已取消，未执行该工具。" });
      }
    }
  }

  pendingCount(turnId: string): number {
    const prefix = `${turnId}\u0000`;
    let count = 0;
    for (const key of this.pending.keys()) if (key.startsWith(prefix)) count += 1;
    return count;
  }
}
