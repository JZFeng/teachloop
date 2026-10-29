#!/usr/bin/env bash
# 在「监控机」上安装 / 重启 / 停止 vitals（只需要 python3，不需要 Docker、不需要 root）。
#
#   bash install.sh            安装并启动（重复执行 = 重启）
#   bash install.sh status     看是否在跑 + 本地探针结果
#   bash install.sh stop       停掉
#   bash install.sh prod-help  打印 prod 侧那一次性的两行操作
#
# 装完的目录布局（默认 ~/vitals）：
#   vitals.py  page.html  config.env(600)  vitals.log  vitals.pid
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
TARGET="${VITALS_HOME:-$HOME/vitals}"
ACTION="${1:-install}"

say()  { printf '\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
die()  { printf '  \033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }

lan_ip() {
  local ip
  ip="$(hostname -I 2>/dev/null | awk '{print $1}')" || true
  [ -n "${ip:-}" ] || ip="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src") print $(i+1)}')" || true
  printf '%s' "${ip:-127.0.0.1}"
}

port_of() {
  local p
  p="$(sed -n 's/^VITALS_PORT=//p' "$TARGET/config.env" 2>/dev/null | tail -1 | tr -d '"'"'"'[:space:]')"
  printf '%s' "${p:-8099}"
}

prod_help() {
  cat <<'EOS'
在 prod 上一次性做两件事（只读，装完 vitals 才能看到容器/磁盘/内存）：

  1) 放探针（普通用户即可，不需要 sudo）：
     mkdir -p ~/vitals-probe && scp <监控机>:/某处/prod-probe.sh ~/vitals-probe/probe.sh
     chmod +x ~/vitals-probe/probe.sh

  2) 把监控机的公钥追加进 prod 的 ~/.ssh/authorized_keys，并限制成只能跑这一个脚本：
     command="$HOME/vitals-probe/probe.sh",no-port-forwarding,no-agent-forwarding,no-pty,no-x11-forwarding ssh-ed25519 AAAA... vitals@监控机

     这样即使监控机被攻破，攻击者也只能拿到「容器列表 / 磁盘 / 内存」这堆事实，
     拿不到 shell，也读不到 .env。

公钥在监控机上用：cat ~/.ssh/id_ed25519.pub（没有就先 ssh-keygen -t ed25519 -N ""）
EOS
}

case "$ACTION" in
  prod-help) prod_help; exit 0 ;;
esac

[ -f "$HERE/vitals.py" ] || die "找不到 $HERE/vitals.py（要在 ops/vitals/ 目录里跑）"
[ -f "$HERE/page.html" ] || die "找不到 $HERE/page.html"

if [ "$ACTION" = "stop" ]; then
  if [ -f "$TARGET/vitals.pid" ] && kill "$(cat "$TARGET/vitals.pid")" 2>/dev/null; then
    rm -f "$TARGET/vitals.pid"; ok "已停止"
  else
    warn "没有在跑的 vitals（或 pid 文件已失效）"
    pkill -f "python3 .*vitals\.py" 2>/dev/null && ok "按进程名停掉了" || true
  fi
  exit 0
fi

if [ "$ACTION" = "status" ]; then
  port="$(port_of)"
  [ -f "$TARGET/vitals.pid" ] && kill -0 "$(cat "$TARGET/vitals.pid")" 2>/dev/null \
    && ok "进程在跑（pid $(cat "$TARGET/vitals.pid")）" || warn "进程不在"
  code="$(curl -s -o /dev/null -w '%{http_code}' -m 3 "http://127.0.0.1:$port/vitals/healthz" || true)"
  [ "$code" = "200" ] && ok "自检 /vitals/healthz 200" || warn "自检返回 $code"
  curl -s -m 5 -u "$(sed -n 's/^VITALS_USER=//p' "$TARGET/config.env" 2>/dev/null):$(sed -n 's/^VITALS_PASS=//p' "$TARGET/config.env" 2>/dev/null)" \
    "http://127.0.0.1:$port/vitals/api" 2>/dev/null | head -c 400 | sed 's/^/  /' || true
  echo
  exit 0
fi

say "安装到 $TARGET"
command -v python3 >/dev/null || die "没有 python3（apt install python3）"
pyv="$(python3 -c 'import sys;print("%d.%d"%sys.version_info[:2])')"
python3 -c 'import sys;raise SystemExit(0 if sys.version_info>=(3,8) else 1)' || die "python3 版本太低：$pyv（需要 3.8+）"
ok "python3 $pyv"

