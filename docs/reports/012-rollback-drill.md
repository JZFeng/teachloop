# 012 班级生命周期 · 回滚脚本与演练报告

任务：T591 / 共享任务 `task-2`（ops 脚本与演练负责人）
日期：2026-10-30
脚本：`ops/rollback/012-class-lifecycle.sh`（新增，`chmod +x`）
分支：工作时所在分支 `feat/011-today`（**未做任何 git 操作**：没有 checkout / revert / add / commit）

---

## 结论

1. **回滚策略已拍板：选 A —— 复位 `status`/`left_at`，不删行**（2026-10-30 lead 定，理由见 §6）。脚本按 A 实现，退出码全路径实测通过：`--dry-run` / `--apply`（两遍，幂等）/ `--verify` 均为 0；失败路径实测 `2`（本库未应用 V081）、`4`（未复位就跑 verify）、`3`（注入行数漂移，事务回滚且 LEFT 行仍在）。单步耗时 **72–208ms**。
2. **已知且接受的后果（原样记录，不粉饰）**：**回滚后旧代码的成员列表会包含退班学生**。旧代码的成员查询没有 status 过滤，行在就在 —— 复位前 3 行，跑完 `--apply` 仍是 3 行。回滚窗口内产品语义回到 012 之前（在班 = 有行）；要让退班学生消失只能删行，而删行违反 FR-215 并永久丢掉退班历史，所以不做。
3. **已验证**：复位把 `status`/`left_at` 归一、`class_members` 行数不变（不删行）、LEFT 归零、脚本幂等（第二次复位 0 行、退出码 0）、失败路径退出码 2/3/4 与事务回滚。
4. **阶段 B（qa 真实库）已执行完毕**：qa 已迁到 V081（V075–V081 全 success），演练中 `--apply` 复位 **1 行**（real **0.129s**）、第二次幂等复位 **0 行**（real 0.154s）、`--verify` 退出 0、`class_members` 37 → 37 行不变，清理后回到基线（34 / 3 / 34，残留 0）。旧查询复位前后都是 **3 行**（A 口径接受项）。详见 §7。
5. **未覆盖（不写进结论当已验证）**：代码回退到 `2e9ce2b`、真实旧 jar 启动、旧接口/页面成员列表验证 —— 三项都没做，本任务只到 SQL 层。

### 这条规则到底防住了什么（评审必问）

复位防的是**「012 重新上线时，把一直在班的学生误判为 LEFT」**，不是「旧代码看不到退班学生」。回退期间旧代码只认行、不认 status，所以退班学生在回滚窗口里短暂「复活」是**已知且接受**的代价——因为让他们消失的唯一办法是 `DELETE FROM class_members`，那会违反 FR-215（「任何路径都不得 DELETE FROM class_members」）并把退班历史永久抹掉，而历史一旦丢失，012 再上线时谁都恢复不出来。换句话说：宁可接受回滚窗口里语义退回到「在班 = 有行」，也不能用删行换一张看起来更干净的成员列表。

---

## 1. 环境与夹具

| 项 | 值 |
|---|---|
| Postgres（dev 容器） | `teachloop-pg`，宿主 `127.0.0.1:55432`，用户/库 `teachloop` |
| Postgres（qa 容器） | `teachloop-pg-qa`，宿主 `127.0.0.1:55433`，库 `teachloop_qa` |
| 宿主 `psql` | **不存在**（`which psql` 空）→ 脚本用 `--docker-container` 在容器内执行 `psql` |
| 演练库 | `rollback_drill`（scratch，建在 dev 容器内；**未触碰 dev 的 `teachloop` 库数据**） |
| 迁移依据 | `teachloop-classroom/src/main/resources/db/migration/V081__classroom_class_member_status.sql` |
| 脚本解释器 | `#!/usr/bin/env bash`，`set -euo pipefail` |

夹具按 V081 的形状建表（最小列集 + 同款 check 约束 + 同款 partial index），数据 1 个班 3 个成员（2 ACTIVE、1 LEFT 且 `left_at` 非空）：

