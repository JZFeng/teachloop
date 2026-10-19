# 任务清单 — TeachLoop（v2，Java）

- 上游：`spec.md`（已确认）、`plan.md`（含实测结果）
- 完成定义（DoD）：代码 + 测试 + `make test` 全绿 + 本文件执行记录勾选。缺一不算完成。
- 依赖规则：`依赖` 列写了前置任务的，必须全部完成才能开始。
- 仓库：`teachloop`（groupId / 包根 `com.teachloop`），九个 `teachloop-*` 子模块摆在根下，外加 `web/`（前端）。v1 的 `batch-grading/` 已于 2026-10-19 删除，仓库里不会出现它。

---

## 阶段 0 — 环境与骨架

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T001 | 建 **Maven 多模块聚合工程**（宪法第十四条）：Spring Boot 4.1.1 parent、Java 21、Maven Wrapper；九个 `teachloop-*` 模块（`shared / identity / classroom / paper / sitting / grading / report / observability / app`）全部建壳 | — | `./mvnw -v` 显示 Java 21；不需要全局 Maven；`./mvnw -q validate` 通过 |
| T002 | `Makefile`：`build`、`test`、`run`、`migrate`、`java-home` 提示 | T001 | `make build` 通过；脚本内显式设置 `JAVA_HOME` |
| T203 | **依赖方向守卫**：`shared` 不依赖业务模块；`identity`/`classroom` 不依赖 `paper`/`sitting`/`grading`；无循环依赖 | T001 | 故意让 `classroom` 依赖 `grading` 时构建失败；`./mvnw dependency:tree` 无反向边 |
| T003 | 应用骨架：`App` + `application.yml`（端口 8010、actuator） | T002 | `/actuator/health` 返回 `UP` |
| T004 | 统一错误模型 `ApiError(code, message, detail)` + `@RestControllerAdvice` | T003 | 未捕获异常返回 500，含稳定 `code`，**不含堆栈与内部路径** |
| T005 | Jackson 3（`tools.jackson`）配置；时间统一 UTC 序列化 | T003 | 时间字段输出带 `Z`；不引入 Jackson 2 依赖 |
| T006 | Testcontainers 基线：一个连 `postgres:16-alpine` 的集成测试 | T001 | `make test` 绿；Testcontainers 锁 `1.21.4` |
| T007 | 静态检查：`api/`、`service/` 禁止中文字符串字面量 | T002 | 故意插入中文常量时检查失败 |
| T204 | **跨模块越界检查**：模块的迁移文件只写自己拥有的表（§3.3 清单）；业务模块不得直接查别人的表；**端口签名只收发 `record`，出现 `@Entity` 即失败**（为拆服务留路，§3.5）；跨模块外键只允许清单内那 10 条 | T203、T010 | 故意在 `grading` 迁移里写 `paper_` 表时检查失败；把 `GradingOutcome` 的参数从 `record` 换成 `@Entity` 时检查失败 |
| T008 | `web/` 前端工程：Vite + React + TS + i18next 骨架 | T001 | `npm run build` 通过 |
| T009 | **`Clock` bean**：全项目统一注入 `java.time.Clock`，禁止直接 `now()` | T003 | 可用 `Clock.fixed(...)` 把系统时间钉在任意时刻（宽限期/峰谷/清扫都要用它） |

