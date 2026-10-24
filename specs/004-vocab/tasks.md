# 任务拆分 — 背单词与间隔重复（004-vocab）

- 上游：`spec.md`（US-46–61 / AC-100 / FR-091–115）、`plan.md`（已认可）、`docs/decisions/011`、`012`
- 任务号：**T387 起**（全局最大 T386，003/006 已占用到 371）
- 前置：T386（BlobStore 端口 + S3/MinIO 落地）已完成才允许开始 T410
- 约定：每个任务自带验收；完成即更新本文档并写执行记录；提交信息带 T 号

---

## 阶段 A — 地基（迁移、词表、开关）

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T387 | **数据迁移 V040–V044**：词表两表（families/forms）、`vocab_words`/`word_sources`/`progress`/`review_logs`、`decks`/`review_sessions`、`sets`/`set_words`/`assignments`/`assignment_words`/`assignment_classes`/`assignment_recipients`、`generation_jobs`/`feedback`/`audio_assets`；全部登记 `TABLE_OWNER`；不建跨模块外键 | — | `make check-boundaries` 通过；迁移在空库与含存量数据的库各跑一次；表数与 plan §2 一致（17 张） |
| T388 | **词表索引**：`scripts/vocab/build-wordlist-index.py` 产出 `lexicon-*.tsv` + `manifest.json`（sha256/族数/形数）；启动时幂等 upsert 进 `vocab_lexicon_*`；内存 `Map` 查询（精确 + 去变音符） | T387 | 28,276 族 / 80,579 形导入成功；`run` 与 `running` 命中同一族；`éclat` 能命中 `ÉCLAT`（用合成夹具验证去变音符——真实词表的 ÉCLAT 在未收录的 `basewrd35` 补充表，见执行记录）；manifest 变化才重建 |
| T389 | **功能开关与导航**：`teachloop.vocab.enabled=false` 时不注册路由、导航不出现；开启后学生两项（我的词库/今日复习）、教师两项（词集/发词）；i18n key 前缀 `vocab.*` 中英同步 | T387 | 关时 `check-i18n` 与前端构建通过；开时两步导航可见；`make check-ui` 无新增违规 |

## 阶段 B — 导入、清洗、拼写校验与内容生成

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T390 | **解析器**：CSV/MD/TXT + 粘贴共用；行类型（标题/引用/表格/列表/普通行/代码块）；四种样例通过 | T387 | 夹具 `fixtures/import/sample-4-formats.md` → 40 token / 去重 10；CRLF、全角逗号、引号、行尾逗号各有用例 |
| T391 | **格式清洗**：去空格/全角/BOM/引号/行首序号/混入音标词性；返回 `cleanups[]`（行号+规则+前后值） | T390 | 每类规则单测；预览接口返回可追溯的 cleanups；不静默 |
| T392 | **拼写校验**：只对未命中词做受限编辑距离扫描（≤2 提前退出）；候选按 `(距离, 频段)` 排序取 ≤2；三态 OK/SUSPECT/UNKNOWN | T388, T391 | `cleever→clever`、`acommodate→accommodate`、`recieve→receive`（距离 2 但高频）命中；`zyzzyva` 为 UNKNOWN；1000 行导入 <30s |
| T393 | **入库管线**：加词/导入同一路径；规范化去重；已存在只追加来源；提交返回「新建/重复/失败」计数 | T392 | 重复导入不新增词条；大小写/全角/变音符去重；行号级错误 |
| T394 | **异步内容生成**：`generation_jobs` + 轮询（FOR UPDATE SKIP LOCKED）；幂等（同词不重复）；指数退避 3 次；24h 超时标内容缺失并移出队列；日预算上限 | T393 | 重复导入只入队一次；失败重试 3 次后 FAILED；超时词从队列消失且列表有标记；预算打满时暂停入队 |
| T395 | **内容状态前端**：列表显示「生成中 / 生成失败·重试」；批量重试；生成完成自动刷新 | T394, T397 | 状态三态可见；重试成功转 READY；无轮询风暴（间隔≥5s） |