mkdir -p "$TARGET"
cp "$HERE/vitals.py" "$HERE/page.html" "$TARGET/"
ok "已复制 vitals.py / page.html"

if [ ! -f "$TARGET/config.env" ]; then
  cat > "$TARGET/config.env" <<'EOS'
# vitals 配置：chmod 600，不要进 git。
# 改完执行 bash install.sh 重启（或 --help 看其它动作）。
VITALS_PORT=8099
VITALS_USER=teachloop
VITALS_PASS=CHANGE_ME_至少16位

# prod 侧（只读）
PROD_SSH=jzfeng@192.168.68.166
PROD_SSH_KEY=
# 装了 authorized_keys 的 command= 限制后，这里留空即可（ssh 不传命令）。
# 临时测试可以填：PROD_PROBE_CMD="bash ~/vitals-probe/probe.sh"
PROD_PROBE_CMD=
PROD_URL=https://teachloop.org

# 采集节奏（秒）
SSH_INTERVAL=5
PUBLIC_INTERVAL=10

# 告警：ALERT_ENABLED=1 才发信；163 的 SMTP_PASS 是「授权码」不是登录密码
ALERT_ENABLED=0
ALERT_COOLDOWN=1800
SMTP_HOST=smtp.163.com
SMTP_PORT=465
SMTP_USER=
SMTP_PASS=
MAIL_FROM=
MAIL_TO=

# 阈值（%）
DISK_WARN=90
MEM_WARN=90
EOS
  chmod 600 "$TARGET/config.env"
  warn "已生成 $TARGET/config.env —— 填好 VITALS_PASS / SMTP_* 后重新执行 bash install.sh"
  echo
  prod_help
  exit 0
fi
chmod 600 "$TARGET/config.env"

if grep -q 'CHANGE_ME' "$TARGET/config.env"; then
  die "config.env 里还有 CHANGE_ME，先把 VITALS_PASS 改掉（至少 16 位）"
fi

say "检查 prod 侧的只读探针"
prod_ssh="$(sed -n 's/^PROD_SSH=//p' "$TARGET/config.env" | tail -1 | tr -d '"'"'"'[:space:]')"
if [ -n "$prod_ssh" ] && ssh -T -o BatchMode=yes -o ConnectTimeout=6 -o StrictHostKeyChecking=accept-new -o LogLevel=ERROR "$prod_ssh" 'test -x ~/vitals-probe/probe.sh' 2>/dev/null; then
  ok "prod 探针已就位（$prod_ssh:~/vitals-probe/probe.sh）"
else
  warn "prod 上还没装探针（页面能开，但容器/磁盘/内存那几项会是空的）"
  echo
  prod_help
  echo
fi

say "启动"
if [ -f "$TARGET/vitals.pid" ]; then
  kill "$(cat "$TARGET/vitals.pid")" 2>/dev/null || true
  sleep 0.3
fi
pkill -f "python3 .*vitals\.py" 2>/dev/null || true
sleep 0.3
( cd "$TARGET" && nohup python3 vitals.py >> vitals.log 2>&1 & echo $! > vitals.pid )
sleep 0.5

# @reboot 自启（用户 crontab，不需要 sudo）
marker="# teachloop-vitals"
if crontab -l 2>/dev/null | grep -qF "$marker"; then
  ok "crontab @reboot 已存在"
else
  { crontab -l 2>/dev/null || true; echo "@reboot cd $TARGET && nohup python3 vitals.py >> vitals.log 2>&1 & $marker"; } | crontab -
  ok "已加 crontab @reboot 自启"
fi

port="$(port_of)"
for _ in $(seq 1 40); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' -m 2 "http://127.0.0.1:$port/vitals/healthz" || true)" = "200" ] && break
  sleep 0.25
done

say "完成"
user="$(sed -n 's/^VITALS_USER=//p' "$TARGET/config.env" | tail -1 | tr -d '"'"'"'[:space:]')"
pass="$(sed -n 's/^VITALS_PASS=//p' "$TARGET/config.env" | tail -1 | tr -d '"'"'"'[:space:]')"
printf '  看板：http://%s:%s/vitals/\n' "$(lan_ip)" "$port"
printf '  用户：%s  口令：%s\n' "${user:-teachloop}" "${pass:-（见 config.env）}"
printf '  日志：%s/vitals.log\n' "$TARGET"
printf '  自检：bash install.sh status\n'
