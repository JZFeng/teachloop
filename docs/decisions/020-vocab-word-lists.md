# 020 学生词单：只引用 word_id 的容器（不做标签分组，不做卡组）

- 日期：2026-10-25
- 触发：用户提「我也想自己攒一个 word list，查到一个生词就加进去，没事的时候开始复习一下」
- 上游：`specs/004-vocab/spec.md` US-69 / AC-69.1–69.11 / FR-142–145
- 影响面：新增 2 张表（V064）、4 组接口、2 个页面；**不动** `vocab_progress`、SRS、`vocab_review_sessions`

## 1. 结论

1. 学生分组用**独立词单表**（`vocab_lists` + `vocab_list_items`）。词单只引用 `word_id`，词条与学习进度仍各有且只有一份。
2. **不**用 `vocab_words.tags` 作为分组载体（列保留、暂不启用）。
3. **不**实现 `vocab_decks`（保存的筛选条件）。`US-47` 的卡组视图标记为暂缓，`AC-47.x` 原文保留。
4. 查询与复习都复用既有能力：`GET /api/vocab/words?list=<id>`、`POST /api/vocab/review-sessions`，不新开会话机制。

## 2. 用户要的动作（原话）

> 我现在已经有了那个 favorite list，这就是自己的 word list。很经常的一个情况，就是我查一个生词，然后把它加到我这个 list 当中。然后我没事的时候，我就想开始复习一下。

明确的三步：**查词 → 加入某份 list → 从这份 list 开始复习**。收藏只能支持一份 list，且不能改名、不能描述、不能排序。

## 3. 决策依据（逐条可复核）

| # | 事实 | 证据 |
| :--- | :--- | :--- |
| 1 | 词条在学生名下只有一行 | `vocab_words` 唯一键 `(user_id, language, normalized_form)`（`teachloop-vocab/src/main/resources/db/migration/V041__vocab_words.sql:21`） |
| 2 | 学习进度只有一份，且主键就是词 | `vocab_progress.word_id uuid primary key`；建表注释「每个 (词) 只有一行进度：状态机 NEW → LEARNING ⇄ REVIEW → GRADUATED」（同文件 `:42-44`）。**任何分组模型都不会让进度分裂**，所以「进度只有一份」不能用来区分方案 |
| 3 | 仓库已有「一个词属于多处」的表模式 | `vocab_word_sources`（一个词可同时有 ASSIGNMENT / IMPORT / SELF 多条，带唯一约束防重复）（同文件 `:28-37`） |
| 4 | 卡组明确不是容器 | `vocab_decks` 建表注释「卡组 = 保存的筛选条件（不是容器）」(`V042__vocab_review.sql:19`)；`spec.md` FR-094、AC-47.5 都是这个口径 |
| 5 | 词内容才是全局共享的，词条行是每人一份 | 内容走 `content_id` → 全局只读包 / 本地 delta（`V062__vocab_content_id.sql:2-4`、`DeltaContentStore.put(language, normalizedForm, …)`）；`vocab_words.user_id` 每人一行 |
| 6 | 标签的 GIN 索引只解决「包含某标签」的过滤 | `create index idx_vocab_words_tags on vocab_words using gin (tags)`（`V047__vocab_words_meta.sql:9`）。按词单分组统计（每份多少词、其中各状态多少）要展开数组，索引帮不上 |
| 7 | 复习会话接受显式词 id 列表，且自带归属校验 | `POST /api/vocab/review-sessions`，`MAX_WORDS = 1000`，逐个 `words.belongsTo(me.id(), wordId)` 过滤（`teachloop-vocab/src/main/java/com/teachloop/vocab/review/ReviewSessionEndpoint.java:46,85-108`；归属校验 `VocabWordRepository.java:253`） |

## 4. 为什么不是另外两种

### 4.1 为什么不用 `tags`

- **行数不是问题**：标签挂在 `vocab_words` 上，而这行本来就是每个学生一份（依据 1、5）。今天 1000 个学生都有 `abandon` 就已经有 1000 行，加标签是给已有行加一列，行数增长为 0。
- **真正的代价有三条**：① 分组统计要展开 jsonb 数组（依据 6）；② 改一份 list 的名字/描述要批量更新这份 list 里**所有词行**的 tags；③ 「词单只有自己用」在库层面没有约束，只能靠应用层自觉。
- 结论：不是不能做，是**做出来的东西没有「一份词单」这个对象**，用户要的第 5 件事（词单页面）表达不出来。

### 4.2 为什么不用 `vocab_decks`（保存的筛选条件）

AC-47.5 明文规定「界面上不存在把卡片移入/移出卡组的操作」。用户要的核心动作恰恰是「手动把**这一个词**加进来」——这不属于筛选条件。`vocab_decks` 表与查询参数 `deckId` 已存在（`VocabularyQueryRepository.java:73-74,217-223`），但没有 CRUD、没有页面、没有配置项 `teachloop.vocab.decks.max-per-student`；本决策后它保持「暂缓」，不删表。

## 5. 数据模型（V064）

