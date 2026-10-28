# 实施计划 — 背单词与间隔重复（004-vocab）

- 状态：**已认可（2026-10-23）**；6 条清单自检见 §14，收尾项见 §15
- 上游：`spec.md`（US-46–61 / AC-100 / FR-091–115）、`docs/decisions/011-vocab-requirements.md`、`docs/decisions/012-storage-port-and-oss.md`、`docs/research/2026-10-23-vocab-wordlists-feasibility.md`
- 日期：2026-10-23
- 迁移起点：`V040`（当前最新是 `V039__identity_ai_providers.sql`）

---

## 1. 架构总览

```
teachloop-vocab（第 11 个模块）
  ├─ 依赖：shared / identity / classroom        ← 宪法第十四条允许的全部
  ├─ 禁止：paper / sitting / grading / report    ← 合并前用 diff 取证
  ├─ 表前缀：vocab_*
  └─ 迁移目录：teachloop-vocab/src/main/resources/db/migration
```

| 用到的端口（都在 `shared`） | 用途 |
| :--- | :--- |
| `BlobStore`（T386 新增） | TTS 音频、可选预生成 HTML |
| `ClassMembership` | 发词时校验「是不是自己带的班」、取班级成员 |
| `StudentDirectory` | 学生显示名（学习小结/教师侧提示） |
| `NotificationPort` | 发词后给收件学生写站内通知（复用现有通知中心） |
| `AiSettingsLookup` | 异步内容生成用的模型配置（全局，`current()`） |

**不新增任何跨模块外键**：`user_id / class_id / set_id` 一律只存 UUID，不做物理外键、不跨模块 JOIN。
`sitting` 的 `StorageBackend` 不直接引用，等 T386 把端口提到 `shared` 后注入 `BlobStore`。

## 2. 数据模型

> 全部 `vocab_` 前缀；建表必须同时登记 `scripts/check-boundaries.py` 的 `TABLE_OWNER`。

### 2.1 学生侧

| 表 | 关键字段 | 约束 / 索引 |
| :--- | :--- | :--- |
| `vocab_words` | `id uuid`、`user_id`、`language`、`form`、`normalized_form`、`content jsonb`、`content_status`(PENDING/READY/FAILED)、`family_id`、`band`、`removed bool`、`removed_at`、`favorite bool`、`tags jsonb`（V047，T396–T398）、`created_at/updated_at` | `unique(user_id, language, normalized_form)`；`index(user_id, removed)`、`index(user_id, favorite)`、GIN(tags) |
| `vocab_word_sources` | `word_id`、`kind`(ASSIGNMENT/IMPORT/SELF)、`set_id/assignment_id/class_id/teacher_id` 可空、`label varchar(80)`、`batch_id uuid`（T393，V046）、`created_at` | `index(word_id)`、`index(batch_id)`、`index(label)`；同一批次内同一来源只记一次 |
| `vocab_progress` | `word_id`(pk)、`user_id`、`state`(NEW/LEARNING/REVIEW/GRADUATED)、`due_at`、`interval_days numeric(8,2)`、`ease numeric(4,2)`、`lapses`、`successes int`（V048，毕业的连续认识次数）、`last_rating`、`reviewed_count`、`updated_at` | pk=`word_id`（一个词只有一份进度）；`index(user_id, due_at)`、`index(user_id, state)` |
| `vocab_review_logs` | `id bigserial`、`user_id`、`word_id`、`rating`、`reviewed_at`、`elapsed_ms`、`session_id` 可空 | `index(user_id, reviewed_at)`、`index(word_id)` |
| `vocab_decks` | `id uuid`、`user_id`、`name`、`criteria jsonb`、`position`、`deleted` | `unique(user_id, name) where not deleted` |
| `vocab_review_sessions` | `id uuid`、`user_id`、`scope jsonb`、`total`、`cursor`、`known_count`、`paused_count`（V049）、`status`(ACTIVE/ENDED)、`started_at/updated_at` | `index(user_id, status)` |
| `vocab_user_prefs`（V047） | `user_id`(pk)、`table_columns jsonb`、`updated_at` | 列显示/顺序按学生保存（AC-59.5） |
| `vocab_review_requests`（V048） | `user_id`、`idempotency_key`、`word_id`、`created_at`；主键 `(user_id, idempotency_key)` | 评分幂等（AC-53.6 / T405），重放不双写 |
| `vocab_lists`（V064，US-69） | `id uuid`、`user_id`、`name varchar(80)`、`description varchar(200)` 可空、`position int`、`deleted bool`、`created_at/updated_at` | `unique(user_id, lower(name)) where not deleted`（AC-69.1 重名拒绝）、`index(user_id, deleted, position)` |
| `vocab_list_items`（V064，US-69） | `id bigserial`、`list_id uuid`（FK→`vocab_lists` on delete cascade）、`word_id uuid`、`position int`、`added_at` | `unique(list_id, word_id)`（AC-69.2 同单不重复）、`index(list_id, position)`、`index(word_id)`；**不对 `word_id` 建外键**——词软删后引用保留，恢复后仍在词单（与 `vocab_review_logs` 同口径），归属由 `words.belongsTo` 在应用层校验 |