## 阶段 1 — 数据库与迁移

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T010 | Flyway 基线 + **按模块分目录的迁移约定**（`teachloop-<module>/src/main/resources/db/migration/`，统一在 `teachloop-app` 调度，文件名 `V<序号>__<模块>_<描述>.sql`） | T006 | 空库 `migrate` 成功；新增模块加目录即可被执行 |
| T011 | `users`：role[admin\|teacher\|student]、email、password_hash、phone、display_name、locale、status、created_by | T010 | 唯一约束生效（email、phone） |
| T012 | Spring Session JDBC 标准表 | T011 | 会话可写入/读取 |
| T013 | `classes`、`class_members`、`teacher_invites`（含 `expires_at`） | T011 | `UNIQUE(class_id,user_id)` 生效 |
| T014 | **内容与布置三张表**：`papers`（标题、`default_duration_minutes`、总分、版本、状态，**无 class_id**）/ `paper_sections` / `section_criteria` + `offerings`（`paper_id`、kind、start_at、due_at、duration_minutes、timing_mode、grace_hours(3)、max_attempts(3)、feedback_language、show_peer_overview、**publish_scores**、rubric_source_md、grading_prompt_template、rubric_version、grading_dispatched_at、status）/ `offering_classes`（多班 + 可空 `due_at_override`、`duration_override`） | T013 | 默认值断言通过；`(status, due_at)` 索引存在；`papers` 表结构里没有任何班级外键 |
| T015 | `paper_sections`、`objective_items`（含 stem/options）细表与唯一约束 | T014 | `UNIQUE(paper_section_id,number)`、`UNIQUE(paper_section_id,position)` |
| T016 | `submissions`：attempt_no、input_kind、active、content_html、content_text、duration_seconds、width、height、objective_total、subjective_total、**started_at、deadline_at、auto_submitted、late_by_seconds、rejected_at、reject_reason** | T015 | **部分唯一索引** `(offering_id,student_id) WHERE active` 生效 |
| T017 | `objective_answers`、`submission_metrics` | T016 | `UNIQUE(submission_id,item_id)` |
| T018 | `grading_runs` + `submissions` 的工作项列（`grading_state`、`grading_attempts`、`grading_started_at`、`grading_error`、`grading_run_id`） | T016 | 工作项查询索引存在；**不建独立队列表** |
| T019 | `reviews`、`audit_logs`、`notifications`、`daily_stats`、`rate_limits`、`ai_calls` | T018 | 建表通过；`audit_logs` 无更新/删除接口 |
| T020 | 迁移集成测试：空库→迁移→断言表/约束/索引；重复执行幂等 | T019 | 新测试全绿 |
| T021 | `make seed`：1 个班 + 30 个学生 + 1 份带标准答案的试卷，**可重复执行**（先清再建） | T020 | 连跑两次结果一致 |

## 阶段 2 — 认证与账号

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T030 | `AuthProvider` 接口 + `DevProvider`（仅 dev 环境注册） | T012 | 生产 profile 下 dev 登录返回 503 |
| T031 | `WeChatOpenProvider`：授权 URL、code 换 token、拉用户资料 | T030 | mock HTTP 覆盖成功/失败/缺 openid |
| T032 | `AdminPasswordProvider` + BCrypt + `POST /api/auth/login` | T030 | 管理员可登录；教师/学生用密码登录被拒 |
| T033 | Spring Security + Spring Session JDBC；Cookie `HttpOnly/Secure/SameSite`；CSRF 双提交 | T032 | 登出后回放旧 Cookie 无效；写操作缺 CSRF 返回 403 |
| T034 | 手机号与邮箱必填补全拦截 | T032 | 未补全时除补全页与登出外一律 403 |
| T035 | 角色守卫 `requireAdmin/requireTeacher/requireStudent` | T033 | 越权返回 403 或 404（按 spec） |
| T036 | 管理员 CLI：`create-admin`；教师邀请码生成与兑换；账号停用 | T035 | 首个管理员可创建；兑换后角色升级且码作废；停用即踢下线 |

## 阶段 3 — 班级与成员

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T037 | 限流：登录 / 上传 / 发起批改 / 事件上报四类端点（Postgres 计数表，跨实例精确） | T033 | 超限返回 429；并发测试下计数不丢 |
| T040 | 建班 + 邀请码生成（**3 天过期**，可轮换、可停用） | T036 | 过期/停用后加入返回 404 |
| T041 | 学生凭码加入（幂等） | T040 | 重复加入不产生两条成员 |
| T042 | 成员列表（教师可见，含提交数） | T040 | 学生访问返回 403 |
| T043 | 班级访问控制：教师只见自己的班、学生只见已加入的班 | T041 | 越权一律 404；学生不能生成邀请码 |

