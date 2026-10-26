#!/usr/bin/env bash
# TeachLoop 每日备份（容器 Postgres 路线，2026-10-25 上线 runbook §5）
#
# 用法（在仓库根目录执行，需要 docker compose 可用）：
#   scripts/backup-pg.sh                          # 备份数据库 + uploads 卷，保留 14 天
#   BACKUP_DIR=/srv/teachloop-backup scripts/backup-pg.sh
#   OSS_BUCKET=oss://teachloop-backup scripts/backup-pg.sh   # 额外上传（需 ossutil 在 PATH）
#   SKIP_UPLOADS=1 scripts/backup-pg.sh           # 只备份数据库
#
# cron 示例（每日 04:10，Asia/Shanghai）：
#   10 4 * * * cd /opt/teachloop && BACKUP_DIR=/srv/teachloop-backup OSS_BUCKET=oss://teachloop-backup /opt/teachloop/scripts/backup-pg.sh >> /var/log/teachloop-backup.log 2>&1
#
# 恢复（演练过一次再上线，见 runbook §8）：
#   gunzip -c /srv/teachloop-backup/teachloop-db-<STAMP>.sql.gz | \
#     docker compose -f docker-compose.prod.yml exec -T db psql -U teachloop -d teachloop
#   # 空库恢复：先 createdb 或 drop 后 create，再导入；导入后启动 app 让 Flyway 校验
#
# 退出码：0 成功；非 0 失败（cron 应据此告警）。

set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
DB_SERVICE="${DB_SERVICE:-db}"
DB_USER="${DB_USER:-teachloop}"
DB_NAME="${DB_NAME:-teachloop}"
UPLOADS_VOLUME="${UPLOADS_VOLUME:-teachloop_uploads}"
BACKUP_DIR="${BACKUP_DIR:-./backup}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
STAMP="$(date +%Y%m%d-%H%M%S)"

log() { printf '[%s] %s\n' "$(date '+%F %T')" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || die "docker 未安装或不在 PATH"
[ -f "$COMPOSE_FILE" ] || die "找不到 compose 文件：${COMPOSE_FILE}（在仓库根目录执行，或设 COMPOSE_FILE）"
mkdir -p "$BACKUP_DIR"

# 容器是否在跑（不在跑直接失败，不要产出一个空 dump 冒充成功）
docker compose -f "$COMPOSE_FILE" ps "$DB_SERVICE" | grep -q "Up\|running" \
  || die "compose 服务 $DB_SERVICE 未运行，备份中止"

DB_OUT="$BACKUP_DIR/${DB_NAME}-db-${STAMP}.sql.gz"
log "开始备份数据库 -> $DB_OUT"
docker compose -f "$COMPOSE_FILE" exec -T "$DB_SERVICE" \
  pg_dump -U "$DB_USER" -d "$DB_NAME" --no-owner --no-privileges \
  | gzip -9 > "$DB_OUT" || die "pg_dump 失败"

# 校验：gzip 完整性 + 非空（只判退出码会漏掉「dump 出一个空文件」的情况）
gzip -t "$DB_OUT" || die "备份文件损坏：$DB_OUT"
DB_BYTES="$(wc -c < "$DB_OUT" | tr -d ' ')"
[ "$DB_BYTES" -gt 1024 ] || die "备份文件异常小（${DB_BYTES}B）：$DB_OUT"
log "数据库备份完成：${DB_BYTES} bytes"

UPLOADS_OUT=""
if [ "${SKIP_UPLOADS:-0}" != "1" ]; then
  UPLOADS_OUT="$BACKUP_DIR/${DB_NAME}-uploads-${STAMP}.tar.gz"
  log "开始备份 uploads 卷（${UPLOADS_VOLUME}）-> $UPLOADS_OUT"
  docker run --rm -v "${UPLOADS_VOLUME}:/data:ro" -v "$(cd "$BACKUP_DIR" && pwd):/backup" \
    alpine:3 sh -c 'tar czf "/backup/$(basename "$1")" -C /data .' _ "$(basename "$UPLOADS_OUT")" \
    || die "uploads 卷打包失败"
  [ -s "$UPLOADS_OUT" ] || die "uploads 备份为空：$UPLOADS_OUT"
  log "uploads 备份完成：$(wc -c < "$UPLOADS_OUT" | tr -d ' ') bytes"
fi

# 保留策略：只清理本脚本的产物，不要把别的文件删掉
log "清理 ${RETENTION_DAYS} 天前的备份（${BACKUP_DIR}）"
find "$BACKUP_DIR" -maxdepth 1 -type f \
  \( -name "${DB_NAME}-db-*.sql.gz" -o -name "${DB_NAME}-uploads-*.tar.gz" \) \
  -mtime "+${RETENTION_DAYS}" -print -delete | sed 's/^/  删除 /' || true

# 可选：上传到 OSS（ossutil 需自带配置，脚本不保存凭据）
if [ -n "${OSS_BUCKET:-}" ]; then
  command -v ossutil >/dev/null 2>&1 || die "设了 OSS_BUCKET 但找不到 ossutil"
  log "上传到 $OSS_BUCKET/db/"
  ossutil cp -f "$DB_OUT" "$OSS_BUCKET/db/$(basename "$DB_OUT")" || die "数据库备份上传 OSS 失败"
  if [ -n "$UPLOADS_OUT" ]; then
    ossutil cp -f "$UPLOADS_OUT" "$OSS_BUCKET/uploads/$(basename "$UPLOADS_OUT")" \
      || die "uploads 备份上传 OSS 失败"
  fi
  log "OSS 上传完成"
else
  log "未设 OSS_BUCKET，跳过上传（本地备份在 ${BACKUP_DIR}，注意磁盘容量）"
fi

log "备份成功：STAMP=$STAMP"