### 2.2 教师侧

| 表 | 关键字段 | 约束 / 索引 |
| :--- | :--- | :--- |
| `vocab_sets` | `id uuid`、`teacher_id`、`language`、`name`、`description`、`deleted`、时间戳 | `index(teacher_id, deleted)` |
| `vocab_set_words` | `id uuid`、`set_id`、`position`、`form`、`normalized_form`、`content jsonb`、`family_id`、`band` | `unique(set_id, normalized_form)`、`index(set_id, position)` |
| `vocab_assignments` | `id uuid`、`set_id`、`teacher_id`、`due_at` 可空、`note`、`created_at` | `index(teacher_id, created_at)` |
| `vocab_assignment_words` | `assignment_id`、`position`、`form`、`normalized_form`、`content jsonb`、`family_id`、`band` | `unique(assignment_id, normalized_form)`（**发送时快照**，改词集不影响） |
| `vocab_assignment_classes` | `assignment_id`、`class_id` | 联合主键 |
| `vocab_assignment_recipients` | `assignment_id`、`student_id`、`claimed_at` 可空 | 联合主键；发送时按班级成员**快照**，退班不影响已发的 |

### 2.3 支撑表

| 表 | 关键字段 | 约束 / 索引 |
| :--- | :--- | :--- |
| `vocab_generation_jobs` | `id bigserial`、`subject_type`(WORD/SET_WORD/ASSIGNMENT_WORD)、`subject_id`、`state`(QUEUED/RUNNING/SUCCEEDED/FAILED)、`attempts`、`next_run_at`、`last_error`、`locked_by/locked_at` | `unique(subject_type, subject_id)`、`index(state, next_run_at)` |
| `vocab_lexicon_families` | `id bigint`、`head`、`band smallint`、`family_freq bigint` | `index(head)`、`index(band)` |
| `vocab_lexicon_forms` | `normalized_form`(pk)、`folded_form`、`family_id`、`form_freq`、`is_head bool` | `index(folded_form)`、`index(family_id)`（这一列是 `band` 的冗余来源，便于按族取） |
| `vocab_lexicon_meta`（T388，V045） | `id smallint`(pk，恒为 1)、`content_sha256`、`family_count`、`form_count`、`applied_at` | 单行；`check(id = 1)`；存「上次写进词表两表的是哪份产物」，指纹一致就跳过写库 |
| `vocab_feedback` | `id`、`user_id`、`word_id`、`payload jsonb`、`note`、`status`、`created_at` | `index(status, created_at)` |
| `vocab_audio_assets`（可选） | `key`(pk)、`form`、`voice`、`rate`、`size_bytes`、`created_at` | 仅做统计与清理；权威内容在 BlobStore |

### 2.4 状态迁移

```
content_status: PENDING → READY            （生成成功）
                PENDING → FAILED           （3 次失败 / 24h 超时）
                FAILED  → PENDING          （手动重试）
removed:        false   → true             （移出复习）
                true    → false            （重新加入 → progress 重置为 NEW，due_at = now）
progress.state: NEW → LEARNING → REVIEW ⇄ LEARNING → GRADUATED
jobs.state:     QUEUED → RUNNING → SUCCEEDED / FAILED(可回 QUEUED)
```

## 3. 词表子系统（本地词族索引）

### 3.1 构建管线

```
WORDLIST_DIR（本地，不入库；默认 fixtures-local/wordlists/basewords_130，已被 .gitignore 挡住）
   └─ scripts/vocab/build-wordlist-index.py
        ├─ 解析 35 个 txt（兼容 CRLF / Tab / 首尾 token）
        ├─ 产出 lexicon-families.json / lexicon-forms.json（或 TSV）
        └─ 产出 manifest.json（来源 URL、抓取日期、逐文件 sha256、族数/形数）
```

- 产物 < 5 MB，gzip 后 < 1 MB；**原始 txt 不进仓库**（许可未明示，用户 2026-10-23 已确认）。
- 两种装载方式，plan 建议 **B**：
  - A. 打包进 `teachloop-vocab` 的 resources，启动时读进内存；
  - B. **应用启动时对比 manifest 的 sha256，发现词表变了就 upsert 进 `vocab_lexicon_*` 两张表**（幂等）；
    查询走内存 map（启动时加载），DB 只做持久化与运维侧可查。
- 索引结构：`Map<String, FormRow>`（80,579 条）+ `Map<String, Long>`（去变音符）+ `Map<Long, Family>`。
- 实测（Python 原型）：解析 60 ms、建索引 17 ms、单次查找 1–4 µs、内存 10–15 MB。生产用 Java 只会更快。

**T388 实现后的实际口径（2026-10-23）**

- 产物：`lexicon-families.tsv`（28,276 行，947 KB）+ `lexicon-forms.tsv`（80,579 行，3.4 MB）+ `manifest.json`（14 KB），
  合计 **4.3 MB**；gzip 约 **1.5 MB**（略超上面 <1 MB 的估算，可接受）。
