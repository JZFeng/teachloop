# 任务拆分 — 自学模式与统一今日页（011-self-study-mode）

- 上游：`spec.md`（7 US / 35 AC / 21 FR）、`plan.md`（2026-10-30 定稿）、`docs/mock/self-study-2026-10-30/`、`docs/mock/today-focus-2026-10-30/`（今日复习全屏，2026-10-30）
- 任务号：**T557 起**（全局最大 T556）
- 约定：每个任务自带验收；完成后回写本文档并写执行记录；提交信息带 T 号
- 迁移序号：`V076`（identity）/ `V077`（classroom）/ `V078`（sitting），**合并前按 main 现状重排**
- 上线顺序：T557 → T566 全部完成后才允许部署；T560 与 T568 可与其它并行

---

## 阶段 A — 地基（时区、端口、查询、守卫）

| ID | 任务 | 依赖 | 验收 |
|---|---|---|---|
| T557 | **时区口径**：迁移 `V076__identity_user_timezone.sql`（`users.timezone` 可空）；shared 新增 `ZoneResolution.resolve(header, stored, default)`；`UserZonePort` + identity 实现；`ReviewQueueEndpoint.countReviewedToday` 与 `QuizEndpoint.todayWords` 改用学生时区；`VocabularyGenerationService` **不动** | — | AC-99.1 / 99.2 / 99.4 单测通过（含 `America/Los_Angeles` 2027-03-14 与 2027-11-07 两天）；同一 UTC 时刻带不同 `X-Timezone` 调 `/api/vocab/review/queue` 得到不同 `todayReviewed`；非法头返回 200 且回落 |
| T558 | **三个端口 + 作业查询**：`Ports.ReviewSlice / AssignmentRow / ExamRow / AgendaPort`；`AgendaAdapter` 用一条 SQL（只碰 `offerings / offering_classes / submissions`，班名走 `ClassMembership.classNames`）；迁移 `V078` 加 `offering_classes(class_id)` 索引 | T557 | AC-90.2 排序正确（逾期在前、按 dueAt 升序、最多 3 条）；AC-90.7 端点只调 3 个端口；`EXPLAIN` 命中 `offering_classes_class_ix`，无 `Seq Scan on offering_classes`（贴执行计划到 `docs/reports/`） |
| T559 | **今日页聚合端点**：`GET /api/student/today`（app 层 `StudentTodayEndpoint`）；三段独立 `try/catch` + `partial`；非学生返回空段 | T558 | AC-94.1 响应三段字段齐全；AC-94.2 任一段失败整体仍 200 且该段 `partial=error`；AC-94.3 教师/管理员调用返回空段不报错 |
| T560 | **app SQL 守卫**：扩展 `scripts/check-boundaries.py` 的 `check_endpoint_persistence`，把 `app` 纳入扫描，白名单仅 `DeepHealthEndpoint.java` | — | 临时在 app 某端点写 `JdbcTemplate` 时 `make check-boundaries` 退出码 1；恢复后为 0；README/AGENTS 无需改 |

## 阶段 B — 资料补全

| ID | 任务 | 依赖 | 验收 |
|---|---|---|---|
| T561 | **补全可跳过**：`ProfileCompletionFilter` 不再 403，改为写请求属性；`/api/auth/me` 返回 `profileIncomplete`；`ClassEndpoint.create` 与 `VocabularyAssignmentEndpoint.create` 显式校验 | — | AC-93.1 未填手机号的学生访问 `/` 与 `/vocab/*` 返回 200；AC-93.4 教师建班/发词仍 403 `PROFILE_INCOMPLETE`；回归：登录、注册、激活、改密、找回密码、资料保存 |

## 阶段 C — 前端

