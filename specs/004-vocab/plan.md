# 实施计划 — 背单词与间隔重复（004-vocab）

- 状态：**初稿，未过 review**（本文件写完后按 6 条清单逐条自检，见 §14）
- 上游：`spec.md`（US-46–61 / AC-88 / FR-091–114）、`docs/decisions/011-vocab-requirements.md`、`docs/decisions/012-storage-port-and-oss.md`、`docs/research/2026-10-23-vocab-wordlists-feasibility.md`
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
| `vocab_words` | `id uuid`、`user_id`、`language`、`form`、`normalized_form`、`content jsonb`、`content_status`(PENDING/READY/FAILED)、`family_id`、`band`、`removed bool`、`removed_at`、`created_at/updated_at` | `unique(user_id, language, normalized_form)`；`index(user_id, removed)` |
| `vocab_word_sources` | `word_id`、`kind`(ASSIGNMENT/IMPORT/SELF)、`set_id/assignment_id/class_id/teacher_id` 可空、`created_at` | `index(word_id)`；同一来源只记一次 |
| `vocab_progress` | `word_id`(pk)、`user_id`、`state`(NEW/LEARNING/REVIEW/GRADUATED)、`due_at`、`interval_days numeric(8,2)`、`ease numeric(4,2)`、`lapses`、`last_rating`、`reviewed_count`、`updated_at` | pk=`word_id`（一个词只有一份进度）；`index(user_id, due_at)`、`index(user_id, state)` |
| `vocab_review_logs` | `id bigserial`、`user_id`、`word_id`、`rating`、`reviewed_at`、`elapsed_ms`、`session_id` 可空 | `index(user_id, reviewed_at)`、`index(word_id)` |
| `vocab_decks` | `id uuid`、`user_id`、`name`、`criteria jsonb`、`position`、`deleted` | `unique(user_id, name) where not deleted` |
| `vocab_review_sessions` | `id uuid`、`user_id`、`scope jsonb`、`total`、`cursor`、`known_count`、`status`(ACTIVE/ENDED)、`started_at/updated_at` | `index(user_id, status)` |

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

- 触发：导入/加词提交后、详情页未命中词表时；只对 `content_status=PENDING` 且**词形尚不存在**的词建 job（幂等键 `(subject_type, subject_id)`）。
- 执行：`vocab_generation_jobs` + 一个 `@Scheduled` 轮询器（与 grading worker 同思路，不引入 MQ）：
  `SELECT ... WHERE state='QUEUED' AND next_run_at<=now() ORDER BY next_run_at LIMIT n FOR UPDATE SKIP LOCKED`。
- 生成内容（写入 `content jsonb`）：`senses[]`（词性 + 中英释义）、`examples[]`（英文 + 中文）、`analysis`（构词）、`family[]`（词族，词表命中时以本地为准）、`phonetic {uk, us}`。
- 幂等：同一词形已 `READY` 不再生成；重复导入不重复入队。
- 重试：失败指数退避（1min / 5min / 30min），3 次后 `FAILED`；24h 仍失败标「内容缺失」，从队列移除并提醒（AC-48.4）。
- 成本：`teachloop.vocab.generation.{enabled,daily-budget,concurrency,timeout-ms,model}`；超预算暂停入队并告警。
- 单条超时 60s，超时按失败计；**成功才写库**，不写半成品。

## 5. TTS 缓存

- 键：`sha256(normalized_form + "|" + provider + "|" + voice + "|" + rate)`；值：音频字节 + contentType。
- 存储：`BlobStore`（T386）→ 开发 `./uploads`，生产 OSS/MinIO；路径 `vocab/tts/{key}.mp3`。
- 读取：`GET /api/vocab/audio/{key}`（需登录）→ 命中直接返回，附 `ETag` 与 `Cache-Control: private, max-age=31536000, immutable`。
- 回落链：**缓存 → 本地引擎（Piper，`http://127.0.0.1:5002`）→ 浏览器 SpeechSynthesis**；本地 2s 超时即回落，不阻塞复习。
- 失败：写指标与审计（不含正文），不弹阻塞错误；同一键 5 分钟内不重复重试。
- 预热（可选）：导入完成后对前 N 个词批量生成，避免首次播放等待。

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
3. `V042__vocab_decks_sessions.sql`
4. `V043__vocab_sets_assignments.sql`
5. `V044__vocab_jobs_feedback_audio.sql`
6. 功能开关：`teachloop.vocab.enabled=false` 时不注册路由、导航不出现（宪法第十五条：不放占位空承诺）。

## 12. 测试策略

| 层 | 覆盖 |
| :--- | :--- |
| 单元 | 解析器 4 种样例 + CRLF/变音符/重复；清洗规则（序号/全角/引号）；拼写三态与候选；SM-2 两档映射；TTS 键计算；自由复习 cursor；规范化与去重 |
| 集成（Testcontainers） | 导入 1000 行（成功/重复/失败行号）、发词快照、领取去重、越权 404、移出/重新加入、自由复习续次、幂等评分 |
| 性能 | 1000 行导入 ≤30s；今日队列 p95 ≤200ms（1 万词/学生）；1 万行内存增长 ≤50% |
| 标签 | `@Tag("oss")` 走 MinIO（T386）；`@Tag("ai")` 走真机模型；默认 `make test` 不联网 |

## 13. 风险与待定

1. **词表许可**：原始 txt 不入库（已定）；manifest 里记来源与 sha256。
2. **预生成 HTML 的托管**：走 app 的 `/api/vocab/words/{id}/page` 还是 nginx 静态，取决于 T386 之后 BlobStore 能否提供公开读；task 阶段定。
3. **多语言**：表结构已带 `language`，但 zh/es 的词表与 TTS 数据没有；V1 只开 en。
4. **时区**：A-3 暂定 `Asia/Shanghai`；若之后要用户级时区，`due_at` 已是绝对时间，只需改「今日」的边界计算。
5. **Bloom filter**：只做否定判断；实现放在 word 页缓存层，非强一致。

## 14. Plan review 六条清单（自检）

| # | 检查项 | 结论 | 证据 |
| :--- | :--- | :--- | :--- |
| 1 | 每条 AC 都能指出由哪张表、哪个流程承接 | **是** | AC-46→§2.1/§7；47→§2.1 decks；48→§3/§7；49/50→§2.2；51→§2.2 recipients；52/53→§6；54→§2/§6；55→§2.1 removed；56→§8；57→§5；58→§2.1 language；59→§2.1/§6；60→§3/§4/§5；61→§6 会话 |
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