- **索引范围 = `basewrd1–30`**：28,276 族 / 80,585 行 / 去重 80,579 形（重复 6 个，按频次高者保留；源文件 2 行杂质跳过并写进 manifest）。
  `basewrd31–35`（专有名词 / 前缀 / 学科词 / 缩写 / 法语借词）**不进索引**（用户 2026-10-23 决定）：
  否则 `band=31` 会被显示成「第 31 千词族」，且拼写校验词形集会偏离 spec §20 的 80,579；
  未纳入的文件逐条写进 manifest 的 `scope.ignored_files`，不静默丢弃。因此 `éclat`（只在 `basewrd35`）在真实词表里查不到，
  去变音符行为用测试夹具验证（T388 执行记录）。
- **族 id = `sha256(band|head)` 前 8 字节取正**（不用频段内序号）：词表更新插入新族时，未变化的族保持同一个 id，
  `vocab_words.family_id` 不会静默指错族。
- 装载指纹存 `vocab_lexicon_meta`（V045 单行表，`content_sha256` 不含构建时间）：指纹一致直接跳过写库。

### 3.2 解析规则（与 spec §14.2 一致，实现时逐条对应）

1. `rstrip("\r\n")`；空行跳过；
2. `line.startsWith("\t")` → 成员，否则新族；
3. 只取首 token（词）与尾 token（频次）；
4. `normalized = NFC(trim(lower(word)))`；`folded = NFD 去 Mn 后拼回`；
5. 重复词形：保留频次高者，记录两个频段；
6. 不依赖文件顺序；
7. 噪声族（族频次 < 某成员）不展示单形频次。

### 3.3 运行时查找

```
lookup(form):
  key = normalize(form)
  row = forms.get(key) ?: forms.get(fold(key))
  if row == null: return MISS → 入队 vocab_generation_jobs（生成结果带回 family）
  return familyOf(row.familyId) + 频段 + 族频次
```

- 命中：详情页显示「BNC 第 N 千词族」+ 最多 8 个同族词（可配 `word-family.max-members`），按成员频次降序、排除当前词。
- 未命中：不显示频段、不编造；生成完成后回填 `family_id/band`。
- 词表缺失或装载失败：词族区块整体降级隐藏，页面其余部分照常，记一次告警指标。

## 4. 异步内容生成

- 触发：**按需准备（2026-10-24 / T426 起默认）**——学生进入复习或打开详情时，只对即将用到的前 N 个词建 job（默认 20，窗口 20/100/200/400…）；导入/加词只入库，不再全量预生成。想恢复旧行为设 `generation.on-import=true`。
- 优先级：按需准备的 job `priority=100`，后台批量任务 `priority=0`，领取排序 `priority DESC, next_run_at`（V061）。
- 执行：`vocab_generation_jobs` + 一个 `@Scheduled` 轮询器（与 grading worker 同思路，不引入 MQ）：
  `SELECT ... WHERE state='QUEUED' AND next_run_at<=now() ORDER BY priority DESC, next_run_at LIMIT n FOR UPDATE SKIP LOCKED`。
- 生成内容（写入 `content jsonb`）：`senses[]`（词性 + 中英释义）、`examples[]`（英文 + 中文）、`analysis`（构词）、`family[]`（词族，词表命中时以本地为准）、`phonetic {uk, us}`。
- 幂等：同一词形已 `READY` 不再生成；重复导入不重复入队。
- 重试：失败指数退避（1min / 5min / 30min），3 次后 `FAILED`；24h 仍失败标「内容缺失」，从队列移除并提醒（AC-48.9）。
- 成本：`teachloop.vocab.generation.{enabled,daily-budget,concurrency,timeout-ms,model}`；超预算暂停入队并告警。
- 单条超时 60s，超时按失败计；**成功才写库**，不写半成品。
- 内容全局共享（decisions/017）：只读内容包 `.pack/.idx/manifest` + 内存索引为主；AI 新内容写
  本地 SQLite delta；`vocab_words.content_id` 引用全局内容，读路径由 `ContentStore` 解析；
  同一 `(language, normalized_form)` 只生成一次。P1 先落 SQLite delta 与 `content_id`，P2 再接预生成包。

## 4.1 全局词内容：只读内容包 + 内存索引 + SQLite delta（decisions/017）

目标（AC-48.14）：同一 `(language, normalized_form)` 只生成一次；不同学生共享同一份内容；
预生成词命中不调 AI。**内容不进业务数据库**，只读包为主、SQLite delta 承接 AI 新内容。

### 4.1.1 数据与产物

| 产物 | 形态 | 键/索引 | 归属 |
| :--- | :--- | :--- | :--- |
| `content-<lang>-<version>.pack` | JSON 记录顺序拼接（记录 = `{id, form, senses, examples, analysis, phonetic}`），可 64KB 块压缩 | 由 idx 指向 offset/length | vocab（只读文件） |
| `content-<lang>-<version>.idx` | 每条 `hash64(8B) + offset(8B) + length(4B) + contentId(8B)`（28B），按 hash 排序 | hash → 记录 / id → offset | vocab（只读文件） |
| `manifest.json` | `language / version / promptVersion / count / packSha256 / idxSha256 / createdAt` | 启动校验 | vocab |
| `content-delta.db` | SQLite（WAL），表 `word_content_delta(content_id INTEGER PRIMARY KEY, language, normalized_form, content TEXT, prompt_version, created_at)`，唯一 `(language, normalized_form, prompt_version)` | 主键 / 唯一键 | vocab（本地可变文件） |
| `vocab_words.content_id` | `bigint null`，引用包内 id 或 delta 的 `content_id` | 仅引用，无跨源外键 | vocab（业务表） |

