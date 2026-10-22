# 任务清单 — TeachLoop（v2，Java）

- 上游：`spec.md`（已确认）、`plan.md`（含实测结果）
- 完成定义（DoD）：代码 + 测试 + `make test` 全绿 + 本文件执行记录勾选。缺一不算完成。
- 依赖规则：`依赖` 列写了前置任务的，必须全部完成才能开始。
- 建表规则：**一条迁移只建一个模块的表**（宪法第十四条）。每个建表任务的验收都写了「迁移文件里只出现 xx 表」，写错了 T204 会失败。
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
| T204 | **跨模块越界检查**：模块的迁移文件只写自己拥有的表（§3.3 清单）；业务模块不得直接查别人的表；**端口签名只收发 `record`，出现 `@Entity` 即失败**（为拆服务留路，§3.5）；跨模块外键只允许 plan §3.5 清单内那 13 条 | T203、T010 | 故意在 `grading` 迁移里写 `paper_` 表时检查失败；把 `GradingOutcome` 的参数从 `record` 换成 `@Entity` 时检查失败 |
| T205 | **端口定义**（宪法第十四条 + plan §3.2）：`PaperCatalog`（paper 暴露，只读快照）、`SittingLedger`（sitting 暴露，只读作答）、`GradingOutcome`（grading 暴露）、`NotificationPort`，以及阅卷改工作项状态用的 `GradingWorkQueue`（sitting 暴露）；**端口只收发 `record`，命令类方法必须幂等** | T210、T018 | 端口签名里没有 `@Entity`；阅卷模块代码里不出现 `submissions` 的 Repository；把 `GradingOutcome` 入参换成 `@Entity` 时 T204 失败 |
| T008 | `web/` 前端工程：Vite + React + TS + i18next 骨架 | T001 | `npm run build` 通过 |
| T009 | **`Clock` bean**：全项目统一注入 `java.time.Clock`，禁止直接 `now()` | T003 | 可用 `Clock.fixed(...)` 把系统时间钉在任意时刻（宽限期/峰谷/清扫都要用它） |

## 阶段 1 — 数据库与迁移

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T010 | Flyway 基线 + **按模块分目录的迁移约定**（`teachloop-<module>/src/main/resources/db/migration/`，统一在 `teachloop-app` 调度，文件名 `V<序号>__<模块>_<描述>.sql`） | T006 | 空库 `migrate` 成功；新增模块加目录即可被执行 |
| T011 | `users`：role[admin\|teacher\|student]、email、password_hash、phone、display_name、locale、status、created_by（只建 identity 模块的表） | T010 | 唯一约束生效（email、phone） |
| T012 | Spring Session JDBC 标准表 `spring_session`、`spring_session_attributes`（只建 identity 模块的表） | T011 | 会话可写入/读取；`DELETE` 按 `principal_name` 删所有会话（退出登录立即全域失效）；迁移文件里只出现 `spring_session*` 表 |
| T013 | `classes`、`class_members`（**只建 classroom 模块的表**） | T011 | `UNIQUE(class_id,user_id)` 生效；迁移文件里只出现 `classes` / `class_members` |
| T014 | `papers`：标题、`default_duration_minutes`、总分、版本、状态、`author_id`，**无 class_id、无 kind**（只建 paper 模块的表） | T011 | `papers` 里没有任何班级或布置相关外键；创建与编辑全程不需要选班级；迁移文件里只出现 `paper*` 表 |
| T015 | `paper_sections`、`objective_items`（含 `stem` / `options` / 标准答案 / `explanation_state` / `question_type`）、`section_criteria`（只建 paper 模块的表） | T014 | `UNIQUE(paper_section_id,number)`、`UNIQUE(paper_section_id,position)`；迁移文件里只出现 `paper*` 表 |
| T210 | `offerings`（`paper_id`、kind、start_at、due_at、duration_minutes、timing_mode、grace_hours 3、max_attempts 3、feedback_language、show_peer_overview、publish_scores、`paper_snapshot` JSONB、`paper_version`、rubric_source_md、grading_prompt_template、rubric_version、grading_dispatched_at、status）+ `offering_classes`（多班 + `due_at_override` / `duration_override`）（只建 sitting 模块的表） | T014、T013 | 默认值断言通过；`(status, due_at)` 索引存在；迁移文件里只出现 `offering*` 表 |
| T016 | `submissions`：attempt_no、input_kind、active、content_html、content_text、duration_seconds、width、height、objective_total、subjective_total、started_at、deadline_at、auto_submitted、late_by_seconds、rejected_at、reject_reason，**外加工作项列 `grading_state` / `grading_attempts` / `grading_started_at` / `grading_error` / `grading_run_id`**（属于 sitting 模块，阅卷只能经 `GradingWorkQueue` 端口改） | T210 | 部分唯一索引 `(offering_id,student_id) WHERE active` 生效；工作项查询索引存在；**不建独立队列表** |
| T017 | `objective_answers`、`submission_metrics`（只建 sitting 模块的表） | T016 | `UNIQUE(submission_id,item_id)`；迁移文件里只出现 `objective_answers` / `submission_metrics` |
| T018 | `grading_runs`：一次派发的运行记录 + 幂等键（只建 grading 模块的表） | T016 | 同一 offering 并发派发只产生一条 run；迁移文件里只出现 `grading_*` 表 |
| T019 | `audit_logs`、`daily_stats`（只建 observability 模块的表；审计由 `AuditPort` 在业务事务内写入） | T011 | 建表通过；`audit_logs` 没有更新/删除接口；迁移文件里只出现 `audit_*` / `daily_*` 表 |
| T207 | `reviews`、`item_explanations`、`ai_calls`（只建 grading 模块的表） | T018 | `item_explanations` 的唯一键 `(item_id,language,prompt_version)` 生效；迁移文件里只出现 `review*` / `item_*` / `ai_*` 表 |
| T208 | `teacher_invites`、`rate_limits`、`notifications`（只建 identity 模块的表；`NotificationPort` 由 identity 暴露，接收方与联系方式不外出） | T011 | 邀请码过期字段、限流计数、站内通知表生效；迁移文件里只出现 `teacher_invites` / `rate_limits` / `notifications` |
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
| T206 | 试卷库列表页：我创建的全部试卷（标题、总分、默认时长、版本、状态、被布置次数），操作：编辑 / 复制为新卷 / 布置 / 归档；默认最近修改在上 | T050、T183 | 一份试卷被布置 3 次后列表显示「已布置 3 次」；复制出的新卷与原卷互不影响 |
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
| T092 | 到期派发：`@Scheduled` + **数据库条件更新**（`WHERE grading_dispatched_at IS NULL`）幂等；不引入 ShedLock | T091,T009 | 两次并发派发只产生一个 `grading_run`；到点判定用固定时钟 |
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

## 阶段 14 — 报表

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
| T191 | 班级汇总页（应交/实交、按时提交率、平均分、分布、正确率、未交名单）；**一场考试挂多班时按班分别统计，不混算** | T150 | 三个班同考一场，每班数字独立且与逐份反馈一致；可导出 md/pdf/xlsx |
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
| T173 | **四模型段落计数 parity**：用 `docs/rubrics/samples/` 那 10 份作文生成**脱敏后可提交**的 fixtures（`src/test/resources/fixtures/`），断言四种口径逐份一致 | T072 | 10 份全部一致；fixtures 在仓库内，CI 不依赖本机文件 |
| T174 | 并发压测：50 份提交 / 2 app + 2 worker | T091 | 记录到 `.docs/perf-report-v2.md` |
| T175 | golden set 质量回归：以同一批 10 份 fixtures 的人工评分为基线，记录平均偏差 / 最大偏差 / 偏差方向 | T081,T173 | 首次基线记到 `docs/reports/golden-set-baseline.md` |

---

## 关键路径

