import { chmodSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  assetFor,
  ensureCloudflared,
  locateCloudflared,
  managedCloudflaredPath,
  sha256File,
} from "../src/cloudflared.js";

describe("cloudflared assets", () => {
  it("maps supported platforms to pinned assets", () => {
    for (const [platform, arch] of [
      ["linux", "x64"],
      ["linux", "arm64"],
      ["darwin", "x64"],
      ["darwin", "arm64"],
      ["win32", "x64"],
    ]) {
      const asset = assetFor(platform, arch);
      expect(asset, `${platform}/${arch}`).not.toBeNull();
      expect(asset?.file).toMatch(/^cloudflared-/);
      expect(asset?.sha256).toMatch(/^[0-9a-f]{64}$/);
    }
  });

  it("rejects platforms without a release asset", () => {
    expect(assetFor("freebsd", "x64")).toBeNull();
    expect(assetFor("linux", "ia32")).toBeNull();
  });

  it("uses the managed bin directory for the connector", () => {
    expect(managedCloudflaredPath("/home/me/.config/claudroid-bridge", "linux")).toBe(
      "/home/me/.config/claudroid-bridge/bin/cloudflared",
    );
    expect(managedCloudflaredPath("/home/me/.config/claudroid-bridge", "win32")).toBe(
      "/home/me/.config/claudroid-bridge/bin/cloudflared.exe",
    );
  });
});

describe("locateCloudflared", () => {
  it("keeps an explicitly configured binary above the WSL connector", () => {
    expect(
      locateCloudflared({ mode: "quick", cloudflaredPath: "/opt/cloudflared" }, "linux", () => "cloudflared.exe"),
    ).toEqual({ path: "/opt/cloudflared", source: "config" });
  });

  it("prefers the Windows connector under WSL", () => {
    expect(locateCloudflared({ mode: "quick" }, "linux", () => "cloudflared.exe")).toEqual({
      path: "cloudflared.exe",
      source: "wsl-windows",
    });
  });

  it("falls back to the managed binary when no Windows connector is available", () => {
    const home = mkdtempSync(join(tmpdir(), "cloudflared-locate-"));
    const previous = process.env.CLAUDROID_BRIDGE_HOME;
    process.env.CLAUDROID_BRIDGE_HOME = home;
    const managed = managedCloudflaredPath(home, "linux");
    mkdirSync(dirname(managed), { recursive: true });
    writeFileSync(managed, "");
    chmodSync(managed, 0o755);
    try {
      expect(locateCloudflared({ mode: "quick" }, "linux", () => null)).toEqual({
        path: managed,
        source: "managed",
      });
    } finally {
      if (previous === undefined) delete process.env.CLAUDROID_BRIDGE_HOME;
      else process.env.CLAUDROID_BRIDGE_HOME = previous;
      rmSync(home, { recursive: true, force: true });
    }
  });
});

describe("ensureCloudflared", () => {
  it("uses the Windows connector under WSL and explains the choice", async () => {
    const logs: string[] = [];
    const path = await ensureCloudflared(
      { mode: "quick" },
      (message) => logs.push(message),
      "linux",
      "x64",
      () => "/mnt/c/Program Files/cloudflared/cloudflared.exe",
    );
    expect(path).toBe("/mnt/c/Program Files/cloudflared/cloudflared.exe");
    expect(logs.join("\n")).toMatch(/Windows 侧 cloudflared/);
  });

  it("keeps an explicitly configured binary", async () => {
    const path = await ensureCloudflared({ mode: "quick", cloudflaredPath: "/opt/cloudflared" });
    expect(path).toBe("/opt/cloudflared");
  });

  it("fails with an actionable message on unsupported platforms", async () => {
    const home = mkdtempSync(join(tmpdir(), "cloudflared-test-"));
    const previous = process.env.CLAUDROID_BRIDGE_HOME;
    process.env.CLAUDROID_BRIDGE_HOME = home;
    try {
      await expect(ensureCloudflared({ mode: "quick" }, () => undefined, "plan9", "vax")).rejects.toThrow(
        /手动安装/,
      );
    } finally {
      if (previous === undefined) delete process.env.CLAUDROID_BRIDGE_HOME;
      else process.env.CLAUDROID_BRIDGE_HOME = previous;
      rmSync(home, { recursive: true, force: true });
    }
  });
});

describe("sha256File", () => {
  it("hashes file contents", async () => {
    const dir = mkdtempSync(join(tmpdir(), "cloudflared-sha-"));
    const file = join(dir, "fixture.bin");
    writeFileSync(file, "claudroid");
    try {
      expect(await sha256File(file)).toBe(
        "a5b23e81d8fb6b3bc375cfb8cb94fca536c629335147d934bb194b7a64881ff5",
      );
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
