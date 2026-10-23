# 技术方案 — TeachLoop（公网 AI 教学平台，v2，Java 版）

- 项目名：**TeachLoop**（2026-10-19 定名；仓库 `teachloop`，groupId / 包根 `com.teachloop`）
- 特性编号：`002-v2-platform`
- 上游：`spec.md`（29 用户故事 / 270 验收标准 / 70 功能需求）、`.specify/memory/constitution.md`（v2.1）
- 日期：2026-10-19（本版为整份重写，非增量修补）
- 技术栈变更说明：早期方案用 Python/FastAPI，理由是复用 v1 资产与 `dsh` 子进程。用户 review 后确定部署阿里云、取消 OCR、生产直连模型 API，这两条理由都不再成立；且用户最熟悉 Java。故整体改用 Java。
- 名字为什么叫 TeachLoop：`loop` 同时指两件事——批改反馈的闭环（出卷→作答→批改→复核→发布），与将来背单词模块的间隔重复循环。所以加模块不需要改名。

---

## 0. 怎么评审这份文件

Plan 的退出条件是下面 6 条全部打勾。每条标注了自评结果与验法，评审时直接照这条走。

| # | 检查项 | 自评 | 验法 |
| :--- | :--- | :--- | :--- |
| 1 | 每个用户故事在本文件里有承接结构 | ✅ | 附录 A 逐条对照 29 个故事。**追溯粒度到故事与 FR**：258 条 AC 不逐条映射（理由见 `tasks.md` 追踪矩阵开头） |
| 2 | 每个实体的表、字段、索引、状态迁移写全 | ✅ | §5（含 `user_ai_settings` / `offerings.created_by` / `submission_metrics` / `reviews` AI 溯源列）；状态机见 §7 与 §7.6 |
| 3 | 每条外部依赖写了失败与重试路径 | ✅ | §6.4 失败处理、§6.6 Key 失败分类（NO_KEY/AUTH/TIMEOUT/INVALID_OUTPUT/RATE_LIMITED）、§7.2 崩溃恢复、§9 上传、§15 字体自检 |
| 4 | 模块依赖无环、跨模块只走端口 | ✅ | §3.1 编译期强制；§3.2 端口表；T203 专门验证 |
| 5 | 每张表只有一个属主、无跨模块 JOIN | ✅ | §3.3 归属清单；T204 验证越界写入与 `@Entity` 泄漏 |
| 6 | 拆服务路径写得出来 | ✅ | §3.5：哪两个进程、先拆谁、外键清单 |

1–3 回答「能不能做出来」，4–6 回答「做出来会不会烂」。这两组必须分别问——只问前者，后者会一条都发现不了。

---

## 1. 架构总览

承接：US-1 ~ US-26 全部（部署与运行形态）

```
浏览器 (React 18 + TS + i18next)
   │ HTTPS
   ▼
Nginx (TLS, 静态资源, /api 反代)
   ▼
Spring Boot 4.1 应用（fat jar，1..N 实例，profile=app）
   ├── Spring Security + Spring Session JDBC（会话入库，可吊销）
   ├── 业务层（试卷 / 布置 / 提交 / 批改 / 复核 / 报表 / 审计）
   ├── 客观题判分（纯代码 + 可扩展匹配策略，不调模型）
   ├── 进度由前端每 2 秒轮询（无长连接、无 SSE）
   ├── Spring AI → 任意 OpenAI 兼容端点（DeepSeek / 本地 Ollama / LM Studio / vLLM）
   └── StorageBackend（OSS / 本地）
   ▼
PostgreSQL（RDS）—— 唯一中间件：业务数据 + 工作项 + 限流 + 会话
   ▼
Worker 进程（同一 jar，profile=worker）—— 独占批改工作项并调用模型
定时（@Scheduled + 数据库条件更新）—— 到期派发、僵尸清扫、到点自动交卷、每日汇总
```

**关键取舍：不引入 Redis / RabbitMQ / Kafka。** 单校规模（数百到数千份/次）下：

- 批改工作项不需要独立队列——`submissions` 行本身就是工作项，条件 UPDATE 领取即可（§7.1）
- 进度不需要事件广播——前端轮询一条聚合 SQL 即可（§8）
- 会话不需要缓存——Postgres 主键查 155 µs，而缓存会留下"退出后仍可用"的窗口（§5.1）

少一个中间件 = 少一套运维、少一笔费用、少一个故障源。

---

## 2. 技术栈

承接：全局

| 层 | 选型 | 说明 |
| :--- | :--- | :--- |
| 语言 | **Java 21 (LTS)** | 本机已装 21.0.11 |
| 框架 | **Spring Boot 4.1.1**（已验证） | Spring Framework 7.0.9 |
| 构建 | **Maven**（聚合 POM + 九模块） | 用户选定 |
| Web | Spring MVC | 进度用轮询，不维护长连接 |
| 持久化 | Spring Data JPA + **Flyway** | 迁移可审阅、可按模块分目录 |
| JSON | **Jackson 3.1.5**（包名 `tools.jackson`） | Spring Boot 4 已从 `com.fasterxml.jackson` 迁到 Jackson 3，**两者不可混用** |
| 驱动 | PostgreSQL JDBC | |
| 会话 | **Spring Session JDBC** | 多实例共享、可服务端吊销 |
| 安全 | Spring Security + BCrypt + Cookie CSRF（双提交） | |
| AI | **Spring AI 2.0.1** + `spring-ai-starter-model-deepseek`，模型名 **`deepseek-flash`** | `deepseek-chat` 是旧名，已下线 |
| 调度 | `@Scheduled` + **数据库条件更新** | 多实例互斥；不引入独立锁表（详见 §7.3） |
| 富文本 | **OWASP Java HTML Sanitizer** | 经过审计的白名单实现 |
| 文档解析 | Apache **PDFBox**（pdf）/ Apache **POI**（docx） | 不做 OCR |
| 导出 | **OpenPDF**（pdf）/ POI（xlsx、docx） | 纯 JVM，无 pango/cairo 依赖 |
| 存储 | 阿里云 OSS SDK | `StorageBackend` 抽象，`LocalStorage` 供开发 |
| 视频探测 | 外部 `ffprobe` 子进程 | 唯一的外部二进制依赖 |
| 测试 | JUnit 5 + **Testcontainers**（已验证） | 2.x 改了构件坐标，旧坐标需显式锁 `1.21.4` |
| 前端 | React 18 + TS + Vite + react-i18next | 从 v1 迁移，语言不变 |

### 本机环境现状

| 项 | 状态 |
| :--- | :--- |
| JDK 21.0.11 | ✅ 已装（Homebrew `openjdk@21`） |
| `JAVA_HOME` | ⚠️ 必须显式设为 `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`（Homebrew JDK 未注册进 `/usr/libexec/java_home`） |
| Maven | ⚠️ 未全局安装，用 Maven Wrapper 或工作区内二进制 |
| `ffprobe` | ✅ 已装 |

---

## 3. 代码组织（模块化单体）

承接：US-1 ~ US-26（边界）+ 未来模块接入

项目名 **TeachLoop**（用户 2026-10-19 定名）。GitHub 仓库 `teachloop`，groupId `com.teachloop`，Java 包根 `com.teachloop`。九个子模块摆在仓库根下，不再套一层目录——仓库本身就是这个 Java 工程。

`batch-grading/`（v1 Python 实现，823MB）已于 2026-10-19 删除（移入废纸篓），不再保留。它的设计结论已经被本文件吸收，代码不可复用。

**为什么不拆微服务**：现在是一个教师、几十个学生、一台 ECS。拆服务的代价（网络、部署、分布式事务、链路追踪）立即要付，收益（独立扩容）现在用不上。**用 Maven 多模块拿到编译期的边界，保留以后拆服务的可能**——模块之间不共享实现类、不互相查表，将来某个模块要独立，把它连表带迁移一起搬走就行。

```
teachloop/                          # 仓库根 = Maven 聚合 POM
├── pom.xml                         # <modules> 九个
├── teachloop-shared/               # 无业务：错误码、i18n、Clock、审计原语、JSON、分页约定
├── teachloop-identity/             # 认证与账号：用户、会话、角色、资料补全、限流
├── teachloop-classroom/            # 班级：班级、成员、邀请码
├── teachloop-paper/                # 出卷：试卷、部分、题项、标准答案、rubric、导入拆分
├── teachloop-sitting/              # 答卷：布置、参与班级、计时、作答、草稿、提交
├── teachloop-grading/              # 阅卷：客观判分、AI 主观、错题解析、复核、发布
├── teachloop-report/               # 报告：统计、错题 Top N、导出（md/pdf/xlsx/docx）
├── teachloop-observability/        # 可观测：请求日志、前端事件、daily_stats、审计查询
├── teachloop-app/                  # 唯一启动模块：Spring Boot、Controller、装配
├── web/                            # React 前端
├── specs/                          # 规范与方案（本文件所在处）
└── AGENTS.md
```

### 3.1 依赖方向（编译期强制）

```
teachloop-app ──▶ paper / sitting / grading / report / observability
                        │
                        ▼
                   classroom ──▶ identity ──▶ shared
```

`shared` 不依赖任何业务模块；`identity` 与 `classroom` 不依赖出卷/答卷/阅卷。反向依赖与循环依赖**编译不过**——这是选多模块而不是多包的唯一理由。

### 3.2 跨模块只走端口

| 谁要什么 | 走哪个端口 | 说明 |
| :--- | :--- | :--- |
| 答卷要读试卷内容 | `PaperCatalog`（paper 暴露，只读快照视图） | 布置时拿快照（AC-26.6），不回查活表 |
| 阅卷要读作答 | `SittingLedger`（sitting 暴露，只读） | 阅卷不 join `sitting_*` 表 |
| 阅卷要写分数 | `GradingOutcome`（grading 暴露，sitting 调用写入） | 写回经端口，不由阅卷直接改表 |
| 阅卷要领取/推进工作项 | `GradingWorkQueue`（sitting 暴露，命令端口） | `submissions.grading_state` 归 sitting，阅卷不得直接 `update`；方法必须幂等（换成 MQ 后至少一次投递不重复扣分） |
| sitting 要派发一场考试去批改 | `GradingDispatch`（grading 暴露，命令端口） | 一个 offering 只产生一条 `grading_runs`；重复调用返回同一 run（幂等） |
| 报告要跨模块统计 | 允许跨模块**只读**聚合 | 唯一豁免，且只能读不能写 |
| 任何模块要发通知 | `NotificationPort`（identity 暴露） | 学生名单与联系方式只在 identity / classroom |

