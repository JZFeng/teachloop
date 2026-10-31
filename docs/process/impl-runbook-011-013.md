# 011 / 012 / 013 实施运行手册（2026-10-30）

S0（地基）已完成并提交：`2e9ce2b feat(foundation): S0 迁移与端口契约（011/012/013）`。

## S0 交付了什么（后续 session 不要再做）

| 项 | 内容 |
|---|---|
| 迁移 | `V076` users.timezone；`V077` classes 收费三列；`V078` offering_classes(class_id) 索引；`V079` 词书三表 + `vocab_lists.book_id`；`V080` 来源 `BOOK`；`V081` class_members 状态列 |
| 端口 | `ReviewSlice / AssignmentRow / ExamRow / UserZone / BookRef / ClassState` + 5 个接口 |
| 守卫 | `check-boundaries` 把 `app` 纳入 SQL 扫描（白名单 `DeepHealthEndpoint`）；3 张新表已登记 `TABLE_OWNER` |
| 工具 | `shared/time/ZoneResolution`（头 > 资料 > 产品默认，非法值回落）；`application.yml` 加 `teachloop.timezone.default` |

**验证**：`check-boundaries` 62 表通过；`check-deps` 通过；全量编译通过；68 个迁移在全新 Postgres 16 上一次跑通（61 张表）。

## 并行规则

- 三个 session 各开自己的分支（或 worktree），**都从 `2e9ce2b` 起**。
- **谁都不许再写迁移**：需要改表 = 停下来报告，不要自己加 `V082`。
- **谁都不许改 `Ports.java` / `scripts/check-boundaries.py` / `scripts/check-deps.py`**：契约与守卫已定，要加东西先报告。
- 前端共享文件归 S1：`web/src/App.tsx`、`web/src/app/nav.ts`、`web/src/app/Shell.tsx`、`web/src/locales/*`。S2/S3 的前端先写页面组件，路由挂载等 S1 合并后再加。
- 并行度上限 3；不要四个人同时改 `teachloop-sitting`。

## 三个 session 的 prompt（原样粘贴）

### S1 — 011 今日页与自学模式（T557–T568）

```
仓库：/Users/jzfeng/Documents/deepseek-harness/teachloop（Spring Boot 3 + React + Postgres）。
先读 AGENTS.md（常驻规则）、再读 specs/011-self-study-mode/{spec,plan,tasks}.md 与 docs/mock/self-study-2026-10-30/。

基线：main 的 2e9ce2b 已包含 S0（6 个迁移、Ports 契约、守卫扩展、ZoneResolution）。**不要再写迁移**，
需要改表就停下来报告；不要改 teachloop-shared/.../Ports.java 与 scripts/*。

你的分支：git checkout -b feat/011-today（从 main 起）。完成后提交到该分支，不推 main。

范围：只做 specs/011-self-study-mode/tasks.md 的 T557–T568，其中
- T557：迁移与 ZoneResolution 已由 S0 完成；你只做剩余部分 —— `UserZonePort` 的 identity 实现、
  `X-Timezone` 解析、`ReviewQueueEndpoint.countReviewedToday` 与 `QuizEndpoint.todayWords` 改用学生时区
  （`VocabularyGenerationService` 的 AI 预算保持产品时区，不动）。
- T560（app SQL 守卫）已由 S0 完成，跳过。
- 其余 T558/T559/T561–T568 按 tasks.md 逐条做。

写文件范围：teachloop-vocab（review/quiz 两处）、teachloop-sitting/port、teachloop-app/student、
web/src（今日页、背单词模块壳、导航、文案、欢迎页）。前端共享文件（App.tsx / nav.ts / Shell.tsx / locales）
由你独占，改之前先确认没有其他人在改。

硬要求：
1. 每个任务完成即按 tasks.md 的「验收」列自检，并把证据（命令、退出码、关键数字）写进报告。
2. 禁止部署（dev/prod 都不动）；禁止改 012/013 的目录与 docs/mock/*。
3. 全部完成后跑：make check-deps check-boundaries check-i18n check-ui && make test（或等价的 mvn 命令），
   把真实退出码写进报告；没跑通的不许写「通过」。

报告格式（最后一条消息）：
- 三行：刚做完 / 正在做 / 卡住或等你
- 文件清单（新增/修改，按模块分组）
- 测试命令 + 退出码 + 关键数字（如 today 端点 P95、E2E 结果）
- 未完成 / 偏离规格的地方（没有就写「无」）
```

