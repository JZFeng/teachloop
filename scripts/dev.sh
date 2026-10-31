#!/usr/bin/env bash
# TeachLoop 一键本地开发：启动 Postgres → 构建/seed → 后端 → 前端；Ctrl-C 自动清理。
# 用法：
#   ./scripts/dev.sh                 # 默认真实 AI 引擎，不 seed（Key 在 /profile 或 /admin/ai 配）
#   ./scripts/dev.sh --seed          # 额外造演示数据（1 班 + 30 学生 + 1 试卷）
#   ./scripts/dev.sh --stub          # 批改与词汇内容生成都用替身引擎（无 Key 也能演示复习卡内容）
#   ./scripts/dev.sh --no-build --no-open --keep-db
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
BACKEND_PORT="${PORT:-8010}"
WEB_PORT="${WEB_PORT:-5173}"
# T411：本地 TTS sidecar 的端口，要和后端 tts.engine-url 对得上（默认 http://127.0.0.1:5002）。
TTS_PORT="${VOCAB_TTS_PORT:-5002}"
if [ -n "${VOCAB_TTS_ENGINE_URL:-}" ]; then
  TTS_PORT="${VOCAB_TTS_ENGINE_URL##*:}"
  TTS_PORT="${TTS_PORT%%/*}"
fi
DB_PORT="${DB_PORT:-55432}"
DB_CONTAINER="${DB_CONTAINER:-teachloop-pg}"
# 第二个环境（scripts/env.sh）要独立的库与附件目录，所以这两项可用环境变量覆盖；
# 不设时行为与以前完全一致（库 teachloop、附件 ./uploads）。
DB_NAME="${DB_NAME:-teachloop}"
STORAGE_LOCAL_DIR="${STORAGE_LOCAL_DIR:-$ROOT/uploads}"
# 2026-10-29：并行会话要能在同一台机器上再起一个隔离实例（不同端口 + 不同运行副本
# + 不同日志文件）时，用 DEV_LOG_DIR / DEV_RUN_JAR 覆盖，互不覆盖对方的日志与 jar。
LOG_DIR="${DEV_LOG_DIR:-$ROOT/.dsh/logs}"
JAR="$ROOT/teachloop-app/target/teachloop-app-0.1.0-SNAPSHOT.jar"

SEED=0
BUILD=1
AI=1
OPEN=1
KEEP_DB=0

for arg in "$@"; do
  case "$arg" in
    --seed) SEED=1 ;;
    --rebuild) BUILD=1 ;;
    --no-build) BUILD=0 ;;
    --ai) AI=1 ;;
    --stub) AI=0 ;;
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
PIPER_PID=""
TTS_STATE="browser"
STALE_PID=""
STARTED_DB=0
CLEANED=0

say() { printf '\033[1;36m[dev]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[dev]\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31m[dev]\033[0m %s\n' "$*" >&2; exit 1; }

port_pid() { lsof -ti tcp:"$1" 2>/dev/null | head -1; }