**content_id 分配**：包内 id 从 **1** 连续递增（由生成脚本按 `normalized_form` 排序后分配，稳定可复现）；
delta 的 `content_id` 从 **1_000_000_000** 起递增，两者不冲突。`ContentStore` 按 id 区间分派：
`id < 1e9` → 包；否则 → delta。

**版本**：包与 delta 都带 `prompt_version`；`ContentStore` 只返回与配置
`teachloop.vocab.content.prompt-version` 一致的内容；换提示词 = 出新一轮包 + 升配置，旧 delta 不参与命中。

### 4.1.2 模块与端口

- 端口（vocab 模块内，不跨模块）：`ContentStore`
  - `Optional<Content> get(language, form)`
  - `Optional<Content> getById(contentId)`
  - `long put(language, form, contentJson, source)`（写 delta + 更新内存索引，返回 content_id）
  - `Stats stats()`（hit/miss/AI 计数，供指标与排障）
- 实现链：`LruContentCache`（可选，进程内）→ `PackContentStore`（只读，idx 常驻）→ `DeltaContentStore`（SQLite）→ miss。
- 包与 delta 都只被 vocab 拥有；`check-boundaries` 的 `TABLE_OWNER` 只登记 `vocab_words.content_id`（列，不是新表）。

### 4.1.3 流程

**prepare（学生进入复习/详情）**
1. 取窗口词 `(language, normalized_form)`；
2. 批量 `ContentStore.get`：命中 → 写 `vocab_words.content_id`、`content_status=READY`（P1/P2 仍同时写 `content` 副本，P3 起不再写）；未命中 → 入队 AI（priority 100）。
3. 页面等 `pending` 归零（T428 规则不变）。

**生成成功**
`GenerationJobStore.succeed` 在同一事务里写用户行后，调用 `ContentStore.put` 写 delta + 内存索引；
若 delta 写失败，用户行仍然 READY（复习不受影响），记 warn + 指标 `vocab.content.delta_write_failed`。

**详情/队列/词库读取**
P1/P2 不变（继续读 `vocab_words.content`）；P3 改为按 `content_id` 从 `ContentStore` 解析，删除用户行副本。

### 4.1.4 迁移

| 迁移 | 内容 | 回滚 |
| :--- | :--- | :--- |
| V062 | `alter table vocab_words add column content_id bigint`（nullable，无外键） | drop column |
| 回填（一次性命令） | 现有 `READY` 词按 `(language, normalized_form)` 写入 delta，拿回 content_id 回填；幂等，可重跑 | 不涉及 |
| V063（P3） | 先把存量 content 复制到 `vocab_words_content_legacy`，再 `alter table vocab_words drop column content`；启动回填成功后 drop legacy 表 | 需从 ContentStore 重建；回填失败启动失败并保留 legacy 表重试 |

### 4.1.5 失败与降级

| 场景 | 行为 |
| :--- | :--- |
| 包缺失/校验失败 | `content.pack.required=true` 时启动失败（提示重新部署包）；false 时 warn 并只用 delta + AI |
| delta 打不开/写失败 | 读：跳过 delta 当 miss；写：warn + 指标，用户行仍 READY |
| SQLite busy/locked | WAL + busy_timeout=5s，失败按上一条降级 |
| 包内 id 越界/记录损坏 | 该词当 miss 走 AI，并记 `vocab.content.pack_corrupt`；不写半成品 |
| 多实例 | v1 单实例；delta 是本地文件。多实例上线前必须把 delta 换成共享存储（另立决策） |

### 4.1.6 容量与性能（数字待用户确认，先记目标）

- 100k 词包：原始 ≈85MB，gzip ≈25–35MB；idx ≈1.6MB；内存索引 ≤8MB。
- 启动：idx 装载 10–30ms；pack sha256 校验 85MB ≈0.2–0.5s（可配置跳过）。
- 命中：内存 hash 查找 ~1µs；一次 `pread` + JSON 解析 ≈30–50µs/词；20 词窗口 <2ms。
- delta 单行查询 0.05–0.2ms；写入 <1ms。
- 指标（Micrometer）：`vocab.content.hit{layer}`、`vocab.content.put`、`vocab.prepare` timer、`vocab.ai.generate` timer。

### 4.1.7 测试

- 单测：idx 构造/查找、manifest 校验、content_id 区间分派、delta 幂等 put、prompt 版本过滤。
- 集成（Testcontainers PG + 临时目录）：两学生先后同词 → 只 1 次 AI、第二个命中 delta；包命中 0 次 AI；
  包损坏降级；重启后索引重建；delta 写失败不影响 READY。
- 性能：10 万词合成包，启动装载、20 词窗口 p95、内存占用。
- 浏览器：有包时进入复习不出现「数据准备中」；无包时退回 AI 并正常出卡。

### 4.1.8 任务与验收