```sql
create table classes (
  id uuid primary key default gen_random_uuid(),
  teacher_id uuid not null,
  archived boolean not null default false
);
create table class_members (
  id uuid primary key default gen_random_uuid(),
  class_id uuid not null references classes(id) on delete cascade,
  student_id uuid not null,
  joined_at timestamptz not null default now()
);
-- 以下 DDL 与 V081 等价
alter table class_members add column status      text        not null default 'ACTIVE';
alter table class_members add column left_at     timestamptz;
alter table class_members add column rejoined_at timestamptz;
alter table class_members add constraint ck_class_members_status check (status in ('ACTIVE', 'LEFT'));
create index idx_class_members_class_active   on class_members (class_id)   where status = 'ACTIVE';
create index idx_class_members_student_active on class_members (student_id) where status = 'ACTIVE';
create index idx_classes_teacher_active       on classes (teacher_id)       where archived = false;

insert into classes (id, teacher_id) values ('11111111-1111-1111-1111-111111111111',
                                             '99999999-9999-9999-9999-999999999999');
insert into class_members (id, class_id, student_id, joined_at, status, left_at) values
 ('aaaaaaaa-0000-0000-0000-000000000001','11111111-1111-1111-1111-111111111111',
  '22222222-2222-2222-2222-222222222201','2026-09-01 08:00:00+08','ACTIVE',null),
 ('aaaaaaaa-0000-0000-0000-000000000002','11111111-1111-1111-1111-111111111111',
  '22222222-2222-2222-2222-222222222202','2026-09-01 08:05:00+08','ACTIVE',null),
 ('aaaaaaaa-0000-0000-0000-000000000003','11111111-1111-1111-1111-111111111111',
  '22222222-2222-2222-2222-222222222203','2026-09-01 08:10:00+08','LEFT','2026-10-15 10:00:00+08');
```

---

## 2. 脚本行为与退出码

| 模式 | 行为 | 写数据 |
|---|---|---|
| `--dry-run` | 打印 `class_members` 总行数、待复位 LEFT 行数、LEFT 中 `left_at` 空/非空、最多 5 行样例、将执行的 SQL | 否 |
| `--apply` | 单个事务内：① 前置检查（表 + `status`/`left_at` 列，缺失退出 2）② 记录复位前行数 ③ `update ... set status='ACTIVE', left_at=null where status='LEFT'` ④ 校验「行数不变 / LEFT=0 / `left_at` 全空」⑤ 通过才 `commit`，任一不过就回滚 | 是（事务） |
| `--verify` | 只读校验 LEFT=0 且 `left_at` 全表为 null | 否 |

| 退出码 | 含义 |
|---|---|
| 0 | 成功（`--apply` 已提交 / `--verify` 可回退 / `--dry-run` 正常完成） |
| 2 | 本库没应用 V081（`class_members` 表或 `status`/`left_at` 列不存在）→ 无需复位；也可能是目标库选错 |
| 3 | `--apply` 失败：复位前后行数不一致（并发删行等）→ 事务已回滚 |
| 4 | `--apply`/`--verify` 失败：仍有 LEFT 行或 `left_at` 仍非空 → 事务已回滚 |
| 64 | 参数错误 |
| 1 | 其他失败（连接失败、找不到 psql、SQL 报错） |

连接方式（三选一，脚本头注释有完整说明）：`--dsn "postgresql://user:pass@host:port/db"`（密码会被摘进 `PGPASSWORD`，不进 argv）／`PGHOST`/`PGPORT`/`PGUSER`/`PGPASSWORD`/`PGDATABASE`／本机无 psql 时 `--docker-container <容器名>`。

---

## 3. 阶段 A 结果（scratch 库 `rollback_drill`，真实执行）

命令模板（下文所有 `A*` 行都在此基础上换模式）：

```bash
./ops/rollback/012-class-lifecycle.sh <MODE> \
  --docker-container teachloop-pg \
  --dsn "postgresql://teachloop@127.0.0.1:55432/rollback_drill"
```

| 步骤 | 命令要点 | 退出码 | 耗时 | 关键结果 |
|---|---|---|---|---|
| 夹具初始化 | 见 §1 | 0 | — | 3 行：ACTIVE 2 / LEFT 1 |
| A0 旧代码查询（复位前） | `select id,class_id,student_id,joined_at from class_members where class_id='1111…';` | 0 | 72ms | **3 行**（含退班学生） |
| A1 | `--dry-run` | 0 | 190ms | 总 3 行 / 待复位 1 行 / 样例 1 行 / 未写入 |
| A2 | `--apply`（第 1 次） | 0 | 192ms | 复位前 3 行、LEFT 1 → **复位 1 行** → 复位后 3 行、LEFT 0、`left_at` 非空 0 |
| A3 | `--apply`（第 2 次，幂等） | 0 | 208ms | LEFT 0 → **复位 0 行**，行数 3 不变 |
| A4 | `--verify` | 0 | 188ms | LEFT=0、`left_at` 全空 → 可回退 |
| A5 旧代码查询（复位后） | 同 A0 | 0 | 78ms | **仍是 3 行**（见 §6，与任务说明的「2 行」不符） |
| A6 行数/状态分布 | `select count(*), count(*) filter (where status='LEFT'), count(*) filter (where left_at is not null) from class_members;` | 0 | 83ms | 总 **3** / LEFT **0** / `left_at` 非空 **0** → 未删行 |

