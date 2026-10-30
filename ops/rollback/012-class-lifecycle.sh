#!/usr/bin/env bash
#
# 012-class-lifecycle.sh —— 班级生命周期 012（class_members.status）回退前的 LEFT 复位脚本
#
# 背景
# -----
# 迁移 V081__classroom_class_member_status.sql 给 class_members 增加了：
#   status      text not null default 'ACTIVE'   -- 'ACTIVE' | 'LEFT'
#   left_at     timestamptz
#   rejoined_at timestamptz
# 新代码里「退班 / 移出」只把行改成 status='LEFT'（不删行）。
# 旧代码（main 2e9ce2b 及更早）**没有 status 概念**，成员查询就是「这个班的全部成员行」。
#
# 所以：代码回退到 main 2e9ce2b 之前，先把 LEFT 复位成 ACTIVE（同时清空 left_at），再回退代码。
#
# ⚠ 本脚本能做到什么、做不到什么（2026-10-30 实测，别照抄「复位后就没事了」的说法）
# ------------------------------------------------------------------------------
# 旧代码的成员查询**没有 status 过滤**，所以它永远看得见 class_members 里的每一行。
# 实测（scratch 库 rollback_drill：1 个班 3 个成员，其中 1 行 LEFT）：
#     select ... from class_members where class_id = '<id>';   -- 旧代码的查询
#       复位前（1 行 LEFT）              → 3 行
#       跑完本脚本 --apply（不删行）      → 3 行   <-- 退班学生仍在旧代码的成员列表里
#       若改成删除 LEFT 行（另一种策略）  → 2 行
# 也就是说：**本脚本让 LEFT/left_at 归一，但不会让退班学生从旧代码的成员列表里消失。**
# 它保证的是：
#   1) 旧 schema 的假设成立——每行都是在班成员，没有任何行带着旧代码读不懂的 left_at；
#   2) 将来再次上线 012 时，不会把这些「在旧代码里一直在班」的学生重新当成 LEFT。
# 另一种做法是「删 LEFT 行」（= 旧代码语义：退班即不在班里，旧查询变 2 行），但已明确**不采用**：
#   - spec/012 的 FR-215 写死「任何路径都不得 DELETE FROM class_members」；
#   - 删行会永久丢掉退班历史，012 再上线时无法恢复。
# 所以取舍是：接受回滚窗口内「退班学生在旧代码里短暂复活」（旧代码只认行、不认 status），
# 换取「012 重新上线时不会把一直在班的学生误判成 LEFT」+ 历史不丢。
# 该取舍 2026-10-30 已由发布负责人拍板（选 A = 本脚本行为）；演练记录见
# docs/reports/012-rollback-drill.md。
#
# 完整回退顺序（顺序不可颠倒，尤其 ② 必须在 ③ 之前）
# -----------------------------------------------
#   ① 停应用（qa / prod 的 app 容器或进程；避免复位过程中新代码继续写入 LEFT）
#   ② 跑本脚本 --apply            <-- 就在新代码还在的位置、数据库仍是 V081 形状时执行
#   ③ 回退代码到 main 2e9ce2b      （git checkout / 重新打包旧 jar；由发布负责人执行，本脚本不做）
#   ④ 起旧版本
#   ⑤ 旧代码验证（见下）
#
#   **禁止**在跑本脚本之前就回退代码：那样 012 写下的 LEFT 行会在旧代码里被当成普通成员行，
#   而数据库里还留着旧代码读不懂的 status/left_at —— 出问题时无法区分「012 造的数据」和「旧数据」。
#
#   ⑤ 旧版本验证（手动，不在本脚本内）：
#      - 旧代码查询语义等价于：
#          select id, class_id, student_id, joined_at from class_members where class_id = '<班级id>';
#        注意：这行查询复位前后**行数相同**（见上面的实测），所以「行数变少」不能当验证通过的标准；
#      - 该验证的是：① 旧代码能正常读到成员列表、不报错；② 列表里的在班学生与业务预期一致；
#        ③ 若产品要求退班学生消失，则必须先做「删除 LEFT 行」的决策（见上），本脚本不提供。
#
# 本脚本做什么
# ------------
#   --dry-run  只读：打印将要复位的 LEFT 行数、LEFT 中 left_at 空/非空各多少、最多 5 行样例。不写。
#   --apply    单个事务内：前置检查 → 记录行数 → update 复位 → 校验 → commit。任何一步不过就回滚。
#   --verify   只读：校验「LEFT 行数 = 0」且「left_at 全表为 null」，即「status 已归一、可以回退代码」。
#
# 退出码
#   0  成功（--apply：已提交；--verify：已可安全回退；--dry-run：正常只读完成）
#   2  本库没应用 V081（class_members 表或 status/left_at 列不存在）——无需复位，不是错误；
#      也可能意味着目标库选错了（库名/端口/环境搞混），请先确认。
#   3  --apply 期间失败：复位前后 class_members 行数不一致（可能有人并发删行）→ 事务已回滚
#   4  --apply/--verify 期间失败：复位后仍有 LEFT 行，或 left_at 仍有非空值 → 事务已回滚
#   64 参数错误
#   1  其他失败（连接失败、psql 不存在、SQL 报错）
#
# 目标库怎么指定（三选一，优先级从上到下）
#   1) --dsn "postgresql://user:pass@127.0.0.1:55433/teachloop"
#      - 密码会从命令行里摘出来放进 PGPASSWORD 环境变量，不会出现在 ps 里；
#      - 密码请用 URL 编码（例如 @ → %40），本脚本会解码。
#   2) 环境变量 PGHOST / PGPORT / PGUSER / PGPASSWORD / PGDATABASE（等同 psql 的惯例）
#   3) 默认：PGHOST 缺省 localhost、PGPORT 缺省 5432、PGUSER 缺省 teachloop、PGDATABASE 缺省 teachloop
#
# 本机没有 psql 时（例如只有 Docker 里的 Postgres）：
#   --docker-container teachloop-pg      # 在容器内调 psql
#   容器模式忽略 PGHOST/PGPORT（容器内走 unix socket 连本地库），库名/用户名仍取 PGDATABASE/PGUSER；
#   密码通过 `docker exec -e PGPASSWORD` 从当前环境转发（不写进 argv）。
#
# 用法
#   ./ops/rollback/012-class-lifecycle.sh --dry-run  --dsn "postgresql://teachloop@127.0.0.1:55433/teachloop"
#   ./ops/rollback/012-class-lifecycle.sh --apply    --dsn "postgresql://teachloop@127.0.0.1:55433/teachloop"
#   ./ops/rollback/012-class-lifecycle.sh --verify   --docker-container teachloop-pg-qa
#
# 幂等：第二次 --apply 会复位 0 行、退出码 0（只做一次空事务 commit）。
#
set -euo pipefail

