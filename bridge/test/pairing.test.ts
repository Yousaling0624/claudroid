import { describe, expect, it } from "vitest";
import { buildPairingPayload, selectPairingUrl } from "../src/pairing.js";
import { renderTerminalQr } from "../src/qr.js";

describe("buildPairingPayload", () => {
  it("encodes a versioned claudroid:// URI with a normalized code", () => {
    const payload = buildPairingPayload({
      baseUrl: "https://claude.example.com/",
      code: " abcdefgh ",
      name: "dev box",
    });
    const uri = new URL(payload);
    expect(uri.protocol).toBe("claudroid:");
    expect(uri.host).toBe("pair");
    expect(uri.searchParams.get("v")).toBe("1");
    expect(uri.searchParams.get("url")).toBe("https://claude.example.com");
    expect(uri.searchParams.get("code")).toBe("ABCDEFGH");
    expect(uri.searchParams.get("name")).toBe("dev box");
  });

  it("keeps non-ASCII host names and omits an empty name", () => {
    const uri = new URL(
      buildPairingPayload({
        baseUrl: "http://192.168.1.5:8787",
        code: "ABCDEFGH",
        name: "  开发机  ",
      }),
    );
    expect(uri.searchParams.get("name")).toBe("开发机");
    const unnamed = new URL(
      buildPairingPayload({ baseUrl: "http://192.168.1.5:8787", code: "ABCDEFGH" }),
    );
    expect(unnamed.searchParams.has("name")).toBe(false);
  });

  it("caps the suggested name at the app's display-name limit", () => {
    const uri = new URL(
      buildPairingPayload({
        baseUrl: "https://claude.example.com",
        code: "ABCDEFGH",
        name: "x".repeat(80),
      }),
    );
    expect(uri.searchParams.get("name")).toHaveLength(40);
  });
});

describe("selectPairingUrl", () => {
  it("prefers the public https tunnel over LAN and loopback", () => {
    expect(
      selectPairingUrl([
        "http://127.0.0.1:8787",
        "http://192.168.1.5:8787",
        "https://claude.example.com",
      ]),
    ).toBe("https://claude.example.com");
  });

  it("falls back to a LAN address when there is no tunnel", () => {
    expect(selectPairingUrl(["http://127.0.0.1:8787", "http://10.0.0.8:8787"])).toBe(
      "http://10.0.0.8:8787",
    );
  });

  it("returns null when only loopback is reachable", () => {
    expect(selectPairingUrl(["http://127.0.0.1:8787"])).toBeNull();
    expect(selectPairingUrl(["http://localhost:8787"])).toBeNull();
    expect(selectPairingUrl(["http://[::1]:8787"])).toBeNull();
    expect(selectPairingUrl([])).toBeNull();
  });

  it("ignores malformed entries", () => {
    expect(selectPairingUrl(["not a url", "http://192.168.1.5:8787"])).toBe(
      "http://192.168.1.5:8787",
    );
  });
});

describe("renderTerminalQr", () => {
  it("renders uniform rows with a fixed black/white palette", () => {
    const qr = renderTerminalQr(
      buildPairingPayload({
        baseUrl: "http://192.168.1.5:8787",
        code: "ABCDEFGH",
      }),
    );
    const lines = qr.split("\n");
    expect(lines.length).toBeGreaterThan(8);
    const widths = new Set(
      lines.map((line) => line.replace(/\x1b\[[0-9;]*m/g, "").length),
    );
    expect(widths.size).toBe(1);
    expect(qr).toContain("\x1b[40m");
    expect(qr).toContain("\x1b[97m");
    expect(qr).toContain("█");
  });
});
