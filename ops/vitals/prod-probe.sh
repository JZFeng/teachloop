#!/usr/bin/env bash
# 在 prod 上被 vitals 通过 SSH「强制命令」调用：只输出只读事实，不接受参数，不写任何东西。
#
# 安装（在 prod 上一次，需要你执行）：
#   mkdir -p ~/vitals-probe && cp prod-probe.sh ~/vitals-probe/probe.sh && chmod +x ~/vitals-probe/probe.sh
#   # 再把监控机的公钥追加进 authorized_keys，并加 command= 限制（见 ops/vitals/README.md）
#
# 输出格式（每行 KEY=VALUE，监控机解析；故意用纯文本，不依赖 jq）：
#   HOST=teachloop
#   CPU=125                 千分比（12.5%），0.5 秒采样
#   MEM=7710|1848|5056      总|已用|可用（MB）
#   DISK=233G|35G|187G|16%  总|已用|可用|使用率
#   LOAD=0.02|0.08|0.09
#   UPTIME=46380            秒
#   CONTAINER=名字|服务|Status
#   RESTART=名字|重启次数
#   REQS=1340               今日请求条数
#   S5XX=0                  今日 5xx 条数
set -u

cd "$HOME/teachloop" 2>/dev/null || { echo "ERROR=no-compose-dir"; exit 0; }

echo "HOST=$(hostname 2>/dev/null || echo unknown)"

# CPU：0.5 秒两次 /proc/stat 取差，避免读到开机以来的平均值
read -r _ p1 p2 p3 p4 p5 p6 p7 _ < /proc/stat
sleep 0.5
read -r _ q1 q2 q3 q4 q5 q6 q7 _ < /proc/stat
total=$(( (q1 + q2 + q3 + q4 + q5 + q6 + q7) - (p1 + p2 + p3 + p4 + p5 + p6 + p7) ))
idle=$(( (q4 + q5) - (p4 + p5) ))
if [ "$total" -gt 0 ]; then
  echo "CPU=$(( (total - idle) * 1000 / total ))"
else
  echo "CPU=0"
fi

df -h / | tail -1 | awk '{print "DISK=" $2 "|" $3 "|" $4 "|" $5}'
free -m | awk '/^Mem:/{print "MEM=" $2 "|" $3 "|" $7}'
awk '{print "LOAD=" $1 "|" $2 "|" $3}' /proc/loadavg
awk '{print "UPTIME=" int($1)}' /proc/uptime

docker compose -f docker-compose.prod.yml ps --format '{{.Name}}|{{.Service}}|{{.Status}}' 2>/dev/null \
  | sed 's/^/CONTAINER=/'
for c in $(docker compose -f docker-compose.prod.yml ps -q 2>/dev/null); do
  docker inspect -f 'RESTART={{.Name}}|{{.RestartCount}}' "$c" 2>/dev/null
done

# 今日请求与 5xx：容器内日期按容器时区（+8），与文件名一致
docker exec teachloop-app-1 sh -c '
  f=/app/logs/requests-$(date +%F).jsonl
  echo "DAY=$(date +%F)"
  if [ -f "$f" ]; then
    echo "REQS=$(wc -l < "$f")"
    echo "S5XX=$(grep -c "\"status\":5" "$f" || true)"
  else
    echo "REQS=0"; echo "S5XX=0"
  fi
' 2>/dev/null || { echo "REQS="; echo "S5XX="; }
