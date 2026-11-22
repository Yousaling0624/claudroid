import { describe, expect, it } from "vitest";
import { EventBus } from "../src/events.js";
import { PermissionBroker } from "../src/permissions.js";
import type { BridgeEvent } from "../src/types.js";
import { waitFor } from "./helpers.js";

const ask = {
  requestId: "req-1",
  toolUseID: "tool-1",
  toolName: "Bash",
  input: { command: "rm -rf /tmp/x" },
};

function setup(timeoutMs = 1_000) {
  const bus = new EventBus();
  const events: BridgeEvent[] = [];
  bus.subscribe("device-1", (event) => events.push(event));
  return { bus, events, broker: new PermissionBroker(bus, timeoutMs) };
}

describe("PermissionBroker", () => {
  it("publishes the request and resolves an allow decision", async () => {
    const { broker, events } = setup();
    const pending = broker.ask("turn-1", "device-1", ask, new AbortController().signal);
    await waitFor(() => events.length === 1);
    expect(events[0]).toMatchObject({ kind: "permission", turnId: "turn-1", requestId: "req-1" });
    expect(broker.respond("turn-1", "req-1", { behavior: "allow", always: true })).toBe(true);
    await expect(pending).resolves.toEqual({ behavior: "allow", always: true });
    expect(broker.respond("turn-1", "req-1", { behavior: "deny" })).toBe(false);
  });

  it("fails closed on timeout", async () => {
    const { broker } = setup(20);
    const decision = await broker.ask("turn-1", "device-1", ask, new AbortController().signal);
    expect(decision.behavior).toBe("deny");
    if (decision.behavior === "deny") expect(decision.message).toMatch(/超时/);
  });

  it("fails closed when the turn is cancelled", async () => {
    const { broker } = setup();
    const controller = new AbortController();
    const pending = broker.ask("turn-1", "device-1", ask, controller.signal);
    controller.abort();
    await expect(pending).resolves.toMatchObject({ behavior: "deny" });
  });

  it("denies all pending requests for a cancelled turn", async () => {
    const { broker } = setup();
    const signal = new AbortController().signal;
    const one = broker.ask("turn-1", "device-1", ask, signal);
    const two = broker.ask("turn-1", "device-1", { ...ask, requestId: "req-2" }, signal);
    broker.cancelTurn("turn-1");
    await expect(one).resolves.toMatchObject({ behavior: "deny" });
    await expect(two).resolves.toMatchObject({ behavior: "deny" });
    expect(broker.pendingCount("turn-1")).toBe(0);
  });

  it("rejects an already-aborted signal", async () => {
    const { broker } = setup();
    const controller = new AbortController();
    controller.abort();
    await expect(
      broker.ask("turn-1", "device-1", ask, controller.signal),
    ).resolves.toMatchObject({ behavior: "deny" });
  });
});