PROG="$(basename "$0")"

MODE=""
DSN=""
DOCKER_CONTAINER="${PSQL_DOCKER_CONTAINER:-}"
CONNINFO=""

# ---- 输出小工具 -------------------------------------------------------------
log()  { printf '%s\n' "$*"; }
warn() { printf '[warn] %s\n' "$*" >&2; }
die()  { local code="$1"; shift; printf '[error] %s\n' "$*" >&2; exit "$code"; }

usage() {
  cat <<'USAGE'
012-class-lifecycle.sh —— 回退代码前把 class_members 的 LEFT 行复位成 ACTIVE

模式（必须且只能给一个）：
  --dry-run     只读预演：打印 LEFT 行数与样例，不写数据
  --apply       事务内复位 LEFT → ACTIVE 并校验（失败自动回滚）
  --verify      只读校验：LEFT = 0 且 left_at 全空才退出 0

连接参数：
  --dsn <URL|conninfo>       postgresql://user:pass@host:port/db 或 "host=... dbname=... password=..."
  --docker-container <name>  在指定容器内执行 psql（本机无 psql 时用；例：teachloop-pg-qa）
  环境变量                   PGHOST / PGPORT / PGUSER / PGPASSWORD / PGDATABASE
  -h, --help                 显示本帮助

完整回退顺序（不可颠倒）：
  ① 停应用 → ② 本脚本 --apply → ③ 回退代码到 main 2e9ce2b → ④ 起旧版本 → ⑤ 旧代码验证

  **禁止先回退代码再跑本脚本**：旧代码没有 status 概念，LEFT 行会被当成普通成员行。

  注意：本脚本只做 status/left_at 归一，**不会**让退班学生从旧代码的成员列表里消失
  （旧查询不受 status 影响，复位前后行数相同；要「消失」必须删 LEFT 行）。脚本头注释有实测数据。

退出码：0 成功 / 2 本库未应用 V081（无需复位）/ 3 行数不一致（已回滚）/
        4 校验未通过（已回滚）/ 64 参数错误 / 1 其他失败
USAGE
}

