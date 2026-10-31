# 任务拆分 — 系统词书（013-word-books）

- 上游：`spec.md`（6 US / 27 AC / 25 FR）、`plan.md`（2026-10-30）、`docs/mock/word-books-2026-10-30/`（5 屏）
- 任务号：**T569 起**（011 占用到 T568）
- 前置：T562（今日页）与 T563（背单词模块壳）已上线——推荐区挂在词单页，需要模块壳先存在
- 约定：每个任务自带验收；完成后回写本文档并写执行记录；提交信息带 T 号
- 迁移序号：`V079`（vocab 书表）/ `V080`（来源约束），**合并前按 main 现状重排**

---

## 阶段 A — 地基（表、迁移、守卫登记）

| ID | 任务 | 依赖 | 验收 |
|---|---|---|---|
| T569 | **迁移**：`V079__vocab_books.sql`（`vocab_books` / `vocab_book_entries` / `vocab_book_joins` + `vocab_lists.book_id` + 唯一索引）；`V080__vocab_word_sources_book.sql`（`book_id` 列 + 放宽 `kind` check）；**同时**把三张新表登记到 `scripts/check-boundaries.py` 的 `TABLE_OWNER` | T563 | 空库与含存量数据的库各跑一次迁移；`make check-boundaries` 退出码 0（未登记表会直接红）；回滚脚本草稿写进 `docs/reports/`（含 `BOOK` 来源行的处理） |
| T570 | **管理端 CRUD**：`/api/admin/vocab/books*`（列表/新建/修改/发布/下架）+ `ADMIN` 判权 + 审计日志 | T569 | AC-100.1 非 ADMIN 403；AC-100.2 下架后学生端 404；AC-100.5 无物理删除接口；审计表新增 `book:publish` / `book:archive` 两类事件 |

## 阶段 B — 内容与推荐

| ID | 任务 | 依赖 | 验收 |
|---|---|---|---|
| T571 | **导入复用**：管理端「导入词条」复用 `/vocab/add` 的解析与预览（同一 `WordImportService`、同一 preview 接口），提交目标改为书 | T570 | AC-100.3 返回 `created/duplicated/invalid`；AC-100.6 与加词页对同一段文本给出**相同**的清洗与跳过结果（同一夹具对比）；非法行不写库 |
| T572 | **覆盖率服务**：按书批量查内容索引，输出 `ready/total`；管理端与学生端共用 | T569 | AC-100.4 抽 10 词人工复核一致；AC-103.3 加入前后覆盖不变；无书条目时返回 0/0 而不是异常 |
| T573 | **推荐端口**：`Ports.BookRecommendation` + vocab 实现（已发布 → position → joins 计数）；`GET /api/vocab/books/recommended` | T569 | AC-104.3 前 2 本顺序正确；不足 2 本时返回全部；端口签名通过 `check-boundaries`；控制器内无 SQL/排序 |

## 阶段 C — 学生端

| ID | 任务 | 依赖 | 验收 |
|---|---|---|---|
| T574 | **书列表/详情/预览接口**：`GET /api/vocab/books`、`/{id}`、`/{id}/entries?page=` | T572 | AC-101.1 只返回已发布；AC-101.2 详情含覆盖率与我的加入进度；AC-101.3 50/页与末页总数正确 |
| T575 | **加入事务**：入词库（去重、分批 ≤500）→ 追加 `BOOK` 来源 → 建/复用副本词单（同名加后缀）→ upsert joins → 返回四元组；**不**触发内容生成 | T574 | AC-102.1 四元组正确；AC-102.2 二次加入 `created=0` 且不新建词单；AC-102.3 进度字段前后一致；AC-102.5 同名后缀正确；AC-103.1 生成任务不新增 |
| T576 | **会话按词单**：`POST /api/vocab/review-sessions` 增加可选 `listId`，服务端展开；沿用 1000 词上限 | T575 | AC-102.4 会话总数 = 书词数、不含书外词；2000 词书按 1000 分批且批次不重叠；无 `listId` 时旧行为不变 |

## 阶段 D — 前端

| ID | 任务 | 依赖 | 验收 |
|---|---|---|---|
| T577 | **五个页面**：词单页推荐区（**位于词单列表下方**）、书详情（底部只放主操作）、副本复习、管理端、导入词条（确认条常驻） | T573–T576 | AC-101.4 / 104.1 / 104.2 / 104.4 / 104.5 逐条走查；834×1194 与 1024×1366 下 `scrollWidth <= innerWidth+2`、首屏外按钮 0（与 mock 同口径复跑）；词库页**无**词书入口 |
| T578 | **付费字段与裁剪**：v1 建书 `PAID` → 400、加入 `PAID` → 409；学生端响应剔除 `pay_mode/price_cents` | T570, T575 | AC-105.1–105.4；学生端接口响应体断言无价格字段；管理端可见 |

## 阶段 E — 验收

