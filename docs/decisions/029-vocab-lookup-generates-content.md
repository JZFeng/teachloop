# 决策 029 — 查词即生成：不在库的词也直接接 AI，不建词条

日期：2026-10-26　状态：**已实现**（T515）
相关：AC-60.2（改写「不在库」分支）/ AC-60.13（新增）/ AC-60.6 / AC-60.12 / FR-155 / AC-69.5
代码锚点：`teachloop-vocab/src/main/resources/db/migration/V069__vocab_lookup_jobs.sql`、
`teachloop-vocab/src/main/java/com/teachloop/vocab/generation/GenerationJobStore.java:74-155`、
`…/generation/VocabularyGenerationWorker.java:73-77,113-146`、`…/generation/VocabularyGenerationService.java:83-103`、
`…/query/VocabularyWordDetailEndpoint.java:107-168`、`web/src/vocab/WordDetailPage.tsx:118-207`、
`teachloop-app/src/main/resources/application.yml:101-105`

## 问题

2026-10-26 用户报 Bug：

> BUG： http://localhost:5174/vocab/words/by-form/Typography 查词，为啥不直接 接AI查询，然后跳转到card back呢？

实测确认不是前端渲染 bug，是**数据链路只认「词条」**：

```
GET /api/vocab/words/by-form/Typography   （student@teachloop.local，QA 8011）
→ {"inLibrary":false,"id":null,"content":null,"contentStatus":null,
   "family":{...}}                        ← 只有本地词族，没有任何释义/例句
```

原因在生成入口：`ReviewPreparationEndpoint` 的 `scope=WORD` 必须先拿到 `vocab_words.id`（`singleWord()` 按 `id + user_id` 查），
而 `GenerationJobStore.enqueueWords` 的幂等键是 `unique(subject_type, subject_id)`，`subject_id` 就是词条 id。
不在库 = 没有词条 = 无法入队，所以查词页只能显示「这个词还不在你的词库。加入后今天就能复习。」+ 一个「＋」图标，
用户必须先点「＋ 加入词库」（`POST /api/vocab/words` → 建词条 → `content_status=PENDING`）才会触发 AI。

这跟「查词」这个动作的语义不符：查词是**想知道这个词什么意思**，不是**要把它加进我的学习计划**。
先入库才给内容，等于把「查询」和「订阅」捆在一起。

## 决策

### D1 · 生成任务的 subject 增加 `FORM`：按词形生成，不绑定词条

- `vocab_generation_jobs.subject_type` 允许 `'FORM'`；新增 `subject_form` / `subject_language` 两列（V069）。
- `subject_id` 由 `(language, normalized_form)` 稳定派生：`UUID.nameUUIDFromBytes("vocab-content:<lang>:<form>")`。
  同一词形并发查词只会有一条任务（`uk_vocab_generation_jobs` 兜底），worker 与端点用同一个算法（`GenerationJobStore.formSubjectId`）。
- worker 的 `processLookup` 生成成功后**只** `contentStore.put(...)` + 任务置 `SUCCEEDED`；不写 `vocab_words`。
- `sweepStale` 只对 `WORD` 任务回写 `vocab_words.content_status`——FORM 任务的 `subject_id` 是派生 UUID，拿去 update 词条是无效写入。

### D2 · 页面自动触发 + 轮询，失败只在用户点「重试」时重排

- `POST /api/vocab/words/lookup` 返回 `{state, detail}`：`READY`（内容已在）/ `PENDING`（已入队或正在生成）/
  `FAILED`（任务重试用尽）/ `DISABLED`（开关关闭）。
- 前端 `WordDetailPage` 打开时若「不在库 + 无内容」就调一次；`PENDING` 每 2 秒再调，`READY` 直接 `setDetail` 渲染卡背（不刷新、不跳转），
  120 秒还没好按失败处理（worker 的退避会到 5 分钟，页面等下去没有意义）。
- **轮询不自动重试**：`retry=true` 只在用户点「重试」时传，否则失败任务不会被页面轮询反复重置——一条生成不了的词不该无限烧 AI。

### D3 · 不改成「查词 = 自动加入词库」

这是最容易实现的做法（前端在 `inLibrary=false && !content` 时直接 `POST /api/vocab/words`），但被否掉：

- 查过的词全部变成词条 → 复习队列、`dueToday` / `newWords` 统计、词库列表全被「随手查的词」和「打错的词」污染；
- 用户已经明确「查词」和「加入词库」是两个动作（AC-60.2 的「＋」、AC-69.5 的「加入词单时先入库」都建立在「入库是有意的」上）；
- 内容本来就是全局的（AC-60.6，同一 `(language, normalized_form)` 只生成一次），查词只需要「请求这份内容」，不需要一条用户词条。

### D4 · 开关默认开，但必须可关

`teachloop.vocab.lookup.generate-on-miss`（`VOCAB_LOOKUP_GENERATE_ON_MISS`，默认 `true`）。查词是全站最容易被随手触发的入口
（打错一个字母就是一次 AI 调用，成本基线 $0.00068/词，AC-60.12），运营上要能一键止血：关掉后页面回到「＋ 加入词库」提示。
不受日预算限制（学生在页面上等，与 `prepareOnDemand` 同口径）。

## 影响与代价

- **成本**：每查一个「全站都没生成过」的词 = 一次 AI 调用。实测 typography 35.3 秒（QA 网关当天较慢，p50 基线 6.1 秒）、
  Zephyr 约 45 秒内完成；两次调用合计约 $0.0014。已有内容或已入队过的词形 0 成本。
- **延迟**：卡背先给「AI 正在生成…」，真实内容平均 6–40 秒到；比「先点＋、再等」少一次点击和一次页面刷新。
- **数据**：新增 2 列 + 1 条部分索引；`vocab_words` 不增长（查词不建词条）。
- **放弃**：不做「查词历史」「查词限流」——如果后面发现被刷，D4 的开关与「查词不建词条」已经保证最坏情况只是花 AI 钱，不污染学习数据。

## 验证

- 后端 `VocabLookupTest` 4/4：FORM 任务生成后 `READY` 且 `vocab_words` 0 行；第二个学生命中全局内容 0 新任务；
  失败只报 `FAILED`、`retry=true` 才清零回队列；在库词不建 FORM 任务。
- 存量 `VocabWordDetailTest` / `VocabPrepareTest` / `VocabGenerationTest` / `VocabGenerationBudgetTest` / `VocabContentBackfillTest` 14/14。
- 前端 `tsc` 0、web 测试 125/125、`check-i18n` 1696 键对齐、`check-boundaries` 通过。
- 5174 浏览器实测（`./scripts/env.sh deploy qa` 后，student01）：`/vocab/words/by-form/Typography` 卡背直接是真实 AI 内容
  （noun 释义 + 6 条例句 + 英/美音标 + 构词 + 词族）；`/vocab/words/by-form/Zephyr` 首次先显示「AI 正在生成这个词的释义与例句，
  好了会自动显示。」，约 45 秒后**同一页面**自动渲染完整卡背（未刷新、未点「＋」）；`vocab_words` 中 typography / zephyr 行数为 0，
  词库统计不变。截图 `work/lookup-generating-2026-10-26.png`、`work/lookup-ready-2026-10-26.png`。
- **未验证**：英文界面截图；`generate-on-miss=false` 的浏览器截图（只验了代码路径与默认值）；120 秒超时分支的浏览器实测。
