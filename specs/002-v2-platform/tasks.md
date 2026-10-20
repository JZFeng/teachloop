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

`spec.md` 的 26 个用户故事、42 条功能需求全部有对应任务。203 条验收标准在各自用户故事的
任务里被覆盖（验收标准与任务是一对多的关系，因此不逐条列出以免矩阵变成噪音）。

### 用户故事 → 任务

| 用户故事 | 覆盖任务 | 用户故事 | 覆盖任务 |
| :--- | :--- | :--- | :--- |
| US-1 | T030–T034 | US-2 | T040–T043 |
| US-3 | T036 | US-4 | T050、T051、T054、T056、T185、T186、T206 |
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
| FR-005 | T036 | FR-006 | T050、T198、T206 |
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
