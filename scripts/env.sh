#!/usr/bin/env bash
# 第二个运行环境（测试台）：用「构建快照」跑在另一组端口上，开发机上的改动不再影响它。
#
# 为什么不是简单换端口：
#   - jar 与 dist 都复制到 .envs/<name>/，开发时重新打包不会覆盖它正在跑的产物；
#   - 前端用 `vite preview` 服务静态 dist，不监听源码，所以开发时的编辑不会漏进去；
#   - 独立数据库 + 独立 uploads，两边的数据不会互相改。
#
# 用法：
#   ./scripts/env.sh deploy qa    # 构建 + 复制快照 + 重启（做完一个特性时用）
#   ./scripts/env.sh up qa        # 启动已有快照，不构建
#   ./scripts/env.sh down qa      # 停掉
#   ./scripts/env.sh status       # 8010/5173 与 8011/5174 的健康、版本与判分引擎
#   ./scripts/env.sh logs qa      # 跟踪测试台后端日志
#
# 判分引擎默认 ai（与 application.yml 一致）：配好 Key 的测试台跑真实 AI 批改。
# 只有「没 Key 也要看界面」的演示才用 TEACHLOOP_GRADING_ENGINE=stub ./scripts/env.sh up qa。
# 引擎写进 .envs/<name>/engine，banner 与 status 都会显示，不再静默回退成替身。
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
JAVA="$JAVA_HOME/bin/java"

NAME="${2:-qa}"
case "$NAME" in
  qa) DEFAULT_PORT=8011; DEFAULT_WEB_PORT=5174; DEFAULT_DB_PORT=55433 ;;
  *)  DEFAULT_PORT=8021; DEFAULT_WEB_PORT=5184; DEFAULT_DB_PORT=55434 ;;
esac
PORT="${PORT:-$DEFAULT_PORT}"
WEB_PORT="${WEB_PORT:-$DEFAULT_WEB_PORT}"
DB_PORT="${DB_PORT:-$DEFAULT_DB_PORT}"
DB_NAME="${DB_NAME:-teachloop_${NAME}}"
DB_CONTAINER="${DB_CONTAINER:-teachloop-pg-${NAME}}"
SHARE_DB="${QA_SHARE_DB:-0}"

ENV_DIR="$ROOT/.envs/$NAME"
JAR="$ENV_DIR/app.jar"
DIST="$ENV_DIR/web"
UPLOADS="$ENV_DIR/uploads"
VERSION_FILE="$ENV_DIR/VERSION"
BACK_PID_FILE="$ENV_DIR/backend.pid"
WEB_PID_FILE="$ENV_DIR/web.pid"

say()  { printf '\033[1;36m[%s]\033[0m %s\n' "$NAME" "$*"; }
warn() { printf '\033[1;33m[%s]\033[0m %s\n' "$NAME" "$*" >&2; }
die()  { printf '\033[1;31m[%s]\033[0m %s\n' "$NAME" "$*" >&2; exit 1; }

port_pid() { lsof -ti tcp:"$1" 2>/dev/null | head -1; }

ensure_docker() {
  command -v docker >/dev/null || die "未找到 docker"
  if [ "$SHARE_DB" = "1" ]; then
    # 复用开发库容器；此时 `make dev` 必须带 --keep-db，否则测试台的库会跟着停。
    DB_CONTAINER="${DEV_DB_CONTAINER:-teachloop-pg}"
    DB_PORT="${DEV_DB_PORT:-55432}"
    say "复用开发库容器 $DB_CONTAINER:${DB_PORT}，库名 $DB_NAME"
  fi
  if ! docker ps --format '{{.Names}}' | grep -qx "$DB_CONTAINER"; then
    if docker ps -a --format '{{.Names}}' | grep -qx "$DB_CONTAINER"; then
      say "启动已有容器 $DB_CONTAINER"
      docker start "$DB_CONTAINER" >/dev/null || die "启动 $DB_CONTAINER 失败"
    else
      say "创建 Postgres 容器 ${DB_CONTAINER}（:${DB_PORT}）"
      docker run -d --name "$DB_CONTAINER" \
        -e POSTGRES_USER=teachloop -e POSTGRES_PASSWORD=teachloop -e POSTGRES_DB=teachloop \
        -p "$DB_PORT:5432" postgres:16-alpine >/dev/null || die "创建 Postgres 失败"
    fi
  fi
  local ready=0
  for _ in $(seq 1 40); do
    if docker exec "$DB_CONTAINER" pg_isready -U teachloop >/dev/null 2>&1; then ready=1; break; fi
    sleep 1
  done
  [ "$ready" = "1" ] || die "Postgres 40 秒内未就绪，见 docker logs $DB_CONTAINER"

  # 独立库：不存在就建（建库后需要 seed 才有登录账号）
  local exists
  exists="$(docker exec "$DB_CONTAINER" psql -U teachloop -d postgres -tAc \
    "SELECT 1 FROM pg_database WHERE datname='$DB_NAME'" 2>/dev/null | tr -d ' \n')"
  if [ "$exists" = "1" ]; then
    return 1   # 已存在
  fi
  say "创建数据库 $DB_NAME"
  docker exec "$DB_CONTAINER" createdb -U teachloop "$DB_NAME" >/dev/null || die "建库失败"
  return 0     # 新建
}