**禁止跨模块 JOIN 是硬规则。** 代价是偶尔多一次查询（本地 Postgres 主键查 155 µs，实测见 §5.1），换来的是模块可搬走。

### 3.3 表归属与迁移

每张表**只属于一个模块**，只有属主能写。用归属清单而不是字符串前缀来约束（前缀是装饰，归属清单可检查）。

| 模块 | 拥有的表 | 迁移目录（模块内） |
| :--- | :--- | :--- |
| identity | `users`、`credentials`、`spring_session`、`spring_session_attributes`、`teacher_invites`、`rate_limits`、`notifications`、`ai_settings`、`user_ai_settings` | `teachloop-identity/src/main/resources/db/migration/` |
| classroom | `classes`、`class_members` | `teachloop-classroom/…/db/migration/` |
| paper | `papers`、`paper_sections`、`objective_items`、`section_criteria` | `teachloop-paper/…/db/migration/` |
| sitting | `offerings`、`offering_classes`、`submissions`、`objective_answers`、`submission_metrics`、`teacher_grading_preferences` | `teachloop-sitting/…/db/migration/` |
| grading | `grading_runs`、`reviews`、`item_explanations`、`ai_calls` | `teachloop-grading/…/db/migration/` |
| report | 无表（只读） | — |
| observability | `audit_logs`、`daily_stats` | `teachloop-observability/…/db/migration/` |

规则：

- Flyway 在 `teachloop-app` 统一调度，按上表顺序执行；文件名 `V<序号>__<模块>_<描述>.sql`。
- **一条迁移只写一个模块的表**。跨模块写即为越界，属 Code Review 检查项，T204 自动验证。
- 跨模块外键**允许**（单库，完整性与级联删除需要它），但**禁止跨模块 JOIN 读数据**——读要走 §3.2 的端口。这个不对称是刻意的：**外键是数据完整性，JOIN 是耦合**。

### 3.4 未来模块怎么接（背单词是第一个试验）

计划中的「背单词 + 间隔重复」模块，按现有边界只需：

- 新增 `teachloop-vocab/`，依赖 `shared` + `identity` + `classroom`，**不依赖 paper / sitting / grading**；
- 自带表 `vocab_wordlist`、`vocab_item`、`vocab_review`、`vocab_schedule`（SRS 的 `due_at`、`interval`、`ease`、`reps`）；
- 复用：登录与会话、班级成员名单（"把词表发给某班全体学生"就是一次 classroom 的成员查询）、通知、审计原语、请求日志；
- 前端加一个一级导航与一组路由。

**验收标准**：接入背单词时，**阅卷模块的文件改动数必须为 0**。做不到就说明边界划错了，先改边界再动功能。

### 3.5 拆服务的路径（V1 不做，但每个决定都为它让路）

V1 的形态是**一个 JAR、一个 Postgres、两个进程**（`app` 与 `grading-worker`，见 §7）。模块之间是**进程内 Java 方法调用**，不是 HTTP。一个进程里的两个模块走 HTTP 是最差组合：付了网络的代价（序列化、超时、重试、链路追踪），却拿不到独立部署的好处。

拆服务真正难的三件事都不是"包怎么分"：

| 难点 | 拆服务时会怎样 | 现在怎么处理 |
| :--- | :--- | :--- |
| 共享表 | 两个服务读写同一张表，必须先拆数据 | 一表一属主、不跨模块 JOIN。拆时每个模块连 Flyway 目录一起搬走，**不需要拆数据** |
| 跨模块外键 | 跨库外键不存在，迁移时必须全部删 | 允许，但**限清单**（下表）。拆时按清单逐条删；新增一条要在这里登记 |
| 跨模块事务 | 审计与业务同事务，跨服务做不到 | 审计收敛到 `AuditPort` 一个入口。拆服务时换实现为"每个服务写自己的 audit 表"，报表层 union |

端口的三条约束是专为拆服务设的：

- **粗粒度**：一次端口调用将来就是一次 RPC。要 `essaysOf(ids)`，不要 `essayOf(id)` 循环。
- **只传 record**：端口签名里的 DTO 必须是 `record`，不得是 `@Entity`、不得带懒加载、不得泄漏 `EntityManager`。否则将来序列化不过网络。
- **查询与命令分开**：查询端口将来变 gRPC/HTTP；命令端口将来变消息队列——消息是至少一次投递，所以**命令类端口必须幂等**。

拆的顺序不是"9 个服务一起拆"：

1. **V1**：`app` + `grading-worker` 两个进程。
2. AI 批改成为瓶颈 → worker 独立扩容，端口调用换队列消息（§7.5 三条约束已保证可换）。
3. 报表压库 → `report` 拆为独立服务，只读副本。
4. **不拆**：`identity`、`classroom`、`paper`、`sitting`。强事务、低压力，拆开只增成本。

跨模块外键清单（拆服务时要删的那几条，新增需在此登记）：

| 外键 | 属主 | 被引用 |
| :--- | :--- | :--- |
| `classes.teacher_id` → `users.id` | classroom | identity |
| `class_members.student_id` → `users.id` | classroom | identity |
| `papers.author_id` → `users.id` | paper | identity |
| `offerings.paper_id` → `papers.id` | sitting | paper |
| `offering_classes.class_id` → `classes.id` | sitting | classroom |
| `submissions.student_id` → `users.id` | sitting | identity |
| `objective_answers.item_id` → `objective_items.id` | sitting | paper |
| `grading_runs.submission_id` → `submissions.id` | grading | sitting |
| `grading_runs.offering_id` → `offerings.id` | grading | sitting |
| `reviews.submission_id` → `submissions.id` | grading | sitting |
| `reviews.teacher_id` → `users.id` | grading | identity |
| `item_explanations.item_id` → `objective_items.id` | grading | paper |
| `audit_logs.actor_id` → `users.id` | observability | identity |

`notifications.recipient_id → users.id` 不在清单里：两张表同属 identity，拆服务时一起搬走。

> 2026-10-19（T204 落地时补齐）：原清单 9 条漏登记了 `papers.author_id`、
> `objective_answers.item_id`、`reviews.teacher_id` 三条已存在的外键；同一次登记了
> T092 新增的 `grading_runs.offering_id`。当前清单 13 条，`scripts/check-boundaries.py`
> 以此表为唯一真源校验。

---

## 4. 可替换边界（宪法第六条）

承接：US-1（认证）、US-10（引擎）、US-14（导出）、US-18（解析）、US-5（匹配）

| 边界 | 接口 | 实现 |
| :--- | :--- | :--- |
| 认证 | `AuthProvider` | `WeChatOpenProvider` / `AdminPasswordProvider` / `DevProvider` |
| AI 引擎 | `GradingEngine` | `SpringAiGradingEngine`（生产）/ `StubGradingEngine`（测试与降级） |
| 存储 | `StorageBackend` | `OssStorage` / `LocalStorage` |
| PDF | `PdfRenderer` | `OpenPdfRenderer` |
| 匹配策略 | `MatchStrategy` | 注册表，内置 4 种，可扩展 |
| 解析生成 | `ExplanationEngine` | `SpringAiExplanationEngine` / `StubExplanationEngine` |
| 日志去向 | `LogSink` | `FileLogSink`（MVP）/ `ElasticsearchLogSink`（可选） |

---

## 5. 数据模型

承接：US-2、US-4、US-5、US-6、US-7、US-8、US-9、US-11、US-16、US-18、US-22、US-26、US-27、US-28

由 **Flyway** 管理，**按模块分目录**（归属清单见 §3.3）。本节只描述表结构；哪个模块拥有哪张表由 §3.3 规定，跨模块读写规则见 §3.1–3.2。

### 5.0 三张表表达"试卷与班级解耦"

```
papers ──< paper_sections ──< objective_items
   │                     └──< section_criteria
   │
   └──< offerings ──< offering_classes >── classes
           (paper_id 可空)     │
                               └──< submissions >── users
```

- `papers`：标题、`default_duration_minutes`、总分、版本、状态。**没有 `class_id`。**
- `offerings`：`paper_id`（**可空**：考试必填、作业必须为空）、`kind`（`exam` / `assignment`）、`start_at`、`due_at`、`duration_minutes`（仅考试，默认取试卷默认值）、`timing_mode`（`individual` / `fixed_window`，仅考试）、`grace_hours`、`max_attempts`、`publish_scores`、`feedback_language`、**`paper_snapshot`**（布置瞬间的**内容**快照 JSONB + `paper_version`）。
- `offering_classes`：`offering_id`、`class_id`、可空的 `due_at_override` / `duration_override`。唯一键 `(offering_id, class_id)`。
- `submissions` 外键挂 `offering_id`；唯一索引 `(offering_id, student_id) WHERE active`。

**为什么快照必须落库**：教师在考前一周还会改标准答案。若不快照，改答案会追溯改变已经考完的 90 份卷子的分数——这是无法用审计解释的事故。

**D-14（2026-10-20）：作业与考试在数据层分开。** 作业不引用试卷，内容来自布置表单；
创建时把内容快照成与考试同构的形状，从而**阅卷、学生端、汇总、导出都不需要分支**：

- 快照里放 1 条 `ESSAY` 题项：`number=1`、`points=总分`、`stem=题目要求`、`sectionTitle="作业要求"`；
  `PaperSnapshot(paperId=offeringId, paperVersion=0, title=标题, totalPoints=总分, durationMinutes=0, items=[该题项], rubricMarkdown=评分标准)`。