| 任务 | 内容 | 验收 |
| :--- | :--- | :--- |
| T429（P1） | `content_id` 列 + `ContentStore` 端口 + SQLite delta + prepare 命中 + 生成写回 | 第二个学生同词 0 个新 job；delta 可追溯；重启后仍命中 |
| T430（P2） | pack/idx/manifest 读取器 + 预生成 JSON 导入脚本 | 10 万词包启动 ≤1s；命中 ≤10ms/20 词；导入可复现（sha256） |
| T431（P3） | 读路径改 `content_id`，删 `vocab_words.content`，回填与回归 | 队列/详情/词库/复习全部从 ContentStore 取内容；库空间下降；全量测试绿 |

### 4.1.9 待定（需用户输入）

1. 预生成 JSON 的路径与格式（一文件一词 / 大文件；id 类型；字段名）；
2. 性能指标数字（用户说待讨论）；
3. delta 在多实例部署时的共享方式（v1 单实例不阻塞）。

## 4.2 词详情内容 v2、语言档案与可配置页面模板（US-60 / decisions/018）

### 4.2.1 内容结构

- 每条内容 = 一个词，`schemaVersion: 2`；字段见 decisions/018：`basicInfo` / `meanings{zh,en,es}` /
  `examples[{text, translations, partOfSpeech, usagePattern, popularity}]`（1–6 条，自然优先）/
  `wordFormation{analysis, parts[], confidence}`（可选）/ `memoryAids` / `wordFamily`。
- 内容包（P2）的每条记录就是这份 JSON；delta 行同样存 v2。`schemaVersion` 与配置不一致时按 miss 处理（重新生成）。
- 词库/复习卡只取 `meanings` + 前 1–2 条例句；详情页按模板渲染。

### 4.2.2 语言档案

- 表 `vocab_language_profile(user_id uuid pk, main_language, target_language, display_order jsonb, updated_at)`，
  默认 `zh` / `en` / `["zh","en"]`；个人资料页提供设置入口（调 vocab 的
  `GET/PUT /api/vocab/language-profile`）。
- 内容生成覆盖 `meanings`/`translations` 的 zh/en 两种语言（V1；`es` 留扩展位，见 decisions/018 §8）；缓存键仍是 `(target 语言, 词形)`，与主语言无关。

### 4.2.3 页面模板与例句参数

- 表 `vocab_ui_config(key varchar pk, value jsonb, updated_at)`；seed 两行：
  `word_detail_template`（sections 顺序/显隐/每节 options）与 `word_examples_config`
  （aiRequestCount=6 / defaultVisibleCount=3 / moreVisibleCount=3 / sortRule）。
- 管理员接口 `PUT /api/admin/vocab/ui-config`（ADMIN），前端 `GET /api/vocab/ui-config/word-detail` 取合并后的配置；
  配置非法时回退代码内默认模板并记 warn。
- 前端 `WordDetailPage` 按 sections 渲染，「More」展开剩余例句；例句文本高亮目标词（大小写/屈折容错）。

### 4.2.4 生成 prompt 与校验

- 系统提示与用户提示按 decisions/018 §7；prompt 版本进 `prompt_version`（包、delta、配置一致才命中）。
- 写库前校验：JSON 结构、例句数量 1..配置上限、`popularity` 1..N 唯一、`text` 含目标词、`translations` 覆盖 zh/en（目标语言自身不重复翻译）；
  不合格重试一次，再失败 → job FAILED，不写半成品。

### 4.2.5 任务

| 任务 | 内容 | 验收 |
| :--- | :--- | :--- |
| T432 | 内容结构 v2 + prompt v2 + 输出校验（替换 v1 生成器输出） | 生成 6 条例句、三种语言、排序字段齐全；非法输出不落库；单测/集成绿 |
| T433 | `vocab_language_profile` + 接口 + 个人资料页设置 | 三种画像（zh/en、en/zh、zh/es）显示顺序正确；默认 zh/en |
| T434 | `vocab_ui_config` + 管理员接口 + 详情页模板渲染 + More | 改配置即可把例句置顶、改显示条数，无需发版 |

### 4.2.6 失败与兼容

| 场景 | 行为 |
| :--- | :--- |
| AI 输出缺语言/条数不对 | 重试一次；仍失败 job FAILED，页面显示失败与重试 |
| 模板配置非法（未知 section） | 回退默认模板，忽略未知 key，记 warn |
| 存量 v1 内容 | 过渡期 UI 兼容渲染；下次 prepare 需要时按 v2 重生成（`schemaVersion` 区分） |
| 语言档案未设置 | 使用默认 zh/en，不报错 |

## 4.3 学生词单（US-69 / decisions/020）

> 词单是**只引用词条**的容器：`vocab_words` 一份、`vocab_progress` 一份，词单里不存任何进度或排期。

### 4.3.1 流程

