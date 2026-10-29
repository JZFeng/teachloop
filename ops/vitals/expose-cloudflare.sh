#!/usr/bin/env bash
# 把 vitals 看板挂到 Cloudflare 隧道上（一条公网入口，不碰路由器、不碰 prod）。
#
#   bash expose-cloudflare.sh <tunnel-token>     首次：保存 token、拉起 cloudflared、加开机自启
#   bash expose-cloudflare.sh status             看进程与最近日志
#   bash expose-cloudflare.sh stop               停掉
#
# token 从哪来：Cloudflare Zero Trust → Networks → Tunnels → Create a tunnel →
#   Cloudflared → 起名（vitals）→ 复制 token。**公网主机名（status.teachloop.org →
#   http://localhost:8099）与 Access 策略是在后台配的，不由本脚本设置**——token 托管型隧道
#   的路由表存在 Cloudflare 云端，本机没有配置文件。
#
# 安全：token 等价于把这条隧道接到你账号的凭据，写进 ~/.cloudflared-vitals.token（600），不进 git。
set -euo pipefail

HOME_DIR="$HOME"
TOKEN_FILE="${TOKEN_FILE:-$HOME_DIR/.cloudflared-vitals.token}"
LOG="$HOME_DIR/cloudflared-vitals.log"
BIN="${CLOUDFLARED_BIN:-$HOME_DIR/bin/cloudflared}"
MARKER="# teachloop-vitals-tunnel"

say()  { printf '\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
die()  { printf '  \033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }

case "${1:-}" in
  status)
    pgrep -af "[c]loudflared tunnel run" >/dev/null && ok "cloudflared 在跑（pid $(pgrep -f '[c]loudflared tunnel run' | head -1)）" || warn "cloudflared 没在跑"
    echo "  最近日志："; tail -n 8 "$LOG" 2>/dev/null | sed 's/^/    /' || echo "    （没有日志）"
    exit 0 ;;
  stop)
    pkill -f "[c]loudflared tunnel run" 2>/dev/null && ok "已停止" || warn "本来就没在跑"
    exit 0 ;;
  ""|-h|--help)
    sed -n '2,16p' "$0" | sed 's/^# \?//'; exit 0 ;;
esac

TOKEN="$1"
[ -x "$BIN" ] || die "找不到 $BIN（先放 cloudflared 二进制进去，或用 CLOUDFLARED_BIN 指定）"
case "$TOKEN" in
  ey[A-Za-z0-9_=-]*) ;;
  *) die "token 看起来不对（Cloudflare 的 tunnel token 以 ey 开头）" ;;
esac

umask 077
printf '%s\n' "$TOKEN" > "$TOKEN_FILE"
chmod 600 "$TOKEN_FILE"
ok "token 已写入 $TOKEN_FILE（600）"

pkill -f "[c]loudflared tunnel run" 2>/dev/null || true
sleep 0.5
setsid --fork "$BIN" tunnel run --token-file "$TOKEN_FILE" </dev/null >> "$LOG" 2>&1
sleep 3

if pgrep -af "[c]loudflared tunnel run" >/dev/null; then
  ok "cloudflared 已启动"
else
  die "cloudflared 没起来，看 $LOG"
fi

# 开机自启（用户 crontab，不需要 sudo）
if crontab -l 2>/dev/null | grep -qF "$MARKER"; then
  ok "crontab @reboot 已存在"
else
  { crontab -l 2>/dev/null || true
    echo "@reboot setsid --fork $BIN tunnel run --token-file $TOKEN_FILE </dev/null >> $LOG 2>&1 & $MARKER"
  } | crontab -
  ok "已加 crontab @reboot 自启"
fi

sleep 2
say "状态"
grep -E "Registered tunnel connection|ERR|error" "$LOG" | tail -3 | sed 's/^/  /' || true

cat <<'EOS'

接下来在 Cloudflare 后台（只做一次）：
  1) Zero Trust → Networks → Tunnels → 这条隧道 → Public Hostname
       Subdomain: status   Domain: teachloop.org
       Service:   HTTP → localhost:8099
  2) Zero Trust → Access → Applications → Add an application → Self-hosted
       Application domain: status.teachloop.org
       Policy: Include → Emails → 你的邮箱（例：jzhfeng001@gmail.com）
  3) 打开 https://status.teachloop.org/vitals/ 验证。

说明：Access 会挡在 Basic 口令前面；两层都留着，URL 泄漏也进不去。
EOS
