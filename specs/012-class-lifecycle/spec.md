# 规格说明 — 班级生命周期（012-class-lifecycle）

- 特性编号：`012-class-lifecycle`
- 状态：**草案（2026-10-30），待用户 review**；未进入 Plan / Tasks
- 上游：2026-10-30 决策（学生退班 / 老师移出 / 结课归档，三选均取「只读可见」方向）；`011-self-study-mode` §9 第 5 条
- 相关规格：`002-v2-platform`（班级与身份）、`011-self-study-mode`（今日页聚合）、`004-vocab`（发词收件人）
- 编号区间（全局唯一，落笔前已取号）：US-96–98 / AC-96.x–98.x / FR-214–224
- 日期：2026-10-30

---

## 1. 摘要

让班级有终点，但**不删除任何数据**：

1. 学生可以**退出**班级（加错班、不想在这个班）；
2. 老师可以**移出**学生（转班、误加）；
3. 老师可以**结课**（一期结束），结课后班级对学生**只读可见**，邀请码失效，今日页不再计入它的作业。

三者都是「状态变更」，不是 `DELETE`：`class_members` 增加 `status`/`left_at`，`classes.archived` 从死字段变成真正被读写的状态。

| 议题 | 现在 | 本次之后 |
| :--- | :--- | :--- |
| 学生退出班级 | 无任何接口 | `DELETE /api/classes/{id}/membership`，写 `LEFT` |
| 老师移出学生 | 无任何接口 | `DELETE /api/classes/{id}/members/{studentId}`，写 `LEFT` |
| 班级结课 | `classes.archived` 字段存在但无读写点 | 结课 / 恢复两个端点；学生侧只读可见 |
| 结课后邀请码 | 仍可加入 | 返回 404（不泄露存在性） |
| 结课后今日页 | 作业仍计入 | 不再计入 |

## 2. 角色

| 角色 | 能做 | 不能做 |
| :--- | :--- | :--- |
| 学生 | 退出自己所在的班；用邀请码重新加入；查看已结束班级的历史作业与成绩 | 退出后继续提交该班作业；查看班级成员 |
| 教师 | 移出本班学生；结课；恢复结课；批改历史提交 | 移出别人班的学生；对已结束班级新建作业 |
| 管理员 | 不变（无班级） | — |

## 3. 用户故事与验收标准

### US-96 学生可以退出一个班级

作为学生，我加错班或不打算在这个班继续时，要能自己退出，而不是找老师手工处理。

- [ ] AC-96.1 班级页对每个进行中的班级提供「退出班级」；点击出现二次确认，文案包含三点：退出后看不到该班作业；已提交的作业与成绩保留；词库与复习进度不受影响。
- [ ] AC-96.2 确认后 `class_members.status` 变为 `LEFT`、`left_at` 写入当前时间；该成员行**不被删除**（退出前后 `select count(*) from class_members` 不变）。
- [ ] AC-96.3 退出后 `GET /api/classes`（学生）不再返回该班；用同一邀请码重新加入后 `status` 回到 `ACTIVE`，`joined_at` **保持首次时间不变**、`rejoined_at` 写入本次时间；历史提交与成绩仍可查。
- [ ] AC-96.4 退出前后 `GET /api/vocab/words` 的总数与到期数一致；`vocab_progress.due_at` 不被改写。
- [ ] AC-96.5 学生在该班有未提交作业时，确认弹窗列出数量（如「还有 2 份作业未交」）；未确认前不执行任何写入。

### US-97 老师可以把学生移出班级

作为老师，学生加错班或转班时，我要能把他移出，但成绩和历史不能丢。

- [ ] AC-97.1 成员列表每行有「移出」；只有班级属主可操作，非属主调用返回 404。
- [ ] AC-97.2 移出写 `LEFT`、不删行；成员列表默认只显示 `ACTIVE`，可切换「含已离开」查看历史成员。
- [ ] AC-97.3 移出后新发的词不再发给该生（收件人只取 `ACTIVE` 成员）；已发出的词集与领取状态不变。
- [ ] AC-97.4 移出时该生有未批改的提交，批改页仍能看到并批改，成绩不丢。