# ---- 参数解析 ---------------------------------------------------------------
set_mode() {
  local want="$1"
  if [[ -n "$MODE" && "$MODE" != "$want" ]]; then
    die 64 "只能指定一个模式，收到了 --${MODE} 和 --${want}"
  fi
  MODE="$want"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)            set_mode "dry-run"; shift ;;
    --apply)              set_mode "apply";   shift ;;
    --verify)             set_mode "verify";  shift ;;
    --dsn)                [[ $# -ge 2 ]] || die 64 "--dsn 需要一个连接串"; DSN="$2"; shift 2 ;;
    --dsn=*)              DSN="${1#--dsn=}"; shift ;;
    --docker-container)   [[ $# -ge 2 ]] || die 64 "--docker-container 需要一个容器名"; DOCKER_CONTAINER="$2"; shift 2 ;;
    --docker-container=*) DOCKER_CONTAINER="${1#--docker-container=}"; shift ;;
    -h|--help)            usage; exit 0 ;;
    *)                    usage >&2; die 64 "未知参数: $1" ;;
  esac
done

[[ -n "$MODE" ]] || { usage >&2; die 64 "必须指定 --dry-run / --apply / --verify 之一"; }

# ---- 连接参数解析 -----------------------------------------------------------
# URL 编码解码（只用于用户名/密码/库名；%XX → 字符）
url_decode() {
  local s="$1"
  if [[ "$s" == *%* ]]; then printf '%b' "${s//%/\\x}"; else printf '%s' "$s"; fi
}

# postgresql://user:pass@host:port/db?params  →  PGHOST/PGPORT/PGUSER/PGPASSWORD/PGDATABASE
apply_uri_dsn() {
  local dsn="$1" rest userinfo="" user="" pass="" hostport host="" port="" tail dbpart=""
  rest="${dsn#*://}"
  if [[ "$rest" == *"@"* ]]; then
    userinfo="${rest%@*}"
    rest="${rest##*@}"
  fi
  hostport="$rest"
  if [[ "$rest" == *"/"* ]]; then
    hostport="${rest%%/*}"
    tail="${rest#*/}"
    dbpart="${tail%%\?*}"
  fi
  if [[ -n "$userinfo" ]]; then
    user="${userinfo%%:*}"
    if [[ "$userinfo" == *":"* ]]; then pass="${userinfo#*:}"; fi
  fi
  # host[:port]；[::1]:5432 这种带方括号的 IPv6 也支持
  if [[ "$hostport" =~ ^\[([^]]+)\]:([0-9]+)$ ]]; then
    host="${BASH_REMATCH[1]}"; port="${BASH_REMATCH[2]}"
  elif [[ "$hostport" =~ ^\[([^]]+)\]$ ]]; then
    host="${BASH_REMATCH[1]}"
  elif [[ "$hostport" =~ ^(.*):([0-9]+)$ ]]; then
    host="${BASH_REMATCH[1]}"; port="${BASH_REMATCH[2]}"
  else
    host="$hostport"
  fi

  [[ -n "$host"   ]] && export PGHOST="$(url_decode "$host")"
  [[ -n "$port"   ]] && export PGPORT="$port"
  [[ -n "$user"   ]] && export PGUSER="$(url_decode "$user")"
  # 密码只进环境变量，绝不进命令行（ps 里看不到）
  [[ -n "$pass"   ]] && export PGPASSWORD="$(url_decode "$pass")"
  [[ -n "$dbpart" ]] && export PGDATABASE="$(url_decode "$dbpart")"
  return 0
}

# "host=... port=... user=... password=... dbname=..." → 密码摘进 PGPASSWORD，其余交给 psql -d
apply_kv_dsn() {
  local conninfo="$1" w
  local -a keep=()
  for w in $conninfo; do
    case "$w" in
      password=*) export PGPASSWORD="${w#password=}" ;;
      *)          keep+=("$w") ;;
    esac
  done
  CONNINFO="${keep[*]-}"
}

