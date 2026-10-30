# T558 证据：今日页作业/考试查询的执行计划（011）

- 日期：2026-10-30
- 对应验收：`specs/011-self-study-mode/tasks.md` T558「`EXPLAIN` 命中 `offering_classes_class_ix`，
  无 `Seq Scan on offering_classes`（贴执行计划到 `docs/reports/`）」
- 代码：`teachloop-sitting/.../port/AgendaStore.java`（SQL）+ `AgendaAdapter.java`（去重 / 班名批量补）
- 索引来源：`teachloop-sitting/src/main/resources/db/migration/V078__sitting_offering_classes_class_ix.sql`（S0 已提交）

## 1. 办法

本机 dev 库（`teachloop-pg`，55432）停在 `flyway_schema_history` 的 **V075**，S0 的 V076–V081 尚未在这台上执行，
因此直接 `EXPLAIN` 会退化成全表扫（索引还不存在）。为了量「迁移之后」的计划，在**一个事务里**：

1. 按 V078 的 DDL 建同名索引 `offering_classes_class_ix`；
2. 造 5000 个班 + 5000 场作业 + 4983 条 `offering_classes`（`generate_series`），`ANALYZE`；
3. 跑 `EXPLAIN (ANALYZE, BUFFERS)`；
4. `ROLLBACK` —— **dev 库无残留**（回滚后 `offering_classes=17`、`classes=17`，与执行前一致）。

查询文本与 `AgendaStore` 里的两条 SQL 逐字一致；`class_id = any(...)` 里的班 id 由真实
`Ports.ClassMembership.classIdsOf` 提供（生产路径不查 `classes` 表，这里为了构造一个班 id 才用了子查询，
所以计划里出现的 `Seq Scan on classes` 属于**造数据脚本**，不在适配器里）。

## 2. 计划（摘录）

### 2.1 pending（今日页「待交作业」）

```
Sort  (cost=292.61..292.63 rows=10 width=104) (actual time=0.251..0.251 rows=1 loops=1)
  Sort Key: (COALESCE(oc.due_at_override, o.due_at)), o.id
  ->  Nested Loop Left Join  (cost=39.33..154.22 rows=10 width=104) (actual time=0.245..0.245 rows=1 loops=1)
        ->  Nested Loop  (cost=39.19..152.40 rows=10 width=87) (actual time=0.236..0.237 rows=1 loops=1)
              ->  Bitmap Heap Scan on offering_classes oc  (cost=38.90..69.34 rows=10 width=40) (actual time=0.229..0.229 rows=1 loops=1)
                    Recheck Cond: (class_id = ANY ($0))
                    Heap Blocks: exact=1
                    ->  Bitmap Index Scan on offering_classes_class_ix  (cost=0.00..38.90 rows=10 width=0) (actual time=0.227..0.227 rows=1 loops=1)
                          Index Cond: (class_id = ANY ($0))
              ->  Index Scan using offerings_pkey on offerings o  (cost=0.28..8.31 rows=1 width=63)
                    Filter: (((status)::text = 'PUBLISHED'::text) AND ((kind)::text = 'ASSIGNMENT'::text))
        ->  Index Scan using submissions_offering_ix on submissions s  (cost=0.14..0.17 rows=1 width=48)
              Index Cond: (offering_id = o.id)
              Filter: (active AND (student_id = 'fff13532-…'::uuid))
Execution Time: 0.282 ms
```

### 2.2 upcoming（未来 7 天考试）

```
Sort  (cost=291.00..291.01 rows=1 width=72) (actual time=0.203..0.204 rows=0 loops=1)
  Sort Key: o.start_at, o.id
  ->  Nested Loop  (cost=39.20..152.77 rows=1 width=72) (actual time=0.199..0.199 rows=0 loops=1)
        ->  Bitmap Heap Scan on offering_classes oc  (cost=38.90..69.34 rows=10 width=32)
              Recheck Cond: (class_id = ANY ($0))
              ->  Bitmap Index Scan on offering_classes_class_ix  (cost=0.00..38.90 rows=10 width=0)
                    Index Cond: (class_id = ANY ($0))
        ->  Index Scan using offerings_pkey on offerings o  (cost=0.28..8.32 rows=1 width=63)
              Filter: ((start_at IS NOT NULL) AND (status = 'PUBLISHED') AND (kind = 'EXAM')
                       AND (start_at >= now()) AND (start_at <= now() + '7 days'))
Execution Time: 0.229 ms
```

## 3. 结论

- 两条查询都走 `offering_classes_class_ix`（`Bitmap Index Scan`），**没有** `Seq Scan on offering_classes`；
  按 `class_id` 过滤后各只读 1 个 heap block。
- `submissions` 侧命中既有 `submissions_offering_ix`，没有再补 `(student_id, offering_id)` 复合索引
  （plan §2.3 预留的「按 EXPLAIN 结果决定」——结论是不需要）。
- 数据量：5000 班 / 5000 作业 / 4983 关联行，执行时间 0.28ms（pending）、0.23ms（upcoming）。
- 回滚核对：`rollback` 后 `offering_classes=17`、`classes=17`，dev 库未被污染。

## 4. 口径说明（AC-90.7）

`StudentTodayEndpoint` 共 4 次 O(1) 端口调用：
`ReviewSlicePort.today`（复习切片）、`AgendaPort.pending`、`AgendaPort.upcoming`、
`ClassMembership.classIdsOf`（判定 `inClass`）。
第 4 次是 accept 的偏离：AC-90.3 要求「在班但没作业」保留作业卡并写「没有要交的作业。」，
AC-90.5 要求「没班」时整卡不出现并换邀请码卡——两者都返回空数组，不读一次成员关系无法区分。
没有按 offering 逐条请求（无 N+1），也没有查 app 层 SQL（FR-230 的守卫仍然是绿的）。