```
T001 → T006 → T010 → T011 → T013 → T014 → T015 → T210 → T016
     → T208 → T018 → T207 → T019 → T205 → T020
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

`spec.md` 的 28 个用户故事、61 条功能需求都有对应任务（FR-043/044 由 2026-10-20 的未编号增量实现）。258 条验收标准在各自用户故事的
任务里被覆盖（验收标准与任务是一对多的关系，因此不逐条列出以免矩阵变成噪音）。

### 用户故事 → 任务

| 用户故事 | 覆盖任务 | 用户故事 | 覆盖任务 |
| :--- | :--- | :--- | :--- |
| US-1 | T030–T034 | US-2 | T040–T043、T213 |
| US-3 | T036 | US-4 | T050、T051、T054、T056、T185、T186、T206、T217 |
| US-5 | T052、T053、T055、T196 | US-6 | T060–T063、T066、T067 |
| US-7 | T064 | US-8 | T065、T202 |
| US-9 | T092、T093 | US-10 | T070、T071、T080–T085、T094 |
| US-11 | T100–T105、T182、T243、T244、T246、T263、T264 | US-12 | T110、T111、T114、T193、T194、T264 |
| US-13 | T112、T113、T211 | US-14 | T120–T124 |
| US-15 | T130–T132、T264 | US-16 | T105、T140–T143、T184 |
| US-17 | T150–T152、T192、T215、T216 | US-18 | T056、T086–T088、T115 |
| US-19 | T180 | US-20 | T181、T182 |
| US-21 | T187–T189、T195、T197、T199、T200 | US-22 | T190、T238、T240、T241 |
| US-23 | T191 | US-24 | T183 |
| US-25 | T184 | US-26 | T198–T201、T202、T212、T237–T239、T241 |
| US-27 | T255–T267 | US-28 | T258、T265、T268 |

### 功能需求 → 任务

| 功能需求 | 覆盖任务 | 功能需求 | 覆盖任务 |
| :--- | :--- | :--- | :--- |
| FR-001 | T030、T031、T032 | FR-002 | T033 |
| FR-003 | T034 | FR-004 | T040、T043、T213 |
| FR-005 | T036 | FR-006 | T050、T198、T206、T230 |
| FR-007 | T052、T055 | FR-008 | T053 |
| FR-009 | T060、T061 | FR-010 | T062 |
| FR-011 | T063、T070 | FR-012 | T064 |
| FR-013 | T065、T202 | FR-014 | T092、T093 |
| FR-015 | T081、T084 | FR-016 | T082 |
| FR-017 | T071 | FR-018 | T094 |
| FR-019 | T100、T101、T218、T219 | FR-020 | T019、T105、T221 |
| FR-021 | T103、T114、T221 | FR-022 | T111、T220 |
| FR-023 | T112、T113、T211 | FR-024 | T120、T122、T123、T124 |
| FR-025 | T130、T131、T226 | FR-026 | T140、T141、T143 |
| FR-027 | T150、T151、T192、T215、T216 | FR-028 | T067 |
| FR-029 | T037、T141 | FR-030 | T056 |
| FR-031 | T086、T087、T088 | FR-032 | T152 |
| FR-043 | 未编号增量（2026-10-20「管理员初始设置」，见执行记录） | FR-044 | 同上 |
| FR-045 | T214 | — | — |
| FR-033 | T180、T228 | FR-034 | T181、T182、T222、T228 |
| FR-035 | T187、T188、T189、T195、T197、T199、T224、T229 | FR-036 | T190、T225、T238、T240、T241 |
| FR-037 | T191、T227 | FR-038 | T183、T228 |
| FR-039 | T184、T222 | FR-040 | T186、T217 |
| FR-041 | T198、T201、T212、T237、T239 | FR-042 | T199、T200 |
| FR-046 | T231 | FR-047 | T235 |
| FR-048 | T238、T240、T241 | FR-049 | T242 |
| FR-051 | T247 | — | — |
| FR-052 | T249 | — | — |
| FR-053 | T251 | — | — |
| FR-050 | T243、T244、T246 | FR-054 | T256、T257、T264 |
| FR-055 | T259 | FR-056 | T261 |
| FR-057 | T258、T264 | FR-058 | T263、T264 |
| FR-059 | T265 | FR-060 | T262 |
| FR-061 | T268 | — | — |

---

## 阶段 18 — Mockup 对等性修复（2026-10-20 用户拍板）

上游：`docs/reports/mockup-parity/README.md`（21 屏逐屏审计，P0 6 / P1 53）＋
`spec.md` §9 的 D-6…D-11。本阶段全部**未开始**；每条验收都可独立测试。

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T211 | **学生班级概览**（D-6）：`GET /api/offerings/{id}/overview` 返回姓名、是否提交、attempt 次数、提交时间、状态、分数；未提交学生出行占位；分页与排序；`show_peer_overview=false` 返回关闭态；`publish_scores=0` 时他人分数置空、自己始终可见；越界字段 404。前端新增学生二级页与导航入口，反馈详情 ④ 段改为入口链接 | T198、T210 | 学生 A 请求 B 的正文/附件/评语 404；`publish_scores=0` 时他人分数为空而自己分数在；反馈未发布也能打开概览；50 人班分页 20/50 正常 |
| T212 | **布置可编辑**（D-8）：`PUT /api/offerings/{id}` 支持截止、宽限期、最多尝试、反馈语言、两个可见性开关、参与班级与按班覆盖；开考后 `due_at`/`duration_minutes` 只增不减，调小返回 400；移除已开始的班返回 400（AC-26.11）；写 `offering:updated` 审计含前后值。前端编辑入口与影响面提示 | T201、T210 | 改截止后学生端剩余时间随之变化；开考后调小被拒并给出原因；可见性开关变更前显示受影响班级与份数；审计可查到前后值 |
| T213 | **邀请码生命周期**（D-9）：迁移加 `invite_expires_at` / `invite_disabled` / `join_deadline`；`join` 对过期、停用、过加入截止一律 404；轮换重置为 now+3d；新增停用端点；班级表显示「剩余有效期 / 已停用 / 加入截止」 | T040、T043 | 过期码 join 404；停用后 join 404；过 `join_deadline` join 404；轮换后旧码 404、新码可用；班级表三种状态可见 |
| T214 | **数据范围**（D-11）：`/api/classes` 对 ADMIN 返回全量并附 `teacherName`，教师仍只返回自己的班；`/api/offerings` 与 `/grading/queue` 对教师按归属过滤、对 ADMIN 全量；ADMIN 的写操作仍受「不做评分决定、不改教学内容」限制 | T043、T181 | 教师 B 看不到教师 A 的班与作业；ADMIN 能看到全部且每行带归属；ADMIN 改他人班级邀请码被拒 |
| T215 | **报表导出接线**（D-7 第一步）：报表页接 `/api/reports/offerings.xlsx` 与 `.pdf`；日期区间把 `from/to` 传给 `wrong-items`；删掉「导出（未接入）」文案 | T151、T192 | 点导出得到非空 xlsx 与以 `%PDF` 开头的文件；缩小日期区间后错题条数下降；页面不再出现「未接入」 |
| T216 | **报表勾选导出**（D-7 第二步，AC-17.13）：学生列表与题目列表可勾选 + 全选 + 已选统计；`POST /api/reports/export` 接收学生集/题目集/格式，异步生成后下载；支持 Excel / PDF / ZIP | T215、T150 | 只勾 1 名学生与 2 道题时导出内容只含这些；100 名学生导出不阻塞请求；ZIP 只含已发布反馈 |
| T217 | **导入解析增强**（D-10）：`ParsedItem` 加 `section` 与 `confidence`，`ImportResult` 加 `sections`；按部分落库；解析结果先进暂存批次，确认后才对试卷生效；校对表按部分分组、低置信度标黄、显示「N 部分 M 节」 | T186、T050 | 4 部分 8 节文本导入得到 4 个 section；缺答案与低置信度行标黄；未确认就放弃时试卷不被污染 |
| T218 | **复核数据补全**（后端）：`WorkItem` 加客观分、错题号、词数与段落数、边界敏感标记；`/grading/queue` 加 `studentName` 与 `status`、`offeringId` 过滤；`ReviewDetail` 返回分项分（criteria）与总分 | T100、T207、T186 | 队列返回姓名且可按状态与作业过滤；详情返回客观分、错题号、指标与分项分；边界敏感提交被标出 |
| T219 | **复核页展示与操作**（前端）：左栏显示姓名与四态；右侧显示客观分与错题、正文词数/段落、分项分可编辑、当前总分实时计算；三态按钮改为「原样通过 / 修改后通过 / 驳回」；批量通过默认跳过边界敏感 | T218 | 缺数据时显示「—」而不是 0 或编造；改分项后总分同步变化；批量通过回执给出通过/跳过/总数 |
| T220 | **学生反馈补全**：`/my-feedback` 加分项分与满分、`rank`、单题 `correctRate`、`publishedAt`、客观/主观满分，反馈列表加错题数；前端渲染分项评分、优点、建议、排名（`publish_scores=0` 时显示「教师未公布」） | T194、T111 | 作文分项 10/15 与 8/15 可见；优点与建议两行出现；排名随开关显示或隐藏；他人内容仍 404 |
| T221 | **审计时间线补全**（AC-16.3）：提交、派发、客观判分、AI 批改完成、解析生成、复核、发布七类事件都能从同一份 submission 查到；事件带 `actorName/role`；`detail` 存 JSON；前端显示中文事件名与时间线 | T105、T184、T086 | 跑完一场从提交到发布，时间线 ≥7 条且每条有「谁」；抽屉不出现 `review:edited` 这类原始 key |
| T222 | **批改详情补全**（AC-20.5/20.6）：未提交学生占位行；行内「复核」直接定位该份；失败行行内「重试」只重派该份 | T181、T182、T218 | 32 人班列表 32 行含未提交；点行内复核直达该份；单项重试不影响其他份 |
| T223 | **班级页补全**：班级下钻显示该班作业与试卷（含草稿），班级名可点；加「新建作业」「导入试卷」入口；补待办数（口径：待复核 + 批改中 + 待发布，页面写明） | T213、T214、T201 | 草稿布置在班级页可见并标「草稿」；从班级能进入作业列表；待办数与首页一致 |
| T224 | **考试段落模型与续答**：快照与 `/paper` 返回材料块（passage），作答页按段落渲染完形/语法空格与阅读语篇；`/session` 返回已保存答案，`writeAnswers` 改增量 upsert；断网重连不覆盖 | T187、T188、T198 | 同一完形 10 空渲染在同一材料下；换设备继续作答保留服务端已存答案；重连后答案不被清空 |
| T225 | **作业视图补全**（US-22，按 D-14 修订）：题目要求取自布置内容快照的 ESSAY 题项（**不新增 `instructions` 列**）；作业不返回考试 NOTES；`rubricMarkdown` 透出；富文本工具栏补粗体/斜体/下划线/有序/无序/小标题 | T190、T186 | 作业页显示题目要求与两项评分标准（15/15)；工具栏 6 个按钮可用且消毒白名单通过；考试页不再显示作业文案 |
| T226 | **登录与 i18n 收口**：微信扫码 tab 与 prod 教师/学生放行；登录页加 `/setup` 首次部署入口；资料未补全前端重定向；资料/初始化/AI 三页文案入 i18n；语言偏好写回 `profile.locale`；前端未定义 key 静态检查 | T030、T034、T036、T130 | prod 下教师可扫码登录；未补全访问 `/` 跳 `/profile`；`check-i18n` 对前端缺失 key 退出码 1 |
| T227 | **班级汇总补全**（US-23）：四张统计卡（应交/实交、平均分仅已提交、最高、最低）；各题正确率按升序并标部分；主观项均分；平均分排除批改失败 | T191、T218 | 统计卡数字与逐份反馈同源；正确率确实升序；未交与失败不计入平均分 |
| T228 | **首页与批改总览状态细分**（US-19/US-20）：首页补「批改中 12/30」「待复核 N」「可发布」等状态细分与行内复核入口；总览四列可排序、状态含「有失败」；四卡点击后表头显示对应过滤条件 | T180、T181 | 状态细分数字来自 `/progress`；点「已完成」后表头出现过滤条件；四列可排序 |
| T229 | **学生端细节**（US-21/US-12）：考前须知补客观/主观拆分与参与班级名；反馈列表卡片补「错题 N」；考试目录树图例；作业补视频提交入口 | T200、T193、T062 | 考前须知显示「客观 120 + 主观 30」；卡片显示错题数；图例与实际状态色一致；视频入口可用 |
| T230 | **新建/导入试卷可用性**（用户实测 critical path）：补 22 个未定义 i18n key；第 2 步导入前自动建草稿卷与部分，使上传/粘贴在新建态可用；向导内支持粘贴整卷；后端补 `PUT/DELETE /api/papers/{id}/sections/{sectionId}` 与 `DELETE /api/papers/{id}/items/{number}`（级联删题项/评分项、重算总分、已发布需 confirm、位置取最大值+1）；前端部分/题项行内编辑与删除 | T185、T186、T050 | 新建卷不先同步即可上传或粘贴导入，导入后第 3 步能看到并可改/删题项；删部分后该部分题项与评分项一并消失且总分重算；已发布卷删除需 `confirm=true`（无则 409）；`PaperCrudTest` 5 条全绿、`cd web && pnpm build` 通过 |

| T231 | **真实试卷 Markdown 导入**（用户 2026-10-20 提供的南昌十九中高一英语月考整卷）：新增 `MarkdownSyntax` 剥掉 `#`/`**`/列表/表格记号（保留下划线空）；`PaperTextParser` 重写结构识别——`第X部分` 落成部分、`第X节` 取「每小题 X 分」让题项分值逐题走、题干行内并排选项拆分、完形/七选五/语法填空无题号自动按空编号并把材料并入题干且标 `___(题号)___`、写作部分不产题项只提醒；修掉答案区标题行差一位被拼进最后一题的 bug；`POST /import` 与 `/import/text` 多部分时按文档结构建部分（单部分仍用目标部分） | T230、T186 | 真实整卷导入后 75 题、4 个部分（20/20/35/0）、总分 120 分、逐题分值 1.5/2.5/1/1.5/1 正确；写作要求里的「1. 表达理解；」不再被当成第 1 题；`RealExamImportTest` 9 条 + 既有 28 条全绿 |
| T232 | **主观作业端到端可用性**（用户 critical case：布置 3 个写作作业 → 学生作答 → 批改 → 复核 → 反馈）：作业页接上「题目要求」（取 `/api/offerings/{id}/paper` 的题项，ESSAY 题干即题目）与「评分标准」（`EntryView` 新增 `rubric`，来自布置上的 rubricMarkdown）；学生反馈页显示教师写的「优点/建议」；作业页删掉考试计时说明与重复的一行 | T190、T201、T110 | 作业页能看到题目与评分标准；学生能看到评语+优点+建议；三条作业在浏览器里跑通「布置→作答→AI 草稿→复核→发布→学生看反馈」 |
| T233 | **布置 create 带 publish=true 未落库**（critical case 中实测）：`offerings.save()` 对自带主键的实体走 merge 返回托管副本，之后 `publishInternal` 改的是原对象 → 接口返回 PUBLISHED、库里仍是 DRAFT，学生看不到作业 | T201 | create 带 publish=true 后重新读库为 PUBLISHED；`publishInternal` 内显式 save |
| T234 | **批改卡在 RUNNING + 会话唯一键冲突**（2026-10-20 已修，根因已定位）：① 卡 RUNNING 的根因是 `NoClassDefFoundError` 未被 `catch (Exception)` 捕获（当时开发机上一个旧进程的 jar 被原地重编覆盖，类加载失败，它抢到 claim 后整条 tick 崩掉，行就停在 RUNNING）——改为 `catch (Throwable)` + ERROR 日志 + 幂等 `markFailed` 兜底，并加 claim/grade/done 的 DEBUG 日志，同时清扫两段化（到 `grading.max-attempts` 标 FAILED）、`retry-failed` 回收卡住 1 分钟以上的 RUNNING；② `spring_session_attributes_pk` 冲突用 V025 触发器把重复插入变成 upsert | T091、T083 | worker 抛 `Error` 也会落 FAILED 并留日志；领满 3 次的卡住项变 FAILED 且不再被反复回收；手动重试能救 1 分钟以上的卡住项；同一 (session, 属性名) 连插两次不报错且值取最后；`GradingDispatchTest` 9 条、`SessionAttributeUpsertTest` 1 条全绿 |
| T235 | **答案格式宽容化 + 独立答案入口**（AC-4.17/4.18，用户 2026-10-20 实测三种写法全失败后定）：`PaperTextParser` 答案标记词扩到 `参考答案/标准答案/正确答案/答案` 并认 `【】` 包裹；`parseAnswerLines` 对「只有题号没有答案」（如 `3.`）改为提醒跳过而不是抛异常让整卷 400；新增纯字母串（`ABCDA`/`A B C D A`）按题号连续配对，不连续则告警拒绝不给分；`ImportPaperPage` 校对页加「粘贴答案表」面板调 `POST /api/papers/{id}/answers`，补完重拉题项并现算缺答案 | T231、T055 | 四种关键字与 `【标准答案】` 都能开启答案区；`3.` 留空只告警不中断；`ABCDA` 在连续题号下正确配对、在不连续题号下告警且不配；`PaperTextParserTest` 15 条 + `RealExamImportTest` 9 条（75 题/4 部分/120 分）全绿、`pnpm build` 通过 |
| T242 | **答案的朴素写法与填空题文本答案**（AC-4.19/FR-049，用户真实卷 `paper.md`+`answers.md`+`paper and answer.md` 回归）：① 字母捕获组加 `(?<![A-Za-z])…(?![A-Za-z])`——`56. challenging` 此前被静默读成 `C`；② 区间答案允许空格分隔 `1-5: A B B C C`；③ 新建 `AnswerFreeText`，题项没有选项时整行取文本答案、按题号切片、截断「空格+左括号」注释，`AnswerTableParser` 加 `parse(text, freeTextNumbers)` 重载、`PaperEndpoint.fillAnswers` 传 `FILL_BLANK` 题号；④ `AnswerTableParser` 先过 `MarkdownSyntax.clean`；⑤ 答案区容错（小标题/范文跳过，只有以题号开头却解析不出才报错）+ 标题放宽（含「参考答案/标准答案/正确答案」且 ≤40 字、无句末标点即认定） | T235、T055 | 两个真实文件原样导入：整卷式 75 题 / 4 部分 / 120 分 / **75 题全有答案**、0 缺；两步式 `answers.md` 原样粘贴得 75 条（含 `56=challenging`、`60=when / if`）；`mvnw -pl teachloop-paper -am test` **64/64** 绿（新增 `AnswerTableParserTest` 13 条、`RealExamWithAnswersTest` 4 条）；`pnpm build` 通过 |
| T247 | **文案集中到 JSON + 后端接通 i18n**（AC-4.20/FR-051，用户「很多翻译不准确、我改起来要方便」）：① 三个 `locales.ts` 与 `i18n.ts` 里的 base 合并成 `web/src/locales/zh-CN.json` / `en.json`（各 907 键，顶层 key 路径不变，组件零改动），删除三个 `locales.ts`；② 补齐 4 个只有中文的 key（`teacher.summary.exportXlsx/exportDocx/exportZip/exportZipBlocked`），删掉 4 个只有英文的死 key；③ 收掉 108 行组件内硬编码中文（18 个文件，含 `ImportPaperPage` 37 行、`SetupPage` 13、`AdminAiPage` 10、`ReviewPage` 12 的组件内私有字典 `zh`/`L`）；④ 后端 `GlobalExceptionHandler.handleApi` 支持消息 key（是 key 才解析，历史字面量原样返回），`ReviewEndpoint` 两条改为 key 并补中英文案，修掉当时是红色的 `check-i18n`；⑤ 前端 `api/client.ts` 每个请求带 `Accept-Language`；⑥ `scripts/check-i18n.py` 扩展成三查：前端 zh/en 键对齐 + 值非空且≠键名、后端中文字面量基线、新增 `i18n-web-baseline.txt` 锁前端存量 | T243、T130 | `python3 scripts/check-i18n.py` 退出码 0（改前为 1）：前端 zh/en 各 907 键完全对齐、后端新增 0 处（基线 291）、前端新增 0 处（基线 1）；`cd web && pnpm build` 通过；`GlobalExceptionHandlerTest` 3 条（key 按语言解析、历史原文不变、空消息不炸）、`I18nErrorTest` 1 条全绿 |

## 阶段 19 — D-14 作业与考试在数据层分开（2026-10-20 用户拍板）

上游：`spec.md` §9 D-14、US-22、AC-26.1/26.6/26.13/26.17。目标：作业永远自带内容、不引用试卷；
试卷只用于考试；`kind` 由内容来源派生，并由数据库约束保证两者不混。

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T236 | **规格/计划/mock 回写**：spec §1/§1.1/US-22/AC-26.1/26.6/26.13/26.17/AC-25.4、D-14（取代 D-13）、FR-048；plan §5/§7.6/§14；mock 新增 assignment-new/assignments 屏并把班级「新建作业」改指向 | — | 四份文档计数自洽、无重复编号；mock 里新建作业不再进试卷向导 |
| T237 | **V026 迁移 + 实体**：存量作业回填 `paper_id=null, paper_version=0`；`offerings.paper_id` 去掉 not null；加 `offerings_kind_content_ck CHECK ((kind='ASSIGNMENT') = (paper_id IS NULL))`；`Offering.paperId` 可空 | T236 | 空库迁移成功；回填后无「作业带试卷」行；`kind=EXAM` 必须带试卷 |
| T238 | **后端创建独立作业**：`CreateOffering` 加 title/instructions/totalPoints/rubricMarkdown；`paperId` 派生 kind（无 paperId=作业、有=考试），`kind` 仅做一致性校验；作业合成 1 条 ESSAY 内容快照；`view().paperId` 空安全；`writeAnswers` 跳过 ESSAY；审计 source | T237 | `paperId+kind=ASSIGNMENT`、无 paperId+kind=EXAM、作业带时长/按班覆盖 → 400；作业 `paper_id IS NULL` 且快照 1 条 ESSAY；学生可打开并提交 |
| T239 | **报表/导出适配无试卷作业**：`ExportReadService` 与 `AggregateReportService` 的 `join papers` 改 `left join`，标题 `coalesce(p.title, paper_snapshot->>'title')` | T237 | 独立作业出现在按作业聚合与 xlsx/pdf，标题非空；考试数字不变 |
| T240 | **前端作业入口**：导航加「作业」；`/assignments` 列表（kind=ASSIGNMENT）+ `/assignments/new` 单页表单（标题/题目要求/总分/评分标准/班级/截止/宽限/尝试次数）；布置页改纯考试并删 kind；i18n zh/en；学生作业页单 ESSAY 去序号 | T236 | 新建作业全程无试卷字段；发布后学生可作答；`tsc --noEmit` 与 `pnpm build` 通过 |
| T241 | **测试与实测**：9 个既有 fixture 按严格版迁移（含客观题改 EXAM、纯写作/派发改自带内容）；新增 `StandaloneAssignmentTest`；补 `MigrationTest`/`AggregateReportTest`/`ExportTest`；`make test` 全绿；浏览器跑通「新建作业→学生提交→派发→复核→反馈」并截图 | T238、T239、T240 | 所有断言真实通过；未跑的验证不许写「已验证」 |

## 阶段 20 — D-15 人工判分（AI 可选，2026-10-20 用户拍板）

上游：`spec.md` D-15、US-11（AC-11.11–11.16）、FR-050。目标：老师可对任何已交卷提交人工判分并发布，
AI 只是可选草稿；AI 晚到不得覆盖人工分。

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T245 | **规格/计划/mock 回写（D-15）**：spec D-15、US-11 intro + AC-11.11–11.16、FR-050；plan §6.5；mock 复核屏补人工判分说明 | — | 四份文档计数自洽、无重复编号 |
| T243 | **后端人工判分**：`Ports.SubmissionScores` + `SittingLedger.scores`；ReviewEndpoint 的 GET/PUT 懒建 review 并返回当前客观/主观分；无 AI 草稿未填分时拒绝直接通过；bulk-approve 跳过未判分项；GradingWorker 在已有教师改分/已发布时跳过（成功与失败都不覆盖）；新增 `ManualGradingTest` | T245 | 无派发直接人工判分→发布→学生可见；人工分不被 AI 覆盖；FAILED 可兜底；批量通过跳过未判分项；指定测试全绿 |
| T244 | **前端判分入口**：批改详情逐份行加「判分 / 复核」→ `/review?submissionId=`；ReviewPage 支持 URL 直达、AI 草稿为空时提示「AI 未参与」、当前客观/主观分正常显示 | T245 | `tsc --noEmit` 与 `pnpm build` 通过；无裸 key |
| T246 | **全量回归 + 浏览器实测**：`make test`、静态守卫、浏览器「不派发 AI，直接人工判分→发布→学生反馈」并截图 | T243、T244 | 真实通过；未跑的验证不写「已验证」 |

---

## 阶段 21 — 2026-10-20 用户实测反馈修复（T248）