| 流程 | 步骤 | 失败路径 |
| :--- | :--- | :--- |
| 建单 | 校验名字（1–80，同学生未删除词单内不重名）→ 校验上限 `lists.max-per-student`（默认 100）→ 插 `vocab_lists` | 重名 409（库层唯一索引兜底）；超上限 409，前端禁用创建按钮（AC-69.4） |
| 加词 | 按 `forms` 提交时先走「加词入库」（与查词页 `POST /api/vocab/words` 同一条路径）→ 取 `word_id` → `insert … on conflict (list_id, word_id) do nothing` | 词不属于本人 404；已存在返回 `duplicated`（幂等，AC-69.2）；词单已软删 404 |
| 移出 | 删 `vocab_list_items` 行 | 只删引用；词条与进度不变（AC-69.3/62.6） |
| 删单 | `vocab_lists.deleted = true` | 引用行保留（恢复词单时可用），查询按 `deleted = false` 过滤；词条与进度不动 |
| 复习 | 取该词单内未暂停词的 `word_id` → `POST /api/vocab/review-sessions`（既有接口，`MAX_WORDS = 1000`） | 空/全部暂停时禁用按钮并提示（AC-69.7）；超过 1000 时按自由复习既有做法分批 |

### 4.3.2 端口与依赖

- 全部在 `teachloop-vocab` 内：`com.teachloop.vocab.lists`（Endpoint + Repository），不新增跨模块依赖。
- 复习走既有会话端点，不复制卡片逻辑（`VocabReviewCard` 复用）。
- 查询：`VocabularyWordsEndpoint` 增加可重复的 `list` 参数 → `VocabularyQueryRepository` 拼 `exists (select 1 from vocab_list_items i where i.list_id = any(...) and i.word_id = w.id)`，与其他条件取交集（AC-69.8）。

### 4.3.3 容量与性能

- 上限 100 份 × 每份 ≤1000 词 = 10 万引用行/学生；列表页 `wordCount` 用一次 `left join … group by`，不做 N+1。
- 复习取词单词 id 时按 `(list_id, position)` 顺序读，单次上限 1000 与会话上限一致。

### 4.3.4 测试

- 后端 `VocabWordListTest`：重名 409、超上限 409、他人词单 404、重复加入幂等、删单后词条与进度行数不变、一个词进 3 份词单复习后 `vocab_progress` 仍 1 行且 `due_at` 只更新一次、软删词后引用保留。
- 前端：列表页与详情页四态；`check-i18n` 中英 key 对齐；词库 `?list=` 筛选与 URL 还原。
- 端到端：查词页 → 加入词单 → 词单详情 → 开始复习 → 评分 → 回到详情，`vocab_progress` 只一行。

## 5. TTS 缓存

- 键：`sha256(normalized_form + "|" + provider + "|" + voice + "|" + rate)`；值：音频字节 + contentType。
- 存储：`BlobStore`（T386）→ 开发 `./uploads`，生产 OSS/MinIO；路径 `vocab/tts/{key}.mp3`。
- 读取：`GET /api/vocab/audio/{key}`（需登录）→ 命中直接返回，附 `ETag` 与 `Cache-Control: private, max-age=31536000, immutable`。
- 回落链：**缓存 → 本地引擎（Piper，`http://127.0.0.1:5002`）→ 浏览器 SpeechSynthesis**；本地 2s 超时即回落，不阻塞复习。
- 失败：写指标与审计（不含正文），不弹阻塞错误；同一键 5 分钟内不重复重试。
- 预热（可选）：内容生成改为按需准备后（§4），首个窗口由复习/详情页触发，不再在导入后预热。

## 6. 复习调度

- 策略接口 `SrsStrategy`（vocab 内部，非 shared）：
  `NextState schedule(SrsState current, Rating rating, Instant now)`；默认 `Sm2Strategy`，参数全部来自 `teachloop.vocab.srs.*`。
- 评分入口：`POST /api/vocab/review`（wordId、rating、elapsedMs、sessionId?）；幂等键 `Idempotency-Key`（同一次提交重放不写两条日志）。
- 自由复习：`POST /api/vocab/review-sessions` 建会话（scope=筛选条件快照、total），
  `POST /api/vocab/review-sessions/{id}/advance`（rating 或 known，cursor+1），`POST .../end`。
  会话状态写 `vocab_review_sessions`，续次从 `cursor` 继续。
- 移出/重新加入：`removed=true` 时进度保留但不再入队；重新加入把 `state=NEW, due_at=now, interval=0, ease=2.5`，日志不动。

## 7. 导入解析器

- 入口两个，**同一个解析器**：`POST /api/vocab/import/preview` + `/commit`（multipart）、`POST /api/vocab/words/paste`（JSON 文本）。
- 支持 `.csv` / `.md` / `.txt`（`.xlsx` 可选）。
- 切分规则（覆盖 spec §15 与 4 种样例）：先按行；每行再按「逗号（中英文）+ 空白 + 分号」切；忽略空 token 与行尾逗号；
  带引号的 token 保留空格（`"take off"`）；无表头时整行都是词；有表头时按列映射。
- `.md` 专有语法（spec §15.1）：`#` 标题 / `>` 引用跳过；代码围栏内按词行；表格按单元格、
  `---` 分隔行跳过、首行当表头；列表标记 `- * + 1. 1)` 去掉后解析。
- **说明文字**：`language=en` 时含 CJK 的行跳过；`language=zh` 时反向规则需在预览页让用户确认。
  所有跳过行必须带「行号 + 原文 + 原因」出现在预览里（不静默丢弃）。
