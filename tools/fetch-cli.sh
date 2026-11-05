#!/usr/bin/env bash
# Download the official CLI artifact by hand (glibc build; the Debian rootfs
# supplies the loader). Useful for offline debugging.
#
#   bash tools/fetch-cli.sh arm64
set -euo pipefail
ARCH="${1:-arm64}"
CACHE="$(cd "$(dirname "$0")/.." && pwd)/.cache"
mkdir -p "$CACHE"

case "$ARCH" in
  arm64) PKG="@anthropic-ai/claude-code-linux-arm64"; ALP="aarch64" ;;
  x64)   PKG="@anthropic-ai/claude-code-linux-x64";   ALP="x86_64" ;;
  *) echo "usage: $0 [arm64|x64]"; exit 1 ;;
esac

echo "== 查询最新版本 =="
VER=$(curl -sS "https://registry.npmjs.org/@anthropic-ai/claude-code/latest" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s).version))')
echo "Claude Code latest = $VER"

echo "== 查询平台包 =="
META=$(curl -sS "https://registry.npmjs.org/$PKG/$VER")
TARBALL=$(printf '%s' "$META" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s).dist.tarball))')
INTEGRITY=$(printf '%s' "$META" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s).dist.integrity))')
echo "tarball   = $TARBALL"
echo "integrity = $INTEGRITY"

OUT="$CACHE/claude-code-$ALP-$VER.tgz"
[ -f "$OUT" ] || curl -# -L -o "$OUT" "$TARBALL"
echo "已下载: $OUT ($(du -h "$OUT" | cut -f1))"
echo
echo "解包（Debian 容器内的落点是 /opt/claude/<version>/claude）："
echo "  tar -xzf '$OUT' -C <dir> package/claude"
echo "OK"
