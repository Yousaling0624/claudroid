import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  assetFor,
  ensureCloudflared,
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

describe("ensureCloudflared", () => {
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