上游：2026-10-20 用户对已交付页面的三条实测反馈——① 作业页交完还要点按钮才能看提交内容；
② 顶部「资料」与中/EN 快捷切换不成体系；③ 批改页「审计」抽屉全是 `submission:submit {late=0, attempt=1}`
这类内部代号，教师读不懂。目标：最小改动修掉这三处，不改数据模型、不加新页面。

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T248 | **三处实测反馈修复**：① `AttemptView` 增 `contentHtml` / `contentText`（学生自己的提交正文，写入时已消毒），作业页「已提交」卡片直接回显正文与附件链接，删掉重复的附件按钮；页面在接口还没返回时退回本次提交的内存正文。② 资料页改为「我的资料」+ 独立「偏好设置」卡片，界面语言改成 `<select>`（缺省中文，选项由 `web/src/locales/*.json` 自动生成，新增语言只需加一份 json），顶栏移除中/EN 快捷按钮。③ 审计抽屉把事件代号、参数、时间翻成中文可读文案（事件名词表、字段名词表、枚举词表、隐藏 UUID 类参数、`late`/`attempt` 读成「按时提交／第 1 次提交」），`fmtDateTime` 跟随界面语言格式化。 | T246 | `SubmissionFormsTest` 断言 `/my-submissions` 返回已消毒正文；`tsc --noEmit`、`pnpm build`、`check-i18n`（zh/en 键对齐）通过；浏览器实测资料页语言下拉可切中/英、审计抽屉三条事件中文可读并截图 |
| T249 | **第二运行环境（快照测试台）**（FR-052，用户「你在改的时候我可以继续 E2E 测试」）：新增 `scripts/env.sh` 管理命名环境，状态全在 `.envs/<name>/`（jar/dist/uploads/VERSION/pid）；`deploy` 在主树构建后**复制**快照并重启，前端用 `vite preview` 服务静态 dist（不监听源码）；独立 Postgres 容器 `teachloop-pg-qa`(55433) + 库 `teachloop_qa`；`web/vite.config.ts` 的代理目标与端口改读 `TEACHLOOP_API`/`WEB_PORT`，并显式配置 `preview.proxy`；`scripts/dev.sh` 的库名与附件目录参数化（`DB_NAME`/`STORAGE_LOCAL_DIR`，默认不变）；Makefile 加 `qa`/`qa-up`/`qa-down`/`qa-status`；`.gitignore` 加 `.envs/` | T130 | `make qa` 后 :5174 可登录、SPA 深链 200、`/api` 代理 200、`/healthz` UP；在主树改文案并重新 `make build` + `pnpm build` 后，测试台 PID 与资源 hash 不变、页面不含新文案；再跑 `make qa` 后测试台资源 hash 变化并出现新文案；`make check-deps/check-boundaries/check-i18n` 全绿 |

---

## 阶段 22 — 2026-10-20 第二轮实测反馈（T250）

上游：用户对 T248 之后的页面继续实测，指出——① 资料保存后顶部「资料」按钮还在；② 必填项空着没有任何提示，
用户不知道要填；③ 资料页要能随时改；④ 富文本看起来不能输入；⑤ 学生交的富文本在复核页显示不出格式；
⑥ 复核页一排按钮分不清哪个才是「提交批改意见」。目标：把「补全资料」做成会消失的提醒，把富文本做成真编辑器，
把复核页收敛成一个明确的主操作。

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T250 | **第二轮实测反馈**：① `AuthProvider` 读 `/api/profile` 计算 `profileComplete`（手机号 + 邮箱都填才算），顶部只在未补全时显示「补全资料」，保存后立即消失；侧栏用户名常驻 `/profile` 入口，资料页仍可随时修改。② 资料页必填项加红色 `*`、红框、字段级红字与顶部红条，未填时禁用保存并说明原因。③ 新增 `RichTextEditor`（粗体/斜体/下划线/有序/无序列表/小标题/正文、空态占位），作业页富文本改用它；本机草稿同时存纯文本、HTML 与输入形态，刷新可恢复。④ `Ports.WorkItem` / `ReviewDetail` 增 `contentHtml`，复核页「学生正文」按消毒后的 HTML 回显。⑤ 复核页操作区收敛为「保存并发布反馈」主按钮 +「仅保存草稿」+「退回重批」+「撤回」+「审计」，发布后隐藏保存类按钮只留撤回；禁用按钮统一降到 45% 透明；队列列名与状态中文化。 | T248 | 资料未补全时顶部有提醒、必填项红色高亮；保存后顶部提醒消失且侧栏仍可进资料页；富文本能输入、能加粗、刷新后草稿（含 HTML）仍在；复核页点「保存并发布反馈」学生立即看到反馈、撤回后回到未发布；富文本提交在复核页按段落回显。验证：`pnpm typecheck`、`pnpm build`、`check-i18n`、`make test`、浏览器实测 |
| T251 | **通知可跳转 + 已读即时生效**（用户 2026-10-20 在测试台提的 bug）：① 通知带可跳转目标——`OfferingEndpoint.notifyStudents` 的 target_type 由写死 `offering` 改为按布置类型给 `exam` / `assignment`；`ReviewEndpoint.notifyPublished` 的反馈通知改存 **offeringId**（反馈页参数是布置 id，原来存的是 submissionId，点过去必然错页）；② 通知卡片整条可点（含键盘 Enter/Space）→ 先标已读再跳转；加「查看」按钮、可点卡片 hover/focus 样式；③ 标已读改为**本地先行**（角标立刻 -1）再同步后端，失败只回滚该条；④ 新增 `NOTIFICATIONS_CHANGED` 事件，Shell 的未读角标收到事件立刻刷新，不再干等 30 秒轮询 | T208、T114、T242 | `NotificationTest` 新增「作业发布的通知 target_type=assignment」；`ReviewWorkflowTest` 新增「布置通知是 exam+布置 id、反馈通知是 offering+布置 id 而不是 submissionId」；`mvnw -pl teachloop-app -am -Dtest=NotificationTest,ReviewWorkflowTest test` **7/7** 绿；测试台实测学生通知 `feedback_published → /feedback/{offeringId}`、目标接口 `my-feedback` 200；`tsc --noEmit` 0、`pnpm build` 通过、`check-i18n` 通过 |

---

## 阶段 23 — 2026-10-20 第三轮实测反馈（T252）

上游：用户继续在测试台实测——① 富文本不支持 Cmd+B / Cmd+I；② 编辑区底色发灰、聚焦有阴影；
③ 想知道这是不是标准插件；④ 保存草稿后回来格式全丢；⑤ 批改详情页想要「先让 AI 批、教师再复核」的绿色通道。

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T252 | **富文本与 AI 绿色通道**：① 编辑器补 Cmd/Ctrl + B/I/U 快捷键；粘贴按后端同一份白名单清洗（丢灰底/字号/颜色/外部标签，b→strong、i→em）；编辑区固定白底、聚焦不再加阴影。② 草稿恢复改为「本机优先、本机没有就用服务端 `contentHtml`/`contentText`」，换浏览器/换端口/清 localStorage 都不再丢格式；提交前 HTML 统一成后端白名单里的 strong/em/u/ol/ul/h3。③ 新增 `POST /api/offerings/{id}/submissions/{submissionId}/grading/dispatch`：只把这一份从 QUEUED/FAILED 推进到 PENDING，run 按场次幂等，写 `submission:ai_dispatch` 审计；批改详情逐份行加「让 AI 先批」，整场按钮改名「全部交给 AI 批改」。 | T250 | 富文本 Cmd+B/I/U 生效；粘贴外部 HTML 后无灰底、无 style/span；编辑区背景纯白、无阴影；保存草稿→清 localStorage→刷新，粗体/斜体/列表格式仍在；单份派发后该份变 PENDING、同场其他提交仍是 QUEUED，重复派发 409，学生派发 403；AI 完成后复核页出现 AI 草稿。验证：`GradingDispatchTest` 10/10、`pnpm typecheck` / `pnpm build` / `check-i18n`、浏览器实测 |

---

## 阶段 24 — 2026-10-20 第四轮实测反馈（T253）

上游：用户看复核页与审计抽屉——① 审计里「复核改分」是一串 `manual/aiSubjectiveDraft/beforeSubjective/textDiff` 原始字段，读不懂；② 顶栏找不到进 profile 的入口；③ 让 AI 批完之后不知道 AI 的结果在哪；④ 要用真实作业（rubric + Maria/Mark 两份 submission）跑一遍完整流程。

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T253 | **AI 结果可见 + 复核页收敛**：① AI 逐项意见与评语落库（`reviews.ai_criteria` JSON 文本 + `ai_feedback`，V027/V028），复核详情返回 `aiCriteria` / `aiFeedback`；② 复核页新增「AI 评估」区（AI 建议分、逐项得分与意见、优点/建议、「采用 AI 建议分」），并把「题项与评分标准」折叠、标题去重（复核详情 → 学生正文 → AI 评估 → 教师判分与评语 → 批改与发布）；③ 审计「复核改分」改写成一句话（主观分 `7.5 → 8`、评语/优点/建议，去尾零、null 不显示）；④ 顶栏右侧用户信息可点击进 `/profile`。 | T252 | AI 批完复核页能看到逐项意见；审计不再出现原始字段名；顶栏点击 `JZ · teacher@…` 进资料页；用真实 rubric + Maria/Mark 跑通「建作业 → 两学生提交 → 单份 AI 派发 → 教师复核发布 → 学生反馈可见」。验证：`GradingDispatchTest` 10/10（补 `aiCriteria/aiFeedback` 断言）、`make test` 209/0/0/0、`pnpm typecheck` / `pnpm build` / `check-i18n` 通过；截图 `docs/reports/t253-review-ai/` |

---

## 阶段 25 — 2026-10-21 评分质量（D-16–D-22）

上游：用户看了复核页的「AI 建议分 7.5 / 内容要点 4.5/6 / 语言准确性 3/4」，指出这不是 AI 评分，
而是 stub；要求按 `Grading_Prompt.md` + `Alejandro_review.txt` 的质量重做，并拍板：
初始化/登录/资料三处配 Key、Key 不明文、反馈语言跟学生界面语言、作业 rubric 优先、
问候语不计段、AI 反馈预填 + 批量通过。**本轮用户选择「只出规范」**：T254 只改文档，
其余任务等用户明确说「开始实现」后再执行。

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T254 | **规格/研究/任务回写**：新增 `docs/research/2026-10-21-grading-quality.md`（stub 归因、10 份探针、外部证据）、`docs/reports/grading-quality/default-rubric-review.md`、spec US-27/US-28（AC-27.1–27.16、AC-28.1–28.11）、D-16–D-22、FR-054–061、plan §6.1/§6.5–6.7/§9/§11/§18.5、tasks 本阶段。 | T253 | 四份文档计数吻合、无重复编号；用户拍板的 7 项都有对应 AC；默认 rubric 的问题与替换方案有书面结论；**不改业务代码**。 |
| T255 | **数据库迁移**：`user_ai_settings`（user_id 主键、密文列）、`offerings.created_by`、`reviews.ai_overall/ai_strength/ai_suggestion/ai_model/ai_prompt_version/ai_key_source/ai_failure/feedback_language`、`ai_calls.key_source`；只写各模块自己的表。 | T254 | `MigrationTest` 空库迁移通过；升级 dev 库后旧数据可读；`check-boundaries.py` 不报越界；`offerings.created_by` 历史空值有回填策略（空 = 全局 Key）。 |
| T256 | **Identity：Key 生命周期**：setup 必填 Key + 测试；`/profile` AI 设置 GET/PUT/test（仅 TEACHER/ADMIN）；Key 加密、GET 只回掩码、保存即生效；审计只记事件与操作者；日志/异常不含 Key。 | T255 | 首个管理员不填 Key 不能完成 setup（可选「仍然保存」需审计警告）；保存后 GET 不回明文；测试连接 401 给出「认证失败」；学生访问 403；`AiSetupTest`/`ProfileAiSettingsTest` 覆盖；审计无 Key 片段。 |
| T257 | **Key 解析 + 客户端工厂**：`AiKeyResolver`（作业创建者 → 全局 → NO_KEY）；`LlmClientFactory` 按次创建；`WorkItem.createdBy`；去掉启动时固化 Key 与 `restartRequired`；鉴权失败不静默换 Key。 | T255, T256 | user/global/none 三种路径单测；改 Key 后下一次调用生效（无需重启）；`key_source` 落 `ai_calls`；`NO_KEY` 时不发网络请求。 |
| T258 | **Rubric 契约服务**：解析评分项/满分/规则；校验名称非空唯一、每项有分值、和 = 总分；缺分值/重复/和不等于总分阻止保存，不可执行规则给 warning；默认 rubric 统一为课程 `docs/rubrics/Rubric.md`（2/4/4）；`GET /api/rubrics/default` 或 classpath 单一来源；`POST /api/rubrics/preview` 返回「AI 将看到」文本。 | T255 | 用 `Rubric.md` 解析出 3 项 2/4/4 且和 = 10；两行空模板被拒；和不等/重复/缺分值各有 400 用例；预览含题目要求、rubric、政策、输出契约；前后端不再各写一份默认。 |
| T259 | **PromptBuilder v2**：角色 + 题目要求 + 完整 rubric（判定规则）+ 评分政策 + 证据要求 + `overall` + 反馈语言（user）+ 可选锚点（system）；`PROMPT_VERSION=subjective-v2`；拒绝把姓名/学号/submission_id 放进 prompt。 | T257, T258 | `PromptBuilderTest` 断言各段存在、语言在 user、姓名不在 prompt；同一作业两次构造 system 完全一致（缓存前缀）；无锚点时不出现空锚点段。 |
| T260 | **GradeOutputValidator v2**：逐项证据引用（逐字、忽略大小写/空白、≥3 词）、comment 非空且 ≥15 字、strength/suggestion 非套话、语言合规、二元/分档、长度结论一致、total 求和；失败重试 1 次；保留原始输出。 | T259 | 编造引用/空评语/中文混入 en/3/4 这类中间分/长度与代码不一致各有拒绝用例；合法输出 1 次通过；连续 2 次失败抛错且不落分。 |
| T261 | **篇幅指标接进产品**：`TextLengthCounter` 四模型在判分与复核详情按 `content_text` 现算（policy_default 段数 + 边界敏感，不新增表）；`SubjectiveGradeRequest` 与 `ReviewDetail` 暴露；批量通过跳过边界项。 | T255 | 10 份 fixture 的段数与 Python 脚本一致；Genesis/JZ/Maria/Mark 标边界；复核页显示段数与政策；批量通过跳过边界项，单份通过给「边界敏感，请确认」提示（用例）。 |
| T262 | **引擎/Worker 去假分**：生产/测试台默认 `ai`；stub 仅 dev/test profile；失败分类 `NO_KEY/AUTH_FAILED/TIMEOUT/INVALID_OUTPUT/RATE_LIMITED` 写 `ai_failure`；AI 草稿写 overall/model/prompt_version/key_source；任何失败不出分。 | T257, T260, T261 | stub 在 ai profile 下不可用；无 Key 提交 FAILED 且无主观分；失败原因在复核详情可见；AI 成功时 `reviews` 五个新列齐全；人工分不被覆盖（沿用测试）。 |
| T263 | **复核 API：预填 + 批量通过预览**：详情返回 aiOverall/aiFailure/来源；批量预览返回合格份数、分数范围、跳过原因；确认后逐份审计 `bulk=true`；发布仍独立。 | T262 | 打开详情预填三字段与分数、不落库；保存后 teacher_id 落库；预览跳过边界/失败/无草稿/未过闸门；批量只通过合格项；审计逐份可查。 |
| T264 | **前端**：setup 增加 Key 字段与测试；教师登录无 Key 提示；`/profile` AI 设置卡片（掩码/保存/测试）；作业创建 rubric 校验结果 + 预览 + 导入；复核页 AI 草稿预填、来源/失败状态、批量通过对话框；学生反馈不出现 AI 内部信息；zh/en key 对齐。 | T256, T258, T263 | `pnpm typecheck`/`build` 0；`check-i18n` 通过；浏览器实测无 Key 提示→设置→AI 可用；创建作业拒绝空 rubric；复核页预填可改；批量通过显示跳过项；学生端无 AI 内部字段。 |
| T265 | **Golden set 评估**：扩展 `scripts/golden-set.py` 为 `scripts/grading-eval.py`（逐项 exact/within-one、总分 MAE、契约/引用/语言合规、边界单列）；按 D-19 更正 JZ 参考为 10/10、Mark 8/10、Maria 10/10；写 known-inconsistency 说明与报告模板。 | T259, T260, T261 | 10 份样本跑出完整报告；JZ 更正前后都有记录；无 Key 退出码 2 不写假数；报告含每份一行与失败样本；阈值写入脚本。 |
| T266 | **测试面**：单测 + 集成（Key 优先级/明文泄漏/假分/学生语言/rubric 校验/长度/validator/批量跳过/预填/锚点匿名化）。 | T256–T265 | 新增测试全绿；`make test` 退出码 0；`check-i18n`/`check-boundaries`/`check-deps` 通过。 |
| T267 | **端到端实测**：初始化填 Key → 教师资料改 Key → 用 `Rubric.md` 建作业 → 3 名学生提交 → 派发 → 真实 AI 出逐项证据 → 复核页预填 → 改一份/批量通过 → 发布 → 学生反馈；截图与数据断言。 | T266 | 浏览器全链跑通；AI 输出含逐字引用与英文评语（学生 locale=en）；批量通过跳过边界项；学生只看到教师最终内容；截图存 `docs/reports/grading-quality/e2e/`；**未跑的部分如实标注**。 |
| T268 | **锚点接缝（可选）**：0–3 份匿名范文的存储/校验/注入/降级；默认关闭；不做自动挑选与专门编辑器（本期）。 | T255, T259 | 未配置时 prompt 无锚点；含姓名/学号/联系方式的锚点被拒；超预算降级为无锚点并记录；同一作业两次 system 一致。 |