## 阶段 4 — 作业、试卷与标准答案

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T050 | **试卷** CRUD（不含班级、不含 kind）与部分（sections）编排 | T014 | 总分 = 各部分之和；不一致 400；创建与编辑全程不需要选班级 |
| T051 | 主观部分评分项 CRUD | T050 | 评分项合计 = 该部分分值 |
| T052 | 客观题项批量录入 + 整行导入（`A B A C C`）+ 每题同分快捷录入 + 按题号区间批量设定题型 | T050 | 题号唯一；分值合计校验 |
| T053 | `MatchStrategy` 注册表（exact / normalized / choice / set） | T052 | 四种策略单测覆盖大小写、空白、标点、多写法、无序 |
| T054 | 发布锁定 + `rubric_version` 递增 + 审计 | T051,T053 | 发布后改结构需显式确认并写审计 |
| T055 | 客观题模板导入（可选）：从粘贴的答案表生成题项 | T052 | 解析失败给出具体行号 |
| T056 | **整卷粘贴拆分**：按题号从整卷文字拆出题干、选项与答案，教师可逐项修正 | T052 | 75 题样卷拆分正确率 ≥ 95%；拆错的项可单条改；题干/选项入库 |
| T196 | **题型模型**：`question_type` ∈ `single_choice` / `multiple_choice` / `fill_blank` / `essay`；逐题或按题号区间批量设定；导入时按「有无选项 / 有无字数要求」给默认值；题型互转不丢已有作答；多选计分策略（`all_or_nothing` 默认 / `partial`） | T052 | 四种题型在判分层分别命中正确 strategy；单选↔多选互转后原有勾选仍在；多选 `partial` 下选错扣至 0 不再为负 |
## 阶段 5 — 提交（三种形态）

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T060 | 提交接口支持 `rich_text`：HTML 消毒（白名单）+ 存 `content_html` + 提取 `content_text` | T016 | 脚本标签被丢弃并记安全事件；允许格式保留 |
| T061 | 文件提交：pdf/docx/doc/txt/md；100 MB 上限；PDFBox/POI 提取文本 | T060 | 各类型单测；`.doc` 拒绝并提示另存 |
| T062 | 视频提交：ffprobe 校验时长 ≤360s、宽高 ≤1920×1080 | T060 | 边界 359/360/361 秒、1920/1921 宽；拒绝时指出具体超限项 |
| T063 | 客观作答录入（**仅学生本人**）+ 即时判分返回错题清单 | T053,T060 | 教师无法代填/代改学生答案 |
| T064 | new attempt：`active` 切换、`max_attempts`（默认 3）、历史保留 | T016 | 同一学生只有一条 active；超限拒绝 |
| T065 | 截止与迟交：`late` 标记 + `late_by_seconds`、**过 `due_at` 仍接受**、`grace_hours` 只决定批改启动时点（不拒绝提交） | T014,T009 | 用 `Clock.fixed` 测 `due−1s` / `due+1s` / `due+grace` 三个边界；`due+grace+1h` 仍能提交且带 `late=true` |
| T066 | 无法解析文件标 `unreadable`，不参与评分 | T061 | 不产生 0 分 |

## 阶段 6 — 判分

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T067 | `StorageBackend` 抽象 + `LocalStorage`（开发）+ `OssStorage`（生产，含签名 URL） | T016 | 两种实现可切换；签名 URL 有有效期；不泄露 bucket 路径给未授权方 |
| T070 | 客观判分服务：逐项比对 → `objective_answers` → `objective_total` | T063 | 全对/全错/部分/缺答四种用例 |
| T071 | 总分合成：`objective_total + subjective_total == final_total` | T070 | 三者自洽；不一致直接报错 |
| T072 | 四模型段落计数移植（与 v1 Python 对齐） | T070 | 见 T173 parity 测试 |

