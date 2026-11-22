import { createReadStream, createWriteStream } from "node:fs";
import type { Stats } from "node:fs";
import { lstat, mkdir, readdir, realpath, rename, rm, rmdir, stat, unlink } from "node:fs/promises";
import { basename, isAbsolute, dirname, join, relative, resolve, sep } from "node:path";
import { randomBytes } from "node:crypto";
import { Transform } from "node:stream";
import { pipeline } from "node:stream/promises";
import type { Readable } from "node:stream";
import type { FileEntry } from "./types.js";

export class FileError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
    this.name = "FileError";
  }
}

function inside(root: string, candidate: string): boolean {
  const rel = relative(root, candidate);
  return rel === "" || (!rel.startsWith(`..${sep}`) && rel !== ".." && !isAbsolute(rel));
}

export class FileService {
  private roots: string[] = [];

  constructor(
    private readonly configuredRoots: string[],
    private readonly maxUploadBytes: number,
  ) {}

  async init(): Promise<void> {
    const resolved: string[] = [];
    for (const root of this.configuredRoots) {
      if (!isAbsolute(root)) throw new FileError(`工作区根目录必须是绝对路径：${root}`, 500);
      const real = await realpath(root).catch(() => {
        throw new FileError(`工作区根目录不存在：${root}`, 500);
      });
      const details = await stat(real);
      if (!details.isDirectory()) throw new FileError(`工作区根目录不是目录：${root}`, 500);
      resolved.push(real);
    }
    this.roots = resolved;
  }

  get workspaceRoots(): string[] {
    return [...this.roots];
  }

  private check(candidate: string): void {
    if (!this.roots.some((root) => inside(root, candidate))) {
      throw new FileError("路径不在允许的工作区内。", 403);
    }
  }

  private checkNotRoot(candidate: string): void {
    if (this.roots.includes(candidate)) throw new FileError("不能删除或覆盖工作区根目录本身。", 400);
  }

  /** Resolves an existing path, following symlinks, and checks the final target. */
  async resolveExisting(input: string): Promise<{ path: string; stats: Stats }> {
    if (!isAbsolute(input)) throw new FileError("路径必须是绝对路径。", 400);
    const real = await realpath(resolve(input)).catch(() => {
      throw new FileError("路径不存在。", 404);
    });
    this.check(real);
    return { path: real, stats: await stat(real) };
  }

  /**
   * Resolves a path for writing without following a final symlink outside the roots.
   * Optionally creates missing parent directories inside an allowed root.
   */
  async resolveForWrite(
    input: string,
    options: { createParents?: boolean } = {},
  ): Promise<string> {
    if (!isAbsolute(input)) throw new FileError("路径必须是绝对路径。", 400);
    const absolute = resolve(input);
    if (this.roots.includes(absolute)) throw new FileError("不能覆盖工作区根目录本身。", 400);
    let parent = dirname(absolute);
    if (options.createParents) {
      await this.ensureDirectory(parent);
    }
    const realParent = await realpath(parent).catch(() => {
      throw new FileError("父目录不存在。", 404);
    });
    this.check(realParent);
    const finalTarget = join(realParent, basename(resolve(input)));
    try {
      const linkStats = await lstat(finalTarget);
      if (linkStats.isSymbolicLink()) {
        const real = await realpath(finalTarget);
        this.check(real);
        return real;
      }
      if (linkStats.isDirectory()) throw new FileError("目标是目录。", 400);
    } catch (error) {
      if (error instanceof FileError) throw error;
      // ENOENT: new file, fine.
    }
    this.check(finalTarget);
    this.checkNotRoot(finalTarget);
    return finalTarget;
  }

  private async ensureDirectory(path: string): Promise<void> {
    if (path === dirname(path)) throw new FileError("无法创建目录。", 400);
    let existing: string | null = null;
    try {
      existing = await realpath(path);
    } catch {
      // need to create
    }
    if (existing) {
      this.check(existing);
      return;
    }
    const parent = dirname(path);
    await this.ensureDirectory(parent);
    const realParent = await realpath(parent);
    this.check(realParent);
    await mkdir(path, { mode: 0o755 }).catch((error: NodeJS.ErrnoException) => {
      if (error.code !== "EEXIST") throw error;
    });
    const real = await realpath(path);
    this.check(real);
  }

