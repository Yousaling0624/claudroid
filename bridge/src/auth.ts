import { createHash, randomBytes, randomUUID, timingSafeEqual } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import { writeFilePrivate } from "./config.js";

export interface DeviceRecord {
  id: string;
  name: string;
  tokenHash: string;
  createdAt: number;
  lastSeenAt: number;
}

export interface PublicDevice {
  id: string;
  name: string;
  createdAt: number;
  lastSeenAt: number;
}

export interface PairingInfo {
  code: string;
  expiresAt: number;
}

const CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const CODE_LENGTH = 8;
const LAST_SEEN_WRITE_MS = 60_000;

export class AuthError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
    this.name = "AuthError";
  }
}

function hashToken(token: string): string {
  return createHash("sha256").update(token, "utf8").digest("hex");
}

function constantTimeEqual(a: string, b: string): boolean {
  const left = Buffer.from(a, "utf8");
  const right = Buffer.from(b, "utf8");
  if (left.length !== right.length) return false;
  return timingSafeEqual(left, right);
}

function newPairingCode(): string {
  const bytes = randomBytes(CODE_LENGTH);
  let out = "";
  for (let i = 0; i < CODE_LENGTH; i += 1) {
    out += CODE_ALPHABET[bytes[i]! % CODE_ALPHABET.length];
  }
  return out;
}

export class DeviceStore {
  private devices: DeviceRecord[];

  constructor(private readonly path: string) {
    this.devices = this.load();
  }

  private load(): DeviceRecord[] {
    if (!existsSync(this.path)) return [];
    try {
      const parsed = JSON.parse(readFileSync(this.path, "utf8"));
      if (!Array.isArray(parsed.devices)) return [];
      return parsed.devices.filter(
        (d: unknown): d is DeviceRecord =>
          d !== null &&
          typeof d === "object" &&
          typeof (d as DeviceRecord).id === "string" &&
          typeof (d as DeviceRecord).name === "string" &&
          typeof (d as DeviceRecord).tokenHash === "string" &&
          typeof (d as DeviceRecord).createdAt === "number",
      );
    } catch {
      // A corrupted device list must not lock the operator out of the daemon.
      return [];
    }
  }

  private save(): void {
    writeFilePrivate(this.path, `${JSON.stringify({ devices: this.devices }, null, 2)}\n`);
  }

  list(): PublicDevice[] {
    return this.devices.map(({ id, name, createdAt, lastSeenAt }) => ({
      id,
      name,
      createdAt,
      lastSeenAt,
    }));
  }

  add(device: DeviceRecord): void {
    this.devices.push(device);
    this.save();
  }

  remove(id: string): boolean {
    const before = this.devices.length;
    this.devices = this.devices.filter((d) => d.id !== id);
    if (this.devices.length === before) return false;
    this.save();
    return true;
  }

  findByToken(token: string): DeviceRecord | undefined {
    const hash = hashToken(token);
    return this.devices.find((d) => constantTimeEqual(d.tokenHash, hash));
  }

  touch(id: string, now: number): void {
    const device = this.devices.find((d) => d.id === id);
    if (!device) return;
    // Event polling authenticates several times per second; persisting every request
    // would rewrite devices.json constantly. Minute granularity is plenty for "最近".
    if (now - device.lastSeenAt < LAST_SEEN_WRITE_MS) return;
    device.lastSeenAt = now;
    try {
      this.save();
    } catch {
      // Last-seen bookkeeping must never fail an authenticated request.
    }
  }
}

interface AttemptWindow {
  count: number;
  resetAt: number;
}

export interface AuthOptions {
  pairingTtlMs?: number;
  maxAttempts?: number;
  attemptWindowMs?: number;
}

export class AuthService {
  private pairing: PairingInfo | null = null;
  private readonly attempts = new Map<string, AttemptWindow>();
  private readonly pairingTtlMs: number;
  private readonly maxAttempts: number;
  private readonly attemptWindowMs: number;

  constructor(
    private readonly store: DeviceStore,
    options: AuthOptions = {},
  ) {
    this.pairingTtlMs = options.pairingTtlMs ?? 10 * 60_000;
    this.maxAttempts = options.maxAttempts ?? 5;
    this.attemptWindowMs = options.attemptWindowMs ?? 10 * 60_000;
  }

  issuePairingCode(now = Date.now()): PairingInfo {
    this.pairing = { code: newPairingCode(), expiresAt: now + this.pairingTtlMs };
    return this.pairing;
  }

  currentPairingInfo(now = Date.now()): PairingInfo | null {
    if (this.pairing && this.pairing.expiresAt <= now) this.pairing = null;
    return this.pairing;
  }

  private allowed(ip: string, now: number): boolean {
    const window = this.attempts.get(ip);
    if (!window || window.resetAt <= now) return true;
    return window.count < this.maxAttempts;
  }

  private recordFailure(ip: string, now: number): void {
    const window = this.attempts.get(ip);
    if (!window || window.resetAt <= now) {
      this.attempts.set(ip, { count: 1, resetAt: now + this.attemptWindowMs });
      return;
    }
    window.count += 1;
  }

  pair(
    code: string,
    deviceName: string,
    ip: string,
    now = Date.now(),
  ): { token: string; device: PublicDevice } {
    if (!this.allowed(ip, now)) {
      throw new AuthError("配对尝试过于频繁，请稍后再试。", 429);
    }
    const pairing = this.currentPairingInfo(now);
    const normalized = code.trim().toUpperCase();
    if (
      !pairing ||
      normalized.length !== CODE_LENGTH ||
      !constantTimeEqual(normalized, pairing.code)
    ) {
      this.recordFailure(ip, now);
      throw new AuthError("配对码无效或已过期。", 401);
    }
    const token = randomBytes(32).toString("base64url");
    const record: DeviceRecord = {
      id: randomUUID(),
      name: deviceName.trim().slice(0, 64) || "Android",
      tokenHash: hashToken(token),
      createdAt: now,
      lastSeenAt: now,
    };
    this.store.add(record);
    // Single use: the operator gets a fresh code for the next device.
    this.pairing = null;
    this.attempts.delete(ip);
    return {
      token,
      device: {
        id: record.id,
        name: record.name,
        createdAt: record.createdAt,
        lastSeenAt: record.lastSeenAt,
      },
    };
  }

  verify(token: string, now = Date.now()): DeviceRecord | null {
    if (!token) return null;
    const device = this.store.findByToken(token);
    if (device) this.store.touch(device.id, now);
    return device ?? null;
  }

  listDevices(): PublicDevice[] {
    return this.store.list();
  }

  revokeDevice(id: string): boolean {
    return this.store.remove(id);
  }
}