- 作业没有 `objective_items` 行，也没有 `duration_minutes` / `start_at` / `deadline_at`；
  错题解析、客观重算、考试计时三条链路天然不命中。
- 数据约束：`offerings_kind_content_ck CHECK ((kind = 'ASSIGNMENT') = (paper_id IS NULL))`。
  存量作业在 V026 迁移里回填 `paper_id = NULL, paper_version = 0`（内容已在该场快照里，不丢）。
- 创建 API：`POST /api/offerings` 的 `kind` 由内容来源派生（`paperId` 有 → 考试，无 → 作业）；
  请求里若显式给 `kind` 且与来源不一致 → 400，避免旧客户端静默建错。

关键约束：

- `offerings` 内容来源约束：`offerings_kind_content_ck CHECK ((kind = 'ASSIGNMENT') = (paper_id IS NULL))`。
- `offerings` 增 `created_by`（创建教师；与 `papers.author_id` 一样由应用写入，**不加跨模块外键**，
  拆服务时随 offering 走）；AI Key 归属用它（见 §6.6）。
- `submissions` 部分唯一索引：`(offering_id, student_id) WHERE active`。
- `submissions` 计时与迟交列：`started_at`、`deadline_at`、`auto_submitted`、`late_by_seconds`、`rejected_at`、`reject_reason`。
- `submissions` 工作项状态列（替代独立队列表，见 §7）：`grading_state`、`grading_attempts`、`grading_started_at`、`grading_error`、`grading_run_id`。
- 篇幅指标不新增表：`TextLengthCounter` 在判分与复核详情里按 `content_text` 现算（policy_default 段数 + 四模型边界标记）；数据量是单班几十份，无需为它建表。
- `ai_settings` 保持全局单行（id=1）；`user_ai_settings`（`user_id` 主键、provider/display_name/base_url/model/api_key_cipher/temperature/updated_at）是教师自定义 provider；两者都只存密文。`display_name` 是 V038 新增（D-30）。
- 新增 `teacher_grading_preferences`（V037，D-30）：`teacher_id` 主键、`trigger_mode`（FIXED_TIME / DUE_DATE）、`fixed_time`、`time_zone`、`catch_up`、`last_fixed_run_at`、`updated_at`、`updated_by`。不建跨模块外键；没有行时按默认 02:30 Asia/Shanghai 解释。
- `reviews` 增 `ai_overall`（总评）、`ai_model`、`ai_prompt_version`、`ai_key_source`、`ai_failure`（失败分类）、`feedback_language`（本次 AI 实际输出语言）；`ai_criteria` / `ai_feedback` 保留。
- `ai_calls` 增 `key_source` 与 `prompt_version`（已有）；不记录 Key 本体。
- `objective_items` 唯一 `(paper_section_id, number)`；增列 `stem`（题干）、`options`（选项 JSONB）、`question_type`（`single_choice` / `multiple_choice` / `fill_blank` / `essay`）、`answer_key`、`match_strategy`、以及解析工作项状态 `explanation_state` / `explanation_attempts` / `explanation_started_at` / `explanation_error`。
- `objective_items` 再增两列（V036，D-24 / D-25）：`rubric_markdown`（仅 `ESSAY` 有值，题项级评分标准）、
  `reference_markdown`（仅 `ESSAY`，教师端参考范文）。两列可空、无索引；**题项 rubric 的分值只约束本题，
  不进 `papers.total_points`**（总分公式仍是 Σ item.points + Σ section_criteria.max_points）。
  写入用 setter（构造器不扩参），避免动既有调用点。
- 写作题由导入器建为 `ESSAY` 题项（D-23）：有「第X节」按节一题、否则按带题号的写作任务行一题；
  没有显式题号时取全卷最大题号 +1。题干保存任务说明与卷面给出的开头/落款，纯下划线作答行剔除。
- 旧口径作废：D-12 里「主观题不产题项、落成评分要点」的部分自 D-23 起失效（D-12 其余内容——材料并入题干、
  多部分切分——仍有效）。
- `item_explanations` 唯一 `(item_id, language, prompt_version)` —— 这是「同一题只调一次模型」的保证。
- 枚举用 PG enum 或 check 约束；新增值必须有迁移，不手改线上。

### 5.1 数据放置原则

承接：US-16、US-17

**两类东西，两个生命周期，不能混在一个库里。**

| | **审计** | **运行日志 / 前端事件** |
| :--- | :--- | :--- |
| 性质 | 证据链 | 运行痕迹 |
| 保留 | **永久，不删除**（可归档冷存） | 90 天，到期清 |
| 一致性 | **与业务写入同一事务** | 不需要 |
| 存储 | 业务库（Postgres） | **文件 + 每日汇总表** |

审计**绝不能**异步写文件：一旦异步化，崩溃窗口里就会出现「有分数、没审计」，直接违反宪法第二条。这意味着审计表跟着业务库一起备份、一起迁移——这是成本，但正确。

**实测（2026-10-18，本地 Postgres 16 + 本机 SSD，各 1000 次）**

| 操作 | 耗时 |
| :--- | :--- |
| 主键查询（会话） | 155 µs |
| 30 行聚合（进度） | 148 µs |
| 单行 INSERT | 188 µs |
| 批量 INSERT（1000 条/批） | **7 µs/条** |
| JSONL 文件追加 | **1.7 µs/行** |

**结论：单条写比批量写慢 27 倍，批量写比写文件慢 4 倍。第一优化方向是「攒批」，不是「把数据搬走」。**

**体量（一学期约 120 天）**

| 数据 | 行数 |
| :--- | :--- |
| 业务数据合计（提交/作答/复核/AI 调用/审计） | ≈ 25,000 |
| 前端事件 | ≈ 72,000 |
| 请求日志（1 万次/天） | ≈ 1,200,000 |

真正撑的不是业务表，是请求日志。放进业务库的四个实际问题（与"撑不撑爆"无关）：

1. 保留期不同——日志 90 天、成绩永久，同库无法分开治理
2. 备份被污染——每次备份都把日志一起带上
3. 清理耦合——大批 DELETE 产生膨胀与 vacuum 压力
4. 查询模式冲突——日志是全表扫描聚合，业务是主键索引查

**方案：原始写文件 + 每日汇总入库**

```
原始事件 / 请求日志 → JSONL，按天滚动，90 天后删除文件
每日任务（凌晨）    → 聚合昨天的数据，写一行进 daily_stats
报表               → 查 daily_stats（纯 SQL，快且简单）
明细排查           → 翻当天的文件
```

`daily_stats(stat_date PK, page_views, actions, submissions, gradings, errors, slow_requests, total_requests, p95_latency_ms)`

**为什么不引入 Kafka**：Kafka 解决「多生产者、高吞吐、多消费者组」。我们是一个应用写、几个用户产生事件、一个消费者做汇总——引入集群运维、位点管理、序列化治理，换不来收益；而写本地文件只要 1.7 µs/行。

**为什么不引入 KV 数据库**：`item_explanations`（错题解析缓存）本身就是标准 KV 用法——按 key 查一份文本，Postgres 主键查 155 µs。嵌入式 KV（RocksDB/MapDB）会引入第二个一致性域，备份、迁移、测试的成本大于收益。

**会话不缓存**：会话是唯一「每请求一次」的读，但 spec 要求退出后**立即失效**。加缓存会留下最多一个 TTL 的窗口期——学生被踢下线后仍能继续操作。用 155 µs 换这个风险不划算。

---

## 6. 判分流程

承接：US-5、US-9、US-10、US-11、US-18、US-27、US-28

```
学生提交
 ├─ 客观部分：写入 objective_answers → 按每题 question_type 与 match_strategy 判分 → objective_total
 │    └─ 立即返回得分与错题清单（不调模型）
 ├─ 富文本：HTML 消毒 → 提取纯文本 → 客观指标
 ├─ 文件：PDFBox / POI 提取文本
 └─ 视频：ffprobe 校验（≤360s、≤1920×1080），无转写

due_at + grace_hours 到达 → 派发（见 §7）

Worker 执行一份：
 ├─ 条件 UPDATE 领取该行（grading_state pending -> running）
 ├─ 校验 paper_version，不一致则重取快照
 ├─ 客观分：按标准答案判分（不调模型），主观分逐篇累加
 ├─ 解析 Key：作业创建者 → 全局；都没有 → NO_KEY，不出分（§6.6）
 ├─ 对该份里的每道 ESSAY 题（D-26，§6.8）：
 │    ├─ 取正文：answers[] 里该题号的作答（全卷仅 1 道 ESSAY 时才回退 content_text）
 │    ├─ 空白作文 → 0 分、不调用、评语「未作答」
 │    ├─ 取本题 rubric（item.rubric_markdown，空则试卷级 rubric）+ 本题满分
 │    ├─ 读题目要求（stem）+ 学生语言 + 该篇段数（policy_default）与边界标记（§6.7）
 │    ├─ 组装 prompt：任务背景 + 本题 rubric + 评分政策 + 可选锚点（system）
 │    │               + 客观指标 + 反馈语言 + 该篇正文（user）
 │    │    （不含标准答案、不含参考范文、不含其他题的 rubric）
 │    ├─ Spring AI ChatClient → deepseek-flash，输出用 Jackson 3 反序列化并校验
 │    ├─ 校验：项名/顺序/满分/总分 + 原文引用 + 语言 + 长度结论 + 二元/分档；失败重试一次；
 │    │    仍失败 → 整份标 failed、写 ai_failure、保存原始输出，不出分
 │    └─ 记下本篇结果（分数、逐项、评语）
 ├─ 合并：主观分 = Σ 各篇；逐项带 itemNumber；评语/优点/建议按「第 N 题（X 分）」分段
 ├─ 写 reviews（AI 草稿：合并后的主观分、逐项意见、overall、优点、建议、model/prompt_version/key_source）
 └─ 写回 grading_state=done（前端轮询看到）
```