### 3.1 `--dry-run` 实际输出（截取）

```
############ A1 --dry-run
=== 012 班级生命周期 · class_members LEFT 复位 ===
脚本        : ops/rollback/012-class-lifecycle.sh
模式        : --dry-run
目标        : docker 容器 teachloop-pg（库 rollback_drill / 用户 teachloop）
class_members: 存在；已应用于本表的相关列：left_at,rejoined_at,status

--- dry-run：只读，不写任何数据 ---
 class_members 总行数
----------------------
                    3
(1 row)

 待复位 LEFT 行数
------------------
                1
(1 row)

 LEFT 中 left_at 非空 | LEFT 中 left_at 为空
----------------------+----------------------
                    1 |                    0

--- 待复位样例（最多 5 行）---
 aaaaaaaa-0000-0000-0000-000000000003 | 11111111-…-111111111111 | 22222222-…-222222222203 | 2026-09-01 00:10:00+00 | 2026-10-15 02:00:00+00

--- 将要执行的 SQL（--apply 时）---
 update class_members set status='ACTIVE', left_at=null where status='LEFT';

--- dry-run 结束：未执行 update ---
RESULT_CODE=0
```

### 3.2 `--apply` 实际输出（第 1 次，节选）

```
--- apply：单个事务内 前置检查 → 记录行数 → 复位 → 校验 → commit ---
BEGIN
DO

--- 复位结果 ---
 复位前 class_members 行数 | 复位前 LEFT 行数 | 本次实际复位行数 | 复位后 class_members 行数 | 复位后 LEFT 行数（应为 0） | 复位后 left_at 非空行数（应为 0）
---------------------------+------------------+------------------+---------------------------+----------------------------+-----------------------------------
                         3 |                1 |                1 |                         3 |                          0 |                                 0

RESULT_CODE=0
COMMIT

【OK】已提交：LEFT → ACTIVE，left_at 清空；行数不变（未删任何行）。
本次复位 1 行。若为 0 行，说明已经是干净状态（幂等）。
```

第 2 次（幂等）同一行的输出变为 `3 | 0 | 0 | 3 | 0 | 0`，退出码 0，`本次复位 0 行`。

---

## 4. 失败路径实测（同一 scratch 库）

| 步骤 | 场景 | 命令 | 退出码 | 耗时 | 观测 |
|---|---|---|---|---|---|
| P1 | 目标库未应用 V081（真 dev 库 `teachloop`） | `--apply --dsn "…:55432/teachloop"` | **2** | 102ms | `前置检查：class_members 缺列 status,left_at` → `本库未应用 V081，无需复位` |
| P2 | 同上 + `--verify` | `--verify --dsn "…:55432/teachloop"` | **2** | 111ms | 文案切到 `--verify` 口径：`无需校验——本库没有 012 的形状` |
| B1 | 未复位就跑校验 | `--verify`（夹具含 1 行 LEFT） | **4** | 203ms | `仍有 status=LEFT 的行` / `仍有 left_at 非空的行` |
| B2 | 行数漂移（注入触发器：复位动作顺带删掉 1 行） | `--apply` | **3** | 188ms | `复位前后行数不一致：3 → 2` → 打印 `RESULT_CODE=3` → `raise exception` 中止 |
| B3 | 确认 B2 已回滚 | `select count(*), count(*) filter (where status='LEFT') …` | 0 | 75ms | 总 **3**、LEFT **1** → 事务确实回滚，数据未被改动 |

B2 的注入方式（演练用，已在 B3 之后清理）：

