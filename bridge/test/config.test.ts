import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { loadConfig } from "../src/config.js";

const tempDirs: string[] = [];

function configFile(raw: unknown): string {
  const dir = mkdtempSync(join(tmpdir(), "claudroid-bridge-config-"));
  tempDirs.push(dir);
  const file = join(dir, "config.json");
  writeFileSync(file, JSON.stringify(raw));
  return file;
}

afterEach(() => {
  for (const dir of tempDirs.splice(0)) rmSync(dir, { recursive: true, force: true });
});

describe("loadConfig", () => {
  it("keeps the external tunnel mode and its public URL file", () => {
    const config = loadConfig(
      configFile({ tunnel: { mode: "external", publicUrlFile: "/tmp/claudroid/public-url" } }),
    );
    expect(config.tunnel.mode).toBe("external");
    expect(config.tunnel.publicUrlFile).toBe("/tmp/claudroid/public-url");
  });

  it("falls back to off for unknown tunnel modes", () => {
    const config = loadConfig(configFile({ tunnel: { mode: "carrier-pigeon" } }));
    expect(config.tunnel.mode).toBe("off");
  });

  it("drops an empty public URL file", () => {
    const config = loadConfig(configFile({ tunnel: { mode: "external", publicUrlFile: "" } }));
    expect(config.tunnel.publicUrlFile).toBeUndefined();
  });
});