# ---------- Piper TTS sidecar（T410/T411；2026-10-31 补齐 dev）----------
# 后端把 /api/vocab/audio 的合成打到 127.0.0.1:5002（application.yml 的 tts.engine-url），
# 但 dev.sh 以前从来不启动 sidecar，于是每次点喇叭都静默回落浏览器 SpeechSynthesis：
# 听感是「尾音杂音 / inflect 重音不对」，而 7ac0945 修的是 Piper 的 WAV 链路，压根没跑到。
# 这里在「piper 可执行 + 模型文件」都在时自动拉起 sidecar，退出时随 dev 一起清理；
# 缺依赖只打一条明确提示（不静默、不阻塞 dev），此时仍可回落浏览器。
tts_sidecar_up() {
  local piper_bin="${PIPER_BIN:-}"
  if [ -z "$piper_bin" ]; then
    if [ -x "$ROOT/.dsh/tts/venv/bin/piper" ]; then
      piper_bin="$ROOT/.dsh/tts/venv/bin/piper"
    elif command -v piper >/dev/null 2>&1; then
      piper_bin="$(command -v piper)"
    fi
  fi
  local model_dir="${PIPER_MODEL_DIR:-$ROOT/.dsh/tts/models}"
  local us_voice="${VOCAB_TTS_VOICE_US:-en_US-amy-medium}"
  local uk_voice="${VOCAB_TTS_VOICE_UK:-en_GB-alan-medium}"
  local us_model="$model_dir/${us_voice}.onnx"
  local uk_model="$model_dir/${uk_voice}.onnx"

  if [ -z "$piper_bin" ] || [ ! -x "$piper_bin" ] || [ ! -f "$us_model" ]; then
    TTS_STATE="浏览器回落（缺 Piper）"
    warn "本地 Piper 未安装，发音会回落到浏览器语音（尾音/重音不如本地模型）："
    warn "  装一次：bash scripts/setup-tts.sh    说明：docs/release/tts-local.md"
    return
  fi
  if ! command -v python3 >/dev/null 2>&1; then
    TTS_STATE="浏览器回落（缺 python3）"
    warn "找不到 python3，无法启动 TTS sidecar；发音会回落浏览器"
    return
  fi

  local pid
  pid="$(port_pid "$TTS_PORT")"
  if [ -n "$pid" ]; then
    TTS_STATE="Piper :${TTS_PORT}（复用已有进程）"
    say "复用已在运行的 TTS sidecar :${TTS_PORT}（PID ${pid}）"
    return
  fi

  say "启动 Piper TTS sidecar :${TTS_PORT}（日志 ${LOG_DIR}/dev-piper.log）"
  PIPER_BIN="$piper_bin" PIPER_MODEL="$us_model" PIPER_MODEL_DIR="$model_dir" \
    PIPER_DEFAULT_VOICE="$us_voice" PIPER_PORT="$TTS_PORT" \
    python3 "$ROOT/docker/piper-sidecar.py" >> "$LOG_DIR/dev-piper.log" 2>&1 &
  PIPER_PID=$!
  local ok=0
  for _ in $(seq 1 25); do
    if curl -fsS "http://127.0.0.1:${TTS_PORT}/health" >/dev/null 2>&1; then ok=1; break; fi
    kill -0 "$PIPER_PID" 2>/dev/null || break
    sleep 0.2
  done
  if [ "$ok" = "1" ]; then
    if [ -f "$uk_model" ]; then
      TTS_STATE="Piper :${TTS_PORT}（${us_voice} / ${uk_voice}）"
    else
      TTS_STATE="Piper :${TTS_PORT}（${us_voice}；缺 ${uk_voice}，英音回落浏览器）"
      warn "缺英音模型 ${uk_model}：英音喇叭会回落浏览器（bash scripts/setup-tts.sh 可补）"
    fi
    say "TTS sidecar 就绪：${TTS_STATE}"
  else
    TTS_STATE="浏览器回落（sidecar 启动失败）"
    warn "TTS sidecar 启动失败，见 ${LOG_DIR}/dev-piper.log；发音会回落浏览器"
  fi
}

cleanup() {
  if [ "$CLEANED" = "1" ]; then return; fi
  CLEANED=1
  trap - EXIT INT TERM
  say "正在清理…"
  if [ -n "$PIPER_PID" ] && kill -0 "$PIPER_PID" 2>/dev/null; then
    kill -TERM "$PIPER_PID" 2>/dev/null || true
  fi
  if [ -n "$STALE_PID" ] && kill -0 "$STALE_PID" 2>/dev/null; then
    kill -TERM "$STALE_PID" 2>/dev/null || true
  fi
  for pid in "$WEB_PID" "$BACK_PID"; do
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      kill -TERM "$pid" 2>/dev/null || true
    fi
  done
  sleep 1
  # 兜底：清掉本脚本起的进程残留的端口占用。
  # 2026-10-29 事故：第二个 dev.sh 在下面的「端口已被占用」检查处 die，清理逻辑却把
  # **第一个** dev.sh 的后端与 vite 一起杀了（两个会话互相踢）。所以只在本次确实启动过
  # 服务时才扫描端口：在端口检查处 die 的实例，BACK_PID/WEB_PID 都为空，不该动别人。
  if [ -n "$BACK_PID" ] || [ -n "$WEB_PID" ]; then
    for p in "$WEB_PORT" "$BACKEND_PORT"; do
      pid="$(port_pid "$p")"
      if [ -n "$pid" ]; then
        kill -TERM "$pid" 2>/dev/null || true
        sleep 1
        pid="$(port_pid "$p")"
        [ -n "$pid" ] && kill -KILL "$pid" 2>/dev/null || true
      fi
    done
  fi
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

# 2026-10-29：并行会话跑 `make build` 会**覆盖** target 里的 jar；正在运行的 JVM 之后
# 加载不到类，会以 `NoClassDefFoundError: ch/qos/logback/classic/spi/ThrowableProxy`
# 退出（当天实际发生过一次，dev 被连坐清理）。启动前拷一份副本再跑，副本不受重建影响。
RUN_JAR="${DEV_RUN_JAR:-$ROOT/.dsh/run/teachloop-dev.jar}"
mkdir -p "$ROOT/.dsh/run"
cp "$JAR" "$RUN_JAR" || die "拷贝运行副本失败：${RUN_JAR}"

# ---------- 可选 seed ----------
if [ "$SEED" = "1" ]; then
  say "写入演示数据（可重复）…"
  DB_URL="jdbc:postgresql://localhost:$DB_PORT/$DB_NAME" DB_USER=teachloop DB_PASSWORD=teachloop \
    STORAGE_LOCAL_DIR="$STORAGE_LOCAL_DIR" \
    SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}" \
    "$JAVA_HOME/bin/java" -jar "$JAR" --teachloop.seed.demo=true --seed-exit --server.port=0 \
    > "$LOG_DIR/dev-seed.log" 2>&1 || { tail -30 "$LOG_DIR/dev-seed.log"; die "seed 失败"; }