---

## 阶段 26 — 2026-10-21 答题页题干标签修复（T320）

上游：用户实测答题页截图，每道阅读题开头多出一个孤立字母（Q26 的 B、Q30 的 C），
误以为是标准答案泄露。核对数据后确认是阅读材料标签 A–D 被拼进题干（学生端接口本来就不返回 `answerKey`）；
用户拍板「开头不需要显示任何字母」。

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T320 | **材料标签不进题干**：`PaperTextParser` 识别到单独一行的 A–G 材料标签时只切分材料、不再写入 `material`；新增 `V032`（`objective_items` 存量题干）与 `V033`（`offerings.paper_snapshot` 存量快照）清理首行标签，答案/选项/分值/顺序不动。 | T231 | 新导入的阅读题题干不以孤立字母开头；QA 库迁移后 30 条题干与 1 场快照不再命中 `^[A-G]`，快照结构 diff 为 0；`PaperTextParserTest` 新用例 + `RealExamImportTest`/`RealExamWithAnswersTest` + `Phase2ExamTimingTest` 全绿；答题页 Q21–Q35 开头无字母。 |
| T321 | **共用材料只渲染一次**：`MaterialGrouper` 在考试快照上识别阅读材料组与完形/语法填空空组，`ExamPaperItemView` 增 `groupKey/material/questionStem/blankIndex`；前端按组渲染：阅读材料一次 + 题目列表，完形/语法填空材料一次 + 行内控（选择题点空弹 A–D，填空题行内输入），目录树跳转、滚动联动、标记、提交检查仍按题号；任一分组校验不过整组回退现状。 | T320 | 真实快照分组为 21–23/24–27/28–31/32–35/36–40/41–55/56–65，66–75 独立；`MaterialGrouperTest` 6 条；答题页 41–55 只出现一次 1,223 字文章、56–65 只出现一次 1,091 字文章；作答保存/草稿/交卷/判分不变；`pnpm typecheck`/`build`/`check-i18n`/`check-ui` 通过。 |
| T322 | **RubricValidator 文案 i18n**：校验问题从 `List<String>` 改为 `Issue(code,args)`，由 `RubricIssueText` 在预览/布置两个出口按 locale 渲染；5 条问题文案进 `messages.properties` / `messages_zh_CN.properties`；中文 rubric 解析词表移到 `sitting/domain/RubricVocabulary`，不靠扩 i18n 基线。 | T258 | `RubricValidatorTest` 4/4、`RubricIssueTextTest` 2/2；`check-i18n` 通过且基线 311 → 290；布置作业的 400 提示按请求语言返回中文/英文；`make test` 225/0/0/0。 |
| T323 | **复核页逐题判分可视化 + 学生作答前置**：`Ports.AnswerRow` / `ReviewDetail.answers` 增 `correct` 与 `awardedPoints`（客观题按快照答案用 `ObjectiveScoring` 重算，作文为 null）；题项表把原来的「分（满分）」拆成「得分 / 满分」，答对绿、答错红、部分给分琥珀，并给汇总；「教师判分与评语」前置学生作答（正文、主观题作答、客观题答题卡）与 AI 草稿预填提示及建议分。 | T263,T264,T322 | `ReviewWorkflowTest` 断言答对 10 分/答错 0 分；`pnpm typecheck` / `pnpm build` / `check-i18n` / `check-ui` 通过；QA 5174 实测 75 题显示 33 对/42 错/48 分，作文页 AI 建议分 7.5 预填且提示不谎称有评语。 |
| T324 | **布置按教师收口**：`GET /api/offerings` 非管理员只返回 `created_by = 自己` 的布置；`detail` / `submissions` / `class-summary` 加归属校验（越权 404）；新增 `V035` 把 V030 之前 `created_by` 为空的存量布置按参与班级的唯一属主回填。 | T201,T030 | 教师 A 的列表不含教师 B 的布置、`GET /{id}` 对 A 返回 404（`ClassAccessTest` 新增用例，2/2 绿）；QA 库 4 条空值布置回填到 `jzhfeng001`，教师首页不再出现裸 UUID 班级；`check-boundaries` 通过（25 表 / 13 外键 / 7 端口）。 |
| T325 | **新建作业页按教师反馈重做**：评分标准不再预填英文示例（留空则要求填写）；「导入 .md」从隐藏 label 改成标签行右侧的真按钮；两段长文本换成 Markdown 编辑器（工具栏 + 编辑/预览，手写渲染器，不引第三方库）；「AI 将看到」不再重复打印原文，改为按后端同一套规则解析出的评分项与合计对比；参考范文去掉「锚点」字样。 | T240,T258 | QA 5174 实测：导入 `评分标准2.md` 后 4 项 2/6/3/3、合计 14=总分 14 并提示可发布；截图 `docs/reports/`；`tsc` / `build` 0；`check-i18n` 1155 键对齐、`check-ui` 125 处不变。 |
| T326 | **班级成员白屏 + 首页班级列**：`ClassMemberView` 类型与成员表（姓名 / 学号 / 本班提交数）修掉「把对象当 React 子节点」导致的整页白屏；新增路由级 `ErrorBoundary`（按 pathname 复位）；首页与作业页班级列解析不到时不再打印裸 UUID。 | T042,T180 | QA 5174 点「成员」正常显示名字与提交数（此前白屏）；首页「最近作业」只列自己的布置、班级显示「3年2班」；边界生效后崩溃只坏一块、侧栏仍可用。 |
| T327 | **导入器：填空题顺推题号不能占用卷面已有题号**：真实卷「七选五 5 个空但只有 2 道题」时，顺推会吃掉后面完形的 9/10 与语法填空的 11，答案区「11. challenging」被当选择题答案而报错；改为先扫描卷面自带题号，填空分组遇到已被占用的题号即停并告警。 | T231 | 真实文件 `AI立即.md` 解析出 1–14 题、答案 `A B A C D B G A A B challenging/anxiety/to walk/impressive`、`missingAnswers` 为空、仅 1 条「还有 3 个空未成题」告警；`PaperTextParserTest` 23/23（新增该卷回归用例）。 |
| T328 | **评分标准解析两处误杀（用户 14 分 rubric 被拒的真因）**：`是否…` 开头的问句式评分项被当成 Yes/No 判定行丢弃；超过 80 字符的整句评分项被静默丢弃，导致「合计 8 分 / 总分 14 分」。改为「是否」视为问句、名称上限放到 200；前端预览同步。 | T258,T322 | `RubricValidatorTest` 6/6（新增中文问句与 95 字符整句两条）；`评分标准2.md` 导入后 4 项合计 14；前端 `rubric.ts` 对同一文本逐字段一致。 |
| T329 | **复核与报表按归属收口**：新增 `Ports.OfferingOwnership`（sitting 实现「这批提交 / 这批布置属不属于这位教师」），`Ports.ClassMembership` 增 `classIdsOwnedBy`；阅卷队列、复核详情与结论、批量通过、报表（按作业 / 学生 / 班级）与导出全部按归属过滤，越权 404。 | T324,T326 | `ClassAccessTest`：教师 A 看不到 / 改不了教师 B 的待复核提交、报表与导出 404，布置人自己正常；`make test` 242/0/0/0；`check-boundaries`（25 表 / 7 端口）、`check-deps` 通过。 |
| T330 | **作业夜间批量批改（决策 003）**：`findDueForGrading` 限定 `kind='EXAM'`；新增 `findAssignmentsWithQueued()` 与 `OfferingGradingDispatcher.dispatchAssignments()`；`AssignmentGradingJob` 每晚 02:30（Asia/Shanghai）跑一次 + 启动补跑；配置 `assignment-cron` / `assignment-zone` / `assignment-catch-up`。 | T092,T093 | 作业提交当晚被派发并批完，不等 due；考试仍按 due+宽限派发；同一场重复跑只有一条 run；启动补跑可重复执行。 |
| T331 | **复核队列只显示最新 attempt**：`Ports.OfferingOwnership` 增 `activeSubmissionIds`，队列过滤掉被重交取代的旧提交（旧复核行保留，不进队列）。 | T329,T330 | 学生重交两次后队列只有 1 条；旧提交仍可按 id 打开详情；批量通过不会作用到旧 attempt。 |
| T332 | **界面说明**：新建作业页与批改详情页写清「作业每晚 02:30 自动批改，急用可立即派发」。 | T325,T330 | 两处文案 zh/en 对齐；`check-i18n` 通过。 |
| T333 | **测试与 QA 验证**：夜间派发 4 条用例（作业当夜批、重复跑幂等、考试不受影响、队列去旧 attempt）。 | T330,T331 | `NightlyAssignmentGradingTest` 绿；`make test` 全绿；QA 实测一次夜间跑批。 |
| T334 | **复核页去重 + 题干格式化 + 去掉「采用 AI 建议分」**：学生正文只在「学生正文」渲染一次，删掉「学生作答」块里重复的正文与「AI 草稿」标签；主观题题干改用学生答题页同一个 `Markdown` 组件渲染，标题、列表、加粗与换行按老师输入显示；删掉「采用 AI 建议分」按钮（主观分首次打开已预填 AI 分，该按钮只在老师改分后用于一键改回），并删掉不再使用的 `review.adoptAi` / `studentAnswer` / `aiDraftBadge` 文案 key。T323 的「教师判分区前置学生正文」中重复正文部分由本条取代。 | T323 | `pnpm typecheck` / `pnpm build` / `check-i18n`（1153 键）/ `check-ui` 通过；`make test` 退出码 0（surefire 243/0/0/0）；QA 5174 实测：正文只出现一次，题干按 `Instructions` 标题 + 加粗标题 + 4 条列表显示，AI 建议分旁无按钮；截图 `docs/reports/t334-review-cleanup/`。 |
| T335 | **批改 run 收尾**：`grading_runs.state` 原来永远停在 `running`、`finished_at` 永远为空（实体有 `finish()` 但全仓无调用点）。新增 `GradingRunLifecycle`：worker 每批跑完后，若该场没有未完成工作项（PENDING/RUNNING/QUEUED）就把 run 收尾；派发时把已收尾的 run 重新打开（一场仍只有一条 run）。`Ports.GradingWorkQueue` 增 `hasOpenWork`。 | T330 | `NightlyAssignmentGradingTest` 断言：批完 → `state=done` 且 `finished_at` 非空；重交进队 → 重新 `running`；再批完 → `done`。`make test` 243/0/0/0。 |


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
| 2026-10-19 | T001 Maven 聚合工程 + 九模块建壳 | 完成 | `./mvnw -v` = Maven 3.9.9 / Java 21.0.11；`make validate` 通过；`make build` 产出 9 个 jar |
| 2026-10-19 | T002 Makefile | 完成 | `make help` 列出 8 个目标；`make java-home` 输出可 export 的路径 |
| 2026-10-19 | T203 依赖方向守卫 | 完成 | 故意让 classroom 依赖 grading 时 `make check-deps` 退出码 1，报「越界依赖」并指出环 `classroom -> grading -> sitting -> classroom`；还原后通过 |
| 2026-10-19 | T198 内容快照与按班覆盖 | 完成 | `paper_snapshot` 改 JSONB，`paper_version` 取自 `papers.version`；`offering_classes.due_at_override` / `duration_override` 参与截止与时长（`OfferingWindows`）。测试：布置后改标准答案分数不变；只覆盖 c1 时 c2 学生仍是 60 分钟/10 天后；同卷布置 3 次 `papers` 仍 1 行 |
| 2026-10-19 | T199 服务端权威计时与到点自动交卷 | 完成 | `started_at` / `deadline_at` 落库，`submitted_at` 区分草稿/已交；`GET /session` 剩余时间由服务端算，换会话重连不重置；`fixed_window` 未点开始也在计时；`ExamDeadlineJob` 每 30 秒扫 `deadline_at < now AND submitted_at IS NULL`，条件 UPDATE 定稿 `auto_submitted=true`，二次扫描 0 条。`grading_state=DRAFT` 的草稿不进批改队列 |
| 2026-10-19 | T200 考试入口页（考前须知） | 完成 | `GET /api/offerings/{id}/entry` 返回标题/题量/总分/时长/窗口/注意事项；`individual` 未点开始不计时；`fixed_window` 无开始按钮，两名学生拿到同一 `deadline_at`，文案「已统一开考，本场在 11:00 结束」 |
| 2026-10-19 | T201 布置页（与建卷分开） | 完成 | `POST /api/offerings` 支持 `classes[]` 多选 + 按班覆盖；勾 3 个班生成 1 条 offering + 3 条 offering_classes；发布触发 `NotificationPort`（当前落审计，T208 换成站内通知）；`GET /{id}` 返回各班覆盖与成员数 |
| 2026-10-19 | T202 迟交与拒收 | 完成 | 迟交按有效 `due_at` 算 `late_by_seconds`，过截止仍可提交；`POST /api/offerings/{id}/submissions/{sid}/reject` 需理由（400/403 用例覆盖），拒收后学生可见理由并可重交 attempt 2；`GET /{id}/class-summary` 分列按时/迟交/拒收与按时提交率 |
| 2026-10-19 | T205 端口定义（查询/命令分离） | 完成 | `SittingLedger` 只保留只读 `load`；命令方法移到 `GradingWorkQueue`；新增 `GradingDispatch`（一场一条 run，幂等）。`make check-boundaries` 断言 7 个端口只收发 record |
| 2026-10-19 | T204 跨模块越界检查 | 完成 | 新增 `scripts/check-boundaries.py` 并接入 `make test`：迁移表归属（23 张表）、跨模块外键清单（13 条）、业务模块 import 越界、端口签名。故意让 grading 迁移 `alter table papers` → 退出码 1；把 `GradingOutcome` 参数换成 `@Entity` → 退出码 1；还原后全绿 |
| 2026-10-19 | T091–T094 批改调度闭环 | 完成 | 僵尸清扫（启动 + 2 分钟，阈值 5 分钟、注入 Clock）；到期派发用 `offerings.grading_dispatched_at` 条件更新互斥（不引入 ShedLock），宽限期按 `due_at + grace_hours`；峰谷 8 个边界时刻 + 周末 + 开关；progress 改单条聚合 SQL。`GradingDispatchTest` 7 个用例全绿，`FlowIntegrationTest` 改为「交卷 QUEUED → 手动派发 → worker」 |
| 2026-10-19 | T080–T083 真实 AI 判分 | 完成（结构化 rubric 待补） | Prompt 分层（system=角色+rubric+输出契约，user=客观指标+正文），单测断言无标准答案/客观分/学生身份；DeepSeek OpenAI 兼容调用（`LlmClient` 可替换；Spring AI 因沙箱无法写 `~/.m2` 而回退，已记录）；输出校验失败重试一次；`ai_calls` 留痕。grading 模块 19 个单测全绿，真机测试无 Key 自动跳过 |
| 2026-10-19 | T008 + T131 前端工程与国际化 | 完成 | 新建 `web/`（Vite 8 + React 19 + TS 7 + i18next + react-router 7）；`make web-build` 通过；中英切换与 localStorage 持久化；ApiError 统一错误模型 |
| 2026-10-19 | T180/T181/T183/T184 教师端框架 | 完成 | 首页跨班聚合（未选班级即可看全部待办）、批改总览/详情、列表排序分页写 URL、行内审计抽屉（无一级「审计」导航）。浏览器实测截图：`.docs/screenshots/teacher-home.png`、`grading-detail.png`、`review.png` |
| 2026-10-19 | T182/T185/T191/T192 教师端流程 | 完成（部分依赖未接入） | 立即派发（runId/firstDispatch/enqueued 回执）、行内拒收理由、审计；5 步建卷向导（localStorage 草稿、全程不选班级）；班级汇总按班分列；报表页按作业×班级汇总。单份重试、导出、Top N 错题、正确率分布**未接入**并在界面可见标注 |
| 2026-10-19 | T187–T189/T195/T197/T200 考试端 | 完成 | 考前须知、专注模式（隐藏全局导航）、两级目录树（已答/未答/已标记、部分级 `已答 x/y`）、IntersectionObserver 滚动联动、断点续答与提交前未答检查弹层、写作框按字数与容器宽度计算高度、服务端计时与到点锁定。浏览器实测截图：`.docs/screenshots/exam-entry.png`、`exam-live.png`、`exam-submit-check.png`、`exam-result.png` |
| 2026-10-19 | T190/T193/T194 学生端 | 完成（部分依赖未接入） | 作业提交（草稿/提交/拒收后重交）、反馈列表表格/卡片切换且切换被记住、反馈详情固定顺序。错题与解析、班级概览依赖尚未实现的 T110/T111，界面明确显示「未接入」，未编造 |
| 2026-10-19 | 浏览器联调（教师 + 学生） | 完成 | 起 8010 后端 + 5173 前端，经 API 种入 1 份卷、1 个班、1 场考试、1 次作业；实测登录 → 教师首页 → 批改详情 → 立即派发 → 复核通过 → 学生考试 → 作答 → 未答检查 → 交卷。1280×813，截图在 `.docs/screenshots/` |
| 2026-10-19 | T070/T152 支撑：逐题对错落库 | 完成 | V012：`objective_answers.correct` + `item_id` 索引；提交/自动保存时按快照标准答案判定并落库。报表与反馈都读这一列，不在 SQL 里重算匹配策略 |
| 2026-10-19 | T111/T112/T113/T194 学生反馈详情与班级概览 | 完成 | 新增 `GET /api/offerings/{id}/my-feedback`：自己的分始终可见；反馈未发布时不返回标准答案与评语；错题含你的作答/正确答案（来自布置快照）；`FeedbackOverview` 给出应交/实交/我的分/全班均分（仅在 publishScores && showPeerOverview 时返回均分）。Phase2ExamTimingTest 覆盖「未发布不泄漏」「发布后错题与快照答案」「概览数字」 |
| 2026-10-19 | T192 报表 Top N 错题聚合 | 完成（导出未接入） | 新增 `GET /api/reports/wrong-items`（report 模块只读 SQL，CTE 去重最新 attempt；`objective_answers.correct` 聚合；classIds/from/to/limit）。报表页接线：查询按钮 + 正确率升序 + 最常错选；`WrongItemsReport` 6 个用例全绿（含 AC-17.10 limit 不缩统计、学生 403、时间/班级过滤、最新 attempt） |
| 2026-10-19 | T182 补：重试失败项 | 完成 | `POST /api/offerings/{id}/grading/retry-failed` 只把 FAILED 退回 PENDING（运行中/已完成不动），写审计；教师端按钮启用并显示重试份数 |
| 2026-10-19 | 浏览器复核（报表 + 反馈） | 完成 | 重启后端（含 V012）与 Vite，实测报表页 Top N 查询（正确率/最常错选/作答分布）与学生反馈详情（错题/正确答案/解析未生成/概览）；截图 `.docs/screenshots/reports-wrong-items.png`、`feedback-detail.png` |
| 2026-10-19 | T186 导入 .docx/.md/.xlsx | 完成 | `POST /api/papers/{id}/import`（multipart，教师；.doc 拒绝）；zip+XML 解析 docx/xlsx（无第三方依赖）；题号/选项/答案区配对，支持 `1. A`、`1-5 ABCDA`、`1 A 2 B`、行内 `（答案：A）`；缺答案创建 item 并返回 missingAnswers；错误带源文档行号；27 个 paper 测试全绿。向导第 2 步接入导入入口、预览与 warnings |
| 2026-10-19 | T120/T121/T124 Markdown/ZIP 导出 | 完成（PDF/XLSX 未接入） | `GET /api/offerings/{id}/export/summary.md`（确定性统计、与 class-summary 逐位一致、不生成教学建议）；`GET /api/offerings/{id}/export/feedback.zip`（publishScores=false → 409；逐份反馈 Markdown 含 YAML front matter、错题标准答案、复核评语；UTF-8 中文文件名）。班级汇总页接入两个下载；PDF/Excel 按钮保留「未接入」 |
| 2026-10-19 | T087/T088/T115 错题解析（后端 + 接口） | 完成（真机 AI 需 Key） | V013 `item_explanations` + 唯一键；`POST /api/offerings/{id}/explanations/generate`（教师；经 `ExplanationWorkQueue` 取答错人次 Top N，跳过已有/缺题干，幂等写入，返回 generated/existing/noStem/failed）；`Ports.ExplanationCatalog` 供 `/my-feedback` 读取，学生端有值显示解析、无值显示「解析未生成」；18 个新增测试全绿。无 DEEPSEEK_API_KEY 时失败计数且不写假解析 |
| 2026-10-19 | V014 历史数据回填 | 完成 | 对 V012 之前的 `objective_answers` 按单选/填空（大小写+空白归一）与多选（忽略顺序）回填 `correct`；复杂策略无法在 SQL 还原，已在迁移注释写明 |
| 2026-10-19 | 前端接导入/导出/解析 | 完成 | 向导第 2 步导入（错误显示 detail 行号、缺答案提示、导入后刷新总分）；班级汇总页下载 Markdown 与反馈 ZIP；批改详情「生成错题解析」按钮 + 四项计数回执。`make web-build` 通过 |
| 2026-10-19 | 全量回归 | 完成 | `make build` / `make test`（85 个测试通过，1 个真机 AI 跳过）/ `make web-build` / `make check-boundaries` / `make check-deps` 全绿 |
| 2026-10-19 | T196 题型模型完整版 | 完成 | V015 `objective_items.scoring_strategy`；新增批量设题型 `POST /{id}/items/question-type/batch`、单题改题型 `PUT /{id}/items/{number}/question-type`、多选计分策略 `PUT …/scoring-strategy`；单选→多选规范标准答案字母、多选→单选取首字母，**不触碰学生已有作答**；判分改为 `ObjectiveScoring`：单选/填空大小写不敏感、多选 `ALL_OR_NOTHING` 默认 / `PARTIAL` 按（对选−错选）/应选数扣至 0 不为负。修复旧多选 token 化 bug（AB 被当成一个 token）。`ObjectiveScoringTest` 5 例 + `T196QuestionTypeTest` 集成 1 例全绿 |
| 2026-10-19 | T196 前端 | 完成 | 出卷向导题项表单增加多选计分策略；第 2 步增加「按题号区间批量设定题型」（本地草稿 + 已保存题项同步后端），题项同步 payload 带 strategy；`make web-build` 通过 |
| 2026-10-19 | T191 班级汇总补全 | 完成（PDF/XLSX 受限） | `GET /api/offerings/{id}/class-summary` 增加：未交名单（经 identity 端口取姓名）、分数分布（按满分百分比 5 桶，便于跨卷比较）、逐题正确率（只统计客观题，未答不拉低分母）。班级汇总页展示三块并保留按班分列；`Phase2ExamTimingTest` 新增断言。md/zip 已可导出；PDF/XLSX 需新增依赖 |
| 2026-10-19 | 全量回归（本轮） | 完成 | `make build` / `make test`（93 个测试：92 通过 + 1 真机 AI 跳过）/ `make web-build` / 两个守卫全绿 |
| 2026-10-19 | T081 切回 Spring AI ChatClient | 完成 | 依赖 spring-ai-client-chat / spring-ai-openai 2.0.1；`SpringAiLlmClient` 使用 `ChatClient…call().content()`（temperature 0.2、response_format=json_object、maxRetries 0）；默认 `teachloop.ai.client=spring-ai`，`http` 回退；无 Key 不构造客户端、不发网络、不影响上下文启动。**真机验证**：`AiGradingRealCallTest` 与 `SpringAiLlmRealCallTest` 在 `DEEPSEEK_API_KEY` 下均为 Tests run 1 / Skipped 0，返回合法 JSON（5.2s / 3.6s） |
| 2026-10-19 | T122 PDF 导出 | 完成（字体口径见备注） | OpenPDF 3.0.5；`feedback.pdf` 每人一页，含客观/主观/总分/错题与标准答案/评语；`PdfFontProbe` 启动自检，失败返回 503；`publish_scores=false` → 409。备注：使用内置 CJK 字体 STSong-Light（非嵌入），中文渲染正常但不可文本检索；若需可选中中文，后续嵌入 Noto Sans CJK |
| 2026-10-19 | T123 XLSX / DOCX 导出 | 完成 | POI 5.5.1：`summary.xlsx`（3 个工作表）与 `summary.docx`，表头随 `lang=zh-CN|en`；中文姓名/班级/题干读回正常；未发布时平均分写「未发布」 |
| 2026-10-19 | 导出前端 | 完成 | 班级汇总页接入 Markdown / XLSX / DOCX / 反馈 ZIP / 反馈 PDF 下载（带当前语言），未发布时 ZIP/PDF 置灰并提示；PDF 503 按字体不可用提示 |
| 2026-10-19 | 依赖与真机授权 | 完成 | 用户授权后以放宽权限预下载 POI 5.5.1、OpenPDF 3.0.5、Spring AI 2.0.1 及其传递依赖；用户提供 `DEEPSEEK_API_KEY` 完成两次真实调用验证（Key 未写入仓库） |
| 2026-10-19 | T101–T104 复核编辑/发布/撤回/批量通过 | 完成 | V016：`reviews` 增加 strengths/suggestions/original_subjective/published_at；新增复核详情（正文/作答/题项/AI 草稿）、`PUT /review`（只改主观分与文本，经 GradingOutcome 回写总分，审计含 before/after 与逐行 diff）、`publish`/`withdraw`、`bulk-approve`；发布后学生 `/my-feedback` 可见、未发布不可见；`ReviewWorkflowTest` 2 例全绿 |
| 2026-10-19 | T208/T114 站内通知 | 完成 | V017 `notifications` 表（identity）；`Ports.NotificationPort.notify` 落库；四个 API（列表/unread-count/read/read-all，他人 404）；场次发布、成绩发布、复核发布都生成通知；`NotificationTest` 3 例全绿。前端顶部铃铛 + `/notifications` 页（未读过滤/标记已读/轮询） |
| 2026-10-19 | 复核工作台前端 | 完成 | 队列 + 详情两栏：正文、客观/主观/AI 草稿、题项作答与标准答案、评语/优点/建议编辑、通过/弃用/发布/撤回、批量通过、审计抽屉；`make web-build` 通过 |
| 2026-10-19 | 回归（复核+通知批次） | 完成 | `make build` / `make test`（105 个常规测试全绿）/ `make web-build` / 两个守卫全绿 |
| 2026-10-19 | T060 富文本提交 | 完成 | V018 submissions 增加 content_html；jsoup 1.23.2 白名单消毒（无属性保留），剥离脚本/事件并写审计 `security:rich_text_stripped`；content_text 从消毒后 DOM 提取，模型拿不到标签；SubmissionFormsTest 覆盖 |
| 2026-10-19 | T061 文件提交 | 完成 | `POST /api/offerings/{id}/submissions/file`（multipart，≤100MB；pdf/docx/txt/md；.doc 拒绝）；PDFBox 3.0.8 / POI 5.5.1 / UTF-8 提取；复用会话与交卷路径；附件下载 `GET /api/submissions/{id}/attachment`（教师或本人，他人 404） |
| 2026-10-19 | T066/T067 不可读与存储 | 完成 | 解析失败/空文本 → `unreadable=true`、`grading_state=SKIPPED`，不产生 0 分；`StorageBackend` + `LocalStorage`（默认 ./uploads，路径穿越防护），OSS 骨架条件装配、未配置 503；上传失败分类 400/503 |
| 2026-10-19 | T064 new attempt | 完成 | `POST /api/offerings/{id}/attempts`：已交卷/被拒收后仍有次数可开新 attempt，旧 attempt 置非 active（native 更新避免唯一索引冲突）；`max_attempts` 超限 409；`NewAttemptTest` 覆盖 active 唯一与三次上限；前端考试/作业页接入「再考一次」 |
| 2026-10-19 | 提交前端 | 完成 | 作业页支持文本/文件两种提交、附件下载、unreadable 提示；考试页与作业页新 attempt 按钮；`make web-build` 通过 |
| 2026-10-19 | T130 后端国际化 | 完成 | `MessageSource`（UTF-8、禁用 system locale fallback）+ `Accept-Language`；框架级错误（未知路由 404、参数校验 400、500）的 message 本地化，`code` 保持不变；`I18nErrorTest` 用中英两种语言断言 code 相同、message 不同。业务级 ApiException 的细粒度文案仍走原 message，待后续补 key |
| 2026-10-19 | 可见性仪表盘 | 完成 | `docs/PROGRESS.md` + `make status`（最近提交/工作区/最近测试/当前与剩余）；每批结束更新并提交，长命令日志落 `.dsh/logs/` |
| 2026-10-19 | T037 限流 | 完成 | V019 `rate_limits`（key/action/window_start/count，唯一键）；`RateLimiter` 单条原子 upsert；过滤器顺序 SessionAuthFilter → RateLimit → 授权；登录按 IP、上传/派发/事件按用户；429 + `RATE_LIMITED` + `Retry-After` + `X-RateLimit-Limit`；默认 登录 30/60s、上传 20/60s、派发 20/60s、事件 120/60s，可用 `teachloop.rate-limit.<action>.*` 覆盖；`RateLimitTest` 3 例全绿（含跨窗恢复、匿名 401 不占配额） |
| 2026-10-19 | T140 请求日志 | 完成 | `RequestLoggingFilter`（order -90）写 `requests-YYYY-MM-DD.jsonl`：ts/requestId/userId/method/route/status/durationMs；响应回写 `X-Request-Id`；**不含正文/query/头**；写失败不影响请求 |
| 2026-10-19 | T141 前端事件 | 完成 | `POST /api/events` 白名单（page_view/action_click/exam_start/answer_save/submit）批量写 `events-YYYY-MM-DD.jsonl`，非白名单 400，不写业务库；前端新增 `trackEvent`，接入页面浏览、开考、自动保存、交卷、作业提交 |
| 2026-10-19 | T142–T144 清理与日报 | 完成 | `LogRetentionService` 按天文件 + 90 天清理（注入 Clock，审计不删）；V020 `daily_stats(day,metric,value)`；`DailyStatsService` 读前一天 JSONL 聚合 8 个指标、`ON CONFLICT` 重跑幂等、每日 00:10 UTC；`LogSink` 接口 + FileLogSink 占位 |
| 2026-10-19 | 可观测回归 | 完成 | `ObservabilityTest` 4 例、`RateLimitTest` 3 例、app 全量 44 例全绿；日志目录已加入 .gitignore |
| 2026-10-19 | T160 生产镜像 | 完成 | 多阶段 Dockerfile（Maven 构建 → Temurin 21 JRE）；运行镜像内置 `fonts-noto-cjk`、`ffmpeg/ffprobe`、`curl`；非 root 用户；HEALTHCHECK 调 `/healthz?deep=1`；新增深度探针检查数据库与 PDF 中文字体，异常 503 |
| 2026-10-19 | T161 compose（app + worker + postgres） | 完成 | `docker-compose.prod.yml`：db 健康检查 + 数据卷；app 迁移/Flyway + 健康检查；worker 同镜像 `SPRING_PROFILES_ACTIVE=worker`（无 Web、关闭 Flyway）、共享 uploads/logs 卷；`docker compose config` 通过 |
| 2026-10-19 | T162 阿里云部署文档 | 完成 | `docs/deploy-aliyun.md`：DNS/安全组、RDS 或容器库、反向代理 100MB、certbot、备份恢复、健康检查、ES 可选、安全清单与未完成项 |
| 2026-10-19 | T163 .env.example | 完成 | 数据库、DeepSeek Key、seed 关闭、AI client/model、存储（local/OSS）、可观测日志目录、本地 JAVA_HOME 提示 |
| 2026-10-19 | 部署回归 | 完成 | `make build/test/web-build/check-*` 全部 exit 0；`DeepHealthTest` 覆盖 `/healthz` 与 `?deep=1`；`docker compose config` 通过；`docker build` 成功；镜像 1.82GB；实际起容器连 Postgres 后 `/healthz?deep=1` 返回 `{"status":"UP","db":"UP","pdfFont":"UP"}` |
| 2026-10-19 | T030/T032 认证提供者 | 完成 | `DevPasswordAuthProvider` 仅非 prod；prod 下教师/学生密码登录 503、管理员 BCrypt 登录可用；`ProdAuthTest` 覆盖 |
| 2026-10-19 | T031 微信登录 | 完成（无真实凭据） | `WeChatAuthProvider`：code→openid（可配 base-url）、同一 openid 复用账号、新 openid 建学生、未配置 503；V021 email 可空 + `wechat_openid` 唯一；`WeChatAuthProviderTest` 用本地 HTTP 替身覆盖成功/errcode/缺 openid |
| 2026-10-19 | T036 管理员 CLI 与教师邀请码 | 完成 | V022 `teacher_invites`（3 天过期、一次性、可停用）；管理员 CLI `--create-admin=email`（密码取 ADMIN_PASSWORD 或随机打印）；`POST /api/auth/teacher-invite` 兑换后升级 TEACHER；`POST /api/admin/teacher-invites`、`/users/{id}/disable`；停用后旧会话立即 401（修掉 Spring Security 会话恢复旧认证）；`IdentityAuthTest` 覆盖 |
| 2026-10-19 | T033 CSRF 与会话 Cookie | 完成（生产开启） | prod：Cookie 双提交（`CookieCsrfTokenRepository`）+ `application-prod.yml` HttpOnly/Secure/SameSite；`/api/csrf` 取 token；前端 `ensureCsrf` + `X-XSRF-TOKEN`；登出 invalidate 会话；缺 token 的已登录写请求 403；dev/test 关闭 CSRF 以免测试与本地被卡 |
| 2026-10-19 | T034 资料补全拦截 | 完成（后端） | `ProfileCompletionFilter`（仅 `require-profile-completion=true`，prod 开启）：STUDENT/TEACHER 缺手机号或邮箱时除 me/logout/profile/health 外一律 403 `PROFILE_INCOMPLETE`；`PUT /api/profile` 补全；管理员豁免；`ProfileCompletionTest` 覆盖。前端资料页尚未做（偏差记录） |
| 2026-10-19 | T035 角色守卫 | 完成 | Security 规则：`/api/admin/**` 仅 ADMIN、`/api/papers/**` 仅 TEACHER/ADMIN；控制器层原有 require* 保留；`IdentityAuthTest` 覆盖学生建卷 403、教师建邀请码 403、未登录 401；停用旧会话失效 |
| 2026-10-19 | T172 安全自查 | 完成 | `.docs/security-self-check.md` 逐项映射：未登录 401、缺 CSRF 403、越权 403/404、上传白名单、富文本消毒、限流 429、停用下线、资料补全、prod 禁开发登录；目标测试组合 exit 0 |
| 2026-10-19 | T020 迁移集成测试 | 完成 | `MigrationTest`：空库迁移全部 applied、无 pending；断言 18 张关键表与 `submissions_active_uk` 索引；重复 `migrate()` 执行 0 条且数量不变 |
| 2026-10-19 | T042 成员列表（含提交数） | 完成 | `GET /api/classes/{id}/members` 改为 `{studentId, displayName, submissionCount}`；sitting 新增 `ClassSubmissionStats` 端口按班聚合提交数；学生访问 403；`ClassAccessTest` 覆盖 |
| 2026-10-19 | T053 匹配策略注册表 | 完成 | `MatchStrategy` + `MatchStrategies`：exact / normalized（大小写/空白/标点）/ choice（字母集合无序）/ set（多写法分隔符）；`ObjectiveScoring` 改用注册表；`MatchStrategyTest` 覆盖四类边界 |
| 2026-10-19 | T055 答案表导入 | 完成 | `AnswerTableParser` 支持「1 A」「1-5 ABCDA」「1 A 2 B」、注释行、重复题号警告；`POST /api/papers/{id}/answers` 补齐已有题项答案，未知题号返回 `unknownNumbers`，错误带行号 400（`第 N 行`） |
| 2026-10-19 | T071 总分合成 | 完成 | `Submission.finalTotal() = objectiveTotal + subjectiveTotal`；两列 setter 拒绝负数/空值归零；`GradingOutcome.apply` 走同一条路径；`SubmissionTotalsTest` 覆盖派生与负数拒绝 |
| 2026-10-19 | T150 聚合查询 | 完成 | `/api/reports/offerings|classes|students`：按作业/班级/学生三视角；平均分只算 active 且已提交，未交单列；`AggregateReportTest` 断言 2 人班级 1 人提交时 expected=2、submitted=1、unsubmitted=1、均分=10.0 |
| 2026-10-19 | T151 报表导出 | 完成 | 按作业报表复用 XLSX（POI）与 PDF（OpenPDF + 内置 CJK 字体）：`/api/reports/offerings.xlsx`、`/api/reports/offerings.pdf`；字体不可用 503；`AggregateReportTest` 断言 XLSX 非空、PDF 以 `%PDF` 开头 |
| 2026-10-19 | T206 试卷库统计/复制/归档 | 完成 | `Ports.PaperUsage` 由 sitting 统计布置次数；列表返回 `assignmentCount`；`POST /copy` 深复制部分/题项/评分项为 DRAFT，复制件与原卷互不影响；`POST /archive` 置 ARCHIVED 并审计；前端试卷库显示「已布置 N 次」+ 复制/归档按钮；`PaperLibraryTest` 覆盖 |
| 2026-10-19 | T062 视频提交 | 完成 | `POST /api/offerings/{id}/submissions/video`（mp4/mov/webm，≤100MB）；`VideoProbe` 调 ffprobe 读取时长/宽高，`VideoRules` 限时长≤360s、宽≤1920、高≤1080 并指出具体超限项；视频存 StorageBackend，提交走同一会话/定稿路径；`VideoRulesTest` 覆盖 359/360/361、1920/1921、1080/1081；`VideoSubmitTest` 用 ffmpeg 生成 1s mp4 实测上传成功、.avi 拒绝 |
| 2026-10-19 | T102 改答案重算 | 完成 | `Ports.ObjectiveRecompute`：改标准答案（逐题 PUT 或答案表）后更新该试卷所有场次快照，重算 `objective_answers.correct` 与 submission 客观总分，写 `offering:objective_recomputed` 审计；复核侧读同一 objective_total 自然同步；`ObjectiveRecomputeTest` 覆盖 A→B 后 10 分变 0 |
| 2026-10-20 | T174 并发压测 | 完成（1+1 与 2+2） | `scripts/load-test.py`：25 学生 × 2 场 = 50 份提交、并发 10；50/50 成功，p95 45ms，批改 33.8s 全部 done；记录 `.docs/perf-report-v2.md`。2+2 拓扑复测：50/50、p95 97ms、批改 7.22s；`docker-compose.scale.yml`。仍为 stub 引擎 |
| 2026-10-19 | T007 中文字面量静态检查 | 完成（基线 + 新增即失败） | `scripts/check-i18n.py` 扫 `api/`、`service/` 的 Java 字符串字面量；`scripts/i18n-baseline.txt` 记录现有 275 处（全量迁移前不阻塞）；新增中文字面量 → 退出码 1，实测插入「新增中文文案必须失败」被拦下；接入 `make test` 依赖。范围决定：先挡住新增，存量迁移到 T130 消息 key 时逐步清零 |







