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