fi

# ---------- 后端 ----------
say "启动后端 :${BACKEND_PORT}（日志 ${LOG_DIR}/dev-backend.log）"
AI_ENV="stub"
[ "$AI" = "1" ] && AI_ENV="ai"
DB_URL="jdbc:postgresql://localhost:$DB_PORT/$DB_NAME" DB_USER=teachloop DB_PASSWORD=teachloop \
  STORAGE_LOCAL_DIR="$STORAGE_LOCAL_DIR" \
  SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}" \
  PORT="$BACKEND_PORT" TEACHLOOP_GRADING_ENGINE="$AI_ENV" \
  TEACHLOOP_APP_BASE_URL="${TEACHLOOP_APP_BASE_URL:-http://127.0.0.1:5173}" \
  VOCAB_GENERATION_ENGINE="$AI_ENV" \
  "$JAVA_HOME/bin/java" -jar "$RUN_JAR" > "$LOG_DIR/dev-backend.log" 2>&1 &
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

# ---------- TTS sidecar（可选，缺依赖只提示）----------
tts_sidecar_up

# ---------- 前端 ----------
if [ ! -d "$ROOT/web/node_modules" ]; then
  say "安装前端依赖（pnpm install）…"
  (cd "$ROOT/web" && pnpm install --store-dir .pnpm-store) > "$LOG_DIR/dev-web-install.log" 2>&1 \
    || { tail -40 "$LOG_DIR/dev-web-install.log"; die "pnpm install 失败"; }
fi
say "启动前端 :${WEB_PORT}（日志 ${LOG_DIR}/dev-web.log）"
(cd "$ROOT/web" && TEACHLOOP_API="http://127.0.0.1:$BACKEND_PORT" WEB_PORT="$WEB_PORT" \
  exec pnpm run dev --host 127.0.0.1 --port "$WEB_PORT") \
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
  AI      默认 ${AI_ENV}；真实 AI 需先在 /profile 或 /admin/ai 保存 Key，--stub 可切回替身
  TTS     ${TTS_STATE}

  教师    teacher@teachloop.local / teachloop123
  学生    student@teachloop.local / teachloop123
  （--seed 还会创建 student01…30@teachloop.local）

  Ctrl-C 结束并清理进程与数据库容器
BANNER

[ "$OPEN" = "1" ] && command -v open >/dev/null && open "http://127.0.0.1:$WEB_PORT" >/dev/null 2>&1 || true

# ---------- 过期代码提示 ----------
# 用副本跑挡住了「别的构建覆盖 jar → 运行中的 JVM 崩掉」，但代价是可能静默跑旧代码。
# 每 10 秒比一次 mtime：target jar 变得比运行副本新，就明确提示「重启 dev 生效」。
# `make dev` 本身永远是「现编现跑」，这条只管「dev 已经开着、别人又构建了」的情况。
(
  last=""
  while kill -0 "$BACK_PID" 2>/dev/null; do
    sleep 10
    [ -f "$JAR" ] || continue
    if [ "$JAR" -nt "$RUN_JAR" ]; then
      stamp="$(date -r "$JAR" "+%H:%M:%S" 2>/dev/null || date -d "@$(stat -c %Y "$JAR" 2>/dev/null)" "+%H:%M:%S" 2>/dev/null)"
      if [ "$stamp" != "$last" ]; then
        last="$stamp"
        warn "target jar 已更新（${stamp}），当前 dev 仍跑旧代码 —— 重启 dev 生效"
      fi
    fi
  done
) &
STALE_PID=$!

# ---------- 守候 ----------
while kill -0 "$BACK_PID" 2>/dev/null && kill -0 "$WEB_PID" 2>/dev/null; do
  sleep 2
done
warn "后端或前端已退出，准备清理"
tail -20 "$LOG_DIR/dev-backend.log" 2>/dev/null || true
tail -20 "$LOG_DIR/dev-web.log" 2>/dev/null || true
exit 1