| 2026-10-19 | T072 四模型段落计数 | 阻塞 | 需要 v1 的 10 份真实作文做 parity；仓库内没有 `docs/rubrics/samples/` 语料，无法在不臆造算法的前提下移植/断言。保留为阻塞项，等语料 |

| 2026-10-19 | T056 整卷拆分与单题修正 | 完成（合成样卷） | `PUT /api/papers/{id}/items/{number}` 可改题干/选项/答案/分值（发布后需 confirm + 审计）；`WholePaperSplitTest` 用 75 题合成文本断言题干/选项/答案全部拆出；`AnswerTableTest` 覆盖单题修正。真实 75 题样卷未在仓库内，拆分正确率只对合成样本断言 |

| 2026-10-19 | T054 发布锁定与 rubric_version | 完成 | V023 `papers.rubric_version`；每次发布递增 version 与 rubricVersion；发布后改结构需 `confirm=true` 并写审计 `paper:edited_after_publish`（含 action/rubricVersion）；`PaperPublishLockTest` 覆盖 409/放行/审计/版本递增 |

| 2026-10-19 | T043 班级访问控制 | 完成 | 教师 `/api/classes` 只返回自己的班；学生只返回已加入的班；跨教师成员列表 404；学生不能用邀请码再生成（404）；`ClassAccessTest` 覆盖 |

