import { query } from "@anthropic-ai/claude-agent-sdk";
import type {
  Options,
  PermissionMode as SdkPermissionMode,
  PermissionResult,
  PermissionUpdate,
} from "@anthropic-ai/claude-agent-sdk";
import { randomUUID } from "node:crypto";
import type { PermissionMode } from "./types.js";
import type { ClaudeRunner, RunCallbacks, RunOutcome, RunRequest } from "./runner.js";

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object";
}

function syntheticErrorResult(message: string, sessionId: string | undefined): Record<string, unknown> {
  return {
    type: "result",
    subtype: "error_during_execution",
    is_error: true,
    duration_ms: 0,
    duration_api_ms: 0,
    num_turns: 0,
    result: message,
    session_id: sessionId ?? "",
    total_cost_usd: 0,
    usage: {
      input_tokens: 0,
      output_tokens: 0,
      cache_creation_input_tokens: 0,
      cache_read_input_tokens: 0,
    },
    uuid: randomUUID(),
  };
}

/**
 * WebFetch asks `api.anthropic.com/api/web/domain_info` before every fetch and
 * refuses the page when that call fails. A daemon started by systemd/launchd
 * does not inherit the interactive shell's proxy, so the host's own `claude`
 * can fetch while the SDK turn cannot; the target page itself is usually
 * reachable. The local engine writes the same flag into the jail's
 * settings.json (CliSettings.kt); `settings` here is the SDK's flag-settings
 * layer, so the host's files and credentials stay untouched.
 */
export const TURN_SETTINGS = { skipWebFetchPreflight: true } as const;

/** Pure options builder, exported so the settings merge is covered by tests. */
export function buildQueryOptions(
  request: RunRequest,
  callbacks: RunCallbacks,
  abortController: AbortController,
): Options {
  return {
    cwd: request.cwd,
    resume: request.resumeSessionId,
    model: request.model,
    permissionMode: request.permissionMode as SdkPermissionMode,
    allowDangerouslySkipPermissions:
      request.permissionMode === "bypassPermissions" ? true : undefined,
    includePartialMessages: true,
    abortController,
    pathToClaudeCodeExecutable: request.executablePath,
    settings: TURN_SETTINGS,
    canUseTool: async (toolName, input, options): Promise<PermissionResult> => {
      const combinedSignal = AbortSignal.any([request.signal, options.signal]);
      const decision = await callbacks.onPermission(
        {
          requestId: options.requestId,
          toolUseID: options.toolUseID,
          toolName,
          input,
          suggestions: options.suggestions,
          title: options.title,
          displayName: options.displayName,
          description: options.description,
          decisionReason: options.decisionReason,
        },
        combinedSignal,
      );
      if (decision.behavior === "allow") {
        return {
          behavior: "allow",
          updatedInput: input,
          updatedPermissions:
            decision.always && options.suggestions
              ? (options.suggestions as PermissionUpdate[])
              : undefined,
          toolUseID: options.toolUseID,
        };
      }
      return {
        behavior: "deny",
        message: decision.message ?? "用户拒绝了此操作。",
        toolUseID: options.toolUseID,
      };
    },
  };
}

/**
 * Runs one headless turn through the official Agent SDK. The SDK resolves the
 * matching native Claude Code binary for this host; callers may override it for
 * single-file builds.
 */
export class SdkClaudeRunner implements ClaudeRunner {
  async run(request: RunRequest, callbacks: RunCallbacks): Promise<RunOutcome> {
    const abortController = new AbortController();
    const onAbort = (): void => abortController.abort();
    if (request.signal.aborted) return { exitCode: -2 };
    request.signal.addEventListener("abort", onAbort, { once: true });

    let sessionId: string | undefined;
    let result: Record<string, unknown> | undefined;

    try {
      const q = query({
        prompt: request.prompt,
        options: buildQueryOptions(request, callbacks, abortController),
      });

      for await (const message of q) {
        if (isRecord(message)) {
          if (
            message.type === "system" &&
            message.subtype === "init" &&
            typeof message.session_id === "string"
          ) {
            sessionId = message.session_id;
          }
          if (message.type === "result") {
            result = message;
            if (typeof message.session_id === "string") sessionId = message.session_id;
          }
        }
        callbacks.onMessage(message);
      }
    } catch (error) {
      if (result) {
        // A late abort after the result must not turn a completed turn into a cancellation.
      } else if (request.signal.aborted) {
        return { exitCode: -2, sessionId };
      } else {
        const text = error instanceof Error ? error.message : String(error);
        callbacks.onMessage(syntheticErrorResult(text, sessionId));
        return { exitCode: 1, sessionId, error: text };
      }
    } finally {
      request.signal.removeEventListener("abort", onAbort);
    }

    if (result) {
      const isError = result.is_error === true;
      return {
        exitCode: isError ? 1 : 0,
        sessionId,
        error: isError ? String(result.result ?? "") : undefined,
      };
    }
    if (request.signal.aborted) return { exitCode: -2, sessionId };
    return { exitCode: 0, sessionId };
  }
}