## 阶段 7 — AI 批改

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T080 | Prompt 构造：`system` = 角色 + rubric + 输出契约；`user` = 客观指标 + 正文；**不含标准答案与客观得分** | T071 | `SubjectiveGradeRequest` 类型上没有 `answers`/`objectiveTotal` 字段 |
| T081 | Spring AI `ChatClient` 调用：模型 `deepseek-flash`、`temperature 0.2`、JSON 输出；用 `.content()` **不用 `.entity()`** | T080 | 真实调用返回合法 JSON |
| T082 | 输出校验：Jackson 反序列化 + 项名/顺序/满分/总分一致性；失败重试一次 | T081 | 非法输出用例：重试后仍失败则标 failed 并保留原文 |
| T083 | `ai_calls` 记录：model、prompt_version、tokens、latency | T081 | 每次调用留痕 |
| T084 | `StubGradingEngine`：测试与降级用，**不伪造分数** | T080 | 返回 `payload=null` + 说明 |
| T085 | 真机 AI 可选测试集：`@Tag("ai")`，`make test` 默认跳过，`make test-ai` 才跑 | T081 | 无 Key 时该组自动跳过且不报错 |
| T086 | `ExplanationEngine` + 解析 prompt：可拿题干/选项/答案，**不得拿学生身份与其他学生数据** | T056,T080 | 入参类型里没有 `studentId`；单测断言 prompt 不含学生信息 |
| T087 | 解析工作项：`objective_items.explanation_state` + 条件 UPDATE 领取 + 僵尸清扫 | T086,T090 | 50 个学生错同一题只生成 1 次（并发测试） |
| T088 | 选题与上限：按答错人次降序取前 N（默认 50）；超上限/无题干跳过并列出；教师可单题触发 | T087 | 有人全卷答错时仍封顶 N；被跳过的题在教师端可见 |

## 阶段 8 — 批改调度与进度查询

> 2026-10-18 简化：取消独立队列表、`SKIP LOCKED`、租约、`LISTEN/NOTIFY` 与 SSE。
> 工作项就是 `submissions` 行，用条件 UPDATE 领取；进度用轮询。详见 plan §7、§8。

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T090 | 领取：条件 UPDATE（`pending` → `running`），多 worker 安全；**`gradeSubmission(submissionId)` 与调用来源解耦** | T018 | 并发抢同一行只有一个成功；批改逻辑里不出现"下一个待处理是谁"的查询（便于将来换 MQ） |
| T091 | 僵尸清扫：启动清扫 + 每 2 分钟定时清扫（阈值 5 分钟） | T090,T009 | 用固定时钟模拟崩溃后任务被重新领取；不重复批在跑的项 |
| T092 | 到期派发：`@Scheduled` + ShedLock + `WHERE grading_dispatched_at IS NULL` 幂等 | T091,T009 | 两次并发派发只产生一个 `grading_run`；到点判定用固定时钟 |
| T093 | 峰谷调度（`peak_aware`）：高峰推迟到空闲起点；**手动派发不受限**；时区 `Asia/Shanghai` | T092,T009 | 用固定时钟测 8:59/9:00/11:59/12:00/13:59/14:00/17:59/18:00 与周末；关闭开关后立即派发 |
| T094 | 进度查询接口 `GET /api/offerings/{id}/progress`（单条聚合 SQL） | T091 | 多实例返回一致；响应 ≤ 200 ms |

## 阶段 9 — 复核与审计

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T100 | 复核三态：`approved` / `edited` / `rejected`；非法流转拒绝 | T082 | 三态流转单测 |
| T101 | 编辑：改主观分与理由、优点、建议；**自动记录是否改过分与文本 diff** | T100 | 审计含 before/after；教师无需自选"小改/大改" |
| T102 | 客观分默认不可手改；改标准答案则整批重算并写审计 | T100,T070 | 重算后所有相关 review 的客观分同步 |
| T103 | 发布 / 撤回；仅 approved 或 edited 可发布 | T100 | 未批准发布 409；已发布编辑 409 |
| T104 | 批量通过（默认跳过边界敏感项） | T103 | 每份独立写审计；回报跳过数量 |
| T105 | 审计时间线接口 + 前端页面（提交→派发→判分→复核→发布） | T103 | 顺序与时间正确 |

