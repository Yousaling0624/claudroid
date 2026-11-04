#!/usr/bin/env python3
"""Fetch the Termux proot runtime and stage it as Android native libraries.

Why this exists
---------------
On Android 10+ a targetSdk >= 29 app cannot exec() files in its own data
directory. We run the official Claude Code CLI without root through PRoot, so
both PRoot itself and the guest binaries must live somewhere executable:

  * PRoot, its loader and its shared libraries are packaged as lib*.so files in
    jniLibs, so Android extracts them into nativeLibraryDir (an executable,
    app-owned directory). This is the same trick Termux users / DSH-Go use.
  * The guest rootfs stays in filesDir. targetSdk 28 (see app/build.gradle.kts)
    keeps exec() allowed there.

Android only extracts files named ``lib*.so``. Termux's ``libtalloc.so.2`` is
therefore renamed to ``libtalloc.so`` and the DT_NEEDED/SONAME strings in both
ELFs are rewritten in place (the replacement is NUL-padded to the same length,
so no ELF offsets move).

The script is pure stdlib Python (ar + tarfile + lzma) so it runs on the Windows
build box and on ubuntu-latest CI without dpkg/ar. Outputs are cached; rerunning
is a no-op unless --force is given.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import lzma
import os
import re
import struct
import sys
import tarfile
import time
import urllib.request
from pathlib import Path

DEFAULT_REPO = "https://packages.termux.dev/apt/termux-main"
UA = "Claudroid-build/1.1 (+https://github.com/yousang0624/claudroid)"
PACKAGES = ("proot", "libtalloc", "libandroid-shmem")

ABIS = {
    "arm64-v8a": {"termux": "aarch64", "machine": 183},  # EM_AARCH64
    "x86_64": {"termux": "x86_64", "machine": 62},       # EM_X86_64
}

TALLOC_OLD = b"libtalloc.so.2\x00"
TALLOC_NEW = b"libtalloc.so\x00\x00\x00"  # same length on purpose


def log(msg: str) -> None:
    print(f"[proot] {msg}", flush=True)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fetch(url: str, dest: Path, expected_sha256: str | None = None) -> Path:
    if dest.is_file():
        if expected_sha256 is None or sha256(dest) == expected_sha256:
            return dest
        log(f"cache hash mismatch, re-downloading {dest.name}")
        dest.unlink()

    dest.parent.mkdir(parents=True, exist_ok=True)
    last: Exception | None = None
    for attempt in range(1, 4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=60) as resp:
                tmp = dest.with_suffix(dest.suffix + ".part")
                with tmp.open("wb") as out:
                    while True:
                        block = resp.read(1 << 20)
                        if not block:
                            break
                        out.write(block)
                tmp.replace(dest)
            if expected_sha256 is not None:
                got = sha256(dest)
                if got != expected_sha256:
                    dest.unlink(missing_ok=True)
                    raise RuntimeError(f"SHA256 mismatch for {url}: {got} != {expected_sha256}")
            return dest
        except Exception as exc:  # noqa: BLE001 - retry any transient network error
            last = exc
            if attempt < 3:
                time.sleep(1.2 * attempt)
    raise RuntimeError(f"download failed: {url}: {last}")


def version_key(version: str) -> tuple:
    """Rough ordering for Termux versions such as 5.1.107.102 or 2.5.0."""
    parts = re.split(r"[._+~-]", version)
    key: list[object] = []
    for part in parts:
        key.append(int(part) if part.isdigit() else part)
    return tuple(key)


def load_packages(repo: str, arch: str, cache: Path) -> dict[str, dict[str, str]]:
    index = cache / f"Packages-{arch}.gz"
    if not index.is_file() or time.time() - index.stat().st_mtime > 6 * 3600:
        fetch(f"{repo}/dists/stable/main/binary-{arch}/Packages.gz", index)
    text = gzip.decompress(index.read_bytes()).decode("utf-8", "replace")
    found: dict[str, dict[str, str]] = {}
    for block in text.split("\n\n"):
        record: dict[str, str] = {}
        for line in block.splitlines():
            if ": " in line:
                key, value = line.split(": ", 1)
                record[key] = value
        name = record.get("Package")
        if name in PACKAGES:
            old = found.get(name)
            if old is None or version_key(record.get("Version", "0")) > version_key(old.get("Version", "0")):
                found[name] = record
    missing = [p for p in PACKAGES if p not in found]
    if missing:
        raise RuntimeError(f"Termux index has no {', '.join(missing)} for {arch}")
    return found


def parse_ar(data: bytes) -> dict[str, bytes]:
    if not data.startswith(b"!<arch>\n"):
        raise RuntimeError("not an ar archive (expected a .deb)")
    members: dict[str, bytes] = {}
    offset = 8
    while offset + 60 <= len(data):
        header = data[offset:offset + 60]
        offset += 60
        name = header[:16].decode("ascii", "replace").strip()
        try:
            size = int(header[48:58].decode("ascii").strip())
        except ValueError:
            break
        body = data[offset:offset + size]
        offset += size + (size % 2)
        members[name.rstrip("/")] = body
    return members


def deb_data_tar(deb: Path) -> tarfile.TarFile:
    members = parse_ar(deb.read_bytes())
    name = next((n for n in members if n.startswith("data.tar")), None)
    if name is None:
        raise RuntimeError(f"{deb.name}: no data.tar member")
    raw = members[name]
    if name.endswith(".xz"):
        raw = lzma.decompress(raw)
    elif name.endswith(".gz"):
        raw = gzip.decompress(raw)
    elif not name.endswith(".tar"):
        raise RuntimeError(f"{deb.name}: unsupported compression ({name})")
    return tarfile.open(fileobj=io.BytesIO(raw), mode="r:")


def extract_member(deb: Path, predicate, label: str) -> bytes:
    with deb_data_tar(deb) as tar:
        for member in tar.getmembers():
            if member.isfile() and predicate(member.name):
                data = tar.extractfile(member)
                if data is None:
                    break
                return data.read()
    raise RuntimeError(f"{deb.name}: {label} not found")


def patch_talloc_name(path: Path) -> None:
    data = path.read_bytes()
    count = data.count(TALLOC_OLD)
    if count == 0:
        return
    if count != 1:
        raise RuntimeError(f"{path.name}: expected one libtalloc.so.2 string, found {count}")
    if len(TALLOC_NEW) != len(TALLOC_OLD):
        raise AssertionError("replacement must keep the same length")
    path.write_bytes(data.replace(TALLOC_OLD, TALLOC_NEW))


def check_elf(path: Path, expected_machine: int) -> None:
    data = path.read_bytes()
    if len(data) < 20 or data[:4] != b"\x7fELF":
        raise RuntimeError(f"{path.name}: not an ELF file")
    if data[4] != 2:
        raise RuntimeError(f"{path.name}: not a 64-bit ELF")
    endian = "<" if data[5] == 1 else ">"
    machine = struct.unpack_from(endian + "H", data, 18)[0]
    if machine != expected_machine:
        raise RuntimeError(f"{path.name}: ELF machine {machine}, expected {expected_machine}")


def stage_abi(repo: str, abi: str, root: Path, cache: Path, force: bool) -> None:
    meta = ABIS[abi]
    arch = meta["termux"]
    out_dir = root / "app" / "src" / "main" / "jniLibs" / abi
    info_file = cache / abi / "info.json"

    if not force and info_file.is_file():
        try:
            info = json.loads(info_file.read_text())
            ok = all(
                (out_dir / name).is_file()
                and (out_dir / name).stat().st_size == entry["bytes"]
                and sha256(out_dir / name) == entry["sha256"]
                for name, entry in info.get("files", {}).items()
            )
            if ok:
                log(f"{abi}: already staged ({info.get('versions')})")
                return
        except Exception:  # noqa: BLE001 - stale manifest, restage below
            pass

    packages = load_packages(repo, arch, cache)
    out_dir.mkdir(parents=True, exist_ok=True)
    deb_dir = cache / abi
    deb_dir.mkdir(parents=True, exist_ok=True)

    debs: dict[str, Path] = {}
    for package in PACKAGES:
        record = packages[package]
        dest = deb_dir / record["Filename"].split("/")[-1]
        debs[package] = fetch(f"{repo}/{record['Filename']}", dest, record.get("SHA256"))
        log(f"{abi}: {package} {record.get('Version')} ok")

    proot_data = extract_member(
        debs["proot"],
        lambda name: name.endswith("/usr/bin/proot"),
        "usr/bin/proot",
    )
    loader_data = extract_member(
        debs["proot"],
        lambda name: name.endswith("/usr/libexec/proot/loader"),
        "usr/libexec/proot/loader",
    )
    talloc_data = extract_member(
        debs["libtalloc"],
        lambda name: name.endswith("/lib/libtalloc.so.2.5.0")
        or re.search(r"/lib/libtalloc\.so\.\d+(\.\d+)*$", name) is not None,
        "lib/libtalloc.so.*",
    )
    shmem_data = extract_member(
        debs["libandroid-shmem"],
        lambda name: name.endswith("/lib/libandroid-shmem.so"),
        "lib/libandroid-shmem.so",
    )

    files = {
        "libproot.so": proot_data,
        "libproot-loader.so": loader_data,
        "libtalloc.so": talloc_data,
        "libandroid-shmem.so": shmem_data,
    }
    out_info: dict[str, dict[str, object]] = {}
    for name, data in files.items():
        path = out_dir / name
        path.write_bytes(data)
        path.chmod(0o755)
        check_elf(path, meta["machine"])
        patch_talloc_name(path)
        out_info[name] = {"bytes": path.stat().st_size, "sha256": sha256(path)}

    # proot's DT_NEEDED after the in-place patch must resolve to a packaged file.
    proot_bytes = (out_dir / "libproot.so").read_bytes()
    if b"libtalloc.so\x00" not in proot_bytes:
        raise RuntimeError("libproot.so: libtalloc DT_NEEDED rename did not take effect")

    manifest = {
        "abi": abi,
        "termuxArch": arch,
        "repo": repo,
        "versions": {p: packages[p].get("Version") for p in PACKAGES},
        "files": out_info,
        "stagedAt": int(time.time()),
    }
    info_file.parent.mkdir(parents=True, exist_ok=True)
    info_file.write_text(json.dumps(manifest, indent=2, sort_keys=True))
    summary = ", ".join(f"{name} ({entry['bytes']} B)" for name, entry in out_info.items())
    log(f"{abi}: staged {summary}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=sorted(ABIS), help="stage one ABI")
    parser.add_argument("--all", action="store_true", help="stage both supported ABIs")
    parser.add_argument("--force", action="store_true", help="ignore cached outputs")
    parser.add_argument("--repo", default=DEFAULT_REPO, help="Termux apt repository")
    args = parser.parse_args()

    root = Path(__file__).resolve().parent.parent
    cache = Path(os.environ.get("CLAUDROID_CACHE", root / ".cache" / "proot")).resolve()
    cache.mkdir(parents=True, exist_ok=True)

    abis = sorted(ABIS) if args.all or not args.abi else [args.abi]
    for abi in abis:
        stage_abi(args.repo, abi, root, cache, args.force)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # noqa: BLE001 - top-level CLI error
        print(f"[proot] ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