```sql
create or replace function _drill_delete_a_row() returns trigger language plpgsql as $$
begin
  delete from class_members where id = 'aaaaaaaa-0000-0000-0000-000000000001';
  return new;
end $$;
create trigger _drill_trg before update on class_members
  for each row execute function _drill_delete_a_row();
-- 演练完：drop trigger _drill_trg on class_members; drop function _drill_delete_a_row();
```

---

## 5. 回退顺序（脚本头注释里同样写了，且明确禁止颠倒）

① 停应用 → ② 跑本脚本 `--apply` → ③ 回退代码到 `main 2e9ce2b` → ④ 起旧版本 → ⑤ 旧代码验证

**禁止在跑脚本之前就回退代码**：那样 012 写下的 LEFT 行会被旧代码当成普通成员行，而库里还留着旧代码读不懂的 `status`/`left_at`，出问题时无法区分「012 造的数据」和「旧数据」。

---

## 6. 规格与实测的矛盾（已拍板：选 A，脚本未擅自改语义）

任务说明写「复位后旧代码查询应为 2 行（退班学生不再被旧代码当在班）」。**用指定的 UPDATE 做不到**，实测如下（同一 scratch 库，同一句旧代码查询 `select id,class_id,student_id,joined_at from class_members where class_id='1111…'`）：

| 数据状态 | 旧代码查询返回 | 实测方式 |
|---|---|---|
| 现状：1 行 LEFT、未做任何回退动作 | **3 行** | 直接查 |
| 策略 A（本脚本）：`update … set status='ACTIVE', left_at=null`，不删行 | **3 行** | `begin; update …; select …; rollback;` |
| 策略 B（备选）：`delete from class_members where status='LEFT'` | **2 行** | `begin; delete …; select …; rollback;`（夹具未被破坏） |

原因：旧代码的成员查询**没有 status 过滤**，只要行还在就一定看得见。所以「先复位再回退代码」**不会**让退班学生从旧代码的成员列表里消失。它真正保证的是：

1. 旧 schema 的假设成立——每行都是在班成员，没有行带着旧代码读不懂的 `left_at`；
2. 将来再次上线 012 时，不会把这些「在旧代码里一直在班」的学生重新当成 LEFT。

两种策略的取舍：

| | 策略 A：复位不删行（当前脚本） | 策略 B：删 LEFT 行 |
|---|---|---|
| 旧代码成员列表 | 退班学生**仍在**（被当在班） | 退班学生不在（与旧代码「退班=不在班里」一致） |
| 退班历史 | 保留（`status`/`left_at` 字段值被抹平，但行还在） | **丢失** |
| 再上线 012 | 不会误判 LEFT | 无法恢复退班记录 |
| 状态 | spec 指定的做法，已实现并演练 | **不采用**（2026-10-30 拍板，见下） |

### 6.1 决策：选 A（2026-10-30，lead 拍板）

三条理由：

1. **规格层面 FR-215 明文禁止删行**：`specs/012-class-lifecycle/spec.md:76` 写的是「学生退出与老师移出都只写状态，不删除成员行；**任何路径都不得 `DELETE FROM class_members`**」。回滚脚本虽不在应用路径上，但它是这条不变量的唯一例外，删行会把「退班历史」永久抹掉——所以 B 不可选。
2. **用户指定的命令就是 A**：任务书原话是「回滚脚本必须先 `update class_members set status='ACTIVE', left_at=null where status='LEFT';` 再回退代码」，A 是它的逐字实现。
3. **A 真正保住的是什么（不要写成「退班学生不再复活」）**：A 让数据回到「旧模型假设的唯一形状」——旧版本的世界里没有 status 列，成员 = 行；同时等 012 重新上线时，不会把一直在班的学生当成 LEFT（那才是真正危险的复活方向）。但它**不会**让退班学生从旧代码的成员列表里消失：旧查询没有 status 过滤，行在就在。

**结论口径**（与 §结论 一致）：

- 「回滚后旧代码的成员列表会包含退班学生」是**已知且接受**的后果：回滚窗口内产品语义回到 012 之前（在班 = 有行），要让他们消失只能删行，而删行违反 FR-215 并丢历史。
- 已验证：复位把 `status`/`left_at` 归一、行数不变、0 LEFT、幂等、失败路径退出码 2/3/4 与事务回滚。
- 未验证：代码回退与旧 jar 启动 —— 保持「未做」。

---

## 7. 阶段 B（qa 真实库，2026-10-30 15:07 之后执行）

### 7.1 前置：qa 迁移到位（lead 部署后实测）