## 阶段 10 — 学生端与班级概览

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T110 | 学生作业列表：提交状态、是否有反馈、是否未读 | T103 | 只列已发布作业 |
| T111 | 学生反馈详情：客观得分 + 错题、主观项、优点、建议、总分 | T110 | 他人反馈一律 404 |
| T112 | 班级概览：姓名、是否提交、attempt、提交时间、状态、分数 | T110 | `publish_scores=0` 时他人分数只显示"已批改"；**自己的分永远可见** |
| T113 | 概览内容隔离 | T112 | 他人正文/附件/评语一律 404；`show_peer_overview=false` 时学生看不到概览 |
| T114 | 通知：发布生成、列表、已读 | T103 | 读数正确 |
| T115 | 学生反馈中为每道错题附上该题解析（查表复用）；未生成的显示「解析未生成」，不编造 | T111,T087 | 解析到达后自动补上；缺解析时不显示假内容 |

## 阶段 11 — 导出

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T120 | 逐份反馈 Markdown（YAML front matter） | T071 | 含客观/主观小计与总分 |
| T121 | 班级汇总 Markdown（确定性统计，不生成教学建议） | T120 | 数字与库一致 |
| T122 | PDF：OpenPDF + 内置 Noto Sans CJK + **启动字体自检** | T120 | 单份与全班 PDF 中文正常；缺字体返回 503 |
| T123 | 成绩 XLSX / DOCX（POI） | T120 | 表头随语言；中文不乱码 |
| T124 | ZIP 打包（只含已发布） | T121 | 内容与界面一致 |

## 阶段 12 — 国际化

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T130 | 后端 `MessageSource` + `ApiError(code)` 渲染 | T004 | 切 `Accept-Language` 文案变、`code` 不变 |
| T131 | 前端 i18next + 语言切换与持久化 | T008 | 中英切换生效并记住 |
| T132 | 作业级 `feedback_language` 注入 prompt | T080,T130 | 两种语言各断言一次 prompt |

## 阶段 13 — 可观测

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T140 | 请求日志 filter：requestId、userId、route、status、耗时；**无正文** | T003 | 日志可解析为 JSON；正文不出现 |
| T141 | `POST /api/events` 前端事件收集（白名单 + 限流），**追加写 JSONL 文件，不入业务库** | T019,T140 | 非白名单拒绝；超限 429；业务库中查不到事件明细 |
| T142 | 日志文件按天轮转 + 90 天删除；**审计不做删除** | T140 | 过期文件被删；审计行数只增不减（有测试断言） |
| T144 | 每日汇总任务：聚合前一天数据写 `daily_stats`（页面浏览、动作、提交、批改、错误、慢请求、p95） | T140,T019 | 可重跑幂等；数字与当天文件一致 |
| T143 | 日志输出预留 `LogSink` 接口（ES 后续接入） | T140 | 关闭 ES 时功能不受影响 |

## 阶段 14 — 报表（spec 待细化，先做骨架）

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T150 | 聚合查询：按学生 / 按班级 / 按作业；**平均分只算已提交，未交单列** | T111 | 数字与列表页一致 |
| T151 | 报表导出复用 XLSX / PDF | T150,T123 | 中文正常 |
| T152 | **常见错误**统计：每题正确率、最常被选错的选项与人数，按正确率升序 | T150 | 覆盖全部客观题（不受 50 道上限影响）；数字与 `objective_answers` 一致 |

> 报表的最终形态（定时生成、自定义、图表）待 spec US-17 定稿后再补任务。