| 2026-10-19 | T021 可重复 seed | 完成 | `DemoSeedRunner`（`teachloop.seed.demo=true`）：1 班 + 30 学生 + 1 份 4 题带答案试卷，固定 UUID/邀请码；重复执行只补缺；`DemoSeedTest` 连跑两次断言用户/班级/成员/试卷/题项数量不变；`make seed` 目标 |

| 2026-10-19 | T170/T171 E2E 记录 | T170 完成；T171 部分 | `.docs/e2e-report-v2.md` 记录实际执行与偏差：旅程 A 用 1 班 3 题跑主链路（3 班/77 题未逐项重放）；旅程 B 富文本/文件/new attempt/迟交/拒收/复核由自动化测试覆盖。**不宣称完整通过** |
| 2026-10-19 | T173/T174/T175 | 阻塞/未开始 | T173 依赖 T072（未实现）；T174 缺 2 app + 2 worker 编排与压测脚本；T175 缺 10 份人工评分语料 |







**说明**：用户于 2026-10-19 明确批准开始实现。每完成一个任务在此追加一行。
| 2026-10-20 | T072 四模型段落计数 | 完成 | `TextLengthCounter` 逐条移植 v1 Python `collect_submissions.py` 的 strict_blocks / wraps_merged / policy_default / lenient，含问候语合并、软换行、列表块、孤立空行规则 |
| 2026-10-20 | T173 四模型 parity | 完成 | 10 份 v1 作文复制到 `teachloop-grading/src/test/resources/fixtures/submissions/`，`expected-paragraphs.json` 由 v1 Python 生成；`TextLengthCounterFixtureTest` 逐份逐模型一致（含 Genesis 24→3、Maria 8→4、Mark 6→4） |
| 2026-10-20 | T175 golden set | 完成 | `docs/reports/golden-set-baseline.md`：10 份人工均分 9.6；deepseek-flash 实测平均偏差 0.0、最大绝对偏差 0、方向持平；`make test-ai` 两个真机测试 Skipped 0 |
| 2026-10-20 | T175 golden set 执行器 | 完成（待 Key 运行） | `scripts/golden-set.py` 读取 10 份 v1 样本与人工总分，同一 rubric 调 DeepSeek，输出每份偏差与平均/最大偏差/方向；无 Key 明确退出码 2，不写假数字 |