### US-98 老师可以结课，结课后学生只读可见

作为老师，一期课结束时我要把班「关掉」，学生还能回看成绩与作业，但不能再交。

- [ ] AC-98.1 老师可对班级点「结课」，二次确认文案说明「学生仍可查看历史作业与成绩，但不能再提交」；确认后 `classes.archived = true`。
- [ ] AC-98.2 学生 `GET /api/classes` 仍返回该班并带 `archived: true`；班级页显示「已结束」，进入后作业与成绩只读，无提交入口。
- [ ] AC-98.3 结课后该班邀请码不可用：`POST /api/classes/join` 返回 404，不返回「已归档」之类的存在性信息。
- [ ] AC-98.4 结课班级的作业不出现在今日页与待交作业列表；`GET /api/student/today`（011 FR-207）不返回该班作业。
- [ ] AC-98.5 老师可「恢复」班级（`archived = false`）；恢复后邀请码重新有效、作业可提交，历史数据不变。
- [ ] AC-98.6 结课与恢复不改写任何学生数据：操作前后 `vocab_progress.due_at`、提交记录、成绩完全一致。
- [ ] AC-98.7 对已结课班级调用「新建作业 / 布置试卷」接口返回 409 `CLASS_ARCHIVED`；前端对应按钮置灰并说明原因（不得只靠前端禁用）。

## 4. 功能需求

| 编号 | 需求 |
| :--- | :--- |
| FR-214 | `class_members` 增加 `status`（`ACTIVE` / `LEFT`，默认 `ACTIVE`）、`left_at`、`rejoined_at`；迁移必须能在已有数据的库上执行，存量行一律 `ACTIVE` |
| FR-215 | 学生退出与老师移出都只写状态，不删除成员行；任何路径都不得 `DELETE FROM class_members` |
| FR-216 | 所有「成员」类查询默认只取 `ACTIVE`；需要历史的入口（含已离开、报表）显式声明包含 `LEFT` |
| FR-217 | 学生用同一邀请码重新加入时，把已有 `LEFT` 行改回 `ACTIVE`：`joined_at` 不改写、`rejoined_at` 更新，不新增第二行（幂等） |
| FR-218 | 启用 `classes.archived`：新增结课与恢复两个端点，只有班级属主可调用 |
| FR-219 | 归档班级的邀请码一律不可用，`join` 返回 404 |
| FR-220 | 归档班级对学生的作答入口只读：提交类接口由服务端拒绝（403 或 409），不得只靠前端隐藏按钮 |
| FR-221 | 归档班级不计入今日页与待交作业聚合（对 `011` 的 `GET /api/student/today` 生效） |
| FR-222 | 跨模块读取班级状态走端口（`Ports.ClassLifecycle` + `Ports.ClassState`），`sitting` / `vocab` 不得直接 join `classes` 表 |
| FR-223 | 退出、移出、结课、恢复四个动作都写审计日志，走 `com.teachloop.shared.audit.AuditPort` |
| FR-224 | 前端班级页按「进行中 / 已结束」两个分组展示；已结束班级不能作为发词目标 |
| FR-225 | 已结课班级禁止新建作业与布置试卷：服务端返回 409 `CLASS_ARCHIVED`，前端同步禁用入口 |

## 5. 界面与交互

- **学生班级页**：进行中班级在上（有「退出班级」），已结束班级在下并标「已结束」；已结束班级点进去只读。
- **老师班级页**：成员表每行「移出」；班级行内「结课」/「恢复」；成员表默认只显示在班学生，有「含已离开」开关。
- **二次确认**：退出与结课都必须先说明后果（看不到作业 / 作业只读），不要让用户在弹窗里才发现。
- **状态文案**：已结束班级不得出现「提交」「去完成」按钮，只保留「查看成绩」。
- 平板上沿用 011 的两档断点（`1100` / `1023`），成员表在 834 宽下允许横向滚动，不得整页横向溢出。

