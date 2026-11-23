import { describe, expect, it } from "vitest";
import type { RunCallbacks, RunRequest } from "../src/runner.js";
import { buildQueryOptions, TURN_SETTINGS } from "../src/sdk-runner.js";

function makeRequest(overrides: Partial<RunRequest> = {}): RunRequest {
  return {
    cwd: "/tmp/work",
    prompt: "hello",
    permissionMode: "default",
    signal: new AbortController().signal,
    ...overrides,
  };
}

const noopCallbacks: RunCallbacks = {
  onMessage: () => {},
  onPermission: async () => ({ behavior: "deny", message: "no" }),
};

describe("buildQueryOptions", () => {
  it("disables WebFetch's domain preflight for daemon turns", () => {
    // The daemon rarely inherits an interactive shell's proxy; without this the
    // host's own `claude` fetches fine while every remote WebFetch fails before
    // the target page is requested.
    const options = buildQueryOptions(makeRequest(), noopCallbacks, new AbortController());
    expect(options.settings).toEqual(TURN_SETTINGS);
    expect(TURN_SETTINGS.skipWebFetchPreflight).toBe(true);
  });

  it("forwards cwd, resume, model and permission mode", () => {
    const options = buildQueryOptions(
      makeRequest({
        cwd: "/srv/project",
        resumeSessionId: "session-9",
        model: "claude-sonnet-5-5",
        permissionMode: "auto",
      }),
      noopCallbacks,
      new AbortController(),
    );
    expect(options.cwd).toBe("/srv/project");
    expect(options.resume).toBe("session-9");
    expect(options.model).toBe("claude-sonnet-5-5");
    expect(options.permissionMode).toBe("auto");
    expect(options.includePartialMessages).toBe(true);
  });

  it("only allows bypass when the host already opted in", () => {
    const request = makeRequest({ permissionMode: "bypassPermissions" });
    const options = buildQueryOptions(request, noopCallbacks, new AbortController());
    expect(options.allowDangerouslySkipPermissions).toBe(true);
    const safe = buildQueryOptions(makeRequest(), noopCallbacks, new AbortController());
    expect(safe.allowDangerouslySkipPermissions).toBeUndefined();
  });
});