| 检查项 | 实测结果 |
|---|---|
| `flyway_schema_history` max(version) | **081**；V075–V081 全部 `success=t`，V081 `classroom class member status` 安装于 `2026-10-30 15:07:01.815` |
| `class_members` 列 | `id, class_id, student_id, joined_at, status, left_at, rejoined_at`（`status`/`left_at` 都在） |
| 应用是否已停 | `127.0.0.1:8099` 与 `127.0.0.1:8011` 均无响应 → 只有我在写库，演练记录干净 |
| 连接方式 | `--docker-container teachloop-pg-qa --dsn "postgresql://teachloop@127.0.0.1:55433/teachloop_qa"` |

> **一个与预期不符的事实（记录，不粉饰）**：qa 并不是空库 —— 基线有 `class_members` **34 行** / `classes` **3** / `users` **34**。本次演练只改动了自己造的 1 行（B3 显示「复位前 LEFT 行数 = 1」，就是夹具那行），既有 33 行未被触碰；B9 的复原证据也证明总量回到 34。

### 7.2 B1–B9 命令与结果（真实执行）

| 步骤 | 命令要点 | 退出码 | 耗时 | 关键结果 |
|---|---|---|---|---|
| B0 | 校验 `status`/`left_at` 存在，否则退出 2 且**不造表** | 0 | — | 7 列齐全，放行 |
| B0b | 基线计数 | 0 | — | members **34** / classes **3** / users **34** / `d2b0…` 残留 **0** |
| B1 | 造场景：1 老师 + 3 学生 + 1 班 + 3 成员，其中 1 条 `status='LEFT', left_at=now()`（**不删行**） | 0 | 83ms | `INSERT 0 4 / 0 1 / 0 3`；夹具状态：ACTIVE 2 行、LEFT 1 行（`left_at=2026-10-30 22:08:27+00`） |
| B2 | **旧代码查询**（复位前）<br>`select id,class_id,student_id,joined_at from class_members where class_id='d2b0…0010';` | 0 | 65ms | **3 行**（含退班学生 = 复活风险现场） |
| B3 | `ops/rollback/012-class-lifecycle.sh --apply` | **0** | **real 0m0.129s**（user 0.025s / sys 0.030s） | 复位前 **37** 行 / LEFT 1 → **复位 1 行** → 复位后 **37** 行 / LEFT **0** / `left_at` 非空 **0**，`COMMIT` |
| B4 | 再跑一次 `--apply`（幂等） | **0** | **real 0m0.154s**（user 0.027s / sys 0.033s） | LEFT 0 → **复位 0 行**，37 行不变 |
| B5 | `--verify` | **0** | 152ms | `LEFT=0`、`left_at` 全空 → `RESULT_CODE=0` |
| B6 | **旧代码查询**（复位后，同 B2 的 SQL） | 0 | 63ms | **仍 3 行** —— 按 §6.1 的 A 口径，这是**已知且接受**的结果，不是缺陷 |
| B7 | 行数不变 + 状态分布 | 0 | 73ms | members **37** / LEFT **0** / `left_at` 非空 **0** → 未删任何行 |
| B8 | 清理（只删 `d2b0…` 前缀） | 0 | — | `DELETE 3`（members）/ `DELETE 1`（class）/ `DELETE 4`（users） |
| B9 | 复原证据 | 0 | 71ms | members **34** / classes **3** / users **34** / `users` 里 `%d2b0%` 残留 **0** / members 里 `d2b0…` 残留 **0** → 与 B0b 基线**完全一致** |

`--apply` 的 `time` 原始输出（B3 / B4）：

```
real	0m0.129s
user	0m0.025s
sys	0m0.030s
```

```
real	0m0.154s
user	0m0.027s
sys	0m0.033s
```

### 7.3 阶段 B 结论

1. **复位在真库上确实生效**：LEFT 1 → 0，`left_at` 全表清空，`class_members` 行数 37 → 37（**不删行**）。
2. **幂等成立**：第二次 `--apply` 复位 0 行、退出码 0、耗时略高（0.154s vs 0.129s，仍是空事务 + 校验）。
3. **`--verify` 可作为回退前的闸门**：LEFT=0 且 `left_at` 全空才退出 0。
4. **旧查询语义（3 行 → 3 行）是 A 口径的接受项**，与 §6.1 一致：旧代码只认行、不认 status；要让它变 2 行只能删行，而删行违反 FR-215 并丢退班历史，所以不做。
5. **清理干净**：只删自己造的 `d2b0…`，qa 回到 B0b 基线（34 / 3 / 34，残留 0）。
6. **仍未验证**：代码回退到 `2e9ce2b`、真实旧 jar 启动、旧接口/页面成员列表 —— 本阶段只做 SQL 层（与 §结论 第 5 条一致）。