| ID | 任务 | 依赖 | 验收 |
|---|---|---|---|
| T562 | **今日页**：`StudentTodayPage` + 四张卡（复习/作业/考试/邀请码）+ 每卡 loading/empty/error 重试；空词库给「加词 / 导入」 | T559 | AC-90.1 / 90.3 / 90.4 / 90.5 逐条走查；AC-90.6 在 834×1194 与 1024×1366 下 `scrollWidth <= innerWidth+2` 且「开始复习」在首屏；单页请求数 ≤2（Network 面板截图） |
| T563 | **背单词模块壳**：`VocabShell` + 页内 Tab（今日复习 / 我的词单 / 我的词库）；`nav.ts` 按 `group` 折叠三项为一项、自定义 `isActive` | — | AC-91.1 / 91.2 / 91.5；AC-91.4 关掉 `teachloop.vocab.enabled` 后侧栏项与 Tab 都不出现、深链回落；`web/tests/vocab-nav.test.mjs` 更新且通过 |
| T564 | **邀请码卡与入班**：今日页右栏/底部「有邀请码？」卡，复用 `POST /api/classes/join` | T562 | AC-92.1 入班后同会话出现作业卡；AC-92.2 入班前后词库总数与到期数一致；AC-92.3 无效码内联报错不跳转；AC-92.4 已入班不显示该卡 |
| T565 | **首次引导**：`/welcome` 收集页（可跳过，`localStorage` 记 `teachloop.profile.prompted`）+ 顶部可关闭提示条 | T561 | AC-93.2 跳过后本设备不再自动出现；AC-93.3 提示条可关闭、点「去补全」进 `/profile`；刷新后状态保持 |
| T595 | **今日复习改真会话**（2026-10-30 拍板）：今日复习页只留张数 + ⓘ + 「开始复习」；`POST /api/vocab/review-sessions` 支持 `source=today`（scope 快照带 `source`）；到期队列按序取齐后传入，**超过 1000 词分两轮**（背完回准备页再开）；`ReviewQueueStore` 的 `todayReviewed` 口径改成「`session_id is null` 或 `scope.source='today'` 会话日志」，自由复习仍不计入 | T563 | AC-91.3 后半句；004 AC-52.2 修订口径；AC-61.5 不变；单测（口径三态：改造前 / 只改前端=0 / 连带改口径=16）+ E2E 三 project；`docs/mock/today-focus-2026-10-30/` 为对照 |
| T596 | **Tab 页去掉同名标题**（2026-10-30 拍板）：三个 Tab 页不再渲染自己的 `h1`，ⓘ（今日复习）与「新建词单」（我的词单）移到 Tab 行右端（`ViewTabs` 加右侧槽）；词单详情页保留自己的标题 | — | AC-91.6；004 AC-53.9 / 53.10 的标题与 ⓘ 位置按本条回改；三页 `getByRole('heading', { level: 1 })` 不再命中与 Tab 同名的标题；平板两档仍 `actionsBelowFold=0` |
| T597 | **今日复习页去掉设置入口**（2026-10-30 拍板）：删掉 `ReviewPage` 的齿轮按钮与设置 `Drawer`（`ReviewPage.tsx:407` 的 `VocabPrefsCard compact`），`/profile` 的「背单词」块（`ProfilePage.tsx:317`，完整版）成为唯一入口；顺手清掉只为抽屉服务的 state / i18n 键 | — | AC-91.7；004 AC-88.1 修订；`/vocab/review` 与复习会话内 `querySelectorAll('button')` 不再含设置入口；`/profile` 的四项（任务量 / 策略 / 反面内容 / 顺序）仍可改且即时生效；`make check-i18n` exit 0 |

## 阶段 D — 收费字段预留

| ID | 任务 | 依赖 | 验收 |
|---|---|---|---|
| T566 | **班级收费三列**：迁移 `V077__classroom_class_pay.sql`（`pay_mode/price_cents/currency` + 两个 check）；建班传 `PAID` 返回 400；入班遇 `PAID` 返回 409 | — | AC-95.1 存量班级全部 `FREE/0/CNY`；AC-95.2/95.3 两个错误码正确；AC-95.4 前端无价格输入 |

## 阶段 E — 验收与观测

| ID | 任务 | 依赖 | 验收 |
|---|---|---|---|
| T567 | **验收与回归**：i18n 新增 `today.*`；Playwright 平板两档 + 三条路径（注册→加词→今日页→复习 / 登录→词单 / 发词→领取） | T562–T565 | `make check-i18n check-ui` 与 `make test` 全绿；E2E 报告贴 `docs/reports/`；回归清单（登录/注册/改密/发词/批改/报告）逐项打勾 |
| T568 | **观测与基准**：开启 `pg_stat_statements`；`scripts/bench-vocab.py` 造 5000 词 + 500 条日志，记录队列与词库页 P95 | T559 | 基线与数字写入 `docs/reports/`；`/api/student/today` P95 < 50ms（达不到则记录并给结论，不掩盖） |

---

## 覆盖自检