**AI 看不到客观答案**这一条用代码结构强制：`SubjectiveGradeRequest` 记录类里根本不存在 `answers` 与 `objectiveTotal` 字段，`buildPrompt` 只能拿到该类型。

### 6.1 Prompt 分层契约（2026-10-22 v3：逐篇）

Prompt 必须严格分两层，**顺序不可变**；v2 把「任务背景、证据、评分政策、锚点」放进 system，
并把长度结论改为服务端计算：

| 层 | 内容 | 变化频率 |
| :--- | :--- | :--- |
| `system` | 角色与受众 + **作业题目要求（`instructions`/`stem`）** + 完整 rubric（名称/满分/判定规则/分档） + 评分政策 + 可选锚点 + 输出契约 | 同一条作业内**完全不变** |
| `user` | 客观指标（词数、段数、长度政策、边界敏感、时长）+ **本次反馈语言** + 学生正文 | **每份都不同** |

实测请求体（写作题、一位学生）：

```json
{
  "model": "deepseek-flash",
  "temperature": 0.2,
  "response_format": { "type": "json_object" },
  "messages": [
    { "role": "system",
      "content": "You are an experienced TESOL instructor writing a first-pass review; the teacher makes the final decision.\n\n## Assignment\n{instructions / stem}\n\n## Rubric (grade only against this)\n1. Required Length — 2 points: {rule}\n2. Clear Position — 4 points: {rule}\n3. Explanation and Supporting Details — 4 points: {rule}\n\n## Grading policy\n- Award 0 or the full points when the rubric says yes/no; no invented partial credit.\n- Judge the quality of the support, not the viewpoint.\n- Quote a short phrase verbatim from the response in every criterion comment.\n- Required Length: use the server count ({paragraphs} paragraphs, {policy}); do not recount.\n- Strength: one specific strength with evidence. Suggestion: one actionable next step.\n- Be concise; do not rewrite the response.\n\n## Optional anchors (only if teacher-confirmed)\n{0-3 anonymized exemplar reviews}\n\n## Output contract\n{\"criteria\":[{\"name\":\"...\",\"points\":0,\"maxPoints\":2,\"comment\":\"...\"}],\"total\":0,\"overall\":\"...\",\"strength\":\"...\",\"suggestion\":\"...\"}" },
    { "role": "user",
      "content": "## Objective metrics\nWord count 96; paragraph count 2 (class policy; boundary-sensitive: no); duration 540s.\n\n## Feedback language\nEnglish (the student's UI locale is en).\n\n## Student response\n{这位学生的作文全文}" }
  ]
}
```

**硬规则（违反即缓存失效，且很难在功能上被发现）**

1. 不变的内容必须位于最前面，从第 0 个 token 起稳定；锚点属于 system，因为同一条作业内不变。
2. **禁止**把每份都不同的内容放进前缀：学生姓名、学号、`submission_id`、提交时间、**反馈语言**
   （同一班内学生的界面语言可能不同，必须放在 user 消息；见 §6.6）。
3. `temperature` 固定 0.2（判分要稳定，不要发挥）。
4. `response_format` 固定 `json_object`。
5. **长度项不由模型判**：服务端把段数与政策写进 user，模型只能引用；输出校验会拒绝与代码结论
   不一致的 points。
6. **证据是契约的一部分**：每项 comment 至少包含一处正文逐字引用；编造引用判输出不合规。

**实现口径（2026-10-21，v2 修订）**：

- 2026-10-22 起 `PROMPT_VERSION` 升为 `subjective-v3`：每次调用只判**一道**作文，system 顶部带
  「本题：第 N 题（满分 X 分）」；本题 rubric 由题项级 rubric 提供，参考范文不进 prompt。
- `PromptBuilder.PROMPT_VERSION` 从 `subjective-v1` 升为 `subjective-v2`；`SubjectiveGradeRequest`
  增加 `instructions`、`paragraphPolicy`、`paragraphBoundarySensitive`、`feedbackLanguage`（学生的），
  仍然**不含**标准答案、客观分、学生姓名、学号。
- rubric 解析仍从 `rubricMarkdown` 开始（短期），但**缺分值不再均分**：创建时校验，遗留 rubric
  标「需补全」；等 `section_criteria` 进端口后再换成结构化数据（见 §5）。
- 锚点由 `offerings.anchor_ids`（或独立表）指向教师确认过的匿名范文；生成时拼进 system，
  超过预算（如 6k tokens）就截断到 2 份并记录；无锚点时整段省略，不留空标题。
- 输出新增 `overall`（1–2 句总评），用于预填教师 `comment`；`strength`/`suggestion` 预填
  `reviews.strengths` / `reviews.suggestions`。
- 模型调用走 `grading/ai/LlmClient`；**每次判分按 §6.6 解析 Key 并创建客户端**，不再在 Bean
  启动时把 Key 固化。`SpringAiLlmClient` 仍为默认，`DeepSeekLlmClient` 为 `http` 回退。
- `ai_calls` 记录 model / prompt_version / key_source / tokens / latency / 原始输出；
  网络与鉴权失败没有 tokens 可记，进日志与 `submissions.grading_error`，并保留失败原因以便复核页
  区分「缺 Key / 鉴权失败 / 超时 / 输出不合规」。

### 6.2 缓存策略与度量

DeepSeek 的上下文硬盘缓存**自动生效、无需代码开关**，但只认"从第 0 个 token 起相同"的前缀，且以 64 tokens 为存储单元（不足 64 不缓存）。

**官方现价（2026 版）**：

| | 空闲时段 | 高峰时段 |
| :--- | :--- | :--- |
| 输入（缓存命中） | 0.02 元/M | 0.04 元/M |
| 输入（缓存未命中） | 1 元/M | 2 元/M |
| 输出 | 4 元/M | 8 元/M |

**缓存命中与未命中的价差是 50 倍**（0.02 vs 1 元），不是早期公告里的 10 倍。所以 §6.1 的前缀排布纪律必须严格执行。

**已定：保留 Spring AI，保持技术栈统一（用户 2026-10-18）。** 代价是 Spring AI 2.0.1 的 `Usage` 只暴露 `promptTokens / completionTokens / totalTokens`，没有 DeepSeek 的 `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`——**缓存命中率只能从 DeepSeek 控制台看，不进我们的报表**。

**已定：不用 `.entity()`。** S-4 实测发现 `.entity()` 会自行追加格式指令，导致"实际发给模型的 prompt"与"我们记录的 prompt"不一致，与宪法第二条（可审计）冲突。统一走「我们的 prompt + `.content()` + 自己的 Jackson 校验 + 失败重试一次」。

### 6.3 为什么不"一次批全班"

| 方案 | 优点 | 问题 |
| :--- | :--- | :--- |
| 全班塞进一次调用 | rubric 只发一次 | 输出过长易截断或格式崩；一份出错整批重跑；UI 无法显示逐份进度；审计上说不清这次调用评的是谁 |
| 每 5 份一批 | 折中 | 重试粒度变粗；失败影响 5 份 |
| **逐份调用 + 前缀缓存（采用）** | 隔离性好、可逐份重试、进度可逐份查询、审计逐份清晰 | rubric 重复发送（被缓存抵消） |

成本量级（30 人班、rubric 600 tokens、正文 400、输出 300）：

| 项 | 每份 tokens | 单价 | 30 份合计 |
| :--- | :--- | :--- | :--- |
| rubric（第 2 份起命中） | 600 | 0.02 元/M | ≈ 0.0003 元 |
| 学生正文（未命中） | 400 | 1 元/M | ≈ 0.012 元 |
| 输出 | 300 | 4 元/M | ≈ 0.036 元 |

结论：整班约几分钱。**重复不是经济问题**，主要收益是命中缓存后首 token 延迟大幅下降。

**另一项更大的节省**：客观题永远不发送——标准答案、客观作答、客观得分均不进 prompt。一份 150 分的试卷，AI 只收到写作题那部分的 rubric 与学生的作文。

### 6.4 错题解析（题目级共享，不是学生级）

**这是与判分完全分开的一条路径。** 宪法第五条的唯一例外：生成解析时 AI 可以拿到题干、选项与标准答案（不看答案写不出"为什么错"），但**不得拿到学生身份、答错的是谁、或其他学生的任何数据**；解析也不参与任何计分。

| 环节 | 做法 |
| :--- | :--- |
| 入口 | 独立的 `ExplanationEngine`，与 `GradingEngine` 不共用调用 |
| 触发 | 主观题批改全部进入终态后，作为同一流程的下一步 |
| 选哪些题 | `objective_answers` 中 `is_correct=false` 的题，按**答错人次降序**取前 N（默认 **50**） |
| 去重 | `INSERT … ON CONFLICT DO NOTHING`，唯一键 `(item_id, language, prompt_version)` |
| 工作项 | `objective_items.explanation_state`，沿用 §7.1 的条件 UPDATE 领取模式 |
| 失败 | 记 `explanation_attempts` 与 `explanation_error`，超过 3 次置 `failed`，教师可手动重试 |
| 复用 | 学生反馈查表取解析，**零额外调用** |
| 发布 | **不等待解析**；解析到达后学生侧自动补上 |
| 按需 | 教师可对单题手动触发生成 |

**成本对比**（一份 75 题客观卷、30 人、人均错 15 题）：

| 做法 | 调用次数 |
| :--- | :--- |
| 朴素：每个学生每道错题都问一次 | 30 × 15 = **450** |
| 题目级去重（本方案） | 实际被错过的题数，通常 **20–50** |
| 上限保护 | 即使有人全卷答错，也封顶在 N（默认 50） |

**成本与班级人数无关**：50 人错同一题仍只生成 1 次。

`prompt_version` 进唯一键：以后想提升解析质量，整卷重新生成即可，旧版自动失效。教师改标准答案或题干时，该题解析置为失效并重新排队。

**统计与解析是两件事**：正确率、最常错选项、Top N 错题榜全部是**纯 SQL 聚合**，覆盖全部客观题，不受 N=50 上限影响；只有解析文本受上限约束。

---

### 6.5 人工判分与 AI 的关系（D-15）