### 7.4 风险提示（qa 迁移号）

qa 现在停在 **V081**，而 V075–V081 是 012 这条线带上去的。**将来用别的 spec 分支的 jar 部署 qa 时，迁移号会不一致**：该分支若不包含 V075–V081，Flyway 会因「已应用的版本在本地迁移脚本里不存在」而拒绝启动（或需要 `baseline`/手工干预）。部署前必须先确认目标分支的迁移集合 ⊇ 已应用集合。

## 8. 实现过程中碰到的几个坑（留给后续维护者）

1. **macOS 自带 bash 3.2 会截断 `$()` 里的 heredoc**：`out="$(psql -f - <<'SQL' … SQL)"` 这种写法，heredoc 正文里只要出现一个**落单的 `)`**（例如 SQL 注释 `-- a)`），bash 3.2 会按括号配对提前结束命令替换，payload 被截断、后半段被当成 shell 命令执行（实测报错 `class_members: command not found`，payload 只喂到 `-- a`）。因此脚本把 payload 先落临时文件、再 `run_psql -f - < "$sqlfile"`，`$()` 里只放函数调用。
2. **`\if` 只吃布尔字面量，不吃 SQL 表达式**：psql 的 `\if :a <> 0` 会报 `unrecognized value "0 <> 0"`；而 `t`/`f` 在 SQL 里会被当成列名（`column "f" does not exist`）。脚本的做法是：判定用数值比较写进 SQL，`\if` 只接收由 SQL 计算好的 `t`/`f`。
3. **bash 3.2 + `set -u` 下空数组展开会崩**：`"${EMPTY_ARR[@]}"` 报 `arr[@]: unbound variable`（bash 4.4 才修）。本脚本原先用「可能为空的数组拼 psql 参数」，在**不给 `--dsn`** 的宿主模式下会直接失败。已改成显式分支（有 conninfo 才加 `-d`）。
4. 附带：`\echo` 会吃掉字面量里的单引号（`status='LEFT'` 打印成 `status=LEFT`），所以「将执行的 SQL」用 `select … as sql` 打印。

### 8.1 连接参数的验证范围（如实标注）

- **真实连接**：只在「`--docker-container` + `--dsn`」这一种组合下真连过（阶段 A / 失败路径全部走这条，见 §3 §4）。
- **宿主 `psql` 模式**：本机没有宿主 `psql`，无法对着真库跑。改用 stub `psql` 单测**参数与密码管道**，三种形式都符合预期：
  - `--dsn "postgresql://teachuser:p%40ss%3Aword@127.0.0.1:55432/teachloop"` → argv 里**没有密码**，环境变量 `PGPASSWORD=p@ss:word`（`%40`→`@`、`%3A`→`:` 解码正确），`PGHOST/PGPORT/PGUSER/PGDATABASE` 齐全；
  - 不给 `--dsn`、只用 `PGHOST/PGPORT/PGUSER/PGPASSWORD/PGDATABASE` → 正常运行（空数组崩溃已修）；
  - `--dsn "host=… port=… dbname=… password=s3cret"` → `-d` 里**没有 password**，`PGPASSWORD=s3cret`。
- 因此「宿主模式连真库」这一档**未验证**，第一次真正回滚时应当在目标机上先跑一次 `--dry-run` 确认能连上。

---

## 9. 交付清单

| 项 | 路径 | 状态 |
|---|---|---|
| 回滚脚本 | `ops/rollback/012-class-lifecycle.sh` | 新增，可执行，`bash -n` 通过 |
| 演练报告 | `docs/reports/012-rollback-drill.md` | 本文件；阶段 A + 阶段 B 均已实测 |
| scratch 库 `rollback_drill` | dev 容器 `teachloop-pg` | 阶段 A 跑完，**已 drop**（夹具 SQL 在 §1，可随时重建） |
| `scripts/`、Java、前端 | — | **未改动** |
| git | — | **未做任何 add / commit / checkout / revert** |
