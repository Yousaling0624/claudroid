import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { AuthService, DeviceStore } from "../src/auth.js";
import { cleanup, tempDir } from "./helpers.js";

const dirs: string[] = [];

function authService(): { auth: AuthService; dir: string } {
  const dir = tempDir();
  dirs.push(dir);
  const auth = new AuthService(new DeviceStore(join(dir, "devices.json")), {
    pairingTtlMs: 60_000,
    maxAttempts: 2,
    attemptWindowMs: 60_000,
  });
  return { auth, dir };
}

afterEach(() => {
  while (dirs.length > 0) cleanup(dirs.pop()!);
});

describe("AuthService", () => {
  it("pairs once with the current code and verifies the token", () => {
    const { auth } = authService();
    const info = auth.issuePairingCode();
    expect(info.code).toHaveLength(8);
    const paired = auth.pair(info.code.toLowerCase(), "  Pixel  ", "1.2.3.4");
    expect(paired.device.name).toBe("Pixel");
    expect(auth.verify(paired.token)?.id).toBe(paired.device.id);
    expect(auth.verify("wrong-token")).toBeNull();
  });

  it("invalidates the code after one successful pairing", () => {
    const { auth } = authService();
    const info = auth.issuePairingCode();
    auth.pair(info.code, "One", "1.1.1.1");
    expect(() => auth.pair(info.code, "Two", "1.1.1.1")).toThrow(/无效|过期/);
  });

  it("expires codes", () => {
    const { auth } = authService();
    const info = auth.issuePairingCode(1_000);
    expect(() => auth.pair(info.code, "Late", "1.1.1.1", 1_000 + 60_001)).toThrow(/无效|过期/);
  });

  it("rate limits failed attempts per address", () => {
    const { auth } = authService();
    auth.issuePairingCode();
    expect(() => auth.pair("AAAAAAAA", "Bad", "9.9.9.9")).toThrow(/无效|过期/);
    expect(() => auth.pair("BBBBBBBB", "Bad", "9.9.9.9")).toThrow(/无效|过期/);
    expect(() => auth.pair("CCCCCCCC", "Bad", "9.9.9.9")).toThrow(/频繁/);
  });

  it("persists devices and revokes them", () => {
    const { auth, dir } = authService();
    const info = auth.issuePairingCode();
    const paired = auth.pair(info.code, "Tablet", "1.1.1.1");
    const reloaded = new AuthService(new DeviceStore(join(dir, "devices.json")));
    expect(reloaded.verify(paired.token)).not.toBeNull();
    expect(reloaded.listDevices()).toHaveLength(1);
    expect(reloaded.revokeDevice(paired.device.id)).toBe(true);
    expect(reloaded.verify(paired.token)).toBeNull();
  });
});