**AI 不是前置条件，只是可选草稿。** 复核行的生命周期因此改成「按需创建」：

- 老师从「作业批改详情」的任意已交卷行进入判分页；`GET /submissions/{id}/review` 在
  `reviews` 没有行时**懒创建**一行 `PENDING`，`original_subjective = NULL` 表示 AI 未参与。
- 老师直接填主观分与三段文本 → `PUT` 保存，`GradingOutcome.apply` 把 submission 置 `DONE`
  并回写主观分（客观分仍不可手改）；随后 `POST review` 通过、`POST publish` 发布。
- 队列仍只列 `PENDING` 的复核行；被打开的人工判分项会自然出现在队列里。
- **AI 不得覆盖人工结果**：`GradingWorker` 在领取前与拿到模型结果后各查一次
  `reviews`——只要已有 `teacher_id` 或已发布，就跳过该份（成功与失败两条路径都不改写）。
  人工判分已把 submission 置 `DONE`，派发队列不会再领到它。
- 判分页需要读当前分数：`Ports.SittingLedger` 增加只读方法
  `SubmissionScores scores(UUID submissionId)`（submittedAt / objectiveTotal / subjectiveTotal /
  gradingState）。它只用于复核展示，**不进 AI prompt**（`SubjectiveGradeRequest` 仍不含分数）。
- **预填（D-20）**：详情同时返回 `aiOverall` / `aiStrength` / `aiSuggestion` 与逐项意见；前端打开
  表单时预填 `subjectivePoints`、`comment`、`strengths`、`suggestions`，标「AI 建议，可编辑」。
  未保存不写教师字段；教师保存后 `teacher_id` 落库，AI 不再覆盖。
- **批量通过（D-20）**：先返回预览（合格份数、分数范围、跳过原因计数），确认后逐份
  `decide(APPROVED)` + 审计（`bulk=true`）。跳过条件：无草稿且无教师分、AI 失败、边界敏感、
  未通过输出质量闸门、已决策/已发布。发布仍是独立动作。
- 失败兜底：AI 失败（`FAILED`）的提交也能人工判分并发布；`apply` 会清掉 `grading_error`。

### 6.6 AI 配置解析与归属（D-16 / D-30）

**问题**：`AiGradingConfig` 在启动时把管理员 Key 固化进 `LlmClient`，无法支持「教师个人 provider」
与「保存即生效」，也无法在 worker 里按作业找配置。本地模型通常没有 API Key，旧逻辑把空 Key 判成
「未配置」，直接拒绝了 Ollama / LM Studio。

**方案**：

```
GradingWorker 领取 submission
  ├─ ledger.load(submissionId) → WorkItem（含 offeringId, createdBy）
  ├─ AiKeyResolver.resolve(createdBy)
  │    ├─ identity: user_ai_settings(teacherId) 且 configured → source=user
  │    ├─ identity: ai_settings(global id=1) 且 configured → source=global
  │    └─ 都没有 → GradingFailure(NO_KEY)，不出分
  ├─ LlmClientFactory.create(settings, timeout) → 每次调用一个短生命周期客户端
  └─ engine.grade(item, client) → ai_calls 写 model/prompt_version/key_source
```

- **数据**：`ai_settings` 保留全局单行（id=1）；`user_ai_settings`（`user_id` 主键、
  `provider/display_name/base_url/model/api_key_cipher/temperature/updated_at`）是教师自定义
  provider，两者都只存密文。教师/管理员的 `/profile` 写 `user_ai_settings`；`/admin/ai` 仍写全局。
- **可用性**：`baseUrl` 与 `model` 非空是硬条件；本地 provider（ollama / lmstudio / vllm /
  llama.cpp / local）或回环/私有地址允许空 Key，云端 provider 仍需 Key 才算 `configured`。
  空 Key 的 JDK 客户端不发 `Authorization`；Spring AI 客户端用 `local-no-key` 占位值。
- **模型发现**：`POST /api/profile/ai-settings/models` 从后端服务器请求
  `GET {baseUrl}/models`，返回 `data[].id`；失败不阻断保存，前端仍可手填。
- **端口**：`Ports.AiSettingsLookup` 的 `resolve(UUID teacherId)` 返回
  `AiSettings(provider/baseUrl/model/apiKey/temperature/configured/source)`；`source` 是
  `user` / `global` / `none`。identity 实现里先查 `user_ai_settings`，再查全局；
  grading 只依赖端口，不查 identity 表。
- **优先级**：作业创建者的 provider → 全局 provider；**不**在鉴权失败时静默改用另一个配置
  （避免同一批混用两种口径），只把失败原因写清楚。
- **归属**：`offerings` 增 `created_by`（创建教师，无跨模块外键，和 `papers.author_id` 一样
  由应用写入）；`WorkItem` 增 `createdBy`。历史数据 `created_by` 为空时直接用全局 Key。
- **生效**：`AiSettingsService` 不再在 Bean 创建时读 Key；`AiKeyResolver` 每次判分现查。
  保存后下一次调用即生效，`restartRequired` 从响应里去掉（或恒为 false）。
- **安全**：沿用 `SecretBox` AES-GCM；GET 只回 `configured` + 掩码（末 4 位）；`test` 接口
  接受临时 Key 但不落库、不写日志；审计只记 `user_ai_settings:updated` 与操作者，
  不记 Key。`teachloop.security.secret` 生产必须显式配置。首次 `/setup` 不再强制 Key。
- **失败分类**：`NO_KEY` / `AUTH_FAILED` / `TIMEOUT` / `INVALID_OUTPUT` / `RATE_LIMITED`，
  写 `submissions.grading_error` 与 `reviews.ai_failure`，复核页按类显示；均不产生分数。

### 6.7 篇幅与语言（D-17 / D-19）

- **篇幅**：`TextLengthCounter` 已有四模型（T072/T173），判分与复核详情按 `content_text` 现算：
  policy_default 段数 + 四模型边界标记（不新增表）。问候语并入首段、连续项目符号合并为一段；
  `boundary_sensitive` = 四种模型的 0/2 结论不一致。AI prompt 的 user 消息写入段数、政策名与
  边界标记；`GradeOutputValidator` 校验长度项的 points 与代码结论一致，不一致即输出不合规，
  并用服务端的确定性句子覆盖模型对篇幅理由的结构猜测。
- **语言**：`WorkItem.feedbackLanguage` 改为在 `SittingLedgerImpl` 装配时读学生
  `users.locale`（经 identity 端口新增 `locales(ids)`），缺失回退 `offering.feedback_language`，
  再回退 `zh-CN`；实际语言写入 `reviews.feedback_language` 与 `ai_calls`。语言放在 user 消息，
  不破坏 system 前缀缓存。
- **参考基准更正**：JZ 在 policy_default 下是 4 段（问候语合并），人工 review 的 0/2 是历史口径；
  按 D-19 更正 golden set，同时在 `docs/reports/grading-quality/known-inconsistency.md` 留档。

---

### 6.8 逐篇判分与题项级 rubric（D-24 / D-26，2026-10-22）

**背景**：一份卷可以有两道写作题（应用文 15 分 + 口语表达 15 分），各有自己的评分项。
现状是「试卷级一份 rubric + 一次调用判全部主观题」，且考试作文正文在 `answers[]` 里、判分只读
`content_text`，等于拿空文本判分。

**做法**

1. **数据**：`ESSAY` 题项带 `rubric_markdown`；为空**直接**用系统默认 rubric（D-29 起考试不再回退试卷级；
   独立作业仍用布置自带 rubric）。
   `reference_markdown` 只给教师看，不进 prompt。
2. **请求对象**：`SubjectiveGradeRequest.forEssay(WorkItem work, ItemSpec essay)` 负责取该篇正文、
   该篇 rubric、该篇满分与长度区间。`SubjectiveGradeRequest` 仍然**不含** `answers` / `answerKey`
   字段（隐私边界由类型系统保证），正文在工厂里取出后只以字符串传入。
3. **正文来源**：`answers[]` 中 `number == essay.number()` 的作答；找不到且全卷只有 1 道 ESSAY 时
   回退 `contentText`（独立作业路径）；仍为空视为未作答。
4. **调用与合并**：逐篇调用（有作答才调用），每次都用现有 `PromptBuilder` + `GradeOutputValidator`
   独立校验；合并为一份 `GradingResult`：主观分求和、`criteria` 带 `itemNumber`、三段文本按篇分段、
   `model/prompt_version/key_source` 取最后一次调用。
5. **失败**：任一篇重试耗尽 → 整份 FAILED（沿用 §7 状态机），不落假分；重跑整份会重复计费。
6. **成本**：k 道写作题 → k 次调用（串行）。2 篇作文的时延约为 2 倍；system prompt 只多「本题」一行。
   若将来出现 10+ 道主观题的卷，再评估并发或一次多篇。

7. **没有 rubric 的默认判分（D-28）**：题项级、试卷级 rubric 都为空时，不在这里造默认文本，
   由判分侧按 `teachloop.grading.essay-default-rubric.content-percent`（默认 80）合成两项：
   内容与任务完成 X%、语法与拼写 100−X%，分值按本题满分拆分（内容四舍五入取整：15 分 → 12 + 3，14 分 → 11 + 3，70% → 11 + 4）。
   英文反馈时用英文文案。题目全文（stem）始终在 prompt 的「题目要求」里；参考范文仍不进 prompt。
   `PaperCatalogImpl` 不再编制「没有分值」的默认 rubric（否则会被均分成 50/50），快照里也不再带试卷级 rubric；
   `SittingLedgerImpl` 对考试忽略 `offerings.rubric_markdown`（D-29）；
   旧快照里那段系统伪造文本由 `EssayRubricWeights.isLegacyDefault` 识别为「没有 rubric」，
   存量卷子无需重新布置。

**测试锚点**：`AiGradingEngineTest` 两篇不同 rubric → 2 次调用、`itemNumber` 正确、总分相加；
第 15 题的 rubric/范文不出现在第 16 题的 prompt；空白篇 0 次调用；考试路径从 `answers[]` 取到正文。