| ID | 任务 | 依赖 | 验收 |
|---|---|---|---|
| T579 | **测试与守卫**：单测（幂等/后缀/覆盖率/推荐排序）、集成（失败回滚不留半份副本）、E2E（词单页→加入→副本→开始复习）、i18n `vocab.books.*` | T577, T578 | `make check-deps check-boundaries check-i18n check-ui` 与 `make test` 全绿；E2E 报告贴 `docs/reports/` |
| T580 | **回滚演练**：执行一次「回退代码 → 处理 `BOOK` 来源行 → 删表删列」的完整演练 | T579 | 在 qa 库演练并记录耗时与命令；`BOOK` 行处理脚本可重复执行（幂等） |

---

## 覆盖自检

```bash
grep -cE '^\| T5(6[9]|7[0-9]|80)' specs/013-word-books/tasks.md   # 期望 12
grep -ho '^| T[0-9]*' specs/*/tasks.md | sort | uniq -d            # 期望空（T341 为存量）
```

---

## 执行记录（2026-10-30，分支 `feat/013-word-books`）

详细数字与命令见 [`docs/reports/013-word-books-2026-10-30.md`](../../docs/reports/013-word-books-2026-10-30.md)；回滚脚本见
[`docs/reports/rollback-013/013-rollback.sql`](../../docs/reports/rollback-013/013-rollback.sql)。本批**没有写迁移**，也没有改 `Ports.java` / `scripts/*`。

- T569 完成于 S0（`main@2e9ce2b`）：三张表、两列、check 放宽、`TABLE_OWNER` 登记。
- T570 管理端 CRUD + 发布/下架 + 审计：完成。`/api/admin/vocab/books*`，非 ADMIN 403（前缀收口），无物理删除接口，
  审计 `book:create|update|publish|archive|entries.import|entry.delete`。用例 `VocabBooksTest#adminCrudPublishArchiveAndAudit`。
- T571 导入复用：完成。解析走同一 `DictionaryTextParser`，返回体与 `/api/vocab/import/preview` 同形；
  实测两边 `cleanups` / `skipped` 逐条相等（`created=4/duplicated=4/failed=2`，二次 `created=0`）。
- T572 覆盖率：完成。`ContentStore.existingForms` 批量探测，管理端与学生端共用；空书 0/0；加入前后不变。
- T573 推荐端口：完成。`BookRecommendationImpl`，排序只在 `BookRepository.listForRecommendation` 的 SQL 里。
- T574 学生端列表/详情/预览：完成。只返回已发布；详情含覆盖率与「已加入 N / M」；预览 50/页、末页总数正确。
- T575 加入事务：完成。幂等实测首次 `created=3 / duplicated=2`、二次 `created=0 / duplicated=5`；
  进度逐字段不变；`vocab_generation_jobs` 不新增；副本唯一且同名加后缀 `书名 (2)`。
- T576 会话按 `listId`：完成。`总张数 = 书词数`、不含书外词；2000 词分两批各 1000 且交集为空；无 `listId` 旧行为不变。
- T577 前端五屏：完成。路由全挂（2026-10-30 补齐 `/admin/vocab/books` 与 `/admin/vocab/books/:bookId/import`
  两条 —— 此前只加了 `nav.ts` 目录项没注册路由，点侧栏与深链都命中兜底 `*` 被弹回 `/admin/ai`，blocker）；
  新增 `web/tests/nav-routes.test.mjs` 锁「目录项必有路由」（修前 3/3 红、修后 3/3 绿）。
  浏览器实测（管理员 dev 账号，2026-10-30）：点侧栏到达且标题为「词书管理」、深链冷启动可开、导入页可开、
  学生深链被 `nav.vocab.books` 守卫拦回落地页；iPad Air 11 竖屏 `scrollWidth=834`、iPad Pro 12.9 竖屏 `1024`
  均无横向溢出，「新建词书」首屏可见。
  文案仍在 `web/src/vocab/books/copy.json`（键名即 `vocab.books.*`），尚未搬进 `locales/*.json`。
- T578 付费字段：完成。建书 `PAID` → 400、加入 `PAID` → 409；学生端响应无 `payMode/priceCents/currency`（用例断言字段不存在）。
- T579 测试：本批 9 个新用例 + 5 个受影响类的回归（共 17 个用例）全绿；`check-deps` / `check-boundaries` / `check-ui` / `pnpm typecheck` / `pnpm build` 退出码 0；
  `check-i18n` 退出码 1，唯一命中是 S3 的 `OfferingWindows.java:104`（不是本批）。**`make test` 未全量跑**（共享工作目录里 S1/S3 同时在改，见报告 §8）。
- T580 回滚演练：完成。脚本 10 条语句，首跑 7ms、重跑 3ms（幂等）；`BOOK` 行 5→0 转 `IMPORT`，词/进度/词单引用一行不少。
  演练在一次性 Postgres 16 容器上做，**没有碰 qa 库**（本 session 禁止部署）。
