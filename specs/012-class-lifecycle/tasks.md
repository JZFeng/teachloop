# 任务拆分 — 班级生命周期（012-class-lifecycle）

- 上游：`spec.md`（3 US / 16 AC / 12 FR）、`plan.md`（2026-10-30）、`docs/mock/class-lifecycle-2026-10-30/`
- 任务号：**T581 起**（011 到 T568，013 到 T580）
- 迁移序号：`V081__classroom_class_member_status.sql`，合并前重排
- 依赖：AC-98.4（今日页过滤结课班）需要 011 的 T559 已上线；其余任务不依赖 011/013
- 约定：每个任务自带验收；完成后回写本文档并写执行记录；提交信息带 T 号

---

## 阶段 A — 地基

| ID | 任务 | 依赖 | 验收 |
|---|---|---|---|
| T581 | **迁移**：`class_members` 加 `status/left_at/rejoined_at` + `ck_class_members_status`；两个 partial 索引；`classes(teacher_id) where archived=false` | — | 空库与含存量库各跑一次；存量全部 `ACTIVE` / `archived=false`；`make check-boundaries` 通过（迁移文件名前缀必须是 `classroom`） |

## 阶段 B — 退出与重入

| ID | 任务 | 依赖 | 验收 |
|---|---|---|---|
| T582 | **退班与移出**：`DELETE /api/classes/{id}/membership`（学生）、`DELETE /api/classes/{id}/members/{studentId}`（属主）；都只写 `LEFT` + `left_at`；审计 `class:leave` / `class:remove-member` | T581 | AC-96.2 退出后 `select count(*) from class_members` 不变；AC-96.1/96.5 确认弹窗所需数据（未交作业数）由接口提供；AC-97.1 非属主 404；`grep -r "DELETE FROM class_members" src` 为空 |
| T583 | **重入幂等**：用邀请码重新加入时 `LEFT → ACTIVE`，`joined_at` 不变、写 `rejoined_at`，不新增行 | T582 | AC-96.3 重入后 `GET /api/classes` 恢复该班；库里仍只有一行；历史提交与成绩可查 |

## 阶段 C — 结课

| ID | 任务 | 依赖 | 验收 |
|---|---|---|---|
| T584 | **结课 / 恢复**：`POST /api/classes/{id}/archive|restore`（属主、幂等）；结课后 `POST /api/classes/join` 对该码返回 404；审计 `class:archive` / `class:restore` | T581 | AC-98.1/98.5 状态正确且幂等；AC-98.3 join 404 且响应体不含「已归档」字样；AC-98.6 操作前后 `vocab_progress` 与提交表逐行一致（对比脚本） |
| T585 | **端口与写入拦截**：`Ports.ClassState` + `Ports.ClassLifecycle`（classroom 实现）；`sitting` 提交接口与新建作业接口接入 | T584 | AC-98.7 对已结课班级新建作业返回 409 `CLASS_ARCHIVED`；提交被拒 409；**多班规则**：作业发两个班、只结其中一个时，该生仍可提交（集成测试） |
| T586 | **今日页过滤**：`GET /api/student/today` 排除已结课班级的作业 | T585 + 011 T559 | AC-98.4 结课前后 `assignments` 数组差异正确；教师/管理员仍返回空段 |

## 阶段 D — 口径收口

| ID | 任务 | 依赖 | 验收 |
|---|---|---|---|
| T587 | **成员列表**：默认只返回 `ACTIVE`，`?includeLeft=1` 时含 `LEFT`；成员数与报表口径同步 | T582 | AC-97.2 默认/含已离开两种结果；`memberCount` 与列表长度一致 |
| T588 | **发词收件人**：只取 `ACTIVE` 成员；已发出的快照不变 | T582 | AC-97.3 移出后新发词不发给该生；旧发词领取状态不变 |

## 阶段 E — 前端与验收

| ID | 任务 | 依赖 | 验收 |
|---|---|---|---|
| T589 | **前端**：学生「我的班级」分进行中/已结束 + 退出确认（列未交数量与三条后果）；老师班级页结课/恢复 + 成员表「含已离开」；已结束班只读 | T582–T587 | AC-96.1/96.4/97.2/98.2 走查；834×1194 与 1024×1366 下 `scrollWidth <= innerWidth+2`、首屏外按钮 0；已结束班无「去完成/提交」入口 |
| T590 | **测试与守卫**：单测（幂等、puts）、集成（退班后词库与提交不变；结课/恢复；多班）、i18n 新增 `class.lifecycle.*` | T589 | `make check-deps check-boundaries check-i18n check-ui` 与 `make test` 全绿；报告贴 `docs/reports/` |
| T591 | **回滚演练**：`update class_members set status='ACTIVE', left_at=null where status='LEFT';` + 回退代码，在 qa 演练一次 | T590 | 演练记录含命令、耗时与「退班学生不再复活」的验证；脚本可重复执行 |