## 6. 非目标

- 学期与续班（term、跨学期继承学生）——需要第二个学期真实出现时再做。
- 退课与退款（依赖付费班级，见 `011` US-95 只预留字段）。
- 转班自动化（退 A 班 + 进 B 班一次完成）——本期是两个独立动作。
- 学生主动退班时的通知推送（老师是否收到提醒见 §9）。
- 管理员代管班级（现在管理员没有班级）。

## 7. 数据与接口

### 7.1 表变更

```sql
alter table class_members add column status      text        not null default 'ACTIVE';
alter table class_members add column left_at     timestamptz;
alter table class_members add column rejoined_at timestamptz;

create index idx_class_members_class_active   on class_members (class_id)   where status = 'ACTIVE';
create index idx_class_members_student_active on class_members (student_id) where status = 'ACTIVE';

-- classes.archived 已存在（V003），补一个部分索引供列表查询使用
create index idx_classes_active on classes (teacher_id) where archived = false;
```

### 7.2 新增接口

| 方法 | 路径 | 调用者 | 语义 |
| :--- | :--- | :--- | :--- |
| `DELETE` | `/api/classes/{id}/membership` | 学生 | 退班：写 `LEFT`；不在班时返回 404 |
| `DELETE` | `/api/classes/{id}/members/{studentId}` | 属主老师 | 移出：写 `LEFT`；非属主 404 |
| `POST` | `/api/classes/{id}/archive` | 属主老师 | 结课：`archived = true` |
| `POST` | `/api/classes/{id}/restore` | 属主老师 | 恢复：`archived = false` |

`ClassView` 增加 `archived`（已有字段透出）与 `memberCount`（只数 `ACTIVE`）。

### 7.3 端口

```java
// teachloop-shared: Ports
public record ClassState(UUID classId, boolean archived, boolean active) {}
public interface ClassLifecycle {
    Optional<ClassState> state(UUID classId);
}
```

- 由 `teachloop-classroom` 实现；`sitting` 与 `vocab` 只依赖端口，不 join `classes`。
- 与现有 `Ports.ClassMembership` 同一模式（`VocabularyAssignmentEndpoint` 已在用）。

## 8. 迁移与兼容

- 存量成员全部 `ACTIVE`、存量班级全部 `archived = false`，迁移无需回填。
- 统计口径变化：成员数从「行数」变「`ACTIVE` 行数」；因为存量没有 `LEFT`，上线当天数字不变。
- **回滚风险**：代码回退到旧版本后，旧查询会读全部成员行（含 `LEFT`），退班学生会「复活」。回滚步骤必须先执行一段 SQL 把 `LEFT` 恢复为 `ACTIVE`，再回退代码——这一条要写进 Plan 的回滚脚本。
- 作业提交与成绩不受影响：它们按 `student_id` 存在 `submissions`，与成员状态无关。

## 9. 已裁决（2026-10-30）

1. 重新加入的时间：**保留首次 `joined_at`，新增 `rejoined_at`**（AC-96.3 / FR-217）。
2. 结课后新建作业：**禁止**，服务端 409 `CLASS_ARCHIVED`（AC-98.7 / FR-225）。
3. 退班 / 移出通知：**本期不发**。理由：`Ports.NotificationPort` 没有「成员变动」模板，收益不明确；若要发，约 +0.5 人日，随时可加。
4. 归档后老师能否批改历史提交：**可以**（AC-97.4 已覆盖移出场景，归档同理）。

无其它待拍板项，等你 review 本规格。

## 10. 自检

```bash
grep -c '^### US-' specs/012-class-lifecycle/spec.md        # 期望 3
grep -cE '^- \[ \] AC-' specs/012-class-lifecycle/spec.md   # 期望 16
grep -c '^| FR-' specs/012-class-lifecycle/spec.md          # 期望 12
grep -ho '^- \[ \] AC-[0-9]*\.[0-9]*' specs/*/spec.md | sort | uniq -d   # 期望只剩存量 27/28 段
```