if [[ -n "$DSN" ]]; then
  case "$DSN" in
    postgres://*|postgresql://*) apply_uri_dsn "$DSN" ;;
    *"://"*) die 64 "不支持的连接串协议: ${DSN%%://*}://（只支持 postgresql:// / postgres://，或 key=value 形式）" ;;
    *=*)     apply_kv_dsn "$DSN" ;;
    *)       export PGDATABASE="$DSN" ;;   # 只写库名
  esac
fi

PGUSER_RESOLVED="${PGUSER:-teachloop}"
PGDATABASE_RESOLVED="${PGDATABASE:-teachloop}"
PGHOST_RESOLVED="${PGHOST:-localhost}"
PGPORT_RESOLVED="${PGPORT:-5432}"

# ---- psql 调用 -------------------------------------------------------------
# 注意（bash 3.2 + set -u）：空数组展开成 "${arr[@]}" 会报 unbound variable，
# 所以这里不用「可能为空的数组拼参数」，改成显式分支。
DOCKER_OPTS=()

if [[ -n "$DOCKER_CONTAINER" ]]; then
  # 容器模式：host/port 是宿主机映射，容器内用 unix socket；显式给了非本地 host 就报错，避免连错库
  if [[ -n "${PGHOST:-}" ]]; then
    case "$PGHOST" in
      localhost|127.0.0.1|::1|/var/run/postgresql|/tmp) : ;;
      *) die 64 "容器模式下 PGHOST=${PGHOST} 无意义（容器内走 unix socket）；请只用 --dsn 指定库名，或去掉 --docker-container" ;;
    esac
  fi
  command -v docker >/dev/null 2>&1 || die 1 "找不到 docker 命令，无法使用 --docker-container"
  # -e PGPASSWORD 是「转发同名环境变量」，值不会出现在 docker 的 argv 里
  DOCKER_OPTS=(-e PGPASSWORD)
else
  command -v psql >/dev/null 2>&1 || die 1 "找不到 psql；本机没有客户端时可用 --docker-container <容器名> 在容器内执行"
  # 密码走环境变量（PGPASSWORD），不拼进命令行
  export PGPASSWORD="${PGPASSWORD:-}"
fi

# run_psql：SQL 从 stdin 读，参数原样传给 psql
run_psql() {
  if [[ -n "$DOCKER_CONTAINER" ]]; then
    docker exec -i "${DOCKER_OPTS[@]}" "$DOCKER_CONTAINER" \
      psql -X -v ON_ERROR_STOP=1 -U "$PGUSER_RESOLVED" -d "$PGDATABASE_RESOLVED" "$@"
  elif [[ -n "${CONNINFO:-}" ]]; then
    psql -X -v ON_ERROR_STOP=1 -d "$CONNINFO" "$@"
  else
    psql -X -v ON_ERROR_STOP=1 "$@"
  fi
}

target_desc() {
  if [[ -n "$DOCKER_CONTAINER" ]]; then
    printf 'docker 容器 %s（库 %s / 用户 %s）' "$DOCKER_CONTAINER" "$PGDATABASE_RESOLVED" "$PGUSER_RESOLVED"
  else
    printf '%s@%s:%s/%s' "$PGUSER_RESOLVED" "$PGHOST_RESOLVED" "$PGPORT_RESOLVED" "$PGDATABASE_RESOLVED"
  fi
}

# ---- 前置检查：本库是否已应用 V081 -----------------------------------------
# 输出：HAS_TABLE=YES|NO  PRESENT_COLS=a,b,c
HAS_TABLE=""
PRESENT_COLS=""

preflight_query() {
  run_psql -tA -F '|' <<'SQL'
select case when to_regclass('class_members') is null then 'NO' else 'YES' end,
       coalesce((select string_agg(a.attname, ',' order by a.attname)
                   from pg_attribute a
                  where a.attrelid = coalesce(to_regclass('class_members'), 0)
                    and a.attnum > 0
                    and not a.attisdropped
                    and a.attname in ('status', 'left_at', 'rejoined_at')), '')
SQL
}