## 7. 批改调度（状态机，不是队列）

承接：US-8、US-9、US-10、US-26

**设计决定**：不建独立任务队列表、不用 `SKIP LOCKED`、不用租约。工作项就是 `submissions` 行本身，用**条件 UPDATE** 领取。

### 7.1 领取

```sql
UPDATE submissions
SET grading_state = 'running',
    grading_started_at = now(),
    grading_attempts = grading_attempts + 1
WHERE id = (
    SELECT id FROM submissions
    WHERE grading_run_id = :runId AND grading_state = 'pending'
    ORDER BY id
    LIMIT 1
)
AND grading_state = 'pending'
RETURNING id;
```

`AND grading_state = 'pending'` 是正确的并发保证：多个 worker 同时抢同一行时，只有一个的 UPDATE 生效（返回 1 行），其余返回 0 行并继续下一轮。**既不需要行级锁，也不需要 `SKIP LOCKED`。**

### 7.2 崩溃恢复（两级清扫）

| 触发 | 动作 |
| :--- | :--- |
| 应用启动 | 把 `grading_state='running'` 且 `grading_started_at` 早于阈值的行退回 `pending` |
| 定时（每 2 分钟） | 同上，处理运行期间的 worker 崩溃 |

阈值默认 **5 分钟**（单份实测 1.8 秒，余量充足）。代价是极小概率重复批一次（多花几分钱）；换来的是不需要维护租约、心跳与回收循环。

### 7.3 到期派发（幂等）

**互斥不靠 ShedLock**：`@Scheduled` 只是触发外壳，互斥是
`UPDATE offerings SET grading_dispatched_at = :now WHERE id = :id AND grading_dispatched_at IS NULL`
这一条条件更新。PostgreSQL READ COMMITTED 下，后到的 UPDATE 会等先到的事务提交，再按新值重新判断
`IS NULL`，得到 0 行并跳过派发；多实例天然一致。`grading_runs (offering_id)` 的部分唯一索引 +
`ON CONFLICT DO NOTHING` 是第二层兜底。条件更新、建 run、`QUEUED → PENDING` 入队与审计在同一个事务里，
进程中途崩溃不会留下半成品。

派发时点是 `due_at + grace_hours`（默认 3 小时），**不是** `due_at`：宽限期留给迟交（§8 的 AC-8.5）。宽限期内的提交照常进入同一个 run。

**决策 010 的作业分支**：考试继续走上面的 `findDueForGrading`；作业按教师级
`teacher_grading_preferences` 走 `TeacherGradingScheduler`（每分钟扫描）：

- `FIXED_TIME`：到达教师设置的本地时刻后，派发该教师所有排队中的作业；同一本地日期最多一次
  （`last_fixed_run_at`），启动时按 `catch_up` 决定是否补跑。
- `DUE_DATE`：`findDueAssignmentsForGrading(teacherId, now)` 取 `due_at + grace_hours <= now`
  且未派发的作业；**不调用** `GradingDispatchPolicy`，即不看峰谷。迟到提交仍补进同一个 run。
- 手动「立即派发」「让 AI 先批」不变，任何时刻可用。

### 7.4 峰谷调度（挑半价时段批改）

DeepSeek 官方时段口径：

> 北京时间**周一至周五（不含中国法定节假日）9:00–12:00、14:00–18:00** 为高峰时段；其余时段（含周末与法定节假日全天）为空闲时段，**空闲价为高峰价的一半**。

| 规则 | 说明 |
| :--- | :--- |
| 默认行为 | 到期派发时若处于**高峰时段**，推迟到下一个空闲时段的起点再派发 |
| 延迟上限 | **最多约 4 小时**（14:00 之后最快要 18:00 才进入空闲）；周末与节假日**零延迟** |
| 手动派发 | 教师点"立即批改"**不受窗口限制**，随时执行 |
| 作业 due-date | 决策 010 起**不看峰谷**：作业的 due-date 模式过截止就派；峰谷只保留给考试（DeepSeek 成本策略） |
| 时区 | 一律按 `Asia/Shanghai` 判断，与服务器时区无关 |
| 关闭开关 | `grading.peak_aware=false` 可完全关闭 |
| 已知局限 | 官方口径含"法定节假日全天空闲"，V1 **不做节假日日历**，因此节假日会被当成工作日、最多多等 4 小时 |

合算：批改一份作业的实际成本已是"几分钱"级别（见 §6.3），峰谷调度的绝对节省很小，主要价值是不浪费；因此**它必须可关闭**，不能因为省钱让老师等反馈。

### 7.5 将来换成消息队列的路径

MVP 用数据库状态机 + 轮询。将来若换消息队列（RabbitMQ / Redis Streams / Kafka），要守住三条约束，替换成本才会真的低：

| 约束 | 为什么 |
| :--- | :--- |
| `gradeSubmission(submissionId)` **不感知自己被谁调用** | 轮询循环与 MQ 消费者都只是调用它的外壳；批改逻辑里不得出现"下一个待处理是谁"这类查询 |
| 进度**只**从数据库 `grading_state` 聚合 | 不读 MQ 的内部队列状态；换掉 MQ 时进度接口与前端一行不改 |
| 状态机自带幂等（已是 `done` 就跳过） | MQ 是 **at-least-once**，重复投递是常态；幂等保证重复投递不会重复计费批改 |

替换时真正要改的只有两处：**派发改为发布消息**、**worker 循环改为消费者**。

**不提前引入 `TaskQueue` 接口抽象**（YAGNI）：只有一个实现时接口是纯开销；只要守住上表三条，加第二个实现就是新增一个类。

### 7.6 考试计时与到点自动交卷

承接：US-21、US-26

**为什么必须服务端权威**：倒计时若放在前端，改本机时间、刷新页面、断网重连、换设备四种情况都能把时长无限延长。考试是唯一有"时间就是分数"性质的场景，这里不能省。

| 事项 | 做法 |
| :--- | :--- |
| 起算点 | `individual`：学生点「开始答题」写 `submissions.started_at = now()`；`fixed_window`：到 `offerings.start_at` 时把参与班所有未开始的提交一次性写入同一 `started_at` |
| 截止点 | `deadline_at = started_at + duration_minutes`（`offering_classes.duration_override` 优先）。**落库，不在请求时计算**，避免时长被中途改掉影响进行中的考试 |
| 下发 | 每次自动保存/心跳的响应带 `remaining_seconds`；前端只做 1 秒插值显示，收到回执即校正。**前端不得自行累计** |
| 归零 | 复用 §7.2 的定时清扫循环，新增一条：`deadline_at < now()` 且非终态的提交 → 置 `auto_submitted=true`，按最后一次自动保存定稿，进入 `pending` 批改 |
| 幂等 | 归零处理用条件更新 `WHERE deadline_at < now() AND grading_state = 'draft'`，重复扫描无副作用 |
| 宽限期不适用 | 考试的 `deadline_at` 到期即终态；`grace_hours` 只推迟**批改派发**（§7.3），不是作答截止 |
| 按班覆盖 | `offering_classes.due_at_override` / `duration_override` 优先于布置级的值 |
| 作业不适用 | `kind=assignment` 不写 `started_at` / `deadline_at`、不设 `duration_minutes`、不接受 `timing_mode` 与按班时长覆盖；迟交只按 `due_at` 计算（US-8） |

**`fixed_window` 的代价要写清**：全班同时起算意味着只要窗口开了，请假的学生也在掉时长。所以它只给"统一监考"的场景用，默认还是 `individual`。

**实现口径（2026-10-19，T199 落地时确认）**：

- `fixed_window` **不做「批量预建 submissions 行」**。截止时间改由服务端在每次读会话/入口页时按
  `start_at + duration` 推导（`剩余 = start_at + duration − now`）；学生第一次开始作答或自动保存时才落
  `submissions` 行，并把 `started_at = start_at`、`deadline_at = start_at + duration` 写死。
  等价保证「未点击的学生也在计时」，代价是**从未打开过考试的学生不会留下空提交**——这是期望行为。
- 到点自动交卷只扫**已有行**：`deadline_at < now AND submitted_at IS NULL AND active`，用条件 UPDATE
  定稿，重复扫描无副作用；扫描周期 `teachloop.exam.scan-ms` 默认 30 秒（验收要求 60 秒内）。
- 草稿态用 `grading_state = 'DRAFT'` 表示，**不进入批改队列**（队列只取 `PENDING`）；
  定稿时若存在主观作答或正文则转 `PENDING` 送 AI 批改，否则直接 `DONE`。
- 学生同时在多个参与班且各班覆盖不同时，**取最早的 `due_at` 与最短的 `duration_minutes`**，
  不允许跨班叠加时长。
- `NotificationPort` 目前只写审计痕迹；站内通知表属 T208，建表后替换投递实现。

---

## 8. 进度查询（轮询，不用 SSE）

承接：US-20

`GET /api/offerings/{id}/progress` 返回：

```json
{ "runId": "…", "status": "running", "total": 30, "done": 12,
  "failed": 1, "pending": 17, "running": 0 }
```

实现是一条聚合 SQL（`GROUP BY grading_state`）。任何实例返回相同结果，不依赖长连接、不需要 nginx 改 `proxy_buffering`、不需要事件回放与重连逻辑。

**不采用 SSE 的理由**：批改是分钟级任务，2 秒粒度不影响体验；而 SSE 需要维护 `SseEmitter` 生命周期、跨实例扇出、断线重连三块代码。将来若确实需要实时性，加回 SSE 是增量改动——进度数据本来就在库里。

前端约定：只在「批改中」的页面轮询，进入终态立即停止；页面隐藏（`visibilitychange`）时暂停。

---

## 9. 认证与安全

承接：US-1、US-3、US-27