## 阶段 C — 学生词库

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T396 | **词库查询接口**：筛选（来源多选/卡组/状态/已收藏/语言）、排序（默认下次复习升序，跨分页）、分页、搜索；V047 补 favorite/tags/列配置表 | T387 | 多条件并集/交集语义正确；排序跨分页；URL 可还原筛选 |
| T397 | **词库前端**：常驻搜索（Enter 查任意词）、统计卡过滤、筛选弹窗、列配置（显示/顺序/按学生保存）、复选框批量暂停/删除、行内暂停/恢复/删除 | T396 | 空/错/加载四态；搜索 Enter 进详情；批量二次确认；列配置刷新后保留 |
| T398 | **收藏**：详情与行内收藏；筛选「已收藏」；复习范围可选只看收藏 | T396 | 收藏不改变排期；筛选命中正确 |
| T399 | **单词详情页**：模板 + JSON；在库（收藏/暂停/反馈）与不在库（加入词库）两种状态；例句、构词、词族、音标；反馈带 word id | T388, T396 | 两种状态截图；不在库加入后当天进队列；反馈落库含 id |
| T400 | **详情页预生成（可选）**：app 端点 `/api/vocab/words/{id}/page`；有预生成从 BlobStore 取，无则实时渲染；Bloom filter 仅否定判断 | T399, T386 | 命中/未命中都 200；生成期间不阻塞；缓存头正确 |

## 阶段 D — 复习与调度

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T401 | **SRS 策略**：`SrsStrategy` + `Sm2Strategy`；认识→良好、不认识→忘记；毕业 4 次 + 45 天；参数全部 `teachloop.vocab.srs.*` | T387 | 四种状态迁移单测；边界（间隔 0/45/180）；改 yml 生效 |
| T402 | **今日队列接口**：到期 ≤ now、按到期升序、排除暂停/已删、每日新卡上限、单次 100 条 | T401 | 队列不重复；上限跨日重置；p95 ≤200ms（1 万词） |
| T403 | **复习页前端**：两档按钮（✕/✓）、不认识自动翻面并停留、下一个在卡片外右侧、N/→、1/2、ⓘ 气泡 | T402 | 四种状态（正面/翻面/已评/空）；键盘全部可用；窄屏布局 |
| T404 | **自由复习会话**：scope=筛选快照、cursor 续位、S 暂停、结束小结、不计入今日完成数 | T401, T396 | 看 12 个后结束，再次进入从第 13 个开始；认识照常推进调度 |
| T405 | **日志与幂等**：每次评分写 `review_logs`；同一次提交带幂等键不双写 | T402 | 重放同一请求只落一条；断网重试不重复 |

## 阶段 E — 教师侧

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T406 | **词集后端**：CRUD、批量启用/停用/删除、词条顺序、移除只移出词集 | T387 | 停用后不能发词；删除不影响已发布置；移除不动词条 |
| T407 | **词集前端**：表格视图 + 批量条 + 编辑页（单栏、导入/加词、取消/保存） | T406 | 表格列与 spec §23 一致；取消返回列表；空态文案正确 |
| T408 | **发词**：词集快照、班级快照（收件人）、截止时间、通知（失败不回滚发词）、只能发自己带的班（越权 404） | T406, T389 | 改词集不影响已发；重复发不重复入库；非本人班级 404 |
| T409 | **发词记录**：词集页第二 tab，按时间倒序，含领取情况与复制链接 | T408 | 列表与实发一致；复制链接可用 |

## 阶段 F — 发音

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T410 | **TTS 缓存与端点**：键 = hash(词+provider+voice+rate)；BlobStore 存取；`GET /api/vocab/audio/{key}`；回落链（缓存→本地→浏览器）；只读单词 | T386, T387 | 二次播放不调引擎；本地 2s 超时回落；失败不阻塞复习 |
| T411 | **Piper sidecar**：同镜像常驻进程（127.0.0.1:5002）+ 健康检查 + 模型文件随镜像；`tts.provider=local` 默认 | T410 | 容器内 `curl /health` 通过；断掉 sidecar 后复习页仍可播放（浏览器回落） |