  async list(path: string): Promise<FileEntry[]> {
    const { path: real, stats } = await this.resolveExisting(path);
    if (!stats.isDirectory()) throw new FileError("不是目录。", 400);
    const entries = await readdir(real, { withFileTypes: true });
    const out: FileEntry[] = [];
    for (const entry of entries) {
      const full = join(real, entry.name);
      try {
        const details = await lstat(full);
        out.push({
          name: entry.name,
          path: full,
          isDir: details.isDirectory() || (details.isSymbolicLink() && (await this.isDirLink(full))),
          size: details.size,
          mtimeMs: details.mtimeMs,
        });
      } catch {
        // A file that disappears mid-listing should not fail the whole request.
      }
    }
    out.sort((a, b) => (a.isDir === b.isDir ? a.name.localeCompare(b.name) : a.isDir ? -1 : 1));
    return out;
  }

  private async isDirLink(path: string): Promise<boolean> {
    try {
      return (await stat(path)).isDirectory();
    } catch {
      return false;
    }
  }

  async statPath(path: string): Promise<FileEntry> {
    const { path: real, stats } = await this.resolveExisting(path);
    return {
      name: real.split(sep).at(-1) ?? real,
      path: real,
      isDir: stats.isDirectory(),
      size: stats.size,
      mtimeMs: stats.mtimeMs,
    };
  }

  async openRead(path: string): Promise<{ stream: Readable; size: number }> {
    const { path: real, stats } = await this.resolveExisting(path);
    if (stats.isDirectory()) throw new FileError("不能下载目录。", 400);
    return { stream: createReadStream(real), size: stats.size };
  }

  async saveStream(path: string, source: Readable): Promise<number> {
    const target = await this.resolveForWrite(path, { createParents: true });
    const temp = `${target}.upload-${randomBytes(6).toString("hex")}`;
    const limit = this.maxUploadBytes;
    let bytes = 0;
    const limiter = new Transform({
      transform(chunk: Buffer, _encoding, callback) {
        bytes += chunk.length;
        if (bytes > limit) {
          callback(new FileError("文件超过允许的大小。", 413));
          return;
        }
        callback(null, chunk);
      },
    });
    try {
      await pipeline(source, limiter, createWriteStream(temp, { mode: 0o600 }));
      await rename(temp, target);
      return bytes;
    } catch (error) {
      await unlink(temp).catch(() => {});
      if (error instanceof FileError) throw error;
      throw new FileError(`写入失败：${(error as Error).message}`, 500);
    }
  }

  async mkdir(path: string): Promise<void> {
    if (!isAbsolute(path)) throw new FileError("路径必须是绝对路径。", 400);
    await this.ensureDirectory(resolve(path));
  }

  /** Resolves a path without following its final symlink (for rename/delete). */
  async resolveLinkPath(input: string): Promise<string> {
    if (!isAbsolute(input)) throw new FileError("路径必须是绝对路径。", 400);
    const absolute = resolve(input);
    if (this.roots.includes(absolute)) throw new FileError("不能删除或重命名工作区根目录本身。", 400);
    const parent = await realpath(dirname(absolute)).catch(() => {
      throw new FileError("父目录不存在。", 404);
    });
    this.check(parent);
    const candidate = join(parent, basename(absolute));
    this.check(candidate);
    this.checkNotRoot(candidate);
    return candidate;
  }

  async rename(from: string, to: string): Promise<void> {
    const source = await this.resolveLinkPath(from);
    const target = await this.resolveLinkPath(to);
    await rename(source, target);
  }

  async remove(path: string, recursive: boolean): Promise<void> {
    const target = await this.resolveLinkPath(path);
    const details = await lstat(target).catch(() => {
      throw new FileError("路径不存在。", 404);
    });
    if (details.isDirectory()) {
      if (recursive) await rm(target, { recursive: true, force: false });
      else await rmdir(target);
    } else {
      await unlink(target);
    }
  }
}