## 阶段 15 — 界面框架与全局约定（需求 1–16）

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T180 | 教师首页：跨班级聚合，统计数字可点击跳转过滤列表 | T043 | 不选班级也能看到全部班级待办；数字点击后列表按状态过滤 |
| T181 | 批改总览页：统计卡片 + 作业级表格，默认按截止倒序 | T092 | 点数字改过滤且写入 URL；表格点行进入详情 |
| T182 | 作业批改详情页：逐份提交表 + 重试失败 / 立即派发 / 进复核 | T181、T100 | 逐份状态正确；三个操作可用 |
| T183 | 全局列表约定：默认时间倒序、服务端分页、列排序、URL 状态 | T003 | 任一列表刷新后排序/分页/过滤保持一致 |
| T184 | 行操作列与审计入口；移除左侧「审计」一级导航 | T105、T183 | 审计只能从实体行进入；倒序显示 |
| T185 | 新建**试卷**分步向导（5 步 + 草稿）：① 基本信息 ② 内容 ③ 标准答案 ④ 评分项 ⑤ 预览发布；**流程中没有选班级** | T050 | 中途退出重进不丢内容；总分不一致时禁止发布；整条流程不出现班级选择 |
| T186 | 导入格式扩展 .docx / .md / .xlsx；**题目与答案同文档抽取并配对** | T056 | 一份「题目+答案写在一起」的卷子能一次导入；缺答案项标出 |
| T187 | 考试专注模式 + inline 试卷：隐藏全局导航；整卷渲染（部分/题干/选项/分值，作答控件内嵌）；**顶部剩余时间常驻，≤5 分钟转警示色；归零后全部控件禁用并显示「已自动交卷」** | T111 | 客观题选项就地可点；完形/语法空格就地填；考试中不显示答案；到点后任何输入都被拒绝 |
| T188 | 试卷目录树：两级（部分→题目）+ 作答状态（未答空心灰／已答蓝色实心／已标记琥珀，按 AC-21.14 的四通道，不以颜色深浅区分）+ **部分级完成态：该部分全答完后不再置灰并显示 `已答 x/y`** + 点击跳转；窄屏折叠为抽屉 | T187 | 77 题可折叠浏览；点节点右侧滚到该题；听力第一节 1–5 全答完后整行恢复正常字色；已答与未答在灰度屏上仍可区分 |
| T189 | 滚动联动：右侧滚动 → 左侧树高亮 + 自动展开父节点 + 保持高亮可见 | T188 | 滚到第 41 题时树高亮 41 且折叠的「完形填空」自动展开 |
| T195 | 考试草稿与断点续答 + **提交前未答检查弹层**（按部分分组列出未答/已标记题号，点击跳转补答，未答不阻止提交） | T189 | 刷新后恢复；3 题未答时弹层列出这 3 个题号且分组正确；点题号跳到该题并关闭弹层；全部答完只做确认 |
| T190 | 学生作业视图（说明 + rubric + 提交区 + 保存草稿） | T060 | 三种提交形态可用；显示剩余尝试次数 |
| T191 | 班级汇总页（应交/实交、平均分、分布、正确率、未交名单） | T150 | 数字与逐份反馈一致；可导出 md/pdf/xlsx |
| T192 | 报表班级多选 + 跨班跨时间段 Top N 错题聚合 | T150 | 默认全选；给定 3 班 6 个月能出 Top 10 错题 |
| T193 | 学生反馈列表支持表格 / 卡片两种视图并可切换 | T110 | 切换被记住；默认时间倒序 + 分页 |
| T194 | 反馈详情信息顺序：总分 → 错题与解析 → 主观评语 → 全班概览 | T111、T115 | 顺序符合定义；错题解析先于班级信息 |
| T197 | 写作题输入框高度由字数算出（`word_limit × 1.2 ÷ 每行字数`，宽度变化时重算）+ 实时字数与容差提示（英文切词 / 中文计字，题面占位不计） | T187 | `word_limit=80` 的框一次显示约 96 词且无滚动条；改窗口宽度后高度跟着变；64–96 词时提示消失 |
| T198 | **内容快照与按班覆盖**：发布时把试卷结构、题项、标准答案写入 `offerings.paper_snapshot`（JSONB）+ `paper_version`；解析层一律读快照不读活表；`offering_classes` 的 `due_at_override` / `duration_override` 参与截止与时长计算 | T014、T040 | 布置后修改标准答案，已交卷子的分数不变；某班覆盖 `due_at` 不影响另两班；同一份试卷布置 3 次，`papers` 仍只有 1 行 |
| T199 | **服务端权威计时与到点自动交卷**：`started_at` / `deadline_at` 落库；剩余时间由服务端算；`individual` 与 `fixed_window` 两种模式；**定时作业扫到期自动交卷（以最后一次自动保存为准，标 `auto_submitted`）**，不依赖学生点提交 | T198、T091 | 改本机时间不延长；换设备重连剩余时间不变；到点后 60 秒内提交状态变为 `auto_submitted` |
| T200 | 考试入口页（考前须知）：标题、时长、题量、总分、开始/截止时间、注意事项；点「开始答题」才起算；`fixed_window` 显示「已统一开考，本场在 hh:mm 结束」 | T198 | `individual` 模式下未点开始不计时；`fixed_window` 模式下全班的 `deadline_at` 完全一致 |
| T201 | **布置页（与建卷分开）**：选试卷 → 类型（考试/作业）→ **参与班级多选** → 可开始时间 / 截止时间 / 时长（默认取试卷默认值）/ 计时模式 → 按班覆盖表 → 发布并通知；发布时做内容快照 | T185、T198 | 勾 3 个班生成 1 条 offering + 3 条 offering_classes；覆 2 班的 `due_at` 不影响 1 班；改试卷不影响已布置的考试 |
| T202 | 迟交与拒收：`late_by_seconds` 记录；班级汇总分列按时提交率；教师可对单份迟交「拒收 + 理由」，学生可见；拒收后仍有尝试次数可重交 | T198、T100 | 过 `due_at` 仍能提交并标记迟交；拒收后学生看到理由并可重交；`grace_hours` 只推迟批改启动，不拒绝提交 |
## 阶段 16 — 部署（阿里云）

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T160 | 多阶段 Dockerfile：构建 + 运行；镜像内置中文字体与 `ffprobe` | T122 | 镜像内 `/healthz?deep=1` 通过（含字体自检） |
| T161 | `docker-compose.prod.yml`：app + worker + postgres；**无 Redis** | T160 | 本地可跑通完整链路 |
| T162 | 部署文档 `docs/deploy-aliyun.md`：DNS/TLS/RDS/OSS/密钥/迁移/worker | T161 | 干净 ECS 可照做 |
| T163 | `.env.example` 补全并注明必填（含 `DEEPSEEK_API_KEY`、`JAVA_HOME`） | T162 | 与配置项逐项比对无缺 |

