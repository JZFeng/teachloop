#!/usr/bin/env bash
# TeachLoop 一键本地开发：启动 Postgres → 构建/seed → 后端 → 前端；Ctrl-C 自动清理。
# 用法：
#   ./scripts/dev.sh                 # 默认 stub 判分，不 seed
#   ./scripts/dev.sh --seed          # 额外造演示数据（1 班 + 30 学生 + 1 试卷）
#   ./scripts/dev.sh --ai            # 用真实 AI 引擎（需先在 /admin/ai 保存 Key）
#   ./scripts/dev.sh --no-build --no-open --keep-db
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
BACKEND_PORT="${PORT:-8010}"
WEB_PORT="${WEB_PORT:-5173}"
DB_PORT="${DB_PORT:-55432}"
DB_CONTAINER="${DB_CONTAINER:-teachloop-pg}"
LOG_DIR="$ROOT/.dsh/logs"
JAR="$ROOT/teachloop-app/target/teachloop-app-0.1.0-SNAPSHOT.jar"

SEED=0
BUILD=1
AI=0
OPEN=1
KEEP_DB=0

for arg in "$@"; do
  case "$arg" in
    --seed) SEED=1 ;;
    --rebuild) BUILD=1 ;;
    --no-build) BUILD=0 ;;
    --ai) AI=1 ;;
    --no-open) OPEN=0 ;;
    --keep-db) KEEP_DB=1 ;;
    -h|--help)
      awk 'NR>1 && /^#/ {sub(/^# ?/, ""); print; next} NR>1 {exit}' "$0"
      exit 0 ;;
    *) echo "未知参数: ${arg}（--help 查看用法）" >&2; exit 2 ;;
  esac
done

mkdir -p "$LOG_DIR"
BACK_PID=""
WEB_PID=""
STARTED_DB=0
CLEANED=0

say() { printf '\033[1;36m[dev]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[dev]\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31m[dev]\033[0m %s\n' "$*" >&2; exit 1; }

port_pid() { lsof -ti tcp:"$1" 2>/dev/null | head -1; }

cleanup() {
  if [ "$CLEANED" = "1" ]; then return; fi
  CLEANED=1
  trap - EXIT INT TERM
  say "正在清理…"
  for pid in "$WEB_PID" "$BACK_PID"; do
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      kill -TERM "$pid" 2>/dev/null || true
    fi
  done
  sleep 1
  # 兜底：只杀本脚本启动前确认空闲的端口，避免误伤其他进程
  for p in "$WEB_PORT" "$BACKEND_PORT"; do
    pid="$(port_pid "$p")"
    if [ -n "$pid" ]; then
      kill -TERM "$pid" 2>/dev/null || true
      sleep 1
      pid="$(port_pid "$p")"
      [ -n "$pid" ] && kill -KILL "$pid" 2>/dev/null || true
    fi
  done
  if [ "$KEEP_DB" = "0" ] && [ "$STARTED_DB" = "1" ]; then
    say "停止 Postgres 容器 $DB_CONTAINER"
    docker stop "$DB_CONTAINER" >/dev/null 2>&1 || true
  elif [ "$KEEP_DB" = "1" ]; then
    say "保留 Postgres 容器（--keep-db）"
  fi
  say "已退出。日志：$LOG_DIR/dev-backend.log / dev-web.log"
}
trap cleanup EXIT INT TERM

command -v docker >/dev/null || die "未找到 docker"
command -v java >/dev/null || [ -x "$JAVA_HOME/bin/java" ] || die "未找到 Java 21（设置 JAVA_HOME）"
command -v pnpm >/dev/null || die "未找到 pnpm（make web-install 需要）"
command -v lsof >/dev/null || die "未找到 lsof"

for p in "$BACKEND_PORT" "$WEB_PORT"; do
  pid="$(port_pid "$p")"
  [ -n "$pid" ] && die "端口 ${p} 已被占用（PID ${pid}）；先停掉它再运行"
done

# ---------- Postgres ----------
if docker ps --format '{{.Names}}' | grep -qx "$DB_CONTAINER"; then
  say "复用已在运行的 Postgres 容器 $DB_CONTAINER"
else
  if docker ps -a --format '{{.Names}}' | grep -qx "$DB_CONTAINER"; then
    say "启动已有 Postgres 容器 $DB_CONTAINER"
    docker start "$DB_CONTAINER" >/dev/null || die "启动 $DB_CONTAINER 失败"
  else
    say "创建 Postgres 容器 ${DB_CONTAINER}（:${DB_PORT}）"
    docker run -d --name "$DB_CONTAINER" \
      -e POSTGRES_USER=teachloop -e POSTGRES_PASSWORD=teachloop -e POSTGRES_DB=teachloop \
      -p "$DB_PORT:5432" postgres:16-alpine >/dev/null || die "创建 Postgres 失败"
  fi
  STARTED_DB=1
fi