write_version() {
  local rev dirty
  rev="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  dirty=""
  [ -n "$(git -C "$ROOT" status --porcelain 2>/dev/null | head -1)" ] && dirty="+dirty"
  mkdir -p "$ENV_DIR"
  {
    echo "version: ${rev}${dirty}"
    echo "built:   $(date '+%Y-%m-%d %H:%M:%S')"
    echo "backend: http://127.0.0.1:${PORT}"
    echo "web:     http://127.0.0.1:${WEB_PORT}"
    echo "db:      ${DB_CONTAINER}:${DB_PORT}/${DB_NAME}"
  } > "$VERSION_FILE"
}

stop_pid_file() {
  local file="$1"
  if [ -f "$file" ]; then
    local pid; pid="$(cat "$file" 2>/dev/null)"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      kill -TERM "$pid" 2>/dev/null || true
    fi
    rm -f "$file"
  fi
}

cmd_down() {
  stop_pid_file "$WEB_PID_FILE"
  stop_pid_file "$BACK_PID_FILE"
  # 兜底：只清理本环境自己的端口
  for p in "$WEB_PORT" "$PORT"; do
    local pid; pid="$(port_pid "$p")"
    if [ -n "$pid" ]; then
      kill -TERM "$pid" 2>/dev/null || true
      sleep 1
      pid="$(port_pid "$p")"
      [ -n "$pid" ] && kill -KILL "$pid" 2>/dev/null || true
    fi
  done
  say "已停止"
}

cmd_up() {
  [ -f "$JAR" ] || die "没有快照：先跑 ./scripts/env.sh deploy $NAME"
  [ -d "$DIST" ] || die "没有前端快照：先跑 ./scripts/env.sh deploy $NAME"

  local seeded=1
  ensure_docker && seeded=0

  for p in "$PORT" "$WEB_PORT"; do
    local pid; pid="$(port_pid "$p")"
    [ -n "$pid" ] && die "端口 ${p} 已被占用（PID ${pid}）；先 ./scripts/env.sh down $NAME"
  done

  mkdir -p "$UPLOADS"

  # 判分引擎：默认 ai，与 application.yml 一致；stub 只用于没 Key 的界面演示。
  local engine="${TEACHLOOP_GRADING_ENGINE:-ai}"
  case "$engine" in
    ai|stub) ;;
    *) die "TEACHLOOP_GRADING_ENGINE 只能是 ai 或 stub（当前：$engine）" ;;
  esac
  echo "$engine" > "$ENV_DIR/engine"
  [ "$engine" = "stub" ] && warn "判分引擎是 stub：批改结果不是真实 AI 评分，别拿去发布"

  say "启动后端 :${PORT}（判分引擎 ${engine}）"
  DB_URL="jdbc:postgresql://localhost:$DB_PORT/$DB_NAME" \
  DB_USER=teachloop DB_PASSWORD=teachloop \
  STORAGE_LOCAL_DIR="$UPLOADS" \
  VOCAB_CONTENT_STORE_PATH="$ENV_DIR/content-delta.db" \
  VOCAB_CONTENT_PACK_DIR="$ROOT/fixtures-local/vocab-content" \
  PORT="$PORT" TEACHLOOP_GRADING_ENGINE="$engine" \
    "$JAVA" -jar "$JAR" > "$ENV_DIR/backend.log" 2>&1 &
  echo $! > "$BACK_PID_FILE"

  local ok=0
  for _ in $(seq 1 90); do
    if curl -fsS "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1; then ok=1; break; fi
    kill -0 "$(cat "$BACK_PID_FILE")" 2>/dev/null || break
    sleep 1
  done
  if [ "$ok" != "1" ]; then
    tail -40 "$ENV_DIR/backend.log" || true
    die "后端未通过 /healthz"
  fi

  if [ "$seeded" = "0" ]; then
    say "新库首次启动：写入演示数据（可重复）"
    DB_URL="jdbc:postgresql://localhost:$DB_PORT/$DB_NAME" \
    DB_USER=teachloop DB_PASSWORD=teachloop STORAGE_LOCAL_DIR="$UPLOADS" \
      "$JAVA" -jar "$JAR" --teachloop.seed.demo=true --seed-exit --server.port=0 \
      > "$ENV_DIR/seed.log" 2>&1 || warn "seed 失败，见 $ENV_DIR/seed.log"
  fi

  say "启动前端 :${WEB_PORT}（vite preview，服务静态快照）"
  ( cd "$ROOT/web" && TEACHLOOP_API="http://127.0.0.1:$PORT" WEB_PORT="$WEB_PORT" \
      exec pnpm exec vite preview --outDir "$DIST" --port "$WEB_PORT" --host 127.0.0.1 ) \
      > "$ENV_DIR/web.log" 2>&1 &
  echo $! > "$WEB_PID_FILE"

  ok=0
  for _ in $(seq 1 40); do
    if curl -fsS "http://127.0.0.1:$WEB_PORT" >/dev/null 2>&1; then ok=1; break; fi
    kill -0 "$(cat "$WEB_PID_FILE")" 2>/dev/null || break
    sleep 1
  done
  if [ "$ok" != "1" ]; then
    tail -30 "$ENV_DIR/web.log" || true
    die "前端未启动"
  fi

  cat <<BANNER

  ${NAME} 测试台已就绪（快照，不受开发机改动影响）

  前端    http://127.0.0.1:${WEB_PORT}
  后端    http://127.0.0.1:${PORT}
  数据    ${DB_CONTAINER}:${DB_PORT}/${DB_NAME}
  附件    ${UPLOADS}
  版本    $(head -1 "$VERSION_FILE" 2>/dev/null | cut -d' ' -f2-)
  引擎    ${engine}$([ "$engine" = "ai" ] && echo "（真实 AI 批改，用配好的 DeepSeek Key）" || echo "（替身引擎，不是真实评分）")

  停掉    ./scripts/env.sh down ${NAME}