- 实测样例：`specs/004-vocab/fixtures/import/sample-4-formats.md`（22 行）→ 40 条 token → 去重 10 个，
  4 行中文说明被跳过。该文件同时作为解析器的固定测试夹具。

### 7.1 清洗与拼写校验（对应 FR-115）

- **格式清洗**在解析器内完成，返回 `cleanups[]`（行号 + 规则 + 前后值），预览页据此渲染报告。
- **拼写校验**复用词表索引（§3），分两段：
  - 命中 → `OK`（大多数词走这条，零成本）；
  - **只有未命中的词**才去搜候选：对 80,579 个词形做一次「带上限的编辑距离」扫描（距离 >2 提前放弃），
    取 (距离, 频段) 排序的前 2 个 → `SUSPECT`；一个候选都没有 → `UNKNOWN`。
- **为什么不用 BK-tree / SymSpell**（2026-10-23 实测，Python 原型）：
  - 暴力全表扫描：**123 ms/词**；1000 词全扫要 2 分钟——但实际只会对 miss 扫，1000 行里通常只有个位数到几十个 miss，合计 <2s；
  - BK-tree：构建 3.1s，查询 **98 ms/词**——在这份「8 万个短词」的数据上剪枝几乎无效，不如暴力；
  - SymSpell 删除索引：查询 **0.02–0.2 ms**，但要建 **270 万条变体**（Java 侧内存约 100–200 MB），对 10 万词级别的小表不划算。
  - 结论：**先用「只对 miss 做受限暴力扫描」**，零索引零内存；等词表变大或要支持多语言，再换删除索引。
- 候选排序按 `(编辑距离, 频段)`，不只看距离——`recieve → receive` 的距离是 2，而 `relieve` 是 1，
  只按距离会把正确答案挤掉。
- **不做自动改写**：`SUSPECT` 行由用户逐条决定或批量确认；提交时把用户的选择记进审计（谁在什么时候把 A 改成了 B）。
- 词表索引不可用时降级：跳过拼写校验、只做格式清洗，并在预览里说明「词表未加载，未做拼写校验」。
- 预览返回：总行数、前 20 条、预计新建/重复/失败；提交后入库并批量入队生成。
- 内存：流式按行解析，1000/10000 行压测（AC-48.4 的 50% 峰值增长）。
- 编码：UTF-8（含 BOM）；其他编码明确报错（AC-48.7）。

## 8. 外部依赖与失败路径

| 依赖 | 失败表现 | 处理 | 用户可见 |
| :--- | :--- | :--- | :--- |
| AI 内容生成 | 超时/限流/输出不合规 | 退避重试 3 次 → FAILED；24h 后标内容缺失并移出队列 | 列表标「生成中 / 生成失败」，可重试 |
| TTS 本地引擎 | 未启动/2s 超时 | 回落浏览器语音 | 无阻塞，按钮仍可用 |
| TTS 云端（可选） | 鉴权/额度 | 回落本地 → 浏览器 | 同上 |
| BlobStore 不可用 | 503 | 音频不缓存，直接走本地引擎/浏览器；不写半条记录 | 播放稍慢，不报错 |
| 班级端口 | 超时/异常 | 发词失败并回滚（事务） | 「稍后重试」 |
| 通知端口 | 写通知失败 | **不回滚发词**（词已发出），记审计与指标 | 学生少一条通知，词仍可领 |
| 词表文件缺失 | 装载失败 | 词族区块降级隐藏 | 详情页其余正常 |
| 评分提交重复 | 网络重试 | 幂等键去重 | 不重复计数 |

## 9. 模块边界与守卫注册

- `scripts/check-deps.py`：`vocab` 已是 `{shared, identity, classroom}`（骨架提交时登记）。
- `scripts/check-boundaries.py`：
  - `MODULES` 已含 `vocab`；
  - `TABLE_OWNER` 新增 14 张 `vocab_*` 表 → `vocab`；
  - `ALLOWED_CROSS_FKS` **不新增**（vocab 不建跨模块物理外键）。
- 端口：只新增 `BlobStore`（shared，T386 落地）；其余复用现有端口。

## 10. 拆服务路径（以后要拆时怎么拆）

| 阶段 | 进程 | 说明 |
| :--- | :--- | :--- |
| 现在 | app + worker（既有两个进程） | vocab 的生成轮询器随 worker 跑；TTS 引擎是 app 容器内的 sidecar 进程 |
| 第一步 | 拆出 `vocab-generation` worker | 只依赖 `vocab_*` 表与 AI；与 app 无共享内存 |
| 第二步 | 词表索引独立 | 已构建产物，可直接做成静态文件服务 |
| 第三步 | TTS 独立服务 | 现在的回落链已经把「引擎」抽象成 HTTP 端点，拆出去只改地址 |

## 11. 迁移与上线顺序