```bash
grep -cE '^\| T5[5-6][0-9]' specs/011-self-study-mode/tasks.md   # 期望 12
grep -ho '^| T[0-9]*' specs/*/tasks.md | sort | uniq -d           # 期望空（T341 为存量，已在 002/006）
```

---

## 执行记录（2026-10-30，分支 `feat/011-today`）

口径：命令与数字都来自实际执行；「通过」只用于真跑绿过的项。详细证据见 `docs/reports/011-*.md`。

| 任务 | 状态 | 证据 |
|---|---|---|
| T557 | 完成（迁移/ZoneResolution 由 S0 交付，本次做剩余部分） | `UserZoneAdapter`（identity，读 `users.timezone`）；`X-Timezone` 进 `ReviewQueueEndpoint` 与 `QuizEndpoint`（`VocabZones`：头 > 资料 > 产品默认，非法值 warning+回落）；`VocabularyGenerationService` 未改。单测 `ZoneResolutionTest` 7 + `VocabZonesTest` 6（含 LA 2027-03-14 / 2027-11-07），集成 `VocabTodayTimezoneTest` 5/5：同一时刻 `X-Timezone=Asia/Shanghai` → `todayReviewed=1`、`America/Los_Angeles` → `2`；非法头仍 200 |
| T558 | 完成 | 端口实现 `ReviewSliceAdapter`（vocab）+ `AgendaStore`/`AgendaAdapter`（sitting，只碰 offerings/offering_classes/submissions，班名走 `ClassMembership.classNames`）；`docs/reports/011-t558-agenda-explain.md`：5000 班/5000 作业下两条查询都命中 `offering_classes_class_ix`，无 `Seq Scan on offering_classes`，0.28ms / 0.23ms |
| T559 | 完成 | `StudentTodayEndpoint`（app/student）：三段各自 try/catch + `partial`；单测 `StudentTodayEndpointTest` 11/11（排序/不截断/单段降级/非学生/时区回落），HTTP 级 `StudentTodayHttpTest` 3/3（字段形状、教师空段、无班 `inClass=false`）。响应在 spec §7.1 上增加 `partial` / `inClass` / `zone` / `defaultZone`（理由见报告） |
| T560 | 跳过（S0 已完成） | `check-boundaries` 把 app 纳入 SQL 扫描，白名单仅 `DeepHealthEndpoint`；`make check-boundaries` exit 0 |
| T561 | 完成 | `ProfileCompletionFilter` 不再 403、只写请求属性；`/api/auth/me` 带 `profileIncomplete`；建班/发词仍 403 `PROFILE_INCOMPLETE`。`ProfileCompletionFilterTest` 6、`ProfileCompletionTest` 2、`ProfileCompletionSwitchOffTest` 1 全绿；seeded 账号补手机号（老库只补空值） |
| T562 | 完成（仅结构校验 + E2E） | `web/src/student/TodayPage.tsx` + 五张卡；E2E `today-student.spec.ts`（平板两档不溢出、「开始复习」首屏、请求数 1）在 3 个 project 全绿，见 T567 |
| T563 | 完成 | `navSpecs` 按 `group` 折叠三项为一项（不新增一级 key），`navItemActive` 支持三路径高亮；`VocabShell` 页内 Tab（今日复习/我的词单/我的词库）；复习会话移到壳外全屏（`VocabFocusLayout`）。`web/tests/vocab-nav.test.mjs` 15/15 |
| T564 | 完成 | 邀请码卡复用 `POST /api/classes/join`，内联错误、不跳转、输入保留；E2E `invite-card.spec.ts` 4/4（AC-92.3/92.4/90.3） |
| T565 | 完成 | `/welcome`（可跳过，`localStorage['teachloop.profile.prompted']`）+ Shell 顶部可关闭提示条（`sessionStorage` 记录本次会话关闭）；`welcomeGate` 纯函数 13 条断言 |
| T566 | 完成 | `ClassEntity` 接三列（默认 FREE/0/CNY）；建班 `PAID` → 400、入班付费班 → 409；`ClassPayTest` 3/3，存量班级 `pay_mode<>'FREE'` 计数 0；前端无价格输入 |
| T567 | 完成 | `docs/reports/011-t567-e2e.md`：Playwright 全量 83 passed（3 project）、`make check-*` exit 0、`make test` exit 0（680 tests / 0 failures）、node 测试 191 通过 |
| T568 | 完成 | `scripts/bench-vocab.py` 造 5000 词（2.7s）+ 500 日志；`docs/reports/011-t568-bench.md`：`/api/student/today` p50 8.8 / **p95 10.1ms < 50ms**、队列 p95 16.5ms、词库 p95 12.5ms；`pg_stat_statements` 已开 |
| T595 | 完成（2026-10-31） | 今日复习改真会话：`ReviewSessionEndpoint` 新增 `source=today`（scope 写 `source`，scope key 加 `today|` 前缀隔离），走到尾再开一轮从 cursor 0 起；`ReviewQueueStore.countReviewedToday` 口径改为「`session_id is null` 或会话 `scope.source='today'`」；前端 `ReviewPage` 只剩张数与「开始复习」，按到期队列游标取齐（≤1000，超出分两轮）后建会话跳全屏；`FreeReviewPage` 认 `?source=today`（标题/进度/小结「今天已复习」）。证据：`VocabReviewSessionTest` 3/3（新增 `todaySessionCountsAsReviewed`）、`VocabReviewQueueTest` 1/1、`VocabTodayTimezoneTest` 5/5；E2E `vocab-flows` 路径 1 已扩展到「进会话、无 Tab 无壳」 |
| T596 | 完成（2026-10-31） | 三个 Tab 页去掉同名 `h1`：`ViewTabs` 加 `extra` 槽 + `.tl-tab-extra`；`VocabTabExtra`（context + portal）供页面把 ⓘ / 新建词单投进 Tab 行右端；`ReviewPage` / `ListsPage` / `LibraryPage` 删 `PageHeader`；词单详情保留自己的标题。证据：`tsc --noEmit` 0 错、`check-ui` 无新增违规、三页 `h1` 数目为 0（1440/834/1024 实测） |
| T598 | **已撤销**（2026-10-31 上午） | 曾按用户要求把「删除当前单词」加进会话（`DELETE` advance + 卡头垃圾桶 + `Delete` 键 + 二次确认），同日用户判断**删除不适合放在卡片上**，整体回滚：后端 `DELETE` 分支、前端 `onDelete` / 键位 / 快捷键表行、`deleteCurrentWordInSession` 用例全部移除。删除入口只在词库页行内（AC-59.11） |
| T597 | 完成（2026-10-31） | 今日复习页删掉齿轮与设置抽屉（原 `ReviewPage.tsx` 的 `VocabPrefsCard compact`），只保留 ⓘ；背单词设置唯一入口是 `/profile` 的「背单词」块，`vocab.prefs.open` 键一并清掉。证据：`/vocab/review` 页内不再有设置按钮（截图见报告）、`make check-i18n` exit 0 |