## 阶段 17 — 回归与硬化

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T170 | E2E 旅程 A（手工）：整份试卷形态 | T124 | 按 plan §18.4 清单逐项执行并记录结果 |
| T171 | E2E 旅程 B（手工）：纯写作 + new attempt | T124 | 按 plan §18.4 清单逐项执行并记录结果 |
| T172 | 安全自查：未登录 401、缺 CSRF 403、越权 404、上传白名单、富文本消毒、限流 | T035 | 逐项测试 |
| T173 | **四模型段落计数 parity**：把 v1 那 10 份真实作业复制进 `src/test/resources/fixtures/`，断言四种口径逐份一致 | T072 | 10 份全部一致 |
| T174 | 并发压测：50 份提交 / 2 app + 2 worker | T091 | 记录到 `.docs/perf-report-v2.md` |
| T175 | golden set 质量回归：10 份已人工评分的真实作业，记录平均偏差/最大偏差/偏差方向 | T081,T173 | 首次基线记录到 `.docs/golden-set-baseline.md` |

---

## 关键路径

```
T001 → T006 → T010 → T011 → T014 → T016 → T018 → T019 → T020
     → T030 → T033 → T036 → T040 → T043
     → T050 → T053 → T060 → T063
     → T070 → T071 → T080 → T081 → T082
     → T090 → T091 → T092 → T094
     → T100 → T103
     → T110 → T112
     → T120 → T122
     → T170 → T173
```

**最短可演示路径**：T001–T008（骨架）→ T010–T020（库）→ T030–T043（登录与班级）
→ T050–T066（作业与提交）→ T070–T082（判分）→ 能看到"客观自动判分 + AI 判主观"。

**可上线路径**：再完成 T090–T105（队列/派发/复核）→ T110–T114（学生端）
→ T120–T124（导出）→ T160–T163（部署）→ T170–T174（硬化）。

---

## 需求追踪矩阵

