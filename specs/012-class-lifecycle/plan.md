# 实施计划 — 班级生命周期（012-class-lifecycle）

- 上游：`specs/012-class-lifecycle/spec.md`（3 US / 16 AC / 12 FR）、`docs/mock/class-lifecycle-2026-10-30/`
- 状态：**草案（2026-10-30），待 review**
- 迁移序号：`V081__classroom_class_member_status.sql`；011 占 `V076–V078`、013 占 `V079–V080`，合并前统一重排
- 依赖：011 的 `GET /api/student/today`（T559）已上线，AC-98.4 才能落地；其它任务不依赖 011

---

## 1. 架构总览

改动集中在 `teachloop-classroom`，加一个跨模块只读端口：

```
students ──DELETE /api/classes/{id}/membership──┐
teachers ──DELETE /api/classes/{id}/members/{sid}─┤→ class_members.status = LEFT（不删行）
teachers ──POST   /api/classes/{id}/archive───────┤→ classes.archived = true
teachers ──POST   /api/classes/{id}/restore───────┘→ classes.archived = false

sitting（提交 / 新建作业）──▶ Ports.ClassLifecycle.state(classId)  ← classroom 实现
app（今日页聚合）──────────▶ 同上（只取未归档班的作业）
vocab（发词收件人）────────▶ 现有 Ports.ClassMembership（加 ACTIVE 过滤）
```

- `classes` / `class_members` 仍只由 classroom 写；`sitting` 与 `vocab` 不 join 这两张表。
- 不发送通知（spec §9 已裁决）；审计走 `com.teachloop.shared.audit.AuditPort`。

## 2. 数据模型

```sql
-- V081__classroom_class_member_status.sql
alter table class_members add column status      text        not null default 'ACTIVE';
alter table class_members add column left_at     timestamptz;
alter table class_members add column rejoined_at timestamptz;
alter table class_members add constraint ck_class_members_status
  check (status in ('ACTIVE','LEFT'));

create index idx_class_members_class_active   on class_members (class_id)   where status = 'ACTIVE';
create index idx_class_members_student_active on class_members (student_id) where status = 'ACTIVE';
create index idx_classes_teacher_active       on classes (teacher_id)       where archived = false;
```

| 实体 | 字段 | 说明 |
|---|---|---|
| `class_members.status` | `ACTIVE` / `LEFT` | 唯一真源；删除行被禁止（FR-215） |
| `class_members.left_at` | 时间戳 | 最近一次离开时间；重入不清空，保留历史 |
| `class_members.rejoined_at` | 时间戳 | 最近一次重新加入时间；`joined_at` 永远保持**首次** |
| `classes.archived` | boolean | 字段早已存在（`V003`），本次开始被读写 |

状态迁移：

| 前 | 后 | 触发 | 副作用 |
|---|---|---|---|
| 无行 | `ACTIVE` | 邀请码加入 | 写 `joined_at` |
| `ACTIVE` | `LEFT` | 学生退出 / 老师移出 | 写 `left_at`；提交与成绩不变 |
| `LEFT` | `ACTIVE` | 重新用邀请码加入 | 写 `rejoined_at`；`joined_at` 不变；不新增行 |
| `archived=false` | `true` | 老师结课 | 邀请码失效；新作业被拒；今日页不再计入 |
| `archived=true` | `false` | 老师恢复 | 邀请码恢复；可再布置与提交 |

## 3. 端口

```java
// teachloop-shared/.../port/Ports.java
public record ClassState(UUID classId, boolean archived, boolean active) {}
public interface ClassLifecycle {
    Optional<ClassState> state(UUID classId);
}
```

- 实现：`teachloop-classroom/…/port/ClassLifecycleImpl`（一次查询 `classes` + 当前用户是否 `ACTIVE` 成员）。
- 调用方：`sitting`（提交、新建作业）、`app`（今日页聚合过滤）、`vocab`（发词收件人可选）。
- 端口签名只收发 record 与 JDK 类型（`check-boundaries` 的 `check_port_signatures` 会校验）。

## 4. 流程与校验

### 4.1 学生退出（`DELETE /api/classes/{id}/membership`）

1. 必须是该班 `ACTIVE` 成员，否则 404（不泄露班级存在性）。
2. 班级 `archived=true` 时仍允许退出（已经结束的班不该把人锁在里面）。
3. `update class_members set status='LEFT', left_at=now()`（不删行）。
4. 审计 `class:leave`。
5. 不通知老师（本期裁决）。

### 4.2 老师移出（`DELETE /api/classes/{id}/members/{studentId}`）

1. 仅班级属主；非属主 404（与现有 `requireOwned` 一致）。
2. 目标不是 `ACTIVE` 成员 → 404。
3. 写 `LEFT`，审计 `class:remove-member`。
4. 不删提交与成绩（AC-97.4）。

### 4.3 结课 / 恢复（`POST /api/classes/{id}/archive|restore`）

1. 仅属主；幂等（重复结课返回 200 与当前状态）。
2. 结课：`archived=true`，审计 `class:archive`。
3. 恢复：`archived=false`，审计 `class:restore`。
4. 结课后：`POST /api/classes/join` 用该码返回 404（不返回「已归档」）；新建作业返回 409 `CLASS_ARCHIVED`。

