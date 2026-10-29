#!/usr/bin/env bash
# prod 健康一眼看：公网入口 → 容器 → 应用 → worker → 主机资源，最后给结论与退出码。
#
# 只读：只跑 curl / docker ps / docker inspect / docker logs / df / free，不 build、不重启、
# 不改 prod 上任何状态。可以随时跑，也可以挂到 cron 里做每小时自检。
#
# 用法：
#   bash scripts/prod-health.sh              # 默认探 https://teachloop.org + jzfeng@192.168.68.166
#   make prod-health
#   PUBLIC_URL=https://staging.example.com PROD_HOST=me@1.2.3.4 bash scripts/prod-health.sh
#
# 退出码：0 = 全绿或有非致命告警；1 = 有致命项（公网/回环探针非 200、容器未起、SSH 不通）。
#
# 各层能证明什么、不能证明什么：
#   公网 deep 探针  证 Cloudflare Tunnel → nginx → app → DB 整条链（含 PDF 中文字体）
#   回环 :8011      绕过 nginx/隧道，只证 app 本身
#   回环 :8010      只证 nginx 反代那一段
#   容器 healthy    app 的 compose healthcheck 同时探 /healthz?deep=1 与 sidecar :5002/health
#   worker          没有 HTTP 探针（compose healthcheck: disable），只能看 Up + 日志错误数
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

PUBLIC_URL="${PUBLIC_URL:-https://teachloop.org}"
PROD_HOST="${PROD_HOST:-jzfeng@192.168.68.166}"
# 相对远端 $HOME 的 compose 目录，以及两个容器名（名字随 compose 项目名变，故可覆盖）
PROD_COMPOSE_DIR="${PROD_COMPOSE_DIR:-teachloop}"
APP_CONTAINER="${APP_CONTAINER:-teachloop-app-1}"
WORKER_CONTAINER="${WORKER_CONTAINER:-teachloop-worker-1}"
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=8 -o StrictHostKeyChecking=accept-new)
DISK_WARN="${DISK_WARN:-85}"
MEM_WARN="${MEM_WARN:-85}"

for v in "$PROD_COMPOSE_DIR" "$APP_CONTAINER" "$WORKER_CONTAINER" "$PROD_HOST" "$PUBLIC_URL"; do
  case "$v" in *"'"*) echo "参数里不能含单引号：$v" >&2; exit 2 ;; esac
done

