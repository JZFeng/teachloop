#!/usr/bin/env bash
# TeachLoop 一键部署到内网 Ubuntu（默认 ai-ubuntu / 192.168.68.101）。
#
# 用法：
#   scripts/deploy-ai-ubuntu.sh                     # 全量：构建前端 → 同步源码 → 远端构建镜像 → 起服务
#   SUDO_PASS=xxx scripts/deploy-ai-ubuntu.sh       # 首次需要安装 Docker 时用（只用于 apt 那几条）
#   HOST=192.168.68.1 USER=jzfeng WEB_PORT=8081 scripts/deploy-ai-ubuntu.sh
#   scripts/deploy-ai-ubuntu.sh --skip-web          # 前端 dist 已有，跳过 pnpm build
#   scripts/deploy-ai-ubuntu.sh --no-build          # 镜像已存在，只重启服务
#
# 做的事：
#   1. 确认 SSH 可达；目标机没有 docker 就装（Ubuntu 24.04 走 apt 的 docker.io + docker-compose-v2）
#   2. 本机构建前端 dist（需要 pnpm）
#   3. rsync 源码（排除 .git/node_modules/build 产物）与部署文件到目标机
#   4. 目标机原生构建镜像（amd64，比在 Mac 上交叉编译快）
#   5. 生成 .env（随机密钥，已存在则不覆盖）→ docker compose up -d → 等 /healthz
#
# 幂等：重复跑只会重建镜像并滚动重启；.env 与数据卷（pgdata/uploads）不会被动。
set -euo pipefail

HOST="${HOST:-192.168.68.101}"
DEPLOY_USER="${USER:-jzfeng}"
WEB_PORT="${WEB_PORT:-80}"
REMOTE_DIR="${REMOTE_DIR:-/home/${DEPLOY_USER}/teachloop}"
TAG="${TAG:-$(git rev-parse --short HEAD 2>/dev/null || date +%Y%m%d%H%M)}"
SKIP_WEB=0
NO_BUILD=0

for arg in "$@"; do
  case "$arg" in
    --skip-web) SKIP_WEB=1 ;;
    --no-build) NO_BUILD=1 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "未知参数: $arg" >&2; exit 2 ;;
  esac