BANNER
}

cmd_deploy() {
  mkdir -p "$ENV_DIR"
  say "构建（jar + 前端 dist）…"
  make build > "$ENV_DIR/build.log" 2>&1 || { tail -40 "$ENV_DIR/build.log"; die "后端构建失败"; }
  ( cd "$ROOT/web" && pnpm build ) > "$ENV_DIR/web-build.log" 2>&1 \
    || { tail -40 "$ENV_DIR/web-build.log"; die "前端构建失败"; }

  cmd_down >/dev/null 2>&1 || true
  say "复制快照到 $ENV_DIR"
  cp "$ROOT/teachloop-app/target/teachloop-app-0.1.0-SNAPSHOT.jar" "$JAR" || die "复制 jar 失败"
  rm -rf "$DIST"
  mkdir -p "$DIST"
  cp -R "$ROOT/web/dist/." "$DIST/" || die "复制 dist 失败"
  write_version
  cmd_up
}

cmd_status() {
  printf '\n%-10s %-22s %-10s %s\n' "环境" "地址" "进程" "版本"
  for pair in "dev:8010:5173" "$NAME:$PORT:$WEB_PORT"; do
    local n bp wp
    IFS=: read -r n bp wp <<< "$pair"
    local back="down" web="down" ver="—"
    [ -n "$(port_pid "$bp")" ] && back="up" || back="down"
    [ -n "$(port_pid "$wp")" ] && web="up" || web="down"
    local health=""
    if [ "$back" = "up" ]; then
      curl -fsS "http://127.0.0.1:$bp/healthz" >/dev/null 2>&1 && health=" /healthz ok"
    fi
    if [ "$n" != "dev" ] && [ -f "$VERSION_FILE" ]; then
      ver="$(head -1 "$VERSION_FILE" | cut -d' ' -f2-)"
    fi
    printf '%-10s %-22s %-10s %s\n' \
      "$n" "http://127.0.0.1:$wp → :$bp" "web:$web backend:$back$health" "$ver"
  done
  echo
  if [ -f "$VERSION_FILE" ]; then sed 's/^/  /' "$VERSION_FILE"; echo; fi
  # 判分引擎记录在 .envs/<name>/engine：后端是 up/deploy 起的就一定有，便于回答「为什么不是真实 AI」。
  if [ -f "$ENV_DIR/engine" ]; then
    local engine_now; engine_now="$(cat "$ENV_DIR/engine")"
    [ -n "$(port_pid "$PORT")" ] \
      && printf '  判分引擎：%s\n\n' "$engine_now" \
      || printf '  判分引擎：%s（上次启动；后端当前未运行）\n\n' "$engine_now"
  else
    printf '  判分引擎：未知（当前进程不是本脚本启动的）\n\n'
  fi
}

cmd_logs() { tail -n 80 -f "$ENV_DIR/backend.log"; }

case "${1:-status}" in
  deploy) cmd_deploy ;;
  up)     cmd_up ;;
  down)   cmd_down ;;
  status) cmd_status ;;
  logs)   cmd_logs ;;
  -h|--help)
    awk 'NR>1 && /^#/ {sub(/^# ?/, ""); print; next} NR>1 {exit}' "$0"
    ;;
  *) die "未知命令：${1}（deploy / up / down / status / logs）" ;;
esac