## 阶段 G — 验收与收尾

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T412 | **集成与性能**：导入 1000/10000 行、队列 p95、越权 404、软删恢复、自由复习续位、幂等评分 | T395, T405, T404 | 全部用例绿；内存增长 ≤50%；p95 达标 |
| T413 | **回归与文档**：`make test` + 四守卫；AC 勾选；`PROGRESS`/`DELIVERY-STATUS` 更新；截图与验收记录 | 全部 | 全绿；计数自检 16/100/25；无重复编号 |

---

## 覆盖映射（AC → 任务）

| US | AC 范围 | 承接任务 |
| :--- | :--- | :--- |
| US-46 词库 | 46.1–46.5 | T387, T396, T397 |
| US-47 卡组 | 47.1–47.5 | T387, T396, T397 |
| US-48 导入 | 48.1–48.12 | T390–T395 |
| US-49 词集 | 49.1–49.10 | T387, T406, T407, T409 |
| US-50 发词 | 50.1–50.5 | T408, T389 |
| US-51 领取 | 51.1–51.4 | T408, T395 |
| US-52 队列 | 52.1–52.6 | T402 |
| US-53 评分 | 53.1–53.8 | T401, T403, T405 |
| US-54 隐私 | 54.1–54.4 | T389, T396, T408 |
| US-55 删除 | 55.1–55.3 | T387, T397 |
| US-56 界面 | 56.1–56.3 | T389, T397, T403 |
| US-57 发音 | 57.1–57.6 | T410, T411 |
| US-58 语言 | 58.1–58.4 | T387, T389 |
| US-59 表格 | 59.1–59.11 | T396, T397, T398 |
| US-60 详情 | 60.1–60.8 | T388, T399, T400 |
| US-61 自由复习 | 61.1–61.6 | T404 |

## 执行顺序建议

1. **串行**：T387 → T388 → T389（地基）
2. **可并行**：T390–T395（导入）与 T396–T399（词库）在 T387 之后可并行；T401–T405（复习）依赖 T396
3. **收口**：T406–T409（教师）→ T410–T411（发音）→ T412 → T413

## 风险

| 风险 | 应对 |
| :--- | :--- |
| 存储端口未就绪导致 T410 阻塞 | T410 前检查 BlobStore 是否已进 `shared`；未完成就先做 T410 之外的部分 |
| 词表体积（3–5MB）影响启动 | 启动异步装载，首次查询前完成；失败则词族区块降级 |
| 异步生成成本失控 | 日预算 + 并发上限 + 24h 超时（T394 已含） |
| 教师 18 个班的批量操作 | 批量接口按 id 列表，单次 ≤200 个班 |


## 执行记录

