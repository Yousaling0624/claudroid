import { describe, expect, it } from "vitest";
import { EventBus } from "../src/events.js";
import type { BridgeEvent } from "../src/types.js";

const tunnel = (url: string) =>
  ({
    kind: "tunnel" as const,
    tunnel: { mode: "quick" as const, state: "up" as const, url, changedAt: 1 },
  });

describe("EventBus", () => {
  it("filters per-turn events by owning device but broadcasts host state", () => {
    const bus = new EventBus();
    const first = bus.publish({ kind: "turn", turnId: "t1", owner: "a", phase: "started" });
    const second = bus.publish({ kind: "turn", turnId: "t2", owner: "b", phase: "started" });
    const shared = bus.publish(tunnel("https://x.trycloudflare.com"));

    expect(bus.replaySince(0, "a").map((e) => e.seq)).toEqual([first.seq, shared.seq]);
    expect(bus.replaySince(0, "b").map((e) => e.seq)).toEqual([second.seq, shared.seq]);
    expect(bus.replaySince(shared.seq, "a")).toEqual([]);
  });

  it("delivers only visible events to a subscriber", () => {
    const bus = new EventBus();
    const received: BridgeEvent[] = [];
    const unsubscribe = bus.subscribe("a", (event) => received.push(event));
    bus.publish({ kind: "turn", turnId: "t1", owner: "b", phase: "started" });
    bus.publish({ kind: "turn", turnId: "t2", owner: "a", phase: "started" });
    bus.publish(tunnel("https://x.trycloudflare.com"));
    unsubscribe();
    bus.publish({ kind: "turn", turnId: "t3", owner: "a", phase: "started" });

    expect(received.map((e) => e.kind)).toEqual(["turn", "tunnel"]);
    expect(received).toHaveLength(2);
  });

  it("drops old events once the replay window is full", () => {
    const bus = new EventBus(2, 60_000);
    bus.publish(tunnel("https://one.trycloudflare.com"));
    bus.publish(tunnel("https://two.trycloudflare.com"));
    const third = bus.publish(tunnel("https://three.trycloudflare.com"));
    expect(bus.replaySince(0, "a").map((e) => e.seq)).toEqual([third.seq - 1, third.seq]);
  });
});