### 偏离与已知限制

1. **AC-91.3 的后半句已收口（2026-10-31 / T595）**：今日复习页只留张数与「开始复习」，
   点开始建 `source=today` 的会话进全屏（`/vocab/review/session/:id?source=today`）。
   交互对照：`docs/mock/today-focus-2026-10-30/index.html`；证据：`docs/reports/2026-10-31-today-focus-session.md`。
2. **「删除当前单词」不在复习卡上（T598 已撤销）**：T595 之后它随卡片一起从今日复习页消失；同日曾加回会话，
   用户判断「删除不适合放在 card 上」后回滚。删除入口只有词库页行内（AC-59.11），会话里只有「暂停这个词 / S」。
3. **AC-91.6 的来由（2026-10-30 新增）**：T563 的页内 Tab 与 004 的「每页 h1 + 标题旁 ⓘ」撞成两行同名，
   线上三页都有；011 自己的 mock 也重复，评审没拦住。四行改法与代价记在 AC-91.6 下。
4. **AC-90.1 的「连续天数」未实现**：聚合契约（`Ports.ReviewSlice`，S0 冻结）没有 streak 字段，学习记录卡是第二期。
5. **AC-90.7 的口径**：端点 4 次 O(1) 端口调用（三个新端口 + 既有 `ClassMembership.classIdsOf` 判 `inClass`），
   第 4 次是为了区分「在班没作业」与「没班」，否则 AC-90.3 与 AC-90.5 无法同时满足。
6. **E2E 的「注册」路径用种子学生代替**：注册要收激活邮件，`docs/process/e2e-playwright.md` §2.3 明确不能自动化。
7. **同工作区并行**：012/013 的 session 与本 session 共用一个 worktree，分支上会看到他们的提交；本记录只认 011 的文件与数字。