done

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
die() { printf '\033[31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

rssh() { ssh -o ConnectTimeout=8 -o StrictHostKeyChecking=accept-new "${DEPLOY_USER}@${HOST}" "$@"; }

say "1/6 检查 SSH 与目标机"
rssh 'echo "  已连接: $(hostname) / $(uname -m)"' || die "SSH 不通：${DEPLOY_USER}@${HOST}"

if ! rssh 'command -v docker >/dev/null'; then
  say "目标机没有 Docker，开始安装（apt：docker.io + docker-compose-v2）"
  [ -n "${SUDO_PASS:-}" ] || die "需要 sudo 密码：SUDO_PASS=你的密码 $0"
  rssh "echo '$SUDO_PASS' | sudo -S -p '' apt-get update -qq" \
    && rssh "echo '$SUDO_PASS' | sudo -S -p '' DEBIAN_FRONTEND=noninteractive apt-get install -y -qq docker.io docker-compose-v2" \
    && rssh "echo '$SUDO_PASS' | sudo -S -p '' usermod -aG docker ${DEPLOY_USER}" \
    && rssh "echo '$SUDO_PASS' | sudo -S -p '' systemctl enable --now docker" \
    || die "Docker 安装失败（看上面的 apt 输出）"
  say "Docker 已装。新加的 docker 组要重连才生效，脚本会自己再试"
fi

rssh 'docker info >/dev/null 2>&1' || die "当前用户仍无 docker 权限；请重新登录 ${HOST} 后再跑本脚本（或让管理员把 ${DEPLOY_USER} 加进 docker 组）"
rssh 'docker compose version >/dev/null' || die "目标机缺 docker compose v2（apt: docker-compose-v2）"

say "2/6 本机构建前端"
if [ "$SKIP_WEB" = "1" ]; then
  echo "  跳过（--skip-web）"
else
  ( cd web && pnpm run build >/tmp/teachloop-web-build.log 2>&1 ) || {
    tail -20 /tmp/teachloop-web-build.log; die "前端构建失败（日志 /tmp/teachloop-web-build.log）"; }
  echo "  完成：web/dist"
fi
[ -f web/dist/index.html ] || die "web/dist/index.html 不存在"
# 校验产物自洽：index.html 引用的入口 JS 必须真的存在（构建被中断会留下半套文件，
# 2026-10-25 实际踩到：线上 dist 只剩 7 个文件、首页白屏）
ENTRY_JS="$(grep -oE 'assets/index-[A-Za-z0-9_-]+\.js' web/dist/index.html | head -1)"
[ -n "$ENTRY_JS" ] || die "dist/index.html 里找不到入口 JS，产物不完整"
[ -f "web/dist/$ENTRY_JS" ] || die "dist 产物不完整：$ENTRY_JS 缺失（重新 pnpm build）"
echo "  入口 JS 校验通过：$ENTRY_JS"

say "3/6 同步源码与部署文件到 ${HOST}:${REMOTE_DIR}"
rssh "mkdir -p '${REMOTE_DIR}'"
rsync -az --delete \
  --exclude '.git' --exclude '.idea' --exclude '.cache' --exclude '.pnpm-store' \
  --exclude '.npm-cache' --exclude 'node_modules' --exclude 'web/node_modules' \
  --exclude 'target' --exclude '*/target' --exclude 'logs' --exclude 'data' \
  --exclude 'uploads' --exclude 'web/dist' --exclude '.dsh' --exclude 'fixtures-local' \
  ./ "${DEPLOY_USER}@${HOST}:${REMOTE_DIR}/src/"
rsync -az deploy/app/docker-compose.yml deploy/app/nginx.conf "${DEPLOY_USER}@${HOST}:${REMOTE_DIR}/"
# 原地同步：nginx 把 ./dist 作为 bind mount，换目录（mv）会让容器挂在旧 inode 上直接 403。
# --delete-after：先传新文件再删旧的，中断不会留下「index.html 指向不存在的 JS」。
rsync -az --delete-after web/dist/ "${DEPLOY_USER}@${HOST}:${REMOTE_DIR}/dist/"
echo "  源码 / dist 已同步（原地，--delete-after）"

say "4/6 远端构建镜像 teachloop:${TAG}（amd64 原生，首次约 5–10 分钟）"
if [ "$NO_BUILD" = "1" ]; then
  echo "  跳过（--no-build）"
else
  rssh "cd '${REMOTE_DIR}' && docker build -t teachloop:${TAG} src/" || die "镜像构建失败"
fi

say "5/6 生成 .env（已存在则保留）并起服务"
if rssh "[ -f '${REMOTE_DIR}/.env' ]"; then
  rssh "cd '${REMOTE_DIR}' && sed -i \"s|^TEACHLOOP_IMAGE=.*|TEACHLOOP_IMAGE=teachloop:${TAG}|; s|^WEB_PORT=.*|WEB_PORT=${WEB_PORT}|\" .env"
  echo "  .env 已存在，只更新镜像 tag 与端口"
else
  # 本地生成再拷过去：避免把变量展开和 heredoc 混在 ssh 的字符串里
  LOCAL_ENV="$(mktemp)"
  DB_PASS="$(openssl rand -hex 16)"
  SECRET="$(openssl rand -base64 48 | tr -d '\n')"
  cat > "$LOCAL_ENV" <<ENVEOF
# 由 deploy-ai-ubuntu.sh 生成（$(date '+%F %T')）；不要提交到仓库
POSTGRES_PASSWORD=${DB_PASS}
DB_PASSWORD=${DB_PASS}
TEACHLOOP_SECURITY_SECRET=${SECRET}
TEACHLOOP_SEED_ENABLED=false
TEACHLOOP_GRADING_ENGINE=ai
STORAGE_BACKEND=local
STORAGE_LOCAL_DIR=/app/uploads
TZ=Asia/Shanghai
TEACHLOOP_IMAGE=teachloop:${TAG}
WEB_PORT=${WEB_PORT}
COOKIE_SECURE=false
ENVEOF
  chmod 600 "$LOCAL_ENV"
  rsync -az "$LOCAL_ENV" "${DEPLOY_USER}@${HOST}:${REMOTE_DIR}/.env"
  rm -f "$LOCAL_ENV"
  rssh "chmod 600 '${REMOTE_DIR}/.env'"
  echo "  .env 已生成（随机密钥，Postgres 与 app 共用同一密码）"
fi
rssh "cd '${REMOTE_DIR}' && docker compose --env-file .env up -d" || die "compose 起服务失败"
# 静态目录是 bind mount：重建 web 容器才会重新解析挂载点（否则可能挂在被删的旧目录上 → 403）
rssh "cd '${REMOTE_DIR}' && docker compose --env-file .env up -d --force-recreate web" >/dev/null 2>&1 || true

say "6/6 等 /healthz"
for i in $(seq 1 60); do
  if rssh "curl -fsS -m 5 'http://127.0.0.1:8010/healthz?deep=1' >/dev/null 2>&1"; then
    echo "  后端就绪（第 ${i} 次探测）"
    break
  fi
  sleep 3
  [ "$i" = "60" ] && die "健康检查超时；看日志： ssh ${DEPLOY_USER}@${HOST} 'cd ${REMOTE_DIR} && docker compose logs --tail=80 app'"
done

rssh "cd '${REMOTE_DIR}' && docker compose ps --format 'table {{.Service}}\t{{.Status}}'"
printf '\n\033[32m部署完成\033[0m\n  入口：http://%s:%s/   首次打开 /setup 建管理员\n  健康：http://%s:%s/healthz?deep=1\n  目录：%s:%s（compose / dist / .env / src）\n  数据卷：teachloop_pgdata、teachloop_uploads（不随重部署删除）\n' \
  "$HOST" "$WEB_PORT" "$HOST" "$WEB_PORT" "$HOST" "$REMOTE_DIR"