| 2026-10-20 | 管理员初始设置（用户新增） | 完成 | `/setup` 首次创建管理员（无 ADMIN 时开放）+ `/admin/ai` AI 配置：provider/baseUrl/model/temperature/API Key；Key AES-GCM 加密存库、接口只回掩码；保存后重启生效；测试连接；`AiSetupTest` 覆盖首个管理员、二次 409、加密、掩码、403 |
| 2026-10-20 | 导入考卷入口与粘贴导入（用户反馈） | 完成 | 试卷库新增「导入考卷」；独立 `/papers/import`：建卷+建部分+上传/粘贴→解析→逐题改题干/选项/答案；后端 `POST /api/papers/{id}/import/text`；`ImportTextTest` 覆盖 2 题粘贴导入 |
| 2026-10-20 | T230 新建/导入试卷可用性 | 完成 | 补 22 个未定义 i18n key（wizard 子树 zh/en 各 144，本页 156 个 key 0 缺失）；第 2 步导入前自动建卷与部分，新建态即可上传/粘贴；向导内新增粘贴导入与「导入到部分」；部分/题项行内编辑与删除；后端 `PUT/DELETE /api/papers/{id}/sections/{sectionId}`、`DELETE /api/papers/{id}/items/{number}`（级联、重算、confirm 闸门、位置取 max+1）；`pointsPerItem`/`points`/`totalPoints` 由 int 改 BigDecimal，小数分值不再被截断。验证：`PaperCrudTest` 5/5；`PaperCrudTest+PaperPublishLockTest+PaperLibraryTest+ImportTextTest+AnswerTableTest+MatchStrategyTest+T196QuestionTypeTest` 全部退出码 0；`cd web && pnpm build` 通过；浏览器实测（临时 8012+5175，验证后已销毁）新建卷免同步粘贴导入 2 题、第 3 步改/删题项、删部分均即时生效，中文界面无原始 key || 2026-10-20 | T230 新建/导入试卷可用性 | 完成 | 补 22 个未定义 i18n key（wizard 子树 zh/en 各 144，本页 156 个 key 0 缺失）；第 2 步导入前自动建卷与部分，新建态即可上传/粘贴；向导内新增粘贴导入与「导入到部分」；部分/题项行内编辑与删除；后端 `PUT/DELETE /api/papers/{id}/sections/{sectionId}`、`DELETE /api/papers/{id}/items/{number}`（级联、重算、confirm 闸门、位置取 max+1）；`pointsPerItem`/`points`/`totalPoints` 由 int 改 BigDecimal，小数分值不再被截断。验证：`PaperCrudTest` 5/5；`PaperCrudTest+PaperPublishLockTest+PaperLibraryTest+ImportTextTest+AnswerTableTest+MatchStrategyTest+T196QuestionTypeTest` 全部退出码 0；`cd web && pnpm build` 通过；浏览器实测（临时 8012+5175，验证后已销毁）新建卷免同步粘贴导入 2 题、第 3 步改/删题项、删部分均即时生效，中文界面无原始 key |
| 2026-10-20 | T231 真实试卷 Markdown 导入 | 完成 | 用用户提供的真实整卷（`teachloop-paper/src/test/resources/exam/english-grade10-2026.md`）做回归：旧解析器在第 385 行把写作要求「1. 表达理解；」当成重复题号，整卷 400 失败；新增 `MarkdownSyntax` 归一化 Markdown、重写 `PaperTextParser`（部分/节、逐题分值、行内选项、无题号按空自动编号、材料并入题干并标注空号、主观部分跳过）、修答案区差一位；端点按文档结构建部分。验证：`./mvnw -pl teachloop-paper -am -Dtest=RealExamImportTest,PaperImportEndpointTest,PaperTextParserTest,DocumentTextExtractorTest,WholePaperSplitTest test` 37/37；`-DskipTests package` 后 8012 起新 jar + 5175 前端，浏览器实测导入 75 题/4 部分/120 分、第 3 步 75 行题项、第 5 步「总分一致：120 分」，文章材料与表格行、完形四选项、七选五 7 选项、语法填空空号标注均正确 || 2026-10-20 | T231 真实试卷 Markdown 导入 | 完成 | 用用户提供的真实整卷（`teachloop-paper/src/test/resources/exam/english-grade10-2026.md`）做回归：旧解析器在第 385 行把写作要求「1. 表达理解；」当成重复题号，整卷 400 失败；新增 `MarkdownSyntax` 归一化 Markdown、重写 `PaperTextParser`（部分/节、逐题分值、行内选项、无题号按空自动编号、材料并入题干并标注空号、主观部分跳过）、修答案区差一位；端点按文档结构建部分。验证：`./mvnw -pl teachloop-paper -am -Dtest=RealExamImportTest,PaperImportEndpointTest,PaperTextParserTest,DocumentTextExtractorTest,WholePaperSplitTest test` 37/37；`-DskipTests package` 后 8012 起新 jar + 5175 前端，浏览器实测导入 75 题/4 部分/120 分、第 3 步 75 行题项、第 5 步「总分一致：120 分」，文章材料与表格行、完形四选项、七选五 7 选项、语法填空空号标注均正确 |
| 2026-10-20 | T232/T233 主观作业 critical case | 完成（T234 未修） | 用户给 3 个写作作业（演讲稿 15 分 / 回信 15 分 / 教案 20 分），按要求跑通整链：建卷（ESSAY 题项 + rubric）→ 布置给演示班 → student01 在浏览器里三份各写正文并提交 → 教师「立即派发」→ stub 引擎出分（11.3/15、11.3/15、15/20）→ 复核改分并写评语/优点/建议 → 发布 → 学生「我的反馈」看到分数与评语。过程中修：作业页接上题目要求与评分标准（`EntryView.rubric`）、反馈页显示优点建议、去掉作业页的考试计时说明与重复行、`create` 带 publish=true 的落库丢失（merge 副本问题）。未修并记录：批改卡 RUNNING 无回收、spring_session 唯一键冲突 500。截图见 `docs/reports/critical-case-subjective/` || 2026-10-20 | T232/T233 主观作业 critical case | 完成（T234 未修） | 用户给 3 个写作作业（演讲稿 15 分 / 回信 15 分 / 教案 20 分），按要求跑通整链：建卷（ESSAY 题项 + rubric）→ 布置给演示班 → student01 在浏览器里三份各写正文并提交 → 教师「立即派发」→ stub 引擎出分（11.3/15、11.3/15、15/20）→ 复核改分并写评语/优点/建议 → 发布 → 学生「我的反馈」看到分数与评语。过程中修：作业页接上题目要求与评分标准（`EntryView.rubric`）、反馈页显示优点建议、去掉作业页的考试计时说明与重复行、`create` 带 publish=true 的落库丢失（merge 副本问题）。未修并记录：批改卡 RUNNING 无回收、spring_session 唯一键冲突 500。截图见 `docs/reports/critical-case-subjective/` |
| 2026-10-20 | T234 批改卡住与会话冲突 | 完成 | ① 批改卡 RUNNING：用户实测「3 份里 1 份」卡住，原清扫（T091，5 分钟/2 分钟）其实存在，缺的是**兜底与手动入口**——现已改为到 `grading.max-attempts` 就标 FAILED（写 `grading_error`），`retry-failed` 端点额外回收卡住超过 1 分钟的 RUNNING，返回 `{retried, reclaimed}`，教师端按钮改「重试失败/卡住项」且在「批改中 >0」时可点；② `spring_session_attributes_pk` 冲突：V025 用 BEFORE INSERT 触发器把重复插入变成覆盖 bytes。验证：`GradingDispatchTest` 9/9（含新增「领满 3 次标 FAILED」「手动重试回收 RUNNING」）、`SessionAttributeUpsertTest` 1/1（同一属性连插两次不报错、取最后值）；`cd web && npx tsc --noEmit` 通过 || 2026-10-20 | T234 批改卡住与会话冲突 | 完成 | ① 批改卡 RUNNING：用户实测「3 份里 1 份」卡住，原清扫（T091，5 分钟/2 分钟）其实存在，缺的是**兜底与手动入口**——现已改为到 `grading.max-attempts` 就标 FAILED（写 `grading_error`），`retry-failed` 端点额外回收卡住超过 1 分钟的 RUNNING，返回 `{retried, reclaimed}`，教师端按钮改「重试失败/卡住项」且在「批改中 >0」时可点；② `spring_session_attributes_pk` 冲突：V025 用 BEFORE INSERT 触发器把重复插入变成覆盖 bytes。验证：`GradingDispatchTest` 9/9（含新增「领满 3 次标 FAILED」「手动重试回收 RUNNING」）、`SessionAttributeUpsertTest` 1/1（同一属性连插两次不报错、取最后值）；`cd web && npx tsc --noEmit` 通过 |
| 2026-10-20 | T234 根因复盘 | 完成 | 按用户要求再复现一次：用 30 轮「把同一提交打回 PENDING」稳定复现（10 轮内每次卡住）。取证在**用户 8010 进程的日志**里：`scheduling-1` 抛 `java.lang.NoClassDefFoundError: com/teachloop/shared/port/Ports$CriterionScore`（栈顶 `StubGradingEngine.grade` ← `GradingWorker.processBatch`）——该进程跑的是我原地重编覆盖过的 jar，嵌套 jar 懒加载失败；`Error` 不被旧 `catch (Exception)` 捕获，把行留在了 RUNNING。结论：**不是 worker 逻辑缺陷，是「覆盖 jar 不重启」的开发环境问题**；但暴露的两个真缺陷已修（捕获 Throwable + 两段清扫 + 手动回收）。运维口径：重编 jar 后必须重启 dev 栈，不要在运行中覆盖。 |
| 2026-10-20 | T235 答案格式宽容化 | 完成 | 用户实测三种「答案标在选项上」的写法全部失败后，确认行业做法是答案与题面分离（Gradescope 答案键独立页 / 腾讯云 `QuestionSplitOCR` 的 `Answer[]` 数组）。改动：① `PaperTextParser` 答案标记词扩到 `参考答案/标准答案/正确答案/答案` 且认 `【】` 包裹（此前 `标准答案` 会**静默失败**，零告警）；② 只有题号没答案（`3.`）改告警跳过，此前会让整卷导入 400；③ 新增纯字母串配对，仅题号连续时启用，不连续告警拒绝（错位给分比失败更糟）；④ `ImportPaperPage` 加「粘贴答案表」面板，补完重拉题项并按真实数据现算缺答案。验证：`./mvnw -pl teachloop-paper -am test` 41/41（`PaperTextParserTest` 15 条含新增 4 条、`RealExamImportTest` 9 条 75 题/4 部分/120 分不变）；`cd web && pnpm build` 通过。**未验证**：浏览器里点「粘贴答案表」的视觉与交互未跑（仅 tsc + vite 构建通过） |
| 2026-10-20 | T236 规格/计划/任务/mock 回写（D-14） | 完成 | spec §1/§1.1/US-22/AC-26.1/26.6/26.13/26.17/AC-25.4、D-14（取代 D-13）、FR-048；plan §5/§7.6/§14；mock 新增 `assignments`/`assignment-new` 两屏并把班级「新建作业」改指向。验证：四份文档计数 26 US / 223 AC / 48 FR / 164 T，无重复编号；mock 自查 22 个 section 无重复 id、`assignment-new` 内「试卷/部分/题号/标准答案/时长/计时模式/按班覆盖」出现 0 次 |
| 2026-10-20 | T237 V026 迁移 + 实体可空 | 完成 | `V026__sitting_assignment_content.sql`：先 `drop not null` → 回填 `paper_id=null, paper_version=0` → 加 `offerings_kind_content_ck`。**首次在 dev 库执行失败**（回填顺序反了，撞 not-null），改序后重跑成功：13 条存量作业回填、`count(paper_id)=0`；1 场考试不受影响；约束定义已核对。空库迁移由 `MigrationTest` 断言 |
| 2026-10-20 | T238 后端独立作业创建 | 完成 | `CreateOffering` 增 title/instructions/totalPoints/rubricMarkdown；`paperId` 派生 kind（有=EXAM、无=ASSIGNMENT），`kind` 仅做一致性校验；作业合成 1 条 ESSAY 内容快照、缺省 rubric、审计 `source`；`view().paperId` 空安全；`writeAnswers` 只在**无试卷作业**上跳过 ESSAY（考试的 ESSAY 作答行保持原行为，避免丢正文）。验证：`StandaloneAssignmentTest` 2/2（数据层/学生可见/提交派发判分/复核 + 5 种 400）；`FlowIntegrationTest` 2/2、`Phase2ExamTimingTest` 3/3 |
| 2026-10-20 | T239 报表/导出适配无试卷作业 | 完成 | `ExportReadService` / `AggregateReportService` 的 `join papers` → `left join`，标题 `coalesce(p.title, paper_snapshot->>'title')`；`AggregateReportTest` 补「独立作业出现在聚合且标题非空」、`ExportTest` 补 xlsx/zip 用例。验证：两个测试类全绿 |
| 2026-10-20 | T240 前端作业入口 + mock | 完成 | 新增 `/assignments`（只列 kind=ASSIGNMENT）与 `/assignments/new` 单页表单；教师导航加「作业」；布置页改纯考试并删 `kind`；`OfferingView.paperId` 改可空；学生作业页单 ESSAY 去序号；i18n zh/en 各 45 个新 key（`assignments` 12 + `assignmentCreate` 33），key 自查双向一致；批改详情的「时长」只对考试显示。验证：`npx tsc --noEmit` 0、`pnpm run build` 0 |
| 2026-10-20 | T241 全量回归 + 浏览器实测 | 完成 | `make test` 退出码 0；surefire 汇总 **tests=173, failures=0, errors=0, skipped=0**（50 个测试类；只有两个 `@Tag("ai")` 真机类按约定未跑）；`check-i18n`/`check-boundaries`/`check-deps` 全通过。浏览器（8010+5173，stub 引擎）实测：新建作业（全程无试卷字段）→ 学生看到题目要求+rubric → 提交 → 教师派发 → stub 判 15/20 → 复核通过并发布 → 学生反馈显示 15/20 与评语。截图与数据断言见 `docs/reports/standalone-assignment/`。复核的 UI 定位未跑（队列未按 offering 过滤），用浏览器会话 API 完成 |
| 2026-10-20 | T242 答案朴素写法与填空题文本答案 | 完成 | 用户真实卷三件套（`paper.md` / `answers.md` / `paper and answer.md`）原样回归。修掉的缺陷：① `56. challenging` 被 `[A-Ga-g]+` 静默读成 `C`（最严重，会往库里写错答案）——字母两侧加 `(?<![A-Za-z])…(?![A-Za-z])`；② `1-5: A B B C C` 报「需要 5 个答案，实际 1 个」——区间内允许空格分隔；③ `answers.md` 第 4 行死在 `---` 上——`AnswerTableParser` 先过 `MarkdownSyntax.clean`；④ 答案区里 `### 第一部分 听力（1-20）` 让整卷失败——改为跳过，只有以题号开头却解析不出才报错；⑤ `试卷参考答案与解析 (卷末附录)` 不认作答案区导致「题号 66 重复」——含「参考答案/标准答案/正确答案」且 ≤40 字、无句末标点即认定；⑥ 填空题无答案通道——新建 `AnswerFreeText`，无选项的题整行取文本、按题号切片、截断括号注释，端点按 `FILL_BLANK` 传白名单。验证：`./mvnw -pl teachloop-paper -am test` **64/64**（新增 `AnswerTableParserTest` 13 条、`RealExamWithAnswersTest` 4 条、`PaperImportEndpointTest` +1）；两条路径实测 **75 题 / 4 部分 / 120 分 / 75 题全有答案 / 0 缺**；`cd web && pnpm build` 通过。**未验证**：浏览器里点「粘贴答案表」的视觉与交互（本机未起 dev 栈） |
| 2026-10-20 | T245 规格/计划/mock 回写（D-15） | 完成 | spec 新增 D-15、US-11 改为「AI 可选 + 人工判分一阶路径」、AC-11.11–11.16、FR-050（FR-049 已被并发 T242 答案格式占用，重新编号）；plan 新增 §6.5（懒建 review / worker 三处保护 / `SubmissionScores` 端口）；tasks 阶段 20。验证：26 US / 230 AC / 50 FR / 169 T，无重复编号 |
| 2026-10-20 | T243 后端人工判分（AI 可选） | 完成 | `Ports.SubmissionScores` + `SittingLedger.scores`；ReviewEndpoint GET/PUT/decide 懒建 review（`original_subjective=null` = AI 未参与）、详情返回当前客观/主观分；无 AI 草稿且未填分时拒绝 APPROVED；bulk-approve 跳过未判分项；GradingWorker 在 teacherId 非空/已发布时三处跳过（认领前、AI 结果后、失败路径）；AI 先被打开详情、后完成的场景补记草稿（`Review.captureAiDraft`）。验证：`ManualGradingTest` 5/5（无派发判分 17.5→发布→学生可见、人工分 12 不被 AI 覆盖、FAILED 兜底、bulk 跳过、先开详情后补草稿）；指定测试类 25/0/0；全量 `make test` 203/0/0 |
| 2026-10-20 | T244 前端判分入口与无 AI 草稿体验 | 完成 | 批改详情逐份行加「判分 / 复核」→ `/review?submissionId=`（仅已交卷且未拒收）；ReviewPage 支持 URL 直达（不在队列也可打开）、切人写 URL、AI 草稿为空显示「AI 未参与」与「直接填写主观分」提示、当前客观/主观分正常显示；i18n 迁入 `web/src/locales/*.json`。**浏览器实测中发现并修复**：ReviewPage 用 `t('teacher.review.*')` 但文案实际在顶层 `review.*`，页面一度显示裸 key；已改为 `review.*` 并逐键核对 zh/en 35/35 存在。验证：`npx tsc --noEmit`、`pnpm run build` 均 0 |
| 2026-10-20 | T246 全量回归 + 人工判分浏览器实测 | 完成 | `make test` 退出码 0，surefire 汇总 **tests=203, failures=0, errors=0, skipped=0**（54 个报告类，含并发 T242 的答案格式测试）；`check-i18n`（前端 907 键对齐、后端/前端新增中文字面量 0）/`check-boundaries`/`check-deps` 全通过。浏览器实测：新建独立作业 → 学生提交（不派发 AI）→ 教师在批改详情点「判分 / 复核」→ 详情显示 AI 未参与、客观/主观 0 → 填 17.5 + 评语/优点/建议 → 保存 → 原样通过 → 发布 → 学生反馈显示 17.5/20 与三段文本。截图：`docs/reports/manual-grading/` |
| 2026-10-20 | T248 三处实测反馈修复（作业页回显提交内容 / 资料页语言下拉 / 审计中文化） | 完成 | 后端 `SittingDtos.AttemptView` 增 `contentHtml`、`contentText`，`/my-submissions` 与 `/my-feedback` 同时受益；`SubmissionFormsTest` 4/4 含新增断言（富文本回显已消毒、纯文本 `Final answer`）。前端：作业页已提交卡片直接渲染正文（`dangerouslySetInnerHTML` 仅用于后端已消毒的 `content_html`）与附件链接；资料页新增「偏好设置 → 界面语言」下拉（`i18n.ts` 用 `import.meta.glob` 加载 `locales/*.json`，语言自称取自各文件 `language.<短码>`，默认 zh-CN），顶栏去掉中/EN 按钮；审计抽屉事件/参数/时间中文化，`fmtDateTime` 改按界面语言输出。验证：`pnpm typecheck` 0、`pnpm build` 0、`check-i18n` 976 键对齐且新增中文字面量 0、`./mvnw -pl teachloop-sitting -am -DskipTests compile` 0、`SubmissionFormsTest` 4/4。**已实测（T250 期间重启 8010 后）**：`/my-submissions` 返回 `contentHtml`（1814 字）/`contentText`（1797 字），作业页「已提交」卡片直接显示「我提交的内容」，无需再点按钮 |
| 2026-10-20 | T250 第二轮实测反馈（资料提醒 / 必填高亮 / 富文本编辑器 / 复核正文与主操作） | 完成 | 后端：`Ports.WorkItem`、`ReviewEndpoint.ReviewDetail` 增 `contentHtml`（`SittingLedgerImpl` 从已消毒的 `submissions.content_html` 取），AI prompt 仍只用纯文本；资料是否补全改由前端读 `/api/profile` 判定，`/api/auth/me` 不变。前端：`AuthProvider` 暴露 `profileComplete`/`refreshProfile`，顶部只在未补全时显示「补全资料」，保存后 `refresh()`+`refreshProfile()` 立即消失；侧栏用户名常驻 `/profile`；资料页必填项红色 `*`+红框+字段级红字+顶部红条，未填禁用保存；新增 `components/RichTextEditor.tsx`（execCommand 工具条、空态占位、挂载时灌入初始 HTML），作业页富文本改用它，本机草稿存 `{text,html,mode}` 刷新可恢复；复核页「学生正文」优先渲染 `contentHtml`（`rich-content`），操作区改为「保存并发布反馈」主按钮（内部先 PUT 再 publish）+「仅保存草稿」+「退回重批」+「撤回」+「审计」，发布后隐藏保存类按钮，禁用按钮统一半透明；队列列名/状态中文化。验证：`pnpm typecheck` 0、`pnpm build` 0、`check-i18n` 1009 键对齐、`make test` 退出码 0（206/0/0/0）；浏览器实测：学生01 资料空手机号→红色高亮+顶部「补全资料」→保存后按钮消失；富文本输入并加粗成功（`<b>Hello rich text editor</b>`）、保存草稿后刷新仍恢复 HTML 与输入形态；教师复核页 914911e8 的 1814 字 HTML 按段落回显；点「保存并发布反馈」→ 学生可见 →「撤回」回到未发布。运维：重建 jar 后已重启 8010（旧实例因 jar 被覆盖出现过 `NoClassDefFoundError: ThrowableProxy`，重启后恢复）；截图 `docs/reports/t250-ux-fixes/` |
| 2026-10-20 | T252 富文本快捷键/粘贴清洗/白底 + 草稿服务端恢复 + 单份「让 AI 先批」 | 完成 | 前端：`RichTextEditor` 加 Cmd/Ctrl+B/I/U、粘贴白名单清洗（DOMParser 同后端白名单，b→strong/i→em，丢 style/span/灰底）、编辑区 `background: var(--panel)`、去掉聚焦 box-shadow；`AssignmentPage` 草稿恢复改成本机优先、本机为空时用 `/my-submissions` 的 `contentHtml`/`contentText`（换 origin/清 localStorage 不丢格式）。后端：`SubmissionRepository.enqueueOne` + `OfferingGradingDispatcher.dispatchSubmission` + `POST /offerings/{id}/submissions/{sid}/grading/dispatch`（run 按场次幂等，QUEUED/FAILED→PENDING，写 `submission:ai_dispatch` 审计，错误走消息 key）；`GradingDetailPage` 行内「让 AI 先批」+ 整场按钮改名「全部交给 AI 批改」+ 已完成行禁用。验证：`GradingDispatchTest` **10/10**（新增单份派发用例：目标 PENDING、同场另一份仍 QUEUED、学生 403、重复 409）；`pnpm typecheck` 0、`pnpm build` 0、`check-i18n` 1017 键对齐、`mvn test-compile` 0；浏览器实测：Cmd+B 取消/加粗、Cmd+I 斜体，粘贴 `<span style=...>` 后 DOM/草稿只剩 `<p>/<ul>/<strong>/<em>`，清 localStorage 刷新后格式仍在；教师行内派发→PENDING→stub 判完 DONE（主观 15）→复核页显示 AI 主观草稿 15；截图 `docs/reports/t252-ux-fixes/`。运维：`scripts/env.sh deploy qa` 已把测试台 5174 更新到本版本；开发机 8010 已重启。 |
| 2026-10-20 | T253 AI 结果可见 + 复核页收敛 + 真实作业流程实测 | 完成 | 后端：`reviews` 增 `ai_criteria`（JSON 文本）/`ai_feedback`（V027 jsonb → V028 text，避免 varchar→jsonb 不匹配），`GradingWorker` 捕获 AI 逐项意见与评语（`Review.captureAiDraft(points, criteria, feedback)`，老师动过或已发布则不覆盖），`ReviewEndpoint.ReviewDetail` 返回 `aiCriteria`/`aiFeedback`。前端：复核页新增「AI 评估」（建议分 / 逐项得分与意见 / 优点建议 / 采用 AI 建议分），标题去重、题项与评分标准折叠；审计「复核改分」改写成「主观分 7.5 → 8 · 评语：… · 优点：… · 建议：…」；顶栏右侧用户信息可点进 `/profile`。实测（dev，stub 引擎，未配置 DEEPSEEK_API_KEY）：用用户给的 `Rubric.md` + `Maria.txt`/`Mark.txt` 建作业（offering 6eb1037b…），student02/student03 提交 → 逐份「让 AI 先批」→ AI 草稿 7.5 + 逐项意见可见 → 教师改 8 / 9.5 并发布 → 两个学生反馈页看到分数与评语。验证：`GradingDispatchTest` 10/10、`make test` 209/0/0/0、`pnpm typecheck` / `pnpm build` / `check-i18n`（1029 键）；截图 `docs/reports/t253-review-ai/`。**注意**：AI 结果来自 stub 引擎（固定比例），不是真实评分；配好 Key 并重启（`TEACHLOOP_GRADING_ENGINE=ai`）后同一流程会出真实逐项意见。 |
| 2026-10-21 | T254 评分质量规格回写（US-27/28、D-16–22） | 完成（仅文档，未动业务代码） | 新增 `docs/research/2026-10-21-grading-quality.md`：用 dev 库与 stub 源码定位截图里的 7.5/4.5/3 是 `StubGradingEngine` 的 `10×0.75` 与 `0.6/0.4`，不是 AI；本地 10 份 golden set 探针显示当前提示词（A）与 v2（B）总分都接近人工，差距在「中文、无引用、泛泛」的评语，B 还有 1/10 总分算错被契约拦下；外部证据（Tang 2026 rubric 粒度、Hong 2026 证据+校准、Choi 2025 锚点 +0.29 QWK、Marigo 2026 few-shot）。新增 `docs/reports/grading-quality/default-rubric-review.md`（两份默认 rubric 均不合格，统一为课程 2/4/4）与 `known-inconsistency.md`（JZ 0/2 与「问候语不计段」冲突，更正为 10/10，旧值留档）。spec 新增 US-27（AC-27.1–27.16）、US-28（AC-28.1–28.11）、FR-054–061、D-16–D-22、A-11–A-14 与 15 条边缘情况；改 AC-11.8/11.15、AC-12.5、AC-26.12、FR-044。plan 更新 §5（user_ai_settings / offerings.created_by / submission_metrics / reviews AI 溯源列）、§6 流程图、§6.1 prompt v2、§6.5 预填+批量、§6.6 Key 解析、§6.7 篇幅/语言、§9 安全、§11 语言、§18.5 golden set。tasks 新增阶段 25（T255–T268）与矩阵。验证：四份计数 **28 US / 258 AC / 61 FR / 191 T**，无重复编号；未改任何业务代码；未跑测试（本轮无代码改动）。 |
| 2026-10-21 | T255 迁移（Key/归属/溯源） | 完成 | V029 `user_ai_settings`、V030 `offerings.created_by`、V031 `reviews.ai_overall/ai_strength/ai_suggestion/ai_model/ai_prompt_version/ai_key_source/ai_failure/feedback_language` + `ai_calls.key_source`；dev 库 Flyway 至 v031，空库 MigrationTest 通过；`check-boundaries.py` 登记 25 张表、13 条跨模块外键。 |
| 2026-10-21 | T256 Identity Key 生命周期 | 完成 | setup 首次必须填 Key；`/api/profile/ai-settings` GET（只回掩码/来源）/PUT/测试（TEACHER/ADMIN，学生 403）；`user_ai_settings` 复用 SecretBox；保存即生效；`AiSetupTest` 扩展了个人 Key 加密/掩码/学生 403 断言。**未做**：setup 保存前的连通性测试（当前只校验非空）。 |
| 2026-10-21 | T257 Key 解析 + 客户端工厂 | 完成 | `Ports.AiSettingsLookup.resolve(teacherId)`；`AiKeyResolver` 逻辑在 `AiGradingConfig` 内按作业创建者 → 全局 → 环境变量兜底；`LlmClientProvider` 每次判分创建客户端；`WorkItem.createdBy`；真实重启后不设 `DEEPSEEK_API_KEY`，用教师个人 Key 判分成功。 |
| 2026-10-21 | T258 Rubric 解析/默认值/预览 | 部分 | 修复 `RubricCriteria`：标题优先，`Rubric.md` 解析出 3 项 2/4/4，Yes/No 规则不再被当成评分项；默认 rubric 前后端都换成课程 2/4/4；创建页新增「AI 将看到」预览与 .md 导入。**未做**：服务端分值之和校验、独立预览 API、按 rubric 逐项编辑。 |
| 2026-10-21 | T259 PromptBuilder v2 | 完成 | `subjective-v2`：题目要求、完整 rubric、二元政策、原文引用、`overall/strength/suggestion`；反馈语言放 user 层；长度段数与政策进 user；测试断言 v2 结构、题目要求、无姓名/标准答案。 |
| 2026-10-21 | T260 Validator v2 | 完成 | 逐项 comment ≥15 字且含正文逐字引用；overall 必填；strength/suggestion 非套话；二元分数 0/满分；长度 points 与代码结论一致并用确定性句子覆盖；语言合规；失败重试 1 次。`AiGradingEngineTest` + 单测通过。 |
| 2026-10-21 | T261 篇幅现算 | 完成 | `SubjectiveGradeRequest` 用 `TextLengthCounter` policy_default 段数 + 边界标记；`ReviewDetail` 返回 `paragraphCount`/`paragraphBoundarySensitive`；复核页显示边界提示；批量预览/通过跳过边界项（Maria/Mark 实测跳过）。未新增表。 |
| 2026-10-21 | T262 去假分 + 失败分类 | 完成 | `application.yml` 默认 `ai`；`StubGradingConfig @Profile("!prod")`；测试 properties 显式 stub；无 Key 抛 `NO_KEY`，失败分类写 `reviews.ai_failure`；成功写 overall/strength/suggestion、prompt_version、language。**未填**：`ai_model` / `ai_key_source` 两列仍为空。 |
| 2026-10-21 | T263 复核 API 预填 + 批量预览 | 完成 | `ReviewDetail` 返回 aiOverall/aiStrength/aiSuggestion/aiFailure/aiModel/aiPromptVersion/aiKeySource/段数；`/grading/bulk-approve/preview` 返回可批份数、分数范围、逐项跳过原因；实际批量通过用同一判据（边界/失败/无草稿/状态）；逐份 `bulk=true` 审计。 |
| 2026-10-21 | T264 前端 Key/预填/批量 | 完成 | `/profile` AI 设置卡片（掩码/来源/保存/测试）；顶栏无 Key 提示；`/setup` 必填 Key；复核页 AI 草稿预填分数/评语/优点/建议、显示总评/失败/边界/来源；批量通过先预览后确认；作业创建页 rubric 预览 + .md 导入。`tsc --noEmit`、`pnpm build`、`check-i18n`（1098 键）通过；浏览器截图。 |
| 2026-10-21 | T265 Golden set 评估 | 完成 | 新增 `GradingQualityEvalTest`（@Tag ai，真实 v2 prompt + validator）：10 份样本逐项 exact **29/30**、总分 **9/10**、契约失败 **0**、英文语言合规 **10/10**；报告 `docs/reports/grading-quality/2026-10-21-subjective-v2.md`。JZ 的 0/2 与「问候语不计段」冲突，按 known-inconsistency 单列。 |
| 2026-10-21 | T267 端到端实测 | 完成 | 新建作业 `09c99ee0-2588-46b8-bce2-20bb1ab94351`（课程 Rubric.md，演示班）→ student01/02 提交 Iam/Alejandro → 真实 AI 两项均 10/10、英文评语带原文引用 → 复核页预填 → 保存并发布一份 → 学生反馈显示 10/10 与英文评语；批量预览 2 份可批、批量通过 1 份、跳过 1 份已发布。截图 `docs/reports/grading-quality/e2e/`。 |
| 2026-10-21 | T268 锚点 | 未开始 | 本期只留 spec/AC；不注入样例，默认关闭。 |
| 2026-10-21 | T266 全量回归 | 完成 | `make test` 退出码 0；surefire 汇总 **212 tests / 0 failures / 0 errors / 0 skipped**（含新增 `GradingQualityEvalTest` 仅 @Tag ai 默认跳过）；`check-deps` / `check-boundaries`（25 表 / 13 外键 / 7 端口）/ `check-i18n`（前端 1099 键对齐、新增中文字面量 0）/ `check-ui`（无新增违规）全通过。测试 JVM 改为每类独立 fork，避免 Testcontainers 容器与 Spring context 缓存跨类复用。 |
| 2026-10-21 | T320 答题页题干材料标签修复 | 完成 | 根因：`PaperTextParser` 把单独一行的 A–G 材料标签写进 `material`，`finish()` 拼进该篇每道题题干；快照里 Q26 是 `"B\nZach Smith…"`、`answerKey` 为 C，学生端 DTO 本就不含 `answerKey`（`Phase2ExamTimingTest` 已有断言）。修：标签只切分材料、不落题干；V032 清 `objective_items` 存量题干、V033 清 `offerings.paper_snapshot` 快照，只删首行一个字母。验证：QA 库干跑与实跑均 30 条题干 + 1 场快照、迁移后命中 0；快照结构/答案/选项/顺序 diff 0；`PaperTextParserTest` 新增 `doesNotPutPassageLabelsIntoStems`；paper 模块 20+9+4+8+13+10+1 全绿；`Phase2ExamTimingTest` 3/3；`make test` 212/0/0/0；`check-boundaries` 通过；QA 部署至 v033 后学生接口 Q21/26/30 题干以正文开头、无 `answerKey`，浏览器实测 Q21–Q35 无孤立字母；截图 `docs/reports/t320-question-label/q26-after.png`。 |
| 2026-10-21 | T321 共用材料只渲染一次 | 完成 | 后端 `MaterialGrouper` 把快照分成 21–23/24–27/28–31/32–35/36–40/41–55/56–65 七组，66–75 独立；`ExamPaperItemView` 增 `groupKey/material/questionStem/blankIndex`，材料只在组内第一条带。前端 `ExamPaper` 按组渲染：阅读材料一次 + 题目列表；完形/七选五材料一次 + 行内空（点空弹 A–D/A–G，可标记/清除）；语法填空行内输入 + 旗标；目录树、滚动联动、已答/标记、提交检查不变。任何校验不过整组回退。验证：`MaterialGrouperTest` 6/6、`Phase2ExamTimingTest` 3/3、`make test` **225/0/0/0**，`check-i18n` 基线 290、`check-ui` 通过；QA 实测整卷高度 32,147 → 14,286 px，41–55 只出现一次 1,219 字材料、56–65 只出现一次 1,091 字；截图 `docs/reports/exam-material-layout/`。注意：验证时在 Maria 的 QA 作答里点过 36/41/56/57 四题。 |
| 2026-10-21 | T322 RubricValidator 文案 i18n | 完成 | `RubricValidator.Issue(code,args)` + `RubricIssueText` 按 `LocaleContextHolder` 渲染；`messages*.properties` 各补 6 个 `error.rubric.*` key；`RubricVocabulary` 承接中文解析词表；`OfferingEndpoint`/`RubricPreviewEndpoint` 注入 `MessageSource`。验证：`RubricValidatorTest` 4/4、`RubricIssueTextTest` 2/2（中英参数与分隔符）、`check-i18n` 通过且基线 311 → 290、`make test` **225/0/0/0**（先前 SIGTERM 是后台任务被外部终止，前台重跑通过）。 |
| 2026-10-21 | T258 补：服务端 rubric 校验 + 预览 API | 完成（无逐项编辑器） | 新增 `RubricValidator`：标题优先、Yes/No 不算评分项；缺分值/名称重复/合计≠作业总分拒绝创建；历史无分值 rubric 仍按均分兼容。新增 `POST /api/rubrics/preview` 返回 criteria/total/issues；`OfferingEndpoint` 创建作业时校验。实测课程 Rubric.md 返回 2/4/4=10，6+5=11 返回 400「评分项合计 11 分，作业总分 10 分」。 |
| 2026-10-21 | T262 补：AI 溯源落库 | 完成 | `GradingResult` 增 `model/promptVersion/keySource`；`LlmClientProvider.source()` 由 `AiGradingConfig` 按 user/global/env 返回；`GradingWorker` 写 `reviews.ai_model/ai_prompt_version/ai_key_source`。实测重批后 `deepseek-flash / subjective-v2 / user`。 |
| 2026-10-21 | T268 锚点（简化版） | 完成（简化） | V034 `offerings.anchor_examples`；创建作业可填 0–3 份匿名范文（文本字段）；`WorkItem`/`SubjectiveGradeRequest` 传递，`PromptBuilder` 在非空时注入「参考范文（教师确认、已匿名）」；作业创建页新增输入框。未做结构化逐份锚点、自动匿名化检查与专门编辑器。 |
| 2026-10-21 | T323 复核页逐题判分可视化 + 学生作答前置 | 完成 | `Ports.AnswerRow` 增 `correct/awarded`，`SittingLedgerImpl` 按快照用 `ObjectiveScoring` 重算；`ReviewDetail.answers` 返回两字段。前端题项表拆「得分/满分」，对绿/错红/部分琥珀并给汇总；教师判分区前置学生正文、主观题作答、客观题答题卡与 AI 草稿预填提示。验证：`ReviewWorkflowTest` 通过（答对 10 / 答错 0）、`pnpm typecheck`/`pnpm build`/`check-i18n`/`check-ui` 通过；QA 5174 实测 75 题 33 对 42 错 48/120，作文页建议分 7.5 预填且提示不谎称有评语。 |
| 2026-10-22 | T334 复核页去重 + 题干格式化 + 去「采用 AI 建议分」 | 完成 | 用户三处反馈：①「学生正文」与「学生作答」重复渲染同一正文——删掉「学生作答」块里的正文与「AI 草稿」标签，正文只在「学生正文」出现一次；②主观题题干原来按纯文本输出，Markdown 的 `#` / `- ` 与换行被 HTML 折叠成一行——改用学生答题页同一个 `Markdown` 组件（`components/Markdown.tsx`），`Instructions` 加粗、标题与 4 条列表按输入格式显示；③按用户拍板删除「采用 AI 建议分」按钮（首次打开主观分已预填 AI 分，按钮只在老师改分后用于改回），同步删掉 `review.adoptAi` / `studentAnswer` / `aiDraftBadge` 三个不再使用的 key。验证：`pnpm typecheck` 0、`pnpm build` 0、`check-i18n` 1153 键对齐、`check-ui` 无新增违规；QA 5174 实测（submissionId 00da6728）：正文只出现一次、题干按 `Instructions` 标题 + 加粗标题 + 4 条列表显示、AI 建议分旁无按钮；截图 `docs/reports/t334-review-cleanup/`；`make test` 退出码 0（surefire 243/0/0/0），T334 为前端改动。 |