say "等待 Postgres 就绪…"
ready=0
for _ in $(seq 1 40); do
  if docker exec "$DB_CONTAINER" pg_isready -U teachloop -d teachloop >/dev/null 2>&1; then ready=1; break; fi
  sleep 1
done
[ "$ready" = "1" ] || die "Postgres 40 秒内未就绪，见 docker logs $DB_CONTAINER"

# 无论容器是本次新建还是复用，退出时默认都停掉；--keep-db 可保留。
STARTED_DB=1

# ---------- 构建 ----------
if [ "$BUILD" = "1" ]; then
  say "构建（make build）…"
  make build > "$LOG_DIR/dev-build.log" 2>&1 || { tail -40 "$LOG_DIR/dev-build.log"; die "构建失败"; }
fi
[ -f "$JAR" ] || die "找不到 ${JAR}；去掉 --no-build 再试"

# ---------- 可选 seed ----------
if [ "$SEED" = "1" ]; then
  say "写入演示数据（可重复）…"
  DB_URL="jdbc:postgresql://localhost:$DB_PORT/teachloop" DB_USER=teachloop DB_PASSWORD=teachloop \
    "$JAVA_HOME/bin/java" -jar "$JAR" --teachloop.seed.demo=true --seed-exit --server.port=0 \
    > "$LOG_DIR/dev-seed.log" 2>&1 || { tail -30 "$LOG_DIR/dev-seed.log"; die "seed 失败"; }
fi

# ---------- 后端 ----------
say "启动后端 :${BACKEND_PORT}（日志 ${LOG_DIR}/dev-backend.log）"
AI_ENV="stub"
[ "$AI" = "1" ] && AI_ENV="ai"
DB_URL="jdbc:postgresql://localhost:$DB_PORT/teachloop" DB_USER=teachloop DB_PASSWORD=teachloop \
  PORT="$BACKEND_PORT" TEACHLOOP_GRADING_ENGINE="$AI_ENV" \
  "$JAVA_HOME/bin/java" -jar "$JAR" > "$LOG_DIR/dev-backend.log" 2>&1 &
BACK_PID=$!

say "等待后端健康检查…"
ok=0
for _ in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:$BACKEND_PORT/healthz" >/dev/null 2>&1; then ok=1; break; fi
  if ! kill -0 "$BACK_PID" 2>/dev/null; then break; fi
  sleep 1
done
if [ "$ok" != "1" ]; then
  tail -40 "$LOG_DIR/dev-backend.log" || true
  die "后端未通过 /healthz"
fi

# ---------- 前端 ----------
if [ ! -d "$ROOT/web/node_modules" ]; then
  say "安装前端依赖（pnpm install）…"
  (cd "$ROOT/web" && pnpm install --store-dir .pnpm-store) > "$LOG_DIR/dev-web-install.log" 2>&1 \
    || { tail -40 "$LOG_DIR/dev-web-install.log"; die "pnpm install 失败"; }
fi
say "启动前端 :${WEB_PORT}（日志 ${LOG_DIR}/dev-web.log）"
(cd "$ROOT/web" && exec pnpm run dev --host 127.0.0.1 --port "$WEB_PORT") \
  > "$LOG_DIR/dev-web.log" 2>&1 &
WEB_PID=$!

say "等待前端…"
ok=0
for _ in $(seq 1 40); do
  if curl -fsS "http://127.0.0.1:$WEB_PORT" >/dev/null 2>&1; then ok=1; break; fi
  if ! kill -0 "$WEB_PID" 2>/dev/null; then break; fi
  sleep 1
done
if [ "$ok" != "1" ]; then
  tail -40 "$LOG_DIR/dev-web.log" || true
  die "前端未启动"
fi

cat <<BANNER

  TeachLoop 已就绪

  前端    http://127.0.0.1:$WEB_PORT
  后端    http://127.0.0.1:$BACKEND_PORT
  初始化  http://127.0.0.1:$WEB_PORT/setup   （还没有管理员时）
  AI      默认 ${AI_ENV}；真实 AI 需在 /admin/ai 保存 Key 后用 --ai 重启

  教师    teacher@teachloop.local / teachloop123
  学生    student@teachloop.local / teachloop123
  （--seed 还会创建 student01…30@teachloop.local）

  Ctrl-C 结束并清理进程与数据库容器
BANNER

[ "$OPEN" = "1" ] && command -v open >/dev/null && open "http://127.0.0.1:$WEB_PORT" >/dev/null 2>&1 || true

# ---------- 守候 ----------
while kill -0 "$BACK_PID" 2>/dev/null && kill -0 "$WEB_PID" 2>/dev/null; do
  sleep 2
done
warn "后端或前端已退出，准备清理"
tail -20 "$LOG_DIR/dev-backend.log" 2>/dev/null || true
tail -20 "$LOG_DIR/dev-web.log" 2>/dev/null || true
exit 1