has_col() { [[ ",${PRESENT_COLS}," == *",$1,"* ]]; }

preflight() {
  # stderr 单独收：不能被并入 raw，否则报错文本会污染 HAS_TABLE/PRESENT_COLS 的解析
  local raw="" rc=0 errf
  errf="$(mktemp)"
  raw="$(preflight_query 2>"$errf")" || rc=$?
  if [[ "$rc" -ne 0 ]]; then
    cat "$errf" >&2
    rm -f "$errf"
    die 1 "连接或查询 class_members 失败（检查 --dsn / 库名 / 服务是否在跑）"
  fi
  rm -f "$errf"
  HAS_TABLE="${raw%%|*}"
  PRESENT_COLS="${raw#*|}"

  # 「这个库没 V081」在不同模式下的措辞不同：apply 是「没事可做」，verify 是「判断不了」
  local verdict
  if [[ "$MODE" == "verify" ]]; then
    verdict="无需校验——本库没有 012 的形状，谈不上「可安全回退」（退出码 2）"
  else
    verdict="无需复位（退出码 2）"
  fi

  if [[ "$HAS_TABLE" != "YES" ]]; then
    log "本次目标：$(target_desc)"
    log "前置检查：本库没有 class_members 表（库未初始化，或不是 classroom 库）"
    log "结论：${verdict}。若你预期这个库该有该表，先确认目标库是否选错。"
    exit 2
  fi

  local missing="" c
  for c in status left_at; do
    has_col "$c" || missing="${missing:+$missing,}$c"
  done
  if [[ -n "$missing" ]]; then
    log "本次目标：$(target_desc)"
    log "前置检查：class_members 缺列 ${missing}"
    log "结论：本库未应用 V081，${verdict}。"
    exit 2
  fi

  if ! has_col rejoined_at; then
    warn "class_members 缺 rejoined_at 列（V081 未完整应用）；本脚本不需要它，继续。"
  fi
}

print_header() {
  log "=== 012 班级生命周期 · class_members LEFT 复位 ==="
  log "脚本        : ops/rollback/012-class-lifecycle.sh"
  log "模式        : --${MODE}"
  log "目标        : $(target_desc)"
  log "class_members: 存在；已应用于本表的相关列：${PRESENT_COLS:-（无）}"
  if [[ "$MODE" == "apply" ]]; then
    log "顺序提醒    : 停应用 → 本脚本 --apply → 回退代码到 main 2e9ce2b → 起旧版本 → 旧代码验证"
    log "              （禁止先回退代码；注意：复位不会让退班学生从旧代码的成员列表消失，见脚本头注释）"
  fi
  log ""
}

# ---- 读 psql 输出、把 RESULT_CODE= 映射成退出码 ------------------------------
# 失败路径下 psql 的退出码统一是 3（ON_ERROR_STOP + 我们主动 raise），
# 所以真正的语义退出码由 SQL 里打印的 RESULT_CODE= 决定。
emit_and_exit() {
  local out="$1" rc="$2" code=""

  printf '%s\n' "$out"

  # 前置检查在事务内再次失败（列被并发删掉等）→ 2
  if [[ "$rc" -ne 0 ]]; then
    case "$out" in
      *PRECHECK_NO_TABLE*|*PRECHECK_NO_V081_COLUMNS*)
        log ""
        log "结论：本库未应用 V081（事务内二次确认），无需复位（退出码 2）。"
        exit 2 ;;
    esac
  fi

  code="$(printf '%s\n' "$out" | sed -n 's/^RESULT_CODE=\([0-9][0-9]*\)$/\1/p' | tail -n 1)"

  if [[ -z "$code" ]]; then
    log ""
    die 1 "没有拿到结果码（psql 退出码 ${rc}）；上面是原始输出。"
  fi

  case "$code" in
    0) exit 0 ;;
    3) exit 3 ;;
    4) exit 4 ;;
    *) exit 1 ;;
  esac
}