| 项 | 方案 |
| :--- | :--- |
| 微信登录 | `AuthProvider` + RestClient 调开放平台 |
| 管理员登录 | 邮箱 + BCrypt 密码 |
| 会话 | Spring Session JDBC，Cookie `HttpOnly` + `Secure` + `SameSite=Lax` |
| CSRF | `CookieCsrfTokenRepository` 双提交 |
| 停用账号 | 会话强制失效（删 session 行） |
| 限流 | `rate_limits(key, window_start, count)` 表 + `INSERT … ON CONFLICT DO UPDATE`，跨实例精确 |
| 上传 | 扩展名 + 内容类型双校验；100 MB 上限；视频 ffprobe 探测 |
| 富文本 | OWASP Sanitizer 白名单；脚本标签丢弃并记安全事件 |
| 越权 | 访问非本人资源一律 **404**（不泄漏存在性） |
| AI Key（D-16） | 管理员初始化必填；教师/管理员 `/profile` 可配；全局 Key 在 `/admin/ai`；AES-GCM 密文；GET 只回掩码（末 4 位）；保存即生效；`test` 临时 Key 不落库；不写日志/审计 |
| Key 归属 | 作业创建者 Key → 全局 Key；不静默跨 Key 重试；审计只记 `key_source` 与操作者 |
| Key 生命周期 | 仅 TEACHER/ADMIN 可写；删除/清空需二次确认；密钥轮换只换 `teachloop.security.secret` 时需重填（解密失败给出明确错误） |

---

## 10. 文档与富文本处理

承接：US-6

| 场景 | 方案 |
| :--- | :--- |
| 富文本入库 | 原文 HTML（已消毒）存 `content_html`，纯文本存 `content_text` 供 AI |
| `.docx` 提取 | POI `XWPFDocument` 段落 + 表格 |
| `.pdf` 提取 | PDFBox `PDFTextStripper`；无文本层 → `unreadable`（不 OCR） |
| `.doc` | 拒绝，提示另存为 `.docx` |
| 视频 | `ProcessBuilder` 调 `ffprobe -show_format -show_streams`，解析 JSON；超时长/超分辨率直接拒收 |
| 邮件/短信 | V1 不做，通知只站内 |

---

## 11. 国际化

承接：US-15

- 后端：`MessageSource` + `messages_zh_CN.properties` / `messages_en.properties`；错误统一 `ApiError(code, message, detail)`，`code` 稳定、`message` 本地化。
- 前端：`react-i18next`，语言存用户资料与 localStorage。
- 批改语言（D-17）：取学生判分时的 `users.locale`，缺失回退 `offerings.feedback_language`，再回退 `zh-CN`。语言放在 user 消息（不破坏 system 前缀缓存），输出做语言合规校验；生成后的反馈不因学生改语言而重写。
- 静态检查：禁止在 `api/`、`service/` 下出现中文字符串字面量（i18n 资源除外）。

---

## 12. 可观测

承接：US-16、US-24

| 层 | 方案 |
| :--- | :--- |
| 请求日志 | `OncePerRequestFilter`：requestId、userId、method、route、status、耗时；**不含正文** |
| 审计 | `audit_logs` 只追加，服务层显式写，与业务同事务；按 `offering_id` 可拉出完整时间线 |
| 前端事件 | `POST /api/events` 批量接收，白名单 + 限流，**追加写 JSONL 文件**（不入业务库） |
| 日志输出 | JSON 结构化到 JSONL，按天滚动；`LogSink` 接口，接 ES 时不改业务代码 |
| 每日汇总 | 凌晨聚合前一天数据写入 `daily_stats`，报表查它（见 §5.1） |
| 清理 | **日志与事件文件按 90 天删除**；**审计不删除**（永久保留，可归档冷存储） |
| 健康检查 | `/healthz`（存活）+ `/healthz?deep=1`（依赖：DB、字体、ffprobe） |

---

## 13. 导出

承接：US-14

| 产物 | 实现 |
| :--- | :--- |
| 逐份反馈 `.md` | 模板渲染（YAML front matter） |
| 逐份反馈 `.pdf` | OpenPDF 3.0.5 + 内置 CJK 字体 STSong-Light / UniGB-UCS2-H；启动自检，字体不可用返回 503 |
| 班级汇总 `.md` | `summary.md`，确定性统计，不生成教学建议 |
| 成绩 `.xlsx` | POI 5.5.1 `XSSFWorkbook`（按班统计 / 逐份提交 / 未提交名单，`lang` 切表头） |
| 成绩 `.docx` | POI 5.5.1 `XWPFDocument`（与 Markdown 汇总同源） |
| 打包 `.zip` | 只含已发布反馈（`publish_scores=false` 返回 409） |

**实现说明（2026-10-19）**：PDF 使用 OpenPDF 内置 CJK 字体（非嵌入），中文渲染正常，
但复制/检索中文不可用（无 ToUnicode）；若要求可选中中文，需改为嵌入 Noto Sans CJK 字体文件。
XLSX/DOCX 由 POI 生成；Markdown/ZIP 不依赖第三方库。

字体自检：启动时校验字体可加载，失败则 `/healthz?deep=1` 报错、导出返回 503。**不提供 `.txt` 导出**（用户明确要求）。

---

## 14. 报表

承接：US-17、US-23、US-24

数据源分两条线，这是报表与日志唯一必须分开的地方：

- **分数与统计**：只查业务库，保证与界面数字绝对一致。
- **日志类指标**（页面行为、API 性能）：走 `daily_stats` 与 JSONL 明细。V1 不接 ES。

落地判断：

- **先不做预计算表**：聚合 SQL（`GROUP BY` + 窗口函数）+ 只读 Service。数据量到万级再考虑物化视图。
- **导出复用**：Excel 用 POI，PDF 用 OpenPDF（与 §13 同一套）。
- **无试卷作业的标题**：`offerings.paper_id` 可空，报表与导出对 `papers` 用 **LEFT JOIN**，
  标题取 `coalesce(papers.title, offerings.paper_snapshot->>'title')`；否则独立作业会在聚合里整行消失。
- **权限**：教师仅限自己班级；管理员全局（但不可见教师评语正文）。
- **口径**：缺考/未交不计入平均分，单列人数（AC-17.8）。
- **常见错误**（AC-17.9）：每道客观题的正确率、最常被选错的选项与人数，按正确率升序，即教师讲评清单。**纯 SQL 聚合，无 AI 成本**，覆盖全部客观题；只有解析文本受 50 道上限约束。
- **跨班聚合**（AC-17.11/17.12）：班级是多选筛选维度，不是入口层级。
- **待定**：定时报表与订阅、教师自定义报表、图表形态。

---

## 15. 部署（阿里云）

承接：全局

| 组件 | 生产 |
| :--- | :--- |
| 应用 | ECS + Docker（或 systemd），`java -jar app.jar`，1..N 实例，`--spring.profiles.active=app` |
| Worker | 同一 jar，`--spring.profiles.active=worker` |
| 前端 | OSS + CDN，或 Nginx 静态目录 |
| 数据库 | RDS PostgreSQL 16（内网） |
| 对象存储 | OSS 私有 Bucket + 签名 URL |
| TLS | 自有 DNS + 证书（Let's Encrypt 或阿里云证书） |
| 迁移 | 部署前独立执行 `flyway migrate` |
| 定时 | 含在 `app` 内；多实例互斥靠数据库条件更新（§7.3），不需要独立锁表 |
| 密钥 | 环境变量 / KMS；**不进配置文件、不进仓库** |

**注意**：多实例下不要用 Spring Boot 自动 Flyway 启动迁移（会并发执行）——用独立迁移步骤或 `flyway` 命令行。

---

## 16. 与 v1（Python）的关系

承接：全局

| 资产 | 处置 |
| :--- | :--- |
| 后端 Python 代码（约 8000 行、136 测试） | **已删除**（2026-10-19）；设计结论已吸收进本文件，代码不再保留 |
| 四模型段落计数算法 | **移植到 Java**，并用 v1 那 10 份真实作业做 parity 测试，段数必须逐份一致 |
| 客观题匹配策略设计 | 设计沿用，Java 重写为注册表 |
| 复核状态机 / 审计结构 | 设计沿用（四态 → 三态按 spec） |
| React 前端 | **迁移复用**，补富文本编辑器、客观题作答页、报表页 |

---

## 17. 实测记录（2026-10-18）

验证工程 `.spike/` 已删除（用完即弃，未进仓库）。结论保留在此，供将来复核。

| 编号 | 验证项 | 结果 | 关键发现 |
| :--- | :--- | :--- | :--- |
| S-1 | 环境 | ✅ | JDK 21.0.11 已装；Maven 3.9.16 可用；`JAVA_HOME` 必须显式设置 |
| S-2 | Spring Boot 骨架 | ✅ | Boot 4.1.1 启动 **1.0 秒**，`/actuator/health` 返回 UP |
| S-3 | Spring AI + DeepSeek | ✅ | 返回合法 JSON，Jackson 3 解析成功；单次调用约 **1.8 秒** |
| S-4 | 输出不合规 | ⚠️ | `.entity()` 会自行追加格式指令 → 弃用，见 §6.2 |
| S-5 | 并发领取 | ✅ | 3 个 worker 抢 5 个任务，**零重复领取**。当时验的是 `SKIP LOCKED`；现方案改条件 UPDATE，并发保证等价且代码更少 |
| S-6 | LISTEN / NOTIFY | ✅ | 通知可送达；payload 约 8 KB 上限（7900 接受 / 8100 拒绝）。**现方案不依赖此能力**，结论留档备查 |
| S-7 | Testcontainers | ✅ | `postgres:16-alpine` 拉起并连通；Testcontainers 2.x 改了构件坐标，旧坐标需锁 `1.21.4` |

两条由实测直接决定的设计：

1. **不用 `.entity()`**（S-4）——审计冲突，见 §6.2。
2. **不用 LISTEN/NOTIFY 做推送**（S-6 的 8 KB payload 限制）——改轮询，见 §8。

---

## 18. 测试策略

承接：全局

### 18.1 四层

