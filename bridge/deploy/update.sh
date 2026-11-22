#!/usr/bin/env bash
# Auto-deploys the latest claudroid-bridge from npm and restarts the daemon.
# Installed by bridge/deploy/install.sh, triggered by
# claudroid-bridge-update.timer. Manual run for a one-off check:
#   systemctl --user start claudroid-bridge-update.service
set -euo pipefail

export PATH="${HOME}/.local/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"

log() { printf '[%s] %s\n' "$(date -Is)" "$*"; }

runtime_dir="${XDG_RUNTIME_DIR:-/tmp}"
lock_file="${runtime_dir}/claudroid-bridge-update.lock"
exec 9>"$lock_file"
if ! flock -n 9; then
  log "已有更新任务在运行，跳过本次检查"
  exit 0
fi

command -v npm >/dev/null || { log "找不到 npm，跳过"; exit 0; }

latest="$(npm view claudroid-bridge version 2>/dev/null)" || {
  log "查询 npm 最新版本失败，稍后重试"
  exit 0
}
latest="${latest%%[[:space:]]*}"
if ! [[ "$latest" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  log "npm 返回了无效版本号：${latest}，跳过"
  exit 0
fi

installed="$(claudroid-bridge --version 2>/dev/null || true)"
installed="${installed%%[[:space:]]*}"

if [ "$installed" = "$latest" ]; then
  log "已是最新版本 ${latest}"
  if systemctl --user is-failed --quiet claudroid-bridge.service; then
    log "服务处于 failed 状态，尝试重新启动"
    systemctl --user restart claudroid-bridge.service
  fi
  exit 0
fi

if [ -n "$installed" ]; then
  newest="$(printf '%s\n%s\n' "$installed" "$latest" | sort -V | tail -n 1)"
  if [ "$newest" != "$latest" ]; then
    log "本地版本 ${installed} 不低于 npm 的 ${latest}，跳过"
    exit 0
  fi
fi

log "发现新版本：${installed:-未安装} → ${latest}，开始部署"
if ! npm install -g "claudroid-bridge@${latest}" --no-fund --no-audit --loglevel=error; then
  log "npm 安装失败，保留当前版本 ${installed:-未知}"
  exit 1
fi

deployed="$(claudroid-bridge --version 2>/dev/null || true)"
deployed="${deployed%%[[:space:]]*}"
if [ "$deployed" != "$latest" ]; then
  log "安装后版本校验失败：期望 ${latest}，实际 ${deployed:-未知}；不重启服务"
  exit 1
fi

log "已安装 ${latest}，重启 claudroid-bridge.service"
systemctl --user restart claudroid-bridge.service
log "部署完成：${latest}"