`spec.md` 的 26 个用户故事、42 条功能需求全部有对应任务。203 条验收标准在各自用户故事的
任务里被覆盖（验收标准与任务是一对多的关系，因此不逐条列出以免矩阵变成噪音）。

### 用户故事 → 任务

| 用户故事 | 覆盖任务 | 用户故事 | 覆盖任务 |
| :--- | :--- | :--- | :--- |
| US-1 | T030–T034 | US-2 | T040–T043 |
| US-3 | T036 | US-4 | T050、T051、T054、T056、T185、T186 |
| US-5 | T052、T053、T055、T196 | US-6 | T060–T063、T066、T067 |
| US-7 | T064 | US-8 | T065、T202 |
| US-9 | T092、T093 | US-10 | T070、T071、T080–T085、T094 |
| US-11 | T100–T105、T182 | US-12 | T110、T111、T114、T193、T194 |
| US-13 | T112、T113 | US-14 | T120–T124 |
| US-15 | T130–T132 | US-16 | T105、T140–T143、T184 |
| US-17 | T150–T152、T192 | US-18 | T056、T086–T088、T115 |
| US-19 | T180 | US-20 | T181、T182 |
| US-21 | T187–T189、T195、T197、T199、T200 | US-22 | T190 |
| US-23 | T191 | US-24 | T183 |
| US-25 | T184 | US-26 | T198–T201、T202 |

### 功能需求 → 任务

| 功能需求 | 覆盖任务 | 功能需求 | 覆盖任务 |
| :--- | :--- | :--- | :--- |
| FR-001 | T030、T031、T032 | FR-002 | T033 |
| FR-003 | T034 | FR-004 | T040、T043 |
| FR-005 | T036 | FR-006 | T050、T198 |
| FR-007 | T052、T055 | FR-008 | T053 |
| FR-009 | T060、T061 | FR-010 | T062 |
| FR-011 | T063、T070 | FR-012 | T064 |
| FR-013 | T065、T202 | FR-014 | T092、T093 |
| FR-015 | T081、T084 | FR-016 | T082 |
| FR-017 | T071 | FR-018 | T094 |
| FR-019 | T100、T101 | FR-020 | T019、T105 |
| FR-021 | T103、T114 | FR-022 | T111 |
| FR-023 | T112、T113 | FR-024 | T120、T122、T123、T124 |
| FR-025 | T130、T131 | FR-026 | T140、T141、T143 |
| FR-027 | T150、T151、T192 | FR-028 | T067 |
| FR-029 | T037、T141 | FR-030 | T056 |
| FR-031 | T086、T087、T088 | FR-032 | T152 |
| FR-033 | T180 | FR-034 | T181、T182 |
| FR-035 | T187、T188、T189、T195、T197、T199 | FR-036 | T190 |
| FR-037 | T191 | FR-038 | T183 |
| FR-039 | T184 | FR-040 | T186 |
| FR-041 | T198、T201 | FR-042 | T199、T200 |

---

## 未实现声明（v2 不做）

- **阅读理解文章分栏（sticky 左文章 / 右题目）**：托福 2023 改版采用此布局，但会与「单一滚动区 + 目录树滚动联动」冲突，V1 保持纵向排列；见 `.docs/research/2026-10-19-toefl-ibt-interface.md`。
- OCR（图片提交一律拒绝）
- 视频内容转写与 AI 评分
- 多租户、LMS 对接、第三方分析服务
- Elasticsearch（只留 `LogSink` 接口）
- Server-Sent Events / WebSocket（进度用轮询）
- 中国法定节假日日历（峰谷调度只按周一至周五时段判断）

---

## 执行记录

| 日期 | 阶段 | 结果 | 验证 |
| :--- | :--- | :--- | :--- |
| 2026-10-18 | 规格与方案定稿 | 完成 | spec 已确认；plan 含 7 项实测结论 |
| 2026-10-18 | 阶段 0–16 任务拆解 | 完成 | 本文件 |

**说明**：本清单尚未开工。按约定，实现需用户明确批准后开始，且每完成一个任务在此追加一行。