# ---- 执行一段 SQL（stdin 先落临时文件，再交给 psql）------------------------
# 为什么不用 out="$(run_psql -f - <<'SQL' ... SQL)"：
# macOS 自带 bash 3.2 在 $() 里包 heredoc 时，会按「括号配对」扫描 heredoc 正文，
# 正文里出现一个**落单的 )**（例如 SQL 注释 "-- a)"）就被当成 $() 的结束，
# 后果是 payload 被截断、后半段被当成 shell 命令执行
# （实测报错：`class_members: command not found`，payload 只喂到 "-- a"）。
# 所以 payload 先落临时文件，$() 里只放函数调用（不跨 heredoc）。
OUT_TEXT=""
PSQL_RC=0
run_sql_block() {
  local sqlfile="" outfile="" errfile="" rc=0
  sqlfile="$(mktemp)"; outfile="$(mktemp)"; errfile="$(mktemp)"
  cat >"$sqlfile"
  # 用 `-f -` + 重定向而不是 `-f <路径>`：容器模式（--docker-container）下临时文件在宿主机上，
  # 容器里的 psql 看不到该路径；灌到 stdin 两种模式都成立。
  run_psql -f - <"$sqlfile" >"$outfile" 2>"$errfile" || rc=$?
  OUT_TEXT="$(cat "$outfile")"
  if [[ -s "$errfile" ]]; then
    OUT_TEXT="${OUT_TEXT:+${OUT_TEXT}
}$(cat "$errfile")"
  fi
  PSQL_RC="$rc"
  rm -f "$sqlfile" "$outfile" "$errfile"
}

# ---- 模式实现 ---------------------------------------------------------------
run_dry_run() {
  run_sql_block <<'SQL'
\echo --- dry-run：只读，不写任何数据 ---
select count(*) as "class_members 总行数" from class_members;
select count(*) as "待复位 LEFT 行数" from class_members where status = 'LEFT';
select count(*) filter (where left_at is not null) as "LEFT 中 left_at 非空",
       count(*) filter (where left_at is null)     as "LEFT 中 left_at 为空"
  from class_members where status = 'LEFT';
\echo ''
\echo --- 待复位样例（最多 5 行）---
select id, class_id, student_id, joined_at, left_at
  from class_members
 where status = 'LEFT'
 order by left_at nulls last, joined_at
 limit 5;
\echo ''
\echo --- 将要执行的 SQL（--apply 时）---
-- 用 select 打印而不是 \echo：\echo 会把字面量里的单引号吃掉
select 'update class_members set status=''ACTIVE'', left_at=null where status=''LEFT'';' as sql;
\echo ''
\echo --- dry-run 结束：未执行 update ---
\echo RESULT_CODE=0
SQL
  emit_and_exit "$OUT_TEXT" "$PSQL_RC"
}

run_verify() {
  run_sql_block <<'SQL'
\echo --- verify：只读校验（LEFT = 0 且 left_at 全表为 null 才可安全回退代码）---
select count(*) as "class_members 总行数" from class_members;
select count(*) as "LEFT 行数（应为 0）" from class_members where status = 'LEFT';
select count(*) as "left_at 非空行数（应为 0）" from class_members where left_at is not null;

-- 判定：两项都满足才 0，否则 4（verify 只读，不改数据）
-- 注意：\if 认 t/f，但 SQL 里的 t/f 会被当成列名，所以判定用数值比较，布尔变量只喂 \if
select count(*) as left_rows  from class_members where status = 'LEFT'     \gset v_
select count(*) as left_at_nn from class_members where left_at is not null \gset v_
select (:v_left_rows  = 0) as ok \gset chk_left_
select (:v_left_at_nn = 0) as ok \gset chk_left_at_
select case when :v_left_rows = 0 and :v_left_at_nn = 0 then 0 else 4 end as code \gset verdict_

\echo ''
\echo RESULT_CODE=:verdict_code
\if :chk_left_ok
\else
  \echo 【FAIL】仍有 status='LEFT' 的行：先跑 --apply 复位，再回退代码（退出码 4）。
\endif
\if :chk_left_at_ok
\else
  \echo 【FAIL】仍有 left_at 非空的行：先跑 --apply 复位，再回退代码（退出码 4）。
\endif
\if :chk_left_ok
\if :chk_left_at_ok
  \echo 【OK】LEFT = 0 且 left_at 全空：status 已归一，可以回退代码。
  \echo       注意：旧代码的成员查询不带 status 过滤，退班学生仍会出现在成员列表里（见脚本头注释）。
\endif
\endif
SQL
  emit_and_exit "$OUT_TEXT" "$PSQL_RC"
}

