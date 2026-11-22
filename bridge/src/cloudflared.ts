import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { createReadStream, createWriteStream, chmodSync, mkdirSync, renameSync, rmSync, statSync } from "node:fs";
import { get as httpsGet } from "node:https";
import { join } from "node:path";
import { bridgeHome, type TunnelConfig } from "./config.js";

/**
 * Pin the connector release and its published SHA-256 digests. The binary is
 * downloaded on first use so `npm i -g claudroid-bridge` + `start --tunnel`
 * works without a manual cloudflared install, while still verifying integrity.
 */
export const CLOUDFLARED_VERSION = "2026.11.1";

export interface CloudflaredAsset {
  /** GitHub release asset name. */
  file: string;
  /** SHA-256 from the GitHub release API (`assets[].digest`). */
  sha256: string;
  archive: "binary" | "tgz";
}

const ASSETS: Record<string, CloudflaredAsset> = {
  "linux-x64": {
    file: "cloudflared-linux-amd64",
    sha256: "97bb3b02d23a5980fc9c15a1a270e692da1923f103f920d5b16d4931d5ab55e7",
    archive: "binary",
  },
  "linux-arm64": {
    file: "cloudflared-linux-arm64",
    sha256: "b155cec3e48e489cfb8e5c602769fb8c771083018d8881d68b4d18a791567c5a",
    archive: "binary",
  },
  "darwin-x64": {
    file: "cloudflared-darwin-amd64.tgz",
    sha256: "5e0403b188dfee71fbd9b995e7dc9910d61b661c52371935ab6475d41185632f",
    archive: "tgz",
  },
  "darwin-arm64": {
    file: "cloudflared-darwin-arm64.tgz",
    sha256: "02fe4bcf08f2c09699cff30658228a739a0c47fb75f3653d1b8772a27ade4462",
    archive: "tgz",
  },
  "win32-x64": {
    file: "cloudflared-windows-amd64.exe",
    sha256: "341deda3db5ec498a0482254d6c38c0b7cf01a30c37bb5dd95d2216d08955e35",
    archive: "binary",
  },
};

export function assetFor(platform: string, arch: string): CloudflaredAsset | null {
  return ASSETS[`${platform}-${arch}`] ?? null;
}

export function managedCloudflaredPath(home = bridgeHome(), platform = process.platform): string {
  return join(home, "bin", platform === "win32" ? "cloudflared.exe" : "cloudflared");
}

function releaseUrl(asset: CloudflaredAsset): string {
  return `https://github.com/cloudflare/cloudflared/releases/download/${CLOUDFLARED_VERSION}/${asset.file}`;
}

export function sha256File(path: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const hash = createHash("sha256");
    const stream = createReadStream(path);
    stream.on("data", (chunk) => hash.update(chunk));
    stream.on("error", reject);
    stream.on("end", () => resolve(hash.digest("hex")));
  });
}

function isExecutable(path: string, platform: string): boolean {
  try {
    const stat = statSync(path);
    if (!stat.isFile()) return false;
    return platform === "win32" || (stat.mode & 0o111) !== 0;
  } catch {
    return false;
  }
}

function onPath(): boolean {
  const result = spawnSync("cloudflared", ["--version"], { stdio: "ignore" });
  return !result.error && result.status === 0;
}

function hasCurl(): boolean {
  const result = spawnSync("curl", ["--version"], { stdio: "ignore" });
  return !result.error && result.status === 0;
}

function curlDownload(url: string, dest: string): void {
  const result = spawnSync(
    "curl",
    ["-fL", "--retry", "3", "--connect-timeout", "20", "--max-time", "600", "-o", dest, url],
    { encoding: "utf8", timeout: 620_000 },
  );
  if (result.error) throw new Error(`下载 cloudflared 失败：${result.error.message}`);
  if (result.status !== 0) {
    const detail = (result.stderr || "").trim();
    throw new Error(`下载 cloudflared 失败（curl exit ${String(result.status)}）${detail ? `：${detail}` : ""}`);
  }
}

