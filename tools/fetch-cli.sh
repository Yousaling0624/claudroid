#!/usr/bin/env bash
# Download the official CLI artifact + musl loader by hand.
# Useful for offline debugging, or to run the RealTarballTest integration check:
#
#   bash tools/fetch-cli.sh arm64
#   CLAUDROID_TARBALL=$PWD/.cache/claude-code-linux-arm64-musl.tgz \
#   CLAUDROID_INTEGRITY=$(python -c "..." ) ./gradlew testDebugUnitTest --tests '*RealTarballTest*'
set -euo pipefail
ARCH="${1:-arm64}"
CACHE="$(cd "$(dirname "$0")/.." && pwd)/.cache"
mkdir -p "$CACHE"

case "$ARCH" in
  arm64) PKG="@anthropic-ai/claude-code-linux-arm64-musl"; ALP="aarch64" ;;
  x64)   PKG="@anthropic-ai/claude-code-linux-x64-musl";   ALP="x86_64" ;;
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
echo "设为测试输入："
echo "  export CLAUDROID_TARBALL='$OUT'"
echo "  export CLAUDROID_INTEGRITY='$INTEGRITY'"

echo "== 预取 musl 加载器（jail 需要它）=="
IDX="$CACHE/APKINDEX-$ALP.tar.gz"
curl -sSL -o "$IDX" "https://dl-cdn.alpinelinux.org/alpine/v3.20/main/$ALP/APKINDEX.tar.gz"
INDEX_TEXT="$CACHE/APKINDEX-$ALP"
tar -xzOf "$IDX" APKINDEX > "$INDEX_TEXT"
MUSL=$(grep -A3 '^P:musl$' "$INDEX_TEXT" | grep '^V:' | head -1 | cut -d: -f2)
[ -n "$MUSL" ] || { echo "无法从 APKINDEX 解析 musl 版本"; exit 1; }
echo "musl = $MUSL"
curl -sSL -o "$CACHE/musl-$ALP.apk" "https://dl-cdn.alpinelinux.org/alpine/v3.20/main/$ALP/musl-$MUSL.apk"
tar -tzvf "$CACHE/musl-$ALP.apk" | grep ld-musl
echo "OK"