### 4.4 提交拦截（sitting）

- 提交接口在现有 dueAt 校验之外，增加 `classLifecycle.state(offering.classId).archived → 409 CLASS_ARCHIVED`。
- 一个作业可能发给多个班：**只要有至少一个未归档班仍包含该生**，就允许提交；全部归档才拒绝。实现上取该生在这些班中的状态集合判断，避免「一个班结课把另一个班也锁死」。

### 4.5 今日页与发词

- 今日页聚合（011）过滤 `archived=true` 的班；依赖 `Ports.ClassLifecycle`。
- 发词收件人只取 `ACTIVE` 成员（`Ports.ClassMembership` 加过滤）；已发出的快照不动。

## 5. 界面（见 mock）

| 屏 | 关键约束 |
|---|---|
| 学生「我的班级」 | 分「进行中 / 已结束」；进行中有「退出班级」，已结束只有「查看成绩」 |
| 退出确认 | 必须列出未交作业数量与三条后果（看不到作业 / 成绩保留 / 词库不变） |
| 老师「班级」 | 班级行有「结课 / 恢复」；成员表默认可切换「含已离开」 |
| 结课确认 | 说明「学生只读、邀请码失效、不能再建作业、可恢复」 |

平板：834×1194 与 1024×1366 下 `scrollWidth <= innerWidth + 2`、首屏外按钮 0（mock 已实测 9 组）。

## 6. 失败与降级

| 环节 | 失败时 | 重试 |
|---|---|---|
| `ClassLifecycle` 查询失败 | 提交按「未归档」放行（宁可放行不误伤），并记 warning；新建作业同样放行 | 下次请求重试 |
| 结课与并发提交 | 结课先写 `archived`，提交侧读到即拒绝；已开始的作答允许交卷（不打断考试） | — |
| 重复结课 / 重复移出 | 幂等返回当前状态，不报 500 | — |

## 7. 迁移与回滚

- 存量成员一律 `ACTIVE`、存量班级 `archived=false`；无需回填。
- **回滚脚枪（必须写进脚本）**：回退代码前先执行
  ```sql
  update class_members set status='ACTIVE', left_at=null where status='LEFT';
  ```
  否则旧代码读全部成员行，退班学生会「复活」；且旧代码没有 `status` 字段概念，`LEFT` 行会被当成在班。
- 索引可随代码回退一起 drop（纯性能）。
- `classes.archived` 字段是存量字段，回滚不需要删列；但要确认没有别处依赖（当前无）。

## 8. 测试策略

| 层 | 覆盖 |
|---|---|
| 单测 | 退班/移出幂等；重入保留 `joined_at`、写 `rejoined_at`；属主校验 404 |
| 集成 | 退班后 `GET /api/classes` 不含该班、提交记录仍在；结课后 join 404、提交 409、恢复后恢复；多班作业只结一个班仍可提交 |
| 今日页 | 结课班的作业不出现在 `/api/student/today`（依赖 011 已上线） |
| 前端 | 确认弹窗的三条后果文案存在；已结束班只读无提交入口 |
| 守卫 | `make check-deps check-boundaries`（新端口签名、迁移前缀 `classroom`） |

## 9. Plan review 清单（6 条）

| # | 条目 | 结论 | 证据 |
|---|---|---|---|
| 1 | 每条 AC 能指出表/流程承接 | ✅ | §10，16 条全覆盖 |
| 2 | 表、字段、索引、状态迁移写全 | ✅ | §2：3 列 + 3 索引 + 状态迁移表；无新表 |
| 3 | 外部依赖失败与重试 | ✅ | §6；本计划无新增外部服务 |
| 4 | 模块依赖无环、跨模块只走端口 | ✅ | 新增 `Ports.ClassLifecycle`；classroom 仍是最内层，不被反向依赖 |
| 5 | 每表一个属主、无跨模块 JOIN | ✅ | `classes` / `class_members` 仍只由 classroom 写 |
| 6 | 拆服务路径 | ✅ | 端口即 HTTP 契约；班级数据可整体迁出（教师/学生两端只读写该模块） |

## 10. AC → 承接

| AC | 承接 |
|---|---|
| AC-96.1–96.5 | §4.1 退班流程 + §5 确认弹窗 + 词库不变（无需写 vocab） |
| AC-97.1–97.4 | §4.2 移出 + 成员列表过滤 + 提交保留 |
| AC-98.1–98.3 | §4.3 结课/恢复 + join 404 |
| AC-98.4 | §4.5 今日页过滤（依赖 011 T559） |
| AC-98.5 | §4.3 恢复路径 |
| AC-98.6 | 结课/恢复不触碰 `vocab_progress` 与提交表（加断言） |
| AC-98.7 | §4.3 新建作业 409 + §4.4 提交拦截 |

## 11. 待定

1. 结课与「已开始的考试」的关系：本计划按「已开始的作答允许交卷」处理（不打断考试中的人）；若产品要求立即锁死，验收要改。
2. 是否给学生「退出后 7 天内可撤销」：本期不做，退出后用邀请码重入即可。