---

## 覆盖自检

```bash
grep -cE '^\| T58[0-9]|^\| T59[01]' specs/012-class-lifecycle/tasks.md   # 期望 11
grep -ho '^| T[0-9]*' specs/*/tasks.md | sort | uniq -d                   # 期望只剩 004 的「定义行 + 执行记录行」假重复
```

---

## 执行记录（2026-10-30）

| ID | 状态 | 证据 |
|---|---|---|
| T581 | ✅ 已完成（S0，2e9ce2b） | `V081__classroom_class_member_status.sql` 已在 main；`make check-boundaries` 通过 |
| T582 | ✅ | `ClassEndpoint.leave/removeMember` 只写 `LEFT` + `left_at`；审计 `class:leave` / `class:remove-member`；`grep -r "DELETE FROM class_members" teachloop-*` 为空；`ClassLifecycleTest.leaveKeepsRowAndRejoinReusesIt` / `teacherRemoveOnlyOwnerAndListFilters` |
| T583 | ✅ | 重入复用同一行（`ClassMember.rejoin`），`joined_at` 不变、写 `rejoined_at`；`ClassLifecycleTest` 断言行数仍 1 |
| T584 | ✅ | `POST /api/classes/{id}/archive|restore` 幂等、仅属主；join 对已结课班 404 且不含归档字样；结课/恢复前后提交与 `vocab_progress` 指纹一致（`archiveAndRestoreLifecycle`） |
| T585 | ✅ | `Ports.ClassLifecycle` 由 `ClassLifecycleImpl` 实现；提交（submit/submitFile/submitVideo）与新建作业接入；**多班规则**见 `multiClassArchiveKeepsSubmittable`（只结一个班仍可提交；全部结课 409 `CLASS_ARCHIVED`；恢复后可再交） |
| T586 | ⏳ 待联调 | 011 的 `GET /api/student/today` 在 011 分支（`teachloop-app/.../app/student/StudentTodayEndpoint.java` + sitting `AgendaAdapter`）里在飞且未合并；本分支不造临时端点。联调动作：在 `StudentTodayEndpoint` 组装 `assignments` 时按 `Ports.ClassLifecycle.state(classId).archived` 过滤。**AC-98.4 未勾选** |
| T587 | ✅ | 成员列表默认 ACTIVE、`?includeLeft=1` 含已离开；`memberCount` 与列表长度同口径（`teacherRemoveOnlyOwnerAndListFilters`） |
| T588 | ✅（端口级） | `ClassMembershipImpl.membersOf/classIdsOf` 只取 ACTIVE，发词收件人循环取自 `membersOf`；另加「已结课班不能作为发词目标」的 409（FR-224）。旧发词快照表不动（既有快照设计） |
| T589 | ✅ | 学生两屏 + 老师两屏 + 三套二次确认；已结课班只读、无提交入口；平板 834×1194 与 1024×1366 实测 `scrollWidth == innerWidth`、首屏外按钮 0（学生/老师各一轮） |
| T590 | ✅ | `ClassLifecycleTest` 5 例全绿；web `pnpm test` 191/191、`check-ui` 退 0；`check-deps`/`check-boundaries` 退 0；`check-i18n` 的 1 处红在纯净 main 复现（`web/src/api/auth.tsx:108`，非本次）；报告 `docs/reports/012-class-lifecycle.md` |
| T591 | ✅ | `ops/rollback/012-class-lifecycle.sh`（`--dry-run/--apply/--verify`）；qa 真库演练 `--apply` 复位 1 行 0.129s、幂等第二次 0 行、行数 37→37 不变；详见 `docs/reports/012-rollback-drill.md` |

### 已知取舍与未完成项

1. **回滚语义（实测修正）**：`update ... set status='ACTIVE', left_at=null where status='LEFT'` 不会让退班学生从**旧代码**的成员列表消失——旧查询没有 status 过滤，行在就在。这条 SQL 真正保住的是「012 重新上线时不把在班学生误判成 LEFT」。删 LEFT 行才能让旧代码看不见他们，但违反 FR-215 并丢历史，故不采用。详见 `docs/reports/012-rollback-drill.md` §6.1。
2. **AC-98.4 待联调**（T586），不造临时端点。
3. **已开始的作答**：按 spec FR-220 / T585 采用「提交即校验」，已开始的作答在班级结课后提交会被 409 拒绝（plan §11 待定项 1 未裁决；若产品要求「已开始允许交卷」，改动点在 `OfferingWindows#requireSubmittable`）。
4. 并发会话提示：本机同时有 011/013 分支在写共享工作区；本轮验证在独立 worktree（`/tmp/tl012`）与独立库（`teachloop_verify012`）完成，未污染 dev 库。