ok=0
warn=0
fail=0
paok()  { printf '  \033[32m✓\033[0m %s\n' "$*"; ok=$((ok + 1)); }
pawn()  { printf '  \033[33m!\033[0m %s\n' "$*"; warn=$((warn + 1)); }
pabbr() { printf '  \033[31m✗\033[0m %s\n' "$*"; fail=$((fail + 1)); }
sect()  { printf '\n\033[1m%s\033[0m\n' "$*"; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

START=$(date +%s)
echo "prod 健康检查  $(date '+%Y-%m-%d %H:%M:%S %Z')"
echo "公网入口 $PUBLIC_URL   小主机 $PROD_HOST"

# ---------- 1. 公网入口（本机发起，不经 ssh） ----------
sect "1. 公网入口（Cloudflare Tunnel → nginx → app）"
http() { # $1 = 路径，$2 = 输出文件；回显 "<code> <time_total>"
  curl -sS -m 12 -o "$2" -w '%{http_code} %{time_total}' "$PUBLIC_URL$1" 2>"$TMP/err" || true
}

read -r code secs < <(http "/healthz?deep=1" "$TMP/deep")
body="$(cat "$TMP/deep" 2>/dev/null || true)"
if [ "$code" = "200" ] && printf '%s' "$body" | grep -q '"db":"UP"' && printf '%s' "$body" | grep -q '"pdfFont":"UP"'; then
  paok "deep 探针 200（${secs}s）$body"
elif [ "$code" = "000" ]; then
  pabbr "deep 探针连不上：$(tr -d '\n' < "$TMP/err" 2>/dev/null | cut -c1-120)"
else
  pabbr "deep 探针 HTTP ${code}（期望 200）$body"
fi

read -r code secs < <(http "/" "$TMP/root")
if [ "$code" = "200" ] && grep -qi '<html' "$TMP/root" 2>/dev/null; then
  paok "前端首页 200（${secs}s，返回 index.html）"
else
  pabbr "前端首页 HTTP ${code}（期望 200）"
fi

read -r code secs < <(http "/actuator/health" "$TMP/act")
if [ "$code" = "200" ] && grep -q '"UP"' "$TMP/act" 2>/dev/null; then
  paok "actuator/health 200 $(cat "$TMP/act" 2>/dev/null)"
else
  pawn "actuator/health HTTP ${code}（唯一真源仍是 /healthz?deep=1）"
fi

# ---------- 2. 小主机与容器（一次 ssh，全部只读） ----------
sect "2. 小主机与容器（${PROD_HOST}）"
REMOTE_SCRIPT=$(cat <<EOS
COMPOSE_DIR='$PROD_COMPOSE_DIR'
APP_CONTAINER='$APP_CONTAINER'
WORKER_CONTAINER='$WORKER_CONTAINER'
cd "\$HOME/\$COMPOSE_DIR" 2>/dev/null || { echo 'FACT=no-compose-dir'; exit 0; }
echo 'FACT=compose-ok'
docker compose -f docker-compose.prod.yml ps --format '{{.Name}}|{{.Service}}|{{.Status}}' 2>/dev/null | sed 's/^/PS=/'
for c in \$(docker compose -f docker-compose.prod.yml ps -q 2>/dev/null); do
  docker inspect -f 'RESTART={{.Name}}|{{.RestartCount}}' "\$c" 2>/dev/null
done
df -h / | tail -1 | awk '{print "DISK=" \$2 "|" \$3 "|" \$4 "|" \$5}'
free -m | awk '/^Mem:/{print "MEM=" \$2 "|" \$3 "|" \$7}'
awk '{print "LOAD=" \$1}' /proc/loadavg
printf 'APP8011='; curl -s -m 6 -w '|%{http_code}' 'http://127.0.0.1:8011/healthz?deep=1' 2>/dev/null; echo
printf 'NGINX8010='; curl -s -m 6 -w '|%{http_code}' 'http://127.0.0.1:8010/healthz?deep=1' 2>/dev/null; echo
docker exec "\$APP_CONTAINER" sh -c 'f=/app/logs/requests-\$(date +%F).jsonl; if [ -f "\$f" ]; then echo "LOGLINES=\$(wc -l < "\$f")"; echo "S5XX=\$(grep -c "\\"status\\":5" "\$f")"; else echo "LOGLINES=0"; echo "S5XX=0"; fi' 2>/dev/null
echo "WORKERERR=\$(docker logs --since 24h "\$WORKER_CONTAINER" 2>&1 | grep -ciE 'ERROR|Exception')"
echo 'FACT=done'
EOS
)

if ! REMOTE_OUT=$(ssh "${SSH_OPTS[@]}" "$PROD_HOST" 'bash -s' <<<"$REMOTE_SCRIPT" 2>"$TMP/ssherr"); then
  pabbr "ssh 连不上：$(tr -d '\n' < "$TMP/ssherr" | cut -c1-160)"
  REMOTE_OUT=""
fi
fact() { printf '%s\n' "${REMOTE_OUT:-}" | sed -n "s/^$1=//p" | head -1; }

if [ -n "${REMOTE_OUT:-}" ]; then
  compose_ok=0
  if printf '%s\n' "$REMOTE_OUT" | grep -q '^FACT=compose-ok'; then
    compose_ok=1
    # 容器状态
    ps_lines="$(printf '%s\n' "$REMOTE_OUT" | sed -n 's/^PS=//p')"
    if [ -z "$ps_lines" ]; then
      pabbr "compose ps 为空：一个容器都没起"
    else
      while IFS='|' read -r name svc status; do
        [ -z "$name" ] && continue
        case "$svc" in
          app|db)
            if printf '%s' "$status" | grep -q '(healthy)'; then paok "$svc  $status"; else pabbr "$svc  ${status}（期望 healthy）"; fi ;;
          worker)
            if printf '%s' "$status" | grep -q '^Up'; then paok "worker  ${status}（无 HTTP 探针，见下方错误数）"; else pabbr "worker  $status"; fi ;;
          *)
            if printf '%s' "$status" | grep -q '^Up'; then paok "$svc  $status"; else pabbr "$svc  $status"; fi ;;
        esac
      done <<<"$ps_lines"
    fi

    # 重启次数
    restarts="$(printf '%s\n' "$REMOTE_OUT" | sed -n 's/^RESTART=//p')"
    if [ -z "$restarts" ]; then
      pawn "读不到 RestartCount"
    else
      busy="$(printf '%s\n' "$restarts" | awk -F'|' '$2 != "0"')"
      if [ -z "$busy" ]; then paok "四个容器重启次数全为 0（$(printf '%s\n' "$restarts" | wc -l | tr -d ' ') 个）"; else pawn "有容器重启过：$(printf '%s' "$busy" | tr '\n' ' ')"; fi
    fi
  else
    pabbr "小主机上找不到 ${PROD_COMPOSE_DIR}（相对 \$HOME）下的 docker-compose.prod.yml"
  fi

  if [ "$compose_ok" = 1 ]; then
  # 回环探针：区分「app 挂了」和「nginx/隧道断了」
  for pair in "APP8011:app 直连 :8011" "NGINX8010:经 nginx :8010"; do
    key="${pair%%:*}"
    label="${pair#*:}"
    val="$(fact "$key")"
    c="${val##*|}"
    if [ "$c" = "200" ]; then
      paok "回环 $label 200"
    elif [ -z "$val" ]; then
      pawn "回环 $label 无响应（容器里可能没有 curl）"
    else
      pabbr "回环 $label HTTP $c"
    fi
  done

  # 今日 5xx 与 worker 错误数
  lines="$(fact LOGLINES)"
  s5xx="$(fact S5XX)"
  if [ -n "$lines" ] && [ -n "$s5xx" ]; then
    if [ "$s5xx" = "0" ]; then paok "今日请求 ${lines} 条，5xx 0 条"; else pawn "今日请求 ${lines} 条，5xx ${s5xx} 条（看 /app/logs/requests-*.jsonl）"; fi
  else
    pawn "读不到请求日志（容器内 /app/logs）"
  fi
  werr="$(fact WORKERERR)"
  if [ -n "$werr" ]; then
    if [ "$werr" = "0" ]; then paok "worker 近 24h ERROR/Exception 0 行"; else pawn "worker 近 24h ERROR/Exception ${werr} 行"; fi
  else
    pawn "读不到 worker 日志"
  fi

  fi  # compose_ok

  # 主机资源
  disk="$(fact DISK)"
  if [ -n "$disk" ]; then
    used="${disk##*|}"; used="${used%\%}"
    detail="根分区 ${disk%%|*}，可用 $(printf '%s' "$disk" | cut -d'|' -f3)，已用 ${used}%"
    if [ "${used:-0}" -ge "$DISK_WARN" ]; then pawn "${detail}（阈值 ${DISK_WARN}%）"; else paok "$detail"; fi
  fi
  mem="$(fact MEM)"
  if [ -n "$mem" ]; then
    total="$(printf '%s' "$mem" | cut -d'|' -f1)"
    usedm="$(printf '%s' "$mem" | cut -d'|' -f2)"
    pct=$((usedm * 100 / total))
    if [ "$pct" -ge "$MEM_WARN" ]; then pawn "内存 ${usedm}/${total} MB（${pct}%，阈值 ${MEM_WARN}%）"; else paok "内存 ${usedm}/${total} MB（${pct}%）"; fi
  fi
  load="$(fact LOAD)"
  [ -n "$load" ] && paok "load1 ${load}"
fi

# ---------- 3. 结论 ----------
sect "3. 结论"
printf '  通过 %s 项，告警 %s 项，失败 %s 项，用时 %ss\n' "$ok" "$warn" "$fail" "$(( $(date +%s) - START ))"
if [ "$fail" -gt 0 ]; then
  printf '  \033[31m有致命项，按第 2 节顺序排：回环 :8011 也挂 = app 自身；只有 :8010 挂 = nginx/隧道；compose 目录或容器缺失 = 部署/机器问题。\033[0m\n'
  exit 1
elif [ "$warn" -gt 0 ]; then
  printf '  \033[33m服务可用，但有告警项需要看一眼（不等于挂了）。\033[0m\n'
  exit 0
else
  printf '  \033[32m全绿。\033[0m\n'
  exit 0
fi