| 层 | 内容 | 速度 | 外部依赖 |
| :--- | :--- | :--- | :--- |
| ① 单元 | 匹配策略、总分合成、段落计数、HTML 消毒、prompt 构造、输出校验 | 秒级 | 无 |
| ② 集成 | 迁移与约束、独立作业创建与约束、条件 UPDATE 领取、僵尸清扫、派发幂等、计时与自动交卷、峰谷边界、依赖方向守卫 | 十几秒 | Testcontainers 起 Postgres |
| ③ 真机 AI（默认跳过） | 真实调 `deepseek-flash`，确认 prompt 被接受、输出可解析 | 几秒 | 真 Key |
| ④ E2E（手工，见 18.4） | 整份试卷、纯写作两条旅程 | 分钟级 | 浏览器 |

`make test` 只跑 ①②：不需要网络、不需要 API Key。`make test-ai` 跑 ③，需显式调用。

### 18.2 外部依赖在本地怎么替

| 依赖 | 本地替代 | 备注 |
| :--- | :--- | :--- |
| 微信登录 | `DevProvider` | 生产 profile 下必须注册失败 |
| 阿里云 OSS | `LocalStorage` | 同一 `StorageBackend` 接口 |
| DeepSeek | `StubGradingEngine`（默认） | 真 Key 仅 `make test-ai` 使用 |
| 中文字体 | 仓库内置 Noto Sans CJK | 保证 PDF 测试跨机器一致 |
| `ffprobe` | 本机二进制 | 缺失时明确报错，不猜时长 |

### 18.3 两条硬性测试纪律

**① 必须注入 `Clock`，禁止直接 `now()`**

有五块逻辑依赖"现在几点"：宽限期判定、峰谷调度、僵尸清扫、到期派发、考试到点自动交卷。写死 `LocalDateTime.now()` 会让这五处**无法测边界**——不可能让真时钟停在 `due_at + 3h` 那一秒，也无法让系统以为现在是周一上午 10 点。全项目统一注入 `java.time.Clock`，测试用 `Clock.fixed(...)`。

**② 断言结构，不断言分数**

模型输出有随机性，断言"这篇应该得 18 分"会变成 flaky 测试。测试只断言：JSON 合法、评分项名与顺序正确、各项不超满分、总分等于各项之和。分数对不对，靠 18.5 的 golden set 人工看。

### 18.4 手工 E2E 清单（MVP 不做自动化 E2E）

每次涉及主链路的改动，按此清单在本地跑一遍并记录结果。

**旅程 A — 整份试卷（考试型）**
1. `DevProvider` 登录为教师 → 补全手机号与邮箱
2. 建班 × 3 → 拿到每班一个邀请码
3. 建**试卷**（4 部分 8 节、77 题、含标准答案与写作 rubric，默认时长 90 分钟）
4. 把试卷**布置给 3 个班**，`kind=exam`、`timing_mode=individual`，并给其中一个班设 `duration_override`
5. 换学生身份凭码入班 → 考前须知 → 点「开始答题」→ 作答（选项点选、完形就地填、写作带字数）→ 提交
6. 观察目录树完成态、剩余时间、到点自动交卷（用 `Clock.fixed` 或调 `deadline_at` 直接触发）
7. 到 `due_at + grace_hours` → 触发派发 → 观察进度轮询
8. 教师复核：一份原样通过、一份改分后通过、一份驳回重批
9. 发布 → 学生看到总分 → 错题解析 → 评语 → 全班概览
10. 导出 Markdown / PDF / XLSX / DOCX，检查中文

**旅程 B — 纯写作（作业型）+ 新 attempt**
1. 建"作业"型（仅主观，无倒计时）→ 发布
2. 学生提交富文本（含粗体与列表）→ 再发起一次新 attempt
3. 过 `due_at` 后仍可提交，确认被标 `late` 且教师可拒收
4. 到点派发 → 复核通过 → 发布
5. 确认被批改的是**最新一次** attempt，历史仍在

### 18.5 golden set：质量回归（2026-10-21 强化）

自动化测不出"AI 批得准不准"，但可以测**与人工参考的一致性和契约合规**：

- 样本：`docs/rubrics/samples` 的 10 份提交 + 人工 review + `Rubric.md`；长度边界项
  （Genesis/JZ/Maria/Mark）单列，不计入主指标。
- 每次改 prompt、换模型、调 temperature、改 rubric 结构或长度政策，跑一遍
  `GradingQualityEvalTest`（`@Tag("ai")`，用真实 `PromptBuilder` + `GradeOutputValidator`）。
- 指标：
  - 逐项 exact agreement（三项分别统计）与总分 exact；
  - 总分 MAE 与偏差方向；
  - 契约通过率（一次过 / 重试后过 / 失败）；
  - 原文引用覆盖率（comment 含正文逐字片段的比例）；
  - 语言合规率（目标语言 vs 实际语言）；
  - 边界敏感项与「人工参考内部不一致」单列。
- 闸门（AC-28.10）：非边界样本逐项 exact ≥ 9/10、总分 MAE ≤ 0.5、契约/引用/语言 100%；
  未达标必须附例外说明，不允许静默上线。
- 报告：`docs/reports/grading-quality/<date>-<prompt-version>.md`，含每份一行与失败样本原文摘要
  （不写 Key）。JZ 的人工参考按 D-19 更正为 10/10，旧值在
  `docs/reports/grading-quality/known-inconsistency.md` 留档。

### 18.6 本地手工验证

```
docker compose up     # postgres + app + worker
make seed             # 1 个班 + 30 个学生 + 1 份带标准答案的试卷
浏览器 127.0.0.1:8010  # DevProvider 直接登录成教师
```

`make seed` 必须可重复执行（先清再建），否则每次手工验证都要手点建数据。

---

## 19. 风险

| 风险 | 影响 | 缓解 |
| :--- | :--- | :--- |
| Spring AI 版本线与 DeepSeek 模块组合未验证 | 白写一层集成代码 | S-3 已验证；仍失败即退裸 HTTP |
| 全量重写 Java | 工期显著长于沿用 Python | 前端与设计可复用；按阶段交付，每阶段可运行 |
| 条件 UPDATE 领取有空转 | 极端并发下少量无效循环 | `ORDER BY id` 走索引，空转代价极低；单校规模可忽略 |
| 僵尸清扫阈值设得过短 | 正在跑的项被重复领取 | 默认 5 分钟（单份 1.8 秒），可配置；重复批只多花几分钱 |
| OpenPDF 中文字体 | 导出乱码 | 字体入库资源 + 启动自检 |
| `ffprobe` 外部依赖 | 部署缺二进制 | 镜像内预装；缺失时明确报错，不猜时长 |
| 多实例 Flyway 并发 | 迁移冲突 | 迁移独立执行，不在应用启动时跑 |
| `fixed_window` 被误用 | 请假学生也在掉时长 | 默认 `individual`；创建页写明代价 |
| 缓存前缀被污染 | 成本涨 50 倍且难被测出 | §6.1 四条硬规则 + 禁止把姓名/学号/id 放进 prompt |

---

## 附录 A：用户故事 → 本文件章节

用于验检查项 1（每个用户故事都有承接结构）。`tasks.md` 的追踪矩阵再把 26 个故事与 42 条 FR 映射到任务；203 条 AC 不逐条映射，理由写在该矩阵开头。

| 故事 | 主题 | 本文件章节 |
| :--- | :--- | :--- |
| US-1 | 登录与账号资料 | §4、§9、§2 |
| US-2 | 班级与邀请码 | §3.3（`classes` / `class_members`）、§5 |
| US-3 | 账号开通 | §4、§9 |
| US-4 | 试卷（内容，独立于班级） | §5.0（`papers` 无 `class_id`）、§5 |
| US-5 | 标准答案与匹配策略 | §4（`MatchStrategy`）、§5、§6 |
| US-6 | 提交 | §6、§10 |
| US-7 | 新 attempt | §5（`(offering_id, student_id) WHERE active`） |
| US-8 | 截止与迟交（作答侧） | §7.3、§7.6、§5 |
| US-9 | 自动派发 | §7.1–7.4 |
| US-10 | AI 批改（仅主观部分） | §6、§6.1–6.3、§4 |
| US-11 | 复核与人工判分 | §6、§6.5、§8 |
| US-12 | 学生反馈 | §6.4、§8 |
| US-13 | 班级概览 | §14 |
| US-14 | 导出 | §13 |
| US-15 | 国际化 | §11 |
| US-16 | 可观测与审计 | §5.1、§12 |
| US-17 | 报表 | §5.1、§14 |
| US-18 | 错题解析（题目级共享） | §6.4、§5 |
| US-19 | 教师首页（跨班级聚合） | §14、§8 |
| US-20 | 批改总览与详情 | §8 |
| US-21 | 学生考试视图 | §7.6、§5（`submissions` 计时列） |
| US-22 | 学生作业视图 | §5.0（D-14 内容快照 + 数据约束）、§7.6（作业不计时）、§7.3 |
| US-23 | 班级汇总 | §14 |
| US-24 | 全局列表与表格约定 | §8、§12 |
| US-25 | 行操作与详情入口 | §12 |
| US-26 | 一次布置（考试 / 作业） | §5.0（`offerings` / `offering_classes` / D-14）、§7.6、§3.3 |
| US-27 | AI 批改质量与密钥 | §6.1（prompt v2）、§6.5（预填/批量）、§6.6（Key 解析）、§6.7（篇幅/语言）、§9、§11、§18.5 |
| US-28 | 评分标准契约与质量基准 | §5（rubric 快照/锚点/指标）、§6.1、§18.5、§3.3 |
| US-45 | 教师自定义 AI 与批改时机 | §5（`user_ai_settings.display_name` / `teacher_grading_preferences`）、§6.6、§7.3、§7.4、§3.3 |

## 附录 B：本文件与下游文档的约定

- 表名、字段名、端口名以本文件为准；`spec.md` 不谈实现，`tasks.md` 按本文件拆。
- 任何架构级改动（新增模块、改端口、加跨模块外键）必须同时改本文件与宪法，否则视为未发生。
- 本文件里的"已定"表示用户已拍板，评审时不必再问；"待定"仅出现在 §14 末尾三条。