```sql
-- 词单 = 只引用 word_id 的容器；词与进度各一份，不随词单复制。
create table vocab_lists (
  id uuid primary key,
  user_id uuid not null,
  name varchar(80) not null,
  description varchar(200),
  position int not null default 0,
  deleted boolean not null default false,
  created_at timestamptz not null,
  updated_at timestamptz not null
);
-- 同一学生未删除词单内重名拒绝（大小写不敏感）
create unique index uk_vocab_lists_user_name on vocab_lists (user_id, lower(name)) where not deleted;
create index idx_vocab_lists_user on vocab_lists (user_id, deleted, position);

create table vocab_list_items (
  id bigserial primary key,
  list_id uuid not null references vocab_lists (id) on delete cascade,
  word_id uuid not null,
  position int not null default 0,
  added_at timestamptz not null,
  constraint uk_vocab_list_items unique (list_id, word_id)
);
create index idx_vocab_list_items_list on vocab_list_items (list_id, position);
create index idx_vocab_list_items_word on vocab_list_items (word_id);
```

**为什么不给 `word_id` 建外键**：与 `vocab_review_logs` 同口径——词软删（AC-55）后引用仍要保留，恢复后同一份词单里还在；归属校验在应用层用 `words.belongsTo(userId, wordId)` 做。建外键会让「软删词」与「词单引用」耦合。

**为什么不加 `session_id`/进度列**：进度的唯一属主是 `vocab_progress`（依据 2）；词单里出现第二份排期就是 bug。

## 6. API 草案（全部 `/api/vocab`，全部限本人）

| 方法 | 路径 | 请求 | 响应 | 失败 |
| :--- | :--- | :--- | :--- | :--- |
| GET | `/lists` | — | `[{id, name, description, wordCount, position, updatedAt}]` | — |
| POST | `/lists` | `{name, description?}` | 同上单条 | 重名 409；超上限 409（`lists.max-per-student`，默认 100） |
| PUT | `/lists/{id}` | `{name?, description?}` | 同上单条 | 重名 409；非本人 404 |
| DELETE | `/lists/{id}` | — | `{affected}` | 非本人 404；软删，引用行保留 |
| GET | `/lists/{id}/words?page&size` | — | 分页词条（`id, form, band, state, dueAt, favorite, position`） | 非本人 404 |
| POST | `/lists/{id}/words` | `{wordIds: []}` 或 `{forms: []}` | `{added, duplicated}` | 词不属于本人 404；forms 形式先入库再引用 |
| DELETE | `/lists/{id}/words/{wordId}` | — | `{affected}` | 只删引用，不动词条与进度 |
| GET | `/words?list=<id>` | 可与来源/标签/状态/收藏取交集 | 词库分页 | 非本人 list 404 |
| POST | `/review-sessions` | `{wordIds: [...]}`（既有接口） | 会话 | 词单复习 = 详情页取词单内未暂停词后调用，**不新增端点** |

## 7. 失败路径与边界

- **重名**：库层唯一索引 + 应用层预检，返回 409 与可读文案（不静默改名）。
- **超上限**：创建前 `count(*)` 预检；并发下允许极小概率超一位，不致命（不引入分布式锁）。
- **加词幂等**：同一 `(list_id, word_id)` 已存在时返回 `duplicated + 1`，不报错（与领取词集 AC-51.x 的口径一致）。
- **暂停的词**：允许加入词单；复习范围按 AC-61.1 排除暂停词，并在词单详情页显示「N 个已暂停不在复习范围」。
- **词被软删**：引用保留；词单详情页该行标「已删除」，不参与复习；恢复后自动恢复参与。
- **词单被删后并发加词**：软删后 `list` 查不到 → 404。
- **他人词单**：一律 404，不泄露存在性（沿用 US-54 口径）。

## 8. 容量与性能

- 上限 100 份词单 × 每份最多 1000 词 = 10 万行引用/学生；按 `(list_id, position)` 索引分页，单页查询与词单大小无关。
- 列表页的 `wordCount` 用一次 `left join … group by` 聚合，不做 N+1。
- 复习范围取词单内未暂停词，受会话上限 1000 约束（`ReviewSessionEndpoint.MAX_WORDS`）；超过时前端分批并提示（沿用自由复习的既有做法）。

## 9. 测试要点（交 T449–T452）

1. 一个词同时进 3 份词单，复习后 `vocab_progress` 仍只有 1 行、`due_at` 只更新一次。
2. 删除词单后 `vocab_words` / `vocab_progress` 行数与内容不变。
3. 重名 409、超上限 409、他人词单 404、重复加入幂等。
4. 软删词后词单引用保留，恢复后可复习。
5. 导入时指定词单名：不存在则创建；已达上限则**导入继续**、该批不入词单并提示（承接 AC-48.10）。

## 10. 遗留与待办

- Mock 未画：建议先出「词单列表 → 词单详情 → 开始复习」三张原型再进实现（spec 与 Mock 循环的规矩）。
- `vocab_decks` 的处置：暂缓，表与 `deckId` 参数保留但无入口；若将来做「保存的筛选条件」，与词单是两件事，可并存。
- `vocab_words.tags` 保留但**不启用**（无写入路径，筛选永远为空）；若要做「跨词单的横切标签」再单独决策。
