#!/usr/bin/env bash
# Installs claudroid-bridge as systemd user services with npm auto-deploy.
#
#   claudroid-bridge.service         the daemon (restarts on failure)
#   claudroid-bridge-tunnel.service  a quick tunnel outside the daemon, so
#                                    daemon upgrades keep the public URL
#   claudroid-bridge-update.timer    polls npm and upgrades + restarts
#
# Re-run this script after changing files in bridge/deploy/.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log() { printf '[install] %s\n' "$*"; }
die() { printf '[install] %s\n' "$*" >&2; exit 1; }

[ -n "${HOME:-}" ] || die "HOME 未设置。"
command -v node >/dev/null || die "需要 Node.js 20+（未找到 node）。"
node -e '
  const major = Number(process.versions.node.split(".")[0]);
  if (major < 20) { console.error(`Node ${process.versions.node} 太旧，需要 20+`); process.exit(1); }
' || die "Node.js 版本不足。"
command -v npm >/dev/null || die "需要 npm。"
command -v systemctl >/dev/null || die "需要 systemd。"
command -v flock >/dev/null || die "需要 flock（util-linux）。"
systemctl --user show-environment >/dev/null 2>&1 || die "systemd 用户实例不可用。"

deploy_dir="$HOME/.local/lib/claudroid-bridge-deploy"
unit_dir="$HOME/.config/systemd/user"
bridge_home="${CLAUDROID_BRIDGE_HOME:-$HOME/.config/claudroid-bridge}"
config_file="$bridge_home/config.json"
url_file="$bridge_home/public-url"

if ! systemctl --user is-active --quiet claudroid-bridge.service; then
  if pgrep -f '[c]laudroid-bridge start' >/dev/null 2>&1; then
    die "检测到手动运行的 claudroid-bridge start。请先停止它（Ctrl-C），再运行本脚本。"
  fi
fi

mkdir -p "$deploy_dir" "$unit_dir" "$bridge_home"

install -m 0755 "$here/update.sh" "$deploy_dir/update.sh"
install -m 0755 "$here/quick-tunnel.mjs" "$deploy_dir/quick-tunnel.mjs"
for unit in \
  claudroid-bridge.service \
  claudroid-bridge-tunnel.service \
  claudroid-bridge-update.service \
  claudroid-bridge-update.timer; do
  install -m 0644 "$here/$unit" "$unit_dir/$unit"
done

if ! command -v claudroid-bridge >/dev/null; then
  log "安装 claudroid-bridge@latest"
  npm install -g claudroid-bridge@latest --no-fund --no-audit --loglevel=error
fi

if [ ! -f "$config_file" ]; then
  log "写入默认配置：$config_file"
  claudroid-bridge init
fi

if [ "${CLAUDROID_DEPLOY_KEEP_TUNNEL:-0}" != "1" ]; then
  cp -a "$config_file" "$config_file.before-deploy"
  node - "$config_file" "$url_file" <<'NODE'
const [file, publicUrlFile] = process.argv.slice(2);
const fs = require("node:fs");
const path = require("node:path");
let config = {};
try {
  const parsed = JSON.parse(fs.readFileSync(file, "utf8"));
  if (parsed && typeof parsed === "object") config = parsed;
} catch {
  config = {};
}
config.tunnel = { ...(config.tunnel ?? {}), mode: "external", publicUrlFile };
fs.mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
const tmp = `${file}.tmp`;
fs.writeFileSync(tmp, `${JSON.stringify(config, null, 2)}\n`, { mode: 0o600 });
fs.renameSync(tmp, file);
NODE
  log "隧道模式已设为 external，公网地址文件：$url_file"
fi

systemctl --user daemon-reload
systemctl --user enable --now \
  claudroid-bridge-tunnel.service \
  claudroid-bridge.service \
  claudroid-bridge-update.timer

if command -v loginctl >/dev/null; then
  loginctl enable-linger "$USER" 2>/dev/null \
    || log "提示：未能启用 linger；注销后用户服务可能停止，可手动执行 loginctl enable-linger $USER"
fi

log "完成。状态："
systemctl --user --no-pager --lines=0 status claudroid-bridge.service || true
systemctl --user --no-pager --lines=0 status claudroid-bridge-tunnel.service || true
systemctl --user list-timers claudroid-bridge-update.timer --no-pager || true
log "查看日志：journalctl --user -u claudroid-bridge -u claudroid-bridge-tunnel -f"