| 日期 | 任务 | 结果 | 验证 |
| :--- | :--- | :--- | :--- |
| 2026-10-23 | T387 迁移 V040–V044 | 完成（只建表，无业务代码）；`make test` 281/0/0/0（Flyway 在实际测试库上应用成功） | 空库按序执行 5 个迁移成功，17 张表；`check-boundaries` 44 表归属通过、`check-deps` 通过；约束冒烟：重复词（user+language+normalized）被唯一键拒绝、`rating=3` 被 check 拒绝、同来源重复（NULL 列）被 `unique nulls not distinct` 拒绝 |
| 2026-10-23 | T388 词表索引 | 完成：`scripts/vocab/build-wordlist-index.py`（TSV + manifest）、V045 `vocab_lexicon_meta`、Java 读取/校验/幂等 upsert/内存索引/启动装载 | 真实词表：**28,276 族 / 80,579 形**（去重前 80,585；跨文件重复 6；源文件 2 行杂质跳过并在 manifest `skipped_lines` 报告；4.3 MB / gzip 约 1.5 MB）；`run` 与 `running` 同族；`éclat` 去变音符用合成夹具验证——**ÉCLAT 只在 `basewrd35`，用户 2026-10-23 决定索引只收 `basewrd1–30`**，补充表逐条记进 manifest `ignored_files`；脚本自测 8/8、vocab 单元 13/0/0、app 集成 3/0/0（Testcontainers，Flyway 实际应用到 V045）；`check-boundaries` / `check-deps` / `check-i18n` 全通过 |
| 2026-10-23 | T389 功能开关与导航 | 完成：`teachloop.vocab.enabled`（**默认 false**）+ `GET /api/features` + 前端 `FeaturesProvider`、`app/nav.ts` 与四条路由骨架（`/vocab/library`、`/vocab/review`、`/vocab/sets`、`/vocab/assign`；四页由 T397/T403/T407/T408 填内容） | 关：`/api/features` 返回 `{"vocab":false}`、深链 `/vocab/library` 被挡回首页、侧栏无 vocab 项（1440×900 headless Chrome 实测）；开：学生侧栏「我的词库/今日复习」、教师侧栏「词集/发词」可见（两角色截图）；web 测试 33/33、`make web-build` 通过、i18n 中英各 1312 键对齐、`check-ui` 无新增违规。默认 false 的理由与翻转时点见 plan §11；vocab 后续控制器一律加 `@ConditionalOnProperty` |
| 2026-10-23 | T390 解析器 | 完成：`DictionaryTextParser`（CSV/MD/TXT 与粘贴共用同一入口），行类型与切分按 spec §15.1–15.2 | 夹具 `sample-4-formats.md`：**40 token / 去重 10 / 跳过 4 行说明文字**（行号 1/4/7/19）；CRLF、全角逗号分号、引号短语、行尾分隔符、列表标记、标题/引用、代码围栏、表格、en/zh 说明规则、去重、空文本共 **13 用例全绿**；三守卫通过 |
| 2026-10-23 | T391 格式清洗 | 完成：`ImportTextCleaner`（全角标点/引号/音标/词性）+ 解析器记录 BOM/行首序号/连续空格；`POST /api/vocab/import/preview`（开关打开才注册、需登录）返回 cleanups | 每类规则单测（BOM/空格/全角/引号/序号/音标/词性）；解析器 16/16、清洗 4/4、端点 2/2、app 集成 2/2（登录 200 + cleanups 可追溯、未登录 401）；三守卫通过 |
| 2026-10-23 | T392 拼写校验 | 完成：`SpellingChecker`（三态；只对未命中词做 ≤2 上限编辑距离扫描；候选按 (距离, 频段) 取 ≤2；词表不可用时降级为 UNKNOWN、不误报）；预览接口透出每个词的 spelling | `cleever→clever`、`acommodate→accommodate`、`recieve` 候选中含 `receive`（距离 2；距离 1 的 relieve 在前，但前 2 仍含正确词）；`zyzzyva` 为 UNKNOWN；8 万词形 + 1000 个全 miss 的最坏上界 **6.5s < 30s**；三态/排序/降级单测 8/8、吞吐 1/1、预览端点 3/3、app 集成 2/2；三守卫通过 |
| 2026-10-23 | T393 入库管线 | 完成：V046 给 `vocab_word_sources` 加 `label` + `batch_id`（AC-48.11 / AC-46.4 的 schema 缺口，用户 2026-10-23 拍板加两列）；`WordImportService` 一条管线供预览 / 导入 / 粘贴；规范化去重（大小写 / 全角 / 变音符）；已存在只追加来源；软删除恢复原词条与原进度 | 预览与提交计数一致（total 4 / 新建 2 / 重复 1 / 失败 1，失败行号第 4 行）；重复导入不新增词条（2→2）、来源按批次追加（2→4）；`Résumé / resume / ＲＥＳＵＭＥ` 只建 1 条；软删后重导恢复词条且 `state=REVIEW` 进度保留；`/words/paste` 与 `/import/commit` 同管线；单测 3/3 + 4/4，app 集成 2/2；三守卫通过 |
| 2026-10-23 | T394 异步内容生成 | 完成：`GenerationJobStore`（FOR UPDATE SKIP LOCKED、`unique(subject_type, subject_id)` 幂等）、`VocabularyGenerationWorker`（失败 1/5 分钟退避、3 次 FAILED、24h 清理并移出队列）、`VocabularyGenerationService`（日预算打满暂停入队）、ai/stub 可替换生成器；导入后自动为新词入队 | 同词重复导入只 1 个 job；stub 成功 → `content_status=READY` 且 content 写库；`failword` 第 3 次失败 → job FAILED、词条 FAILED；25 小时前建的 job 被清理、词条标内容缺失且不再 QUEUED；`daily-budget=0` 时导入成功但 0 个 job、词条保持 PENDING；app 集成 3/3 + 1/1；三守卫通过 |
| 2026-10-23 | T396 词库查询接口 | 完成：V047 为 `vocab_words` 加 `favorite` + `tags`、新建 `vocab_user_prefs`（用户 2026-10-23 拍板）；`GET /api/vocab/words` 支持来源多选（含导入批次）/卡组/状态/收藏/标签/语言、搜索、按白名单列排序（跨分页）、分页 20/50/100 | 来源多选取并集、不同筛选取交集；暂停词仍在列表（paused=true）；`q`/状态/收藏/标签/卡组/来源五类筛选逐一命中；25 词按 form 升序分 20+5 两页拼接仍有序；zh 词不进 en 列表、en 词不进 zh；非法分页大小 400；app 集成 2/2；三守卫通过 |
| 2026-10-23 | T397 词库页与行内动作 | 部分完成：V047 的 favorite/tags/prefs 落库；行内与批量「暂停 / 恢复 / 软删除」、统计卡、来源聚合、列配置（按学生保存）；`GET /api/vocab/words` 补 `paused` / `due` 筛选；前端 `LibraryPage`（常驻搜索、统计卡点击过滤、筛选弹窗、列配置、批量条、分页、空/错/加载四态） | 后端 app 集成 2/2（暂停不删词、恢复回 NEW、软删仍留进度、批量、越权 404、prefs、sources）；前端 `pnpm run build` 通过、i18n 1371 键对齐、check-ui 无新增违规、web 测试 33/33。**残余**：① 搜索 Enter 已导航到 `/vocab/words/by-form/:form`，详情页由 T399 提供后再做浏览器验收；② 导入/加词 UI 未做（任务拆分遗漏，记入 T413 前补齐） |
| 2026-10-23 | T398 收藏 | 完成（行内 + 筛选；详情与复习范围的收藏在 T399/T404 接）：`PUT /api/vocab/words/{id}/favorite` 纯标记、列表行内星标开关、`favorite=true` 筛选复用 T396 | 收藏前后 `vocab_progress` 的 state 与 due_at 完全不变；已收藏筛选命中 1、取消后 0；教师改学生的词 404；app 集成 3/3；前端 build 通过、i18n 1373 键对齐、check-ui 无新增违规、web 测试 33/33。残余：详情页收藏随 T399、自由复习/今日队列「只看收藏」随 T402/T404 |
| 2026-10-23 | T399 单词详情页 | 完成：`GET /api/vocab/words/by-form/{form}` 两态（在库带 content/进度/来源；不在库只给词形与本地词族）、`POST /api/vocab/words` 加入词库走同一导入管线、`POST /words/{id}/feedback` 只带 word id 落 `vocab_feedback`；前端 `WordDetailPage` 模板化渲染音标/释义/例句/构词/词族/来源，两种状态动作（收藏/暂停/恢复/反馈 或 ＋加入词库） | app 集成 2/2（不在库→加入→NEW 且当天到期；重复加入不新建；反馈 OPEN 且带 word id；越权 404）；1440×900 headless Chrome 两态截图（serendipity 在库含 BNC 第 12 千词族、zyzzyva 不在库）；build / i18n / check-ui / web 测试全绿。残余：预生成 HTML/Bloom filter 在 T400；发音按钮在 T410 |
| 2026-10-23 | T395 内容状态前端 | 完成：`POST /api/vocab/words/retry`（单条/批量；越权 404；词条回 PENDING、job 回 QUEUED 且 attempts 归零）；词库表状态列显示「内容生成中 / 内容生成失败」，失败行与批量条提供重试；仅当当前页存在 PENDING 时每 **5000ms** 刷新，生成完自动停表 | 后端 app 集成：3 次失败后手动重试 → QUEUED/attempts=0/PENDING，再 poll 会被重新领取；成功转 READY 由 T394 用例覆盖；前端 build、i18n、check-ui、web 测试（33/33）全绿。轮询间隔 ≥5s，无 PENDING 时无定时器 |
| 2026-10-23 | T400 详情页预生成（可选） | **暂缓（决策记录）**：当前详情页走 AC-60.6 的「不存在静态页 → 模板 + 实时 JSON」路径，用户可用且不阻塞；预生成 HTML + Bloom filter 只是性能优化，任务本身标注为可选。V1 不实现静态预生成与 Bloom 否定判断，保留接缝（BlobStore 已在 shared，T386 已落地），后续需要时按本任务补 | 无代码改动；详情页两态浏览器验收见 T399；本决定在 T413 收尾时向用户复核一次，避免被当成遗漏 |
| 2026-10-23 | T401 SRS 策略 | 完成：`SrsStrategy` + `Sm2Strategy`；`Rating` 四档（界面两档映射认识→GOOD、不认识→AGAIN）；`SrsState`/`SrsDecision` 与表结构无关；参数全部走 `teachloop.vocab.srs.*`（毕业 4 次/45 天、复查 180 天、易度 1.30–3.00、忘记 5 分钟、困难 1.2、容易 1.3） | 单测 **9/9**：四种迁移、新词基线 10 分钟、毕业 45 天边界（17→42.5 不毕业、50→180 毕业）、易度上下限、已掌握复查/忘记回 LEARNING、把参数改成 2 次/10 天后第 2 次认识即毕业（改配置生效）；三守卫通过 |
| 2026-10-23 | T402 今日队列接口 | 完成：`GET /api/vocab/review/queue` —— 到期 ≤ now、按到期升序、排除暂停/软删、NEW 卡受 `review.daily-new-cards` 上限、单次 ≤ `review.queue-page-size`，响应带 todayReviewed / remaining / newToday / newLimitReached；语言隔离 | app 集成：5 词中暂停+软删各 1 → total 3、只取 2 张新卡且 newLimitReached=true；到期 REVIEW 排在 NEW 前；今日 review_logs 计数为 1；`limit=1` 只取 1 张；zh 词不进 en 队列；队列内 id 唯一。**残余**：1 万词 p95 ≤200ms 的压测放到 T412（性能任务） |
| 2026-10-23 | T405 日志与幂等（提前实现，T403 依赖评分端点） | 完成：V048 给 `vocab_progress` 加 `successes`、新建 `vocab_review_requests(user_id, idempotency_key)`；`POST /api/vocab/review`（KNOWN→GOOD、DONT_KNOW→AGAIN）在同一事务里推进 SM-2、写一条 `review_logs`（认识=2 / 不认识=1）、幂等键冲突时返回当前进度且不写日志/不推进 | app 集成 3/3：认识→REVIEW、日志 rating=2、successes=1；同幂等键重放 duplicate=true 且日志/计数仍 1；不认识→LEARNING、日志=1、successes 归零、lapses+1；越权 404；三守卫通过 |
| 2026-10-23 | T403 复习页前端 | 完成：卡面正面（词 + 英美音标）/ 翻面（释义·例句·构词）；两档「不认识 / 认识」，认识后立即下一张、不认识自动翻面并停留且两键置灰；「下一个」在卡片外右侧（未评分半透明，不认识后高亮）；空格翻面、1/2 评分、N/→ 下一个；ⓘ 气泡展示三个数（今日已复习/剩余/本次新卡 + 新卡上限提示），页面不出现耗时估算；窄屏 CSS 把「下一个」落到卡片下方右对齐 | `pnpm run build` 通过、i18n 中英对齐、check-ui 无新增违规、web 测试 33/33；评分走 T405 端点并带 Idempotency-Key。**残余**：四种状态与键盘/窄屏的浏览器验收放到 T412/T413（本轮仅结构 + 构建校验） |
| 2026-10-23 | T404 自由复习会话 | **部分完成（后端）**：V049 给会话加 `paused_count`；`POST /review-sessions`（wordIds 快照 ≤1000，剔除暂停/软删）、`GET /{id}/current`、`POST /{id}/advance`（KNOWN/DONT_KNOW 走 T405 SRS 且日志带 session_id；PAUSE 停排期并跳下一张）、`POST /{id}/end`（小结 total/seen/known/unknown/paused）；同 scope key 默认续 cursor，fromStart 从头；自由复习日志不计入队列 todayReviewed | app 集成 1/1：4 词里 1 暂停 → total 3；KNOWN → REVIEW 且 session 日志 1 条；再次进入 cursor=1、fromStart cursor=0；DONT_KNOW+PAUSE → cursor=3 done、对应词 removed；小结 seen=3/known=0/unknown=1/paused=1；队列 todayReviewed=0。**残余**：自由复习前端页（卡片、S 键、结束页、续位提示与三个出口）下一轮补 |
| 2026-10-23 | T404 自由复习前端（补完后端） | 完成：词库工具条「复习这些词（N）」（按当前筛选、排除已暂停、≤1000）收集 wordIds 建会话；会话页复用两档评分 + S 暂停 + 随时结束；结束页显示 看了多少/认识/不认识/暂停，三个出口（返回词库 / 继续复习剩余词 / 去看今日复习）；进入时显示「上次看到第 N / 总数」；后端补 `POST /{id}/continue` | `pnpm run build` 通过、i18n 对齐、check-ui 无新增违规、web 测试 33/33；后端会话用例 1/1 已绿。**残余**：续位/结束页的浏览器验收并入 T412/T413 |
| 2026-10-23 | T406 词集后端 | 完成：`/api/vocab/sets` CRUD、批量 ACTIVATE/DEACTIVATE/DELETE、加词去重（规范化+≤64）、词条按 position 排序与重排、移除只删 `vocab_set_words` 行、软删除；仅 TEACHER/ADMIN，非本人 404；单集词数上限可配 | app 集成 1/1：创建/重名 409、加词 added2/skipped2、排序生效、移除后 wordCount=1、停用后批量启用、软删后列表空且详情 404、学生 403；三守卫通过 |
| 2026-10-23 | T407 词集前端 | 完成：表格视图（名称/语言/词数/状态/已发班级/更新时间/操作 + 复选框与批量启停删）、新建/编辑页（单栏：名称/语言/说明 → 解析预览 + 加入词集 → 取消/保存 → 词条列表，含上移下移与移除）；后端 SetView 补 assignedClasses 列 | `pnpm run build` 通过、i18n 对齐、check-ui 无新增违规、web 测试 33/33；后端词集用例 1/1 已绿；取消返回列表、空态文案与「移除只移出词集」提示已实现。残余：浏览器视觉验收并入 T412/T413 |
| 2026-10-23 | T408 发词（后端） | 完成：`POST /api/vocab/assignments`（词集快照 + 班级 + 收件人快照 + 可选截止/说明；不属于自己的班 404；通知失败只记 warn 不回滚）、`GET /assignments/pending`、`POST /{id}/claim`（按快照入库、来源 ASSIGNMENT、重复领取幂等、软删词恢复）；无新迁移 | app 集成 1/1：wordCount=2/recipient=1；发送后再往词集加词，领取仍只有快照的 2 个；重复领取 added=0/duplicated=2 且 ASSIGNMENT 来源仍 2 条；非本人班 404、学生发词 403；三守卫通过。**残余**：发词页 UI（可搜索词集 + 班级平铺多选 + ⓘ）与待办横幅 —— 任务拆分里没有承接，记入 T413 前补齐 |
| 2026-10-23 | T409 发词记录 | 完成：后端 `GET /api/vocab/assignments`（按发送时间倒序，班级数/收件数/已领数实时聚合）；前端词集页第二 tab「发词记录」表（词集/班级数/发送时间/截止/已领·收件/复制链接） | app 集成扩展断言：发送后 1 条记录、recipient=1/claimed=0；学生领取后 claimed=1；build、i18n、check-ui、web 测试全绿。**残余**：新建布置的 UI（可搜索词集 + 班级平铺多选 + ⓘ）仍未做，记入 T413 前补齐 |
| 2026-10-23 | T410 TTS 缓存与端点 | 完成：`TtsEngine`（Piper HTTP 2s 超时 / stub）、`GET /api/vocab/audio?form=` 缓存→本地引擎→失败 404 交给浏览器、`GET /audio/{key}` 只读缓存；键 = sha256(规范化词|provider|voice|rate)；`ETag` + `Cache-Control: immutable`；前端 `SpeakerButton` 接在详情页、复习页、词集编辑词条三处，失败回落 SpeechSynthesis | app 集成 1/1：首播调 stub 1 次、二次播放（大小写不同）不再调引擎、按 ETag 取缓存、未知 key 404、只读单词（lastText=apple）；build/i18n/check-ui/web 测试全绿。**残余**：T411 Piper sidecar 容器与健康检查 |
| 2026-10-23 | T411 Piper sidecar | 完成：`docker/piper-sidecar.py`（127.0.0.1:5002，`/health` 模型存在=200/缺失=503，`/synthesize` 调 piper CLI 返回 audio/wav）、`docker/entrypoint.sh` 按 `RUN_TTS_SIDECAR` 启动 sidecar、Dockerfile 安装 piper-tts + 下载 en_US-amy-medium 模型、compose healthcheck 同时探 app 与 sidecar | 本地 sidecar 健康检查实测：模型存在 200、模型缺失 503；真实 Docker 镜像构建/容器内 curl **未执行**（需网络下载模型），断 sidecar 时 T410 端点返回 404、前端 SpeakerButton 回落浏览器（代码路径已测）。**残余**：有网络环境时复核镜像构建与 `curl 127.0.0.1:5002/health` |
| 2026-10-23 | T412 集成与性能 | 完成（性能用例新增）：1 万词队列 **p95=25ms**（40 采样）；导入 **1000 行 681ms / 10000 行 5.3s**；越权 404、软删恢复、自由复习续位、幂等评分由既有 app 集成用例覆盖；修掉 TTS 测试被 `./uploads` 持久缓存命中的缺陷（唯一词形） | 全量 `make test`（6m32s）：121 个 app 用例中 2 失败——TTS（已修并单跑绿）、RateLimitTest（单跑绿，判断为 full-suite 抖动）；**内存**只测了导入前后堆差（1000 行 0MB / 10000 行 1MB），**不是峰值**；当前导入非流式，AC-48.9「峰值不随行数线性增长」仍有风险，需流式解析改造。全量复跑与边界取证放 T413 |
| 2026-10-24 | T413 回归与收尾 | **部分完成（代码与测试绿、已合 main；AC 勾选与默认开关暂缓）**：全量 `make test` **375/0/0/0**（6m30s）+ web 33/33 + 词表脚本 8/8；四守卫通过（47 表归属、i18n 1489 键对齐、check-ui 无新增）；`git diff --stat main...HEAD -- paper sitting grading report` **0 行**；分支 `feat/vocab-b-import` 已并入 main | **未完成/未验证**：① 导入/加词 UI 与发词页 UI（任务拆分遗漏）；② T400 预生成按决策暂缓；③ AC-48.9 峰值内存（当前导入非流式，仅测到前后堆差）；④ T403/T404/T407 浏览器验收、T411 容器内 sidecar 验收；⑤ `teachloop.vocab.enabled` 保持 **false**（缺口未闭，不放占位承诺）。待上述收口后再勾 AC 与翻默认开关 |