run_apply() {
  run_sql_block <<'SQL'
\echo --- apply：单个事务内 前置检查 → 记录行数 → 复位 → 校验 → commit ---
begin;

-- a) 事务内前置检查（bash 侧已检查过一次；这里是防止两次检查之间表/列被改）
do $$
declare
  v_present text;
begin
  if to_regclass('class_members') is null then
    raise exception 'PRECHECK_NO_TABLE';
  end if;
  select string_agg(a.attname, ',' order by a.attname)
    into v_present
    from pg_attribute a
   where a.attrelid = to_regclass('class_members')
     and a.attnum > 0
     and not a.attisdropped
     and a.attname in ('status', 'left_at');
  if v_present is null
     or v_present not like '%status%'
     or v_present not like '%left_at%' then
    raise exception 'PRECHECK_NO_V081_COLUMNS present=%', coalesce(v_present, '');
  end if;
end $$;

-- b) 复位前快照
select count(*) as total     from class_members                       \gset pre_
select count(*) as left_rows from class_members where status = 'LEFT' \gset pre_

-- c) 复位：只改状态，不动行（不删行、不改 id/joined_at）
with updated_rows as (
  update class_members
     set status  = 'ACTIVE',
         left_at = null
   where status = 'LEFT'
  returning 1
)
select count(*) as updated from updated_rows \gset upd_

-- d) 复位后校验
select count(*) as total                from class_members                        \gset post_
select count(*) as left_rows            from class_members where status = 'LEFT'  \gset post_
select count(*) as left_at_not_null     from class_members where left_at is not null \gset post_
-- 布尔变量只喂 \if（\if 认 t/f；SQL 里 t/f 会被当列名，所以下面的判定用数值比较）
select (:pre_total            = :post_total)           as ok \gset chk_rows_
select (:post_left_rows       = 0)                     as ok \gset chk_left_
select (:post_left_at_not_null = 0)                    as ok \gset chk_left_at_

\echo ''
\echo --- 复位结果 ---
select :pre_total           as "复位前 class_members 行数",
       :pre_left_rows       as "复位前 LEFT 行数",
       :upd_updated         as "本次实际复位行数",
       :post_total          as "复位后 class_members 行数",
       :post_left_rows      as "复位后 LEFT 行数（应为 0）",
       :post_left_at_not_null as "复位后 left_at 非空行数（应为 0）";
\echo ''

-- e) 判定并决定 commit / 回滚（数值比较，见上）
select case
         when :pre_total            <> :post_total            then 3
         when :post_left_rows       <> 0                      then 4
         when :post_left_at_not_null <> 0                     then 4
         else 0
       end as code \gset decision_
\echo RESULT_CODE=:decision_code

\if :chk_rows_ok
\else
  \echo 【FAIL】复位前后 class_members 行数不一致 —— 事务回滚，未改任何数据（退出码 3）。
  do $$ begin raise exception 'ROLLBACK_DRILL_ROWCOUNT_MISMATCH'; end $$;
\endif

\if :chk_left_ok
\else
  \echo 【FAIL】复位后仍有 status='LEFT' 的行 —— 事务回滚（退出码 4）。
  do $$ begin raise exception 'ROLLBACK_DRILL_LEFT_REMAINS'; end $$;
\endif

\if :chk_left_at_ok
\else
  \echo 【FAIL】复位后仍有 left_at 非空的行 —— 事务回滚（退出码 4）。
  do $$ begin raise exception 'ROLLBACK_DRILL_LEFT_AT_REMAINS'; end $$;
\endif

commit;
\echo ''
\echo 【OK】已提交：LEFT → ACTIVE，left_at 清空；行数不变（未删任何行）。
\echo       本次复位 :upd_updated 行。若为 0 行，说明已经是干净状态（幂等）。
SQL
  emit_and_exit "$OUT_TEXT" "$PSQL_RC"
}

# ---- main ------------------------------------------------------------------
case "$MODE" in
  dry-run) preflight; print_header; run_dry_run ;;
  verify)  preflight; print_header; run_verify ;;
  apply)   preflight; print_header; run_apply ;;
  *)       die 64 "未知模式: $MODE" ;;
esac