### S2 — 013 系统词书（T569–T580）

```
仓库：/Users/jzfeng/Documents/deepseek-harness/teachloop（Spring Boot 3 + React + Postgres）。
先读 AGENTS.md、specs/013-word-books/{spec,plan,tasks}.md、docs/mock/word-books-2026-10-30/、docs/research/2026-10-30-word-books.md。

基线：main 的 2e9ce2b 已包含 S0 —— 你要的表与列已经建好（vocab_books / vocab_book_entries /
vocab_book_joins / vocab_lists.book_id / vocab_word_sources.book_id + kind 含 BOOK），TABLE_OWNER 也登记了。
**不要再写迁移**；需要改表就停下来报告。不要改 Ports.java（BookRecommendation 契约已在）与 scripts/*。

你的分支：git checkout -b feat/013-word-books（从 main 起）。完成后提交到该分支。

范围：tasks.md 的 T569–T580，其中 T569（迁移）已完成，你从 T570 开始：
- 管理端 CRUD + 发布/下架 + 审计（T570）
- 导入复用「加词」页的解析与预览，提交目标换成书（T571）
- 覆盖率实时计算（T572）、推荐端口实现（T573）
- 学生端列表/详情/预览（T574）、加入事务（T575）、会话按 listId（T576）
- 前端五屏（T577）、付费字段校验与学生端裁剪（T578）、测试（T579）、回滚演练（T580）

约束：
1. 加入必须幂等、只追加来源、不改 vocab_progress、不触发全量内容生成（tasks.md 的三条验收有硬断言）。
2. 前端不要动 App.tsx / nav.ts / Shell.tsx / locales —— 这些归 S1。先写成独立页面组件，
   路由挂载留一个 TODO 并在报告里说明；需要共享文案 key 时先写成常量，S1 合并后再迁到 locales。
3. 禁止部署；禁止改 011/012 的目录。

报告格式同 S1（三行 + 文件清单 + 命令与退出码 + 未完成项）。另外必须贴出：
- 加入幂等的实测数字（两次调用 created/duplicated）
- 同一段文本在「加词」页与「书导入」页的清洗/跳过结果对比
```

### S3 — 012 班级生命周期（T581–T591）

```
仓库：/Users/jzfeng/Documents/deepseek-harness/teachloop（Spring Boot 3 + React + Postgres）。
先读 AGENTS.md、specs/012-class-lifecycle/{spec,plan,tasks}.md、docs/mock/class-lifecycle-2026-10-30/。

基线：main 的 2e9ce2b 已包含 S0 —— class_members 已有 status/left_at/rejoined_at 与三个索引，
Ports 里已有 ClassState / ClassLifecycle。**不要再写迁移**；不要改 Ports.java 与 scripts/*。

你的分支：git checkout -b feat/012-class-lifecycle（从 main 起）。完成后提交到该分支。

范围：T581（迁移已完成，跳过）→ T582–T591：
退班/移出（写 LEFT 不删行）、重入幂等、结课/恢复、ClassLifecycle 实现与 sitting 两侧拦截、
成员列表默认 ACTIVE、发词收件人过滤、前端两屏、测试、回滚演练。

两条关键规则（写错了会返工）：
1. 一个作业发给多个班、只结其中一个班时，该生仍可提交；必须用「至少一个未归档班仍包含该生」判断。
2. 回滚脚本必须先 `update class_members set status='ACTIVE', left_at=null where status='LEFT';`
   再回退代码，否则旧代码会把退班学生当在班。这条要写成可执行脚本并演练。

依赖：AC-98.4（今日页排除结课班）依赖 011 的 /api/student/today。它可能还没上线；
若接口不存在，先把其它任务做完，AC-98.4 在报告里标「待联调」，不要自己造一个临时端点。

前端：只改班级相关页面与组件；不要动 App.tsx / nav.ts / Shell.tsx（归 S1）。

报告格式同 S1（三行 + 文件清单 + 命令与退出码 + 未完成项）。另外必须贴出：
- 多班只结一个班的提交测试结果
- 回滚演练的命令与验证结论
```

## 合并顺序建议

`S1 → S2 → S3`（S1 持有前端共享文件与今日页接口；S2 的 AC-98.4 依赖 S1 的聚合端点，S3 排最后验收一次到位）。
若三个人并行结束，先合 S1，再让 S2/S3 各自 rebase 后合。
