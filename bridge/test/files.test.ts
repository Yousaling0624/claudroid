import { mkdirSync, readFileSync, symlinkSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { Readable } from "node:stream";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { FileService } from "../src/files.js";
import { cleanup, tempDir } from "./helpers.js";

let root = "";
let outside = "";
let files: FileService;

async function readAll(stream: Readable): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of stream) chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
  return Buffer.concat(chunks).toString("utf8");
}

beforeEach(async () => {
  root = join(tempDir(), "inside");
  outside = tempDir();
  mkdirSync(root, { recursive: true });
  files = new FileService([root], 8);
  await files.init();
});

afterEach(() => {
  cleanup(join(root, ".."));
  cleanup(outside);
});

describe("FileService", () => {
  it("writes, lists, reads and deletes inside the root", async () => {
    const target = join(root, "hello.txt");
    const size = await files.saveStream(target, Readable.from("hello"));
    expect(size).toBe(5);
    expect(readFileSync(target, "utf8")).toBe("hello");

    const entries = await files.list(root);
    expect(entries.map((e) => e.name)).toContain("hello.txt");

    const { stream } = await files.openRead(target);
    expect(await readAll(stream)).toBe("hello");

    await files.remove(target, false);
    await expect(files.list(target)).rejects.toMatchObject({ status: 404 });
  });

  it("creates parent directories for uploads", async () => {
    const target = join(root, "nested", "deeper", "file.txt");
    await files.saveStream(target, Readable.from("x"));
    expect(readFileSync(target, "utf8")).toBe("x");
  });

  it("rejects traversal and paths outside the roots", async () => {
    writeFileSync(join(outside, "secret.txt"), "secret");
    await expect(files.list(join(root, ".."))).rejects.toMatchObject({ status: 403 });
    await expect(files.list(join(outside, "secret.txt"))).rejects.toMatchObject({ status: 403 });
    await expect(
      files.saveStream(join(outside, "new.txt"), Readable.from("x")),
    ).rejects.toMatchObject({ status: 403 });
  });

  it("rejects symlinks that escape the root", async () => {
    writeFileSync(join(outside, "secret.txt"), "secret");
    symlinkSync(join(outside, "secret.txt"), join(root, "escape"));
    await expect(files.openRead(join(root, "escape"))).rejects.toMatchObject({ status: 403 });
    await expect(
      files.saveStream(join(root, "escape"), Readable.from("x")),
    ).rejects.toMatchObject({ status: 403 });
  });

  it("allows symlinks that stay inside the root", async () => {
    writeFileSync(join(root, "real.txt"), "old");
    symlinkSync(join(root, "real.txt"), join(root, "link.txt"));
    await files.saveStream(join(root, "link.txt"), Readable.from("new"));
    expect(readFileSync(join(root, "real.txt"), "utf8")).toBe("new");
  });

  it("renames and deletes", async () => {
    writeFileSync(join(root, "a.txt"), "a");
    await files.rename(join(root, "a.txt"), join(root, "b.txt"));
    expect((await files.list(root)).map((e) => e.name)).toContain("b.txt");
    await files.remove(join(root, "b.txt"), false);
    expect((await files.list(root)).map((e) => e.name)).not.toContain("b.txt");
  });

  it("refuses to delete the root itself", async () => {
    await expect(files.remove(root, true)).rejects.toMatchObject({ status: 400 });
  });

  it("enforces the upload size limit", async () => {
    await expect(
      files.saveStream(join(root, "big.txt"), Readable.from("0123456789")),
    ).rejects.toMatchObject({ status: 413 });
    await expect(files.list(join(root, "big.txt"))).rejects.toMatchObject({ status: 404 });
  });
});