1. `V040__vocab_lexicon.sql`（词表两表）
2. `V041__vocab_words.sql`（words / sources / progress）
3. `V042__vocab_review.sql`（review_logs / decks / review_sessions）
4. `V043__vocab_sets_assignments.sql`
5. `V044__vocab_jobs_feedback_audio.sql`
6. `V045__vocab_lexicon_meta.sql`（T388：词表装载指纹单行表）
7. 功能开关：`teachloop.vocab.enabled=false` 时不注册路由、导航不出现（宪法第十五条：不放占位空承诺）。
   **T389 起默认 false**：四个页面在 T397/T403/T407/T408 落地后由 T413 收尾验收再置 true。
   开关值由 `GET /api/features` 透出，前端 `FeaturesProvider` 统一消费（未拿到答案前不提前重定向、不显示导航）。
   vocab 的控制器一律加 `@ConditionalOnProperty(name="teachloop.vocab.enabled", havingValue="true")`，关闭时端点不存在。

## 12. 测试策略

| 层 | 覆盖 |
| :--- | :--- |
| 单元 | 解析器 4 种样例 + CRLF/变音符/重复；清洗规则（序号/全角/引号）；拼写三态与候选；SM-2 两档映射；TTS 键计算；自由复习 cursor；规范化与去重 |
| 集成（Testcontainers） | 导入 1000 行（成功/重复/失败行号）、发词快照、领取去重、越权 404、移出/重新加入、自由复习续次、幂等评分 |
| 性能 | 1000 行导入 ≤30s；今日队列 p95 ≤200ms（1 万词/学生）；1 万行内存增长 ≤50% |
| 标签 | `@Tag("oss")` 走 MinIO（T386）；`@Tag("ai")` 走真机模型；默认 `make test` 不联网 |

## 13. 风险与待定

1. **词表许可**：原始 txt 不入库（已定）；manifest 里记来源与 sha256。
2. **预生成 HTML 的托管 —— 2026-10-23 决定暂缓（T400 可选）**：V1 只走「模板 + 实时 JSON」，不做静态预生成与 Bloom 否定判断；BlobStore 接缝保留（T386 已落地），需要时再补。
3. **多语言**：表结构已带 `language`，但 zh/es 的词表与 TTS 数据没有；V1 只开 en。
4. **时区**：A-3 暂定 `Asia/Shanghai`；若之后要用户级时区，`due_at` 已是绝对时间，只需改「今日」的边界计算。
5. **Bloom filter**：只做否定判断；实现放在 word 页缓存层，非强一致。

## 14. Plan review 六条清单（自检）

| # | 检查项 | 结论 | 证据 |
| :--- | :--- | :--- | :--- |
| 1 | 每条 AC 都能指出由哪张表、哪个流程承接 | **是** | AC-46→§2.1/§7；47→§2.1 decks（2026-10-25 起暂缓，见 US-47 注记）；48→§3/§7；49/50→§2.2；51→§2.2 recipients；52/53→§6；54→§2/§6；55→§2.1 removed；56→§8；57→§5；58→§2.1 language；59→§2.1/§6；60→§3/§4/§5；61→§6 会话；**62→§2.1 `vocab_lists`/`vocab_list_items` + §4.3** |
| 2 | 每个实体的表、字段、索引、状态迁移写全 | **是** | §2.1–2.4 |
| 3 | 每条外部依赖都有失败与重试路径 | **是** | §8 八条 + §4 退避策略 |
| 4 | 模块依赖无环、跨模块只走端口 | **是** | §1/§9；vocab 只依赖 shared/identity/classroom，无反向依赖 |
| 5 | 每张表单一属主、无跨模块 JOIN | **是** | §2 全部 `vocab_*`；§9 不建跨模块外键 |
| 6 | 拆服务路径写得出来 | **是** | §10 三步 |

## 15. 收尾项状态（拆 tasks 前）

| 项 | 状态 | 结论 |
| :--- | :--- | :--- |
| `.md` 导入语法白名单 | **已闭环** | spec §15 + 样例夹具 `fixtures/import/sample-4-formats.md`（22 行 → 40 token → 10 唯一） |
| 学生「待办」入口 | **已定** | 学生首页新增「待预习」区块（不新增一级导航），点击进 `/vocab/assignments/{id}` |
| 教师「已发出的布置」 | **已定** | 放在词集页的第二个 tab（不占一级导航），按时间倒序，可复制链接 |
| 路由表与 i18n key 清单 | **留给 tasks** | 每个页面一条任务，key 前缀 `vocab.*`，中英同步 |
| 词表 manifest 发布流程 | **已定** | `scripts/vocab/build-wordlist-index.py` 生成 → `make vocab-lexicon` 校验 sha256；产物不进 git，部署时构建阶段生成或挂卷 |
| 学生词单入口（US-69，2026-10-25；2026-10-26 修订） | **已定** | 侧栏一级入口「我的词单」→ `/vocab/lists`（列表），详情 `/vocab/lists/:id`；紧跟「我的词库」，key 为 `nav.vocab.lists`；**词库页工具条的「词单」按钮删除**（2026-10-26 用户拍板，侧栏是唯一入口，`vocab.lists.toolbarEntry` 一并删）；文案前缀 `vocab.lists.*`。原「不新增一级导航」（AC-69.11 旧文）被 2026-10-26 用户要求覆盖 |
| 卡组视图（US-47） | **暂缓** | 表与 `deckId` 参数保留、不开接口与页面；学生分组由 US-69 承接（`decisions/020`） |
