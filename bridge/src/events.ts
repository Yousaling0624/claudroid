import type { BridgeEvent, PublishableEvent } from "./types.js";

interface Buffered {
  event: BridgeEvent;
  at: number;
}

export type EventListener = (event: BridgeEvent) => void;

function visibleTo(event: BridgeEvent, deviceId: string): boolean {
  return event.kind === "host" || event.kind === "tunnel" || event.owner === deviceId;
}

/**
 * Ordered event log with a bounded replay window. Per-turn events are visible only
 * to the device that started the turn; host/tunnel state is broadcast.
 */
export class EventBus {
  private seq = 0;
  private readonly buffer: Buffered[] = [];
  private readonly listeners = new Map<string, Set<EventListener>>();

  constructor(
    private readonly maxEvents = 5_000,
    private readonly maxAgeMs = 10 * 60_000,
  ) {}

  publish(event: PublishableEvent): BridgeEvent {
    this.seq += 1;
    const full = { ...event, seq: this.seq } as BridgeEvent;
    this.buffer.push({ event: full, at: Date.now() });
    this.trim();
    for (const [deviceId, listeners] of this.listeners) {
      if (!visibleTo(full, deviceId)) continue;
      for (const listener of listeners) {
        try {
          listener(full);
        } catch {
          // A broken SSE client must not stop the bus.
        }
      }
    }
    return full;
  }

  replaySince(since: number, deviceId: string): BridgeEvent[] {
    return this.buffer
      .map((b) => b.event)
      .filter((event) => event.seq > since && visibleTo(event, deviceId));
  }

  oldestSeq(): number {
    return this.buffer[0]?.event.seq ?? this.seq;
  }

  /** Newest sequence number issued by this process, even if the event was trimmed. */
  latestSeq(): number {
    return this.seq;
  }

  subscribe(deviceId: string, listener: EventListener): () => void {
    let set = this.listeners.get(deviceId);
    if (!set) {
      set = new Set();
      this.listeners.set(deviceId, set);
    }
    set.add(listener);
    return () => {
      const current = this.listeners.get(deviceId);
      if (!current) return;
      current.delete(listener);
      if (current.size === 0) this.listeners.delete(deviceId);
    };
  }

  private trim(): void {
    const cutoff = Date.now() - this.maxAgeMs;
    while (this.buffer.length > 0 && (this.buffer.length > this.maxEvents || this.buffer[0]!.at < cutoff)) {
      this.buffer.shift();
    }
  }
}