function httpsDownload(url: string, dest: string, redirects = 5): Promise<void> {
  return new Promise((resolve, reject) => {
    const request = httpsGet(
      url,
      { headers: { "user-agent": `claudroid-bridge/${CLOUDFLARED_VERSION}` }, timeout: 30_000 },
      (response) => {
        const status = response.statusCode ?? 0;
        if (status >= 300 && status < 400 && response.headers.location && redirects > 0) {
          response.resume();
          resolve(httpsDownload(new URL(response.headers.location, url).toString(), dest, redirects - 1));
          return;
        }
        if (status !== 200) {
          response.resume();
          reject(new Error(`下载 cloudflared 失败：HTTP ${String(status)}`));
          return;
        }
        const file = createWriteStream(dest, { mode: 0o600 });
        response.pipe(file);
        response.on("error", reject);
        file.on("error", reject);
        file.on("finish", () => file.close(() => resolve()));
      },
    );
    request.on("timeout", () => request.destroy(new Error("下载 cloudflared 超时")));
    request.on("error", reject);
  });
}

function download(url: string, dest: string): Promise<void> {
  if (hasCurl()) {
    curlDownload(url, dest);
    return Promise.resolve();
  }
  return httpsDownload(url, dest);
}

function extractTgz(archive: string, dest: string): void {
  const result = spawnSync("tar", ["-xzf", archive, "-C", dest, "cloudflared"], {
    encoding: "utf8",
    timeout: 120_000,
  });
  if (result.error) throw new Error(`解压 cloudflared 失败：${result.error.message}`);
  if (result.status !== 0) {
    throw new Error(`解压 cloudflared 失败（tar exit ${String(result.status)}）`);
  }
}

/**
 * Return a usable cloudflared path, downloading the pinned official release on
 * first use. `tunnel.cloudflaredPath` always wins; an existing PATH install is
 * reused before anything is downloaded.
 */
export async function ensureCloudflared(
  config: TunnelConfig,
  log: (message: string) => void = () => undefined,
  platform = process.platform,
  arch = process.arch,
): Promise<string> {
  if (config.cloudflaredPath) return config.cloudflaredPath;
  const managed = managedCloudflaredPath(bridgeHome(), platform);
  if (isExecutable(managed, platform)) return managed;
  if (onPath()) return "cloudflared";

  const asset = assetFor(platform, arch);
  if (!asset) {
    throw new Error(
      `当前平台 ${platform}/${arch} 没有可自动安装的 cloudflared，请手动安装后设置 tunnel.cloudflaredPath。`,
    );
  }

  const binDir = join(bridgeHome(), "bin");
  mkdirSync(binDir, { recursive: true, mode: 0o700 });
  const temp = join(binDir, `.cloudflared-${String(process.pid)}.download`);
  log(`未找到 cloudflared，正在从官方 Release 下载 ${CLOUDFLARED_VERSION}…`);

  try {
    await download(releaseUrl(asset), temp);
    const actual = await sha256File(temp);
    if (actual !== asset.sha256) {
      throw new Error(`cloudflared 校验失败（期望 ${asset.sha256}，实际 ${actual}），已丢弃下载文件。`);
    }
    if (asset.archive === "tgz") {
      const extractDir = join(binDir, `.cloudflared-${String(process.pid)}.extract`);
      mkdirSync(extractDir, { recursive: true, mode: 0o700 });
      try {
        extractTgz(temp, extractDir);
        renameSync(join(extractDir, "cloudflared"), managed);
      } finally {
        rmSync(extractDir, { recursive: true, force: true });
      }
    } else {
      renameSync(temp, managed);
    }
    if (platform !== "win32") chmodSync(managed, 0o755);
    log(`cloudflared ${CLOUDFLARED_VERSION} 已安装到 ${managed}`);
    return managed;
  } finally {
    rmSync(temp, { force: true });
  }
}
