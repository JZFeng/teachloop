# 任务清单 — AI 批量批改作业 Web 系统（v1）

- 上游：`spec.md`、`plan.md`
- 状态标记：`[ ]` 未开始 · `[~]` 进行中 · `[x]` 已完成并验证
- **状态以文末「执行记录」为准**（表格不设状态列，避免两处打架）。
- **依赖规则**：严格按编号顺序执行；`依赖` 列写了前置任务的，必须全部 `[x]` 才能开始。
- **完成定义（DoD）**：代码 + 测试 + `make test` 全绿 + 本文档勾选。缺一不算完成。

---

## 阶段 0 — 环境探测与脚手架

| ID | 任务 | 依赖 | 产出 | 验收 |
| :--- | :--- | :--- | :--- | :--- |
| T001 | 探测本机能力：Docker、Postgres、Swift、tesseract、端口占用 | — | `.docs/env-report.md` | 四项结论写入报告 |
| T002 | 创建仓库骨架 `batch-grading/{backend,frontend}`、`.gitignore`、`Makefile` | T001 | 目录结构 | `make help` 可用 |
| T003 | 后端脚手架：FastAPI 应用、`/healthz`、配置加载（pydantic-settings）、日志 | T002 | `backend/app/main.py` | `curl /healthz` 返回 ok |
| T004 | 前端脚手架：Vite+React+TS，代理 `/api` 到 8010 | T002 | `frontend/` | `npm run dev` 页面可访问 |
| T005 | `docker-compose.yml`：postgres + 后端 + 前端 | T001 | compose 文件 | `docker compose config` 通过 |
| T006 | 测试骨架：pytest + httpx AsyncClient + 一个健康检查测试 | T003 | `backend/tests/` | `make test` 绿 |

## 阶段 1 — 数据模型与迁移

| ID | 任务 | 依赖 | 产出 | 验收 |
| :--- | :--- | :--- | :--- | :--- |
| T010 | 连接 Postgres、SQLAlchemy async engine、Alembic 初始化 | T003, T005 | `db.py`、`migrations/` | `alembic upgrade head` 成功 |
| T011 | 模型：`users`、`sessions` | T010 | `models/user.py` | 迁移生成并应用 |
| T012 | 模型：`classes`、`class_members` | T011 | `models/class_.py` | 唯一约束生效（测试） |
| T013 | 模型：`assignments`、`rubric_criteria`（含 length_* 字段） | T012 | `models/assignment.py` | 迁移通过 |
| T014 | 模型：`submissions`、`submission_metrics` | T013 | `models/submission.py` | 状态枚举落库 |
| T015 | 模型：`grading_jobs`、`grading_job_items` | T014 | `models/grading.py` | 外键级联正确 |
| T016 | 模型：`reviews`、`audit_logs`、`notifications` | T015 | `models/review.py` | 迁移通过 |
| T017 | 迁移集成测试：空库重建 + 采样数据插入 | T016 | `tests/test_migrations.py` | 测试绿 |

## 阶段 2 — 认证抽象

| ID | 任务 | 依赖 | 产出 | 验收 |
| :--- | :--- | :--- | :--- | :--- |
| T020 | `AuthProvider` Protocol、`ExternalIdentity`、`LoginChallenge` | T011 | `auth/base.py` | 类型检查通过 |
| T021 | `DevProvider`：按角色直接登录，仅 `ENV=dev` 注册 | T020 | `auth/dev.py` | 端点可用；生产环境不注册（测试） |
| T022 | 会话：签名 Cookie + `sessions` 表，登录/退出/过期/吊销 | T021 | `auth/session.py`、`/api/auth/*` | AC-1.5 测试通过 |
| T023 | 依赖注入：`current_user`、`require_teacher`、`require_student` | T022 | `auth/deps.py` | 未登录 401、错角色 403 |
| T024 | 前端登录页 + `/api/auth/me` 引导 + 受保护路由 | T004, T022 | `pages/Login.tsx`、`AuthGuard` | 未登录跳转（AC-1.1） |

## 阶段 3 — 班级与成员

| ID | 任务 | 依赖 | 产出 | 验收 |
| :--- | :--- | :--- | :--- | :--- |
| T030 | 班级 CRUD（教师） + 邀请码生成/轮换 | T023, T012 | `/api/classes/*` | AC-2.1/2.3/2.4 |
| T031 | 学生凭邀请码加入 | T030 | `POST /api/classes/join` | AC-2.2；重复加入幂等 |
| T032 | 成员列表与提交进度聚合 | T030 | `GET /api/classes/{id}/members` | AC-2.5 |
| T033 | 班级隔离：教师只见自己的班、学生只见已加入的班 | T031 | 查询层约束 | 越权返回 404（宪法七） |
| T034 | 前端：班级列表 / 创建 / 加入 / 详情 | T030, T024 | `pages/Classes*.tsx` | 端到端手动走通 |

## 阶段 4 — 作业与 Rubric

| ID | 任务 | 依赖 | 产出 | 验收 |
| :--- | :--- | :--- | :--- | :--- |
| T040 | 作业 CRUD + 状态（draft/published/closed） | T033, T013 | `/api/assignments/*` | 状态流转测试 |
| T041 | rubric 评分项 CRUD + 总分一致性校验 | T040 | `rubric_criteria` 端点 | AC-3.2/3.4 |
| T042 | 篇幅口径字段（mode/min/max/note）读写与校验 | T041 | 字段校验 | AC-3.3 |
| T043 | 作业发布后 rubric 锁定 + 修改需确认 + 审计 | T042, T016 | 锁定逻辑 | AC-3.5 |
| T044 | 前端：作业创建/编辑表单（含评分项动态列表） | T041, T034 | `pages/AssignmentEdit.tsx` | 总分不符时有可见错误 |

## 阶段 5 — 上传与文本提取

| ID | 任务 | 依赖 | 产出 | 验收 |
| :--- | :--- | :--- | :--- | :--- |
| T050 | `StorageBackend` 接口 + `LocalStorage`（`data/objects/`） | T003 | `storage/` | 存取测试 |
| T051 | 上传端点：类型白名单、100MB 上限、版本号 | T050, T040 | `POST /api/assignments/{id}/submissions` | AC-4.1/4.2/4.3 |
| T052 | 教师批量导入（按文件名匹配学生） | T051 | `/submissions/import` | AC-4.4 |
| T053 | 文本提取：txt/md 直读、docx（python-docx）、pdf（pypdf） | T051 | `grading/extract.py` | 各格式单测 |
| T054 | `OcrProvider` 接口 + 结果数据类 | T053 | `ocr/base.py` | 类型检查 |
| T055 | **可行性验证**：Swift `ocrtool`（Vision + PDFKit）能识别一张实拍图与一页扫描 PDF | T054 | `tools/ocrtool/` | 手动验证并记录 |
| T056 | `MacVisionOcr` 接入子进程 + 错误映射 | T055 | `ocr/macvision.py` | 扫描件提取出非空文本 |
| T057 | 无文本层判定：提取为空 → `needs_ocr`；OCR 空 → `unreadable` | T056 | 状态流转 | AC-4.5/4.6（宪法四） |
| T058 | 前端：学生提交页（拖拽上传、状态回显） | T051, T044 | `pages/Submit.tsx` | 走通一次真实提交 |

## 阶段 6 — 客观指标

| ID | 任务 | 依赖 | 产出 | 验收 |
| :--- | :--- | :--- | :--- | :--- |
| T060 | 移植四模型段落计数到 `grading/length.py`（保持行为一致） | T053 | `length.py` | 与原始脚本同输入的输出一致 |
| T061 | 移植测试：用工作区真实的 10 份作业做回归基线 | T060 | `tests/fixtures/` | 段数快照测试绿 |
| T062 | 词数 / 时长（ffprobe）/ 文件类型指标 | T060 | `grading/objective.py` | 时长缺失时标记未知 |
| T063 | 写入 `submission_metrics`（含 `boundary_sensitive`） | T062, T014 | 指标落库 | 重复计算幂等 |

## 阶段 7 — 批改编排与 SSE

| ID | 任务 | 依赖 | 产出 | 验收 |
| :--- | :--- | :--- | :--- | :--- |
| T070 | `GradingEngine` Protocol + `GradeRequest/GradeResult` | T063 | `grading/engine.py` | 类型检查 |
| T071 | `ScriptOnlyEngine`（降级：只出客观项） | T070 | 实现 | 无 AI 也能完成一份 |
| T072 | `DshHeadlessEngine`：子进程 + NDJSON 解析 + 超时 | T070 | 实现 | 实测返回合法结果 |
| T073 | 模型输出 Pydantic 校验 + 重试 1 次 + 失败留证 | T072 | `schemas/grade_result.py` | 非法 JSON 用例测试（AC-5.5） |
| T074 | 任务创建：job + items（跳过 unreadable） | T070, T015 | `POST /grading-jobs` | AC-5.1（宪法四） |
| T075 | asyncio worker：并发可配（默认 3）、单份超时 180s | T074 | `grading/pipeline.py` | 单份超时不阻塞整批（AC-5.6） |
| T076 | SSE 端点：事件序列 + 断线重连（Last-Event-ID） | T075 | `/events` | AC-5.2/5.7 |
| T077 | 服务重启后 job 标记 `interrupted` + `resume` 续跑 | T076 | resume 端点 | 重启场景测试（边缘情况） |
| T078 | 前端：批改进度页（逐份状态、失败原因、重试） | T076, T058 | `pages/GradingRun.tsx` | 实时更新可见 |

## 阶段 8 — 四态复核与审计

| ID | 任务 | 依赖 | 产出 | 验收 |
| :--- | :--- | :--- | :--- | :--- |
| T080 | 复核状态机：pending→approved/minor_edit/major_edit/rejected | T073 | `reviews` 流转 | 非法流转被拒（测试） |
| T081 | 编辑接口：改分数/理由，写入前值后值 | T080 | `PATCH /api/reviews/{id}` | AC-6.2 |
| T082 | 审计日志：只追加、不可改、含 actor/action/before/after | T081, T016 | `audit_logs` 写入 | 更新/删除接口不存在（测试） |
| T083 | 发布/撤回：仅 approved 可发布 | T080 | `/publish` | AC-6.3 |
| T084 | 批量批准（逐份留审计） | T080 | `/approve-all` | AC-6.7 |
| T085 | 边界敏感提示：`boundary_sensitive` 显式标注 | T063, T080 | 响应含标记 | AC-6.6 |
| T086 | 前端：复核工作台（列表筛选、详情、四态按钮、差异显示） | T084, T078 | `pages/Review*.tsx` | 走通一次完整复核 |

## 阶段 9 — 学生端与通知

| ID | 任务 | 依赖 | 产出 | 验收 |
| :--- | :--- | :--- | :--- | :--- |
| T090 | 学生作业列表（含状态与是否有新反馈） | T083 | `/api/me/assignments` | AC-7.1 |
| T091 | 学生反馈详情：仅本人、仅已发布、越权 404 | T090 | `/api/submissions/{id}/feedback` | AC-7.2/7.4 |
| T092 | 通知：发布时生成记录，列表与已读 | T091, T016 | `/api/notifications` | AC-7.1 |
| T093 | 前端：学生首页 / 反馈页 | T092, T024 | `pages/Student*.tsx` | 学生账号走通 |

## 阶段 10 — 导出

| ID | 任务 | 依赖 | 产出 | 验收 |
| :--- | :--- | :--- | :--- | :--- |
| T100 | ZIP 导出：逐份反馈 + `class_summary.txt` | T083 | `/export.zip` | AC-8.1 |
| T101 | CSV 导出：成绩与状态 | T100 | `/export.csv` | AC-8.2 |
| T102 | 导出内容与界面一致、仅含已批准 | T101 | 一致性测试 | AC-8.3 |

## 阶段 11 — 微信登录接入（需 B-1/B-2）

| ID | 任务 | 依赖 | 产出 | 验收 |
| :--- | :--- | :--- | :--- | :--- |
| T110 | `WeChatOpenProvider`：构造授权 URL、state 校验 | T020 | `auth/wechat.py` | 单测（模拟响应） |
| T111 | 回调换 openid/unionid、首登建号、绑定已有用户 | T110 | `/callback` | AC-1.3/1.4 |
| T112 | 前端扫码登录与轮询/回调处理 | T111, T024 | 登录页增强 | 真实扫码成功（**待凭据**） |

## 阶段 12 — 集成与硬化

| ID | 任务 | 依赖 | 产出 | 验收 |
| :--- | :--- | :--- | :--- | :--- |
| T120 | 端到端测试：教师建班→学生交→批改→复核→发布→学生看→导出 | 全部 | `tests/e2e/` | 一条命令跑通 |
| T121 | 安全自查：CSRF、越权 404、上传白名单、日志脱敏 | T120 | 自查清单 | 逐项通过 |
| T122 | 性能：40 份批量批改压测，记录耗时与并发表现 | T075 | 压测报告 | 满足 NFR |
| T123 | README：安装、启动、微信凭据配置、OCR 切换、引擎切换 | T120 | `README.md` | 新人可照做启动 |

---

## 关键路径

```
T001→T002→T003→T010→T011→T012→T013→T014→T015→T016
   →T020→T021→T022→T023→T030→T033→T040→T041→T050→T051
   →T053→T055→T056→T060→T063→T070→T072→T074→T075→T076
   →T080→T083→T091→T100→T120
```

最短可用路径（能跑通一次批改）止于 **T078**；能交给学生用止于 **T092**；完整 v1 止于 **T123**。

---

## 需求追踪矩阵（/analyze 结果）

每条需求都必须能落到至少一个任务上。本矩阵由一致性检查生成并人工确认：
**spec 中 8 条用户故事、19 条功能需求、45 条验收标准全部有覆盖任务。**

### 功能需求 → 任务

| 编号 | 覆盖任务 | 编号 | 覆盖任务 |
| :--- | :--- | :--- | :--- |
| FR-001 | ['T020', 'T021', 'T110', 'T111'] | FR-002 | ['T022', 'T023'] |
| FR-003 | ['T030', 'T031'] | FR-004 | ['T040', 'T041', 'T042'] |
| FR-005 | ['T051'] | FR-006 | ['T053'] |
| FR-007 | ['T054', 'T055', 'T056'] | FR-008 | ['T062'] |
| FR-009 | ['T060', 'T061', 'T062', 'T063'] | FR-010 | ['T074', 'T075'] |
| FR-011 | ['T076'] | FR-012 | ['T070', 'T071', 'T072'] |
| FR-013 | ['T073'] | FR-014 | ['T080', 'T081'] |
| FR-015 | ['T082'] | FR-016 | ['T083', 'T084'] |
| FR-017 | ['T090', 'T091', 'T093'] | FR-018 | ['T092'] |
| FR-019 | ['T100', 'T101', 'T102'] |  |  |

### 用户故事 → 任务

| 编号 | 覆盖任务 | 编号 | 覆盖任务 |
| :--- | :--- | :--- | :--- |
| US-1 | T020–T024、T110–T112 | US-2 | T030–T034 |
| US-3 | T040–T044 | US-4 | T050–T058 |
| US-5 | T060–T078 | US-6 | T080–T086 |
| US-7 | T090–T093 | US-8 | T100–T102 |

### 验收标准 → 任务

| 编号 | 覆盖任务 | 编号 | 覆盖任务 |
| :--- | :--- | :--- | :--- |
| AC-1.1 | T024 | AC-1.2 | T110、T112 |
| AC-1.3 | T111 | AC-1.4 | T111 |
| AC-1.5 | T022 | AC-1.6 | T021 |
| AC-2.1 | T030 | AC-2.2 | T031 |
| AC-2.3 | T030 | AC-2.4 | T030 |
| AC-2.5 | T032 | AC-2.6 | T033 |
| AC-3.1 | T040 | AC-3.2 | T041 |
| AC-3.3 | T042 | AC-3.4 | T041 |
| AC-3.5 | T043 | AC-4.1 | T051 |
| AC-4.2 | T051 | AC-4.3 | T051 |
| AC-4.4 | T052 | AC-4.5 | T057 |
| AC-4.6 | T057 | AC-5.1 | T074 |
| AC-5.2 | T076 | AC-5.3 | T063 |
| AC-5.4 | T073 | AC-5.5 | T073 |
| AC-5.6 | T075 | AC-5.7 | T076 |
| AC-5.8 | T074 | AC-6.1 | T080 |
| AC-6.2 | T081 | AC-6.3 | T083 |
| AC-6.4 | T080 | AC-6.5 | T086 |
| AC-6.6 | T085 | AC-6.7 | T084 |
| AC-7.1 | T092 | AC-7.2 | T091 |
| AC-7.3 | T091 | AC-7.4 | T091 |
| AC-8.1 | T100 | AC-8.2 | T101 |
| AC-8.3 | T102 |  |  |

---

## 未实现声明（v1 不做）

- LMS 对接（Canvas / 教室 / 校级系统）
- 推送通知与邮件（AC 中明确为后续）
- 公众号网页授权（仅留接口）
- 手写识别专项优化
- 多租户隔离（v1 单租户）
- 统计看板与图表

---

## 执行记录

每完成一个任务，在此追加一行：`日期 · 任务ID · 结果 · 测试命令与结论`。

| 日期 | 任务 | 结果 | 验证 |
| :--- | :--- | :--- | :--- |
| 2026-10-18 | 规范文档（宪法/spec/plan/tasks） | 完成 | 一致性检查：US 8/8、FR 19/19、AC 45/45 均有覆盖任务；72 任务无重复 |
| 2026-10-18 | T001 环境探测 | 完成 | 见 `.docs/env-report.md`：Docker daemon 已启动、postgres:16-alpine 已拉取、Swift 6.3.2 可用、tesseract 未装、8000 被占用故后端改 8010 |
| 2026-10-18 | T002 仓库骨架 | 完成 | `make help` 输出 9 个目标；`.gitignore` 覆盖 venv/node_modules/data/.npm-cache |
| 2026-10-18 | T003 后端脚手架 | 完成 | `curl :8010/healthz` → `{"status":"ok"}`；`/api/meta` 暴露三处实现 |
| 2026-10-18 | T004 前端脚手架 | 完成 | `npm run build` 通过（tsc --noEmit + vite，79 模块）；经 5173 代理 `/api/meta` 返回后端 JSON |
| 2026-10-18 | T005 docker compose | 完成 | `docker compose config` VALID；`--profile full` 含 db/backend/frontend |
| 2026-10-18 | T006 测试骨架 | 完成 | `make test` → 2 passed |
| 2026-10-18 | T010 引擎 + Alembic | 完成 | `alembic upgrade head` 成功，`alembic current` = fc6af7429397 (head)，重复执行幂等 |
| 2026-10-18 | T011 users / sessions | 完成 | 建表验证：`users` 8 列（id uuid、role 枚举、微信两列、时间戳），`sessions` 外键级联；`asyncpg` 连接 PostgreSQL 16.15 通过 DDL 验证 |
| 2026-10-18 | T012 classes / class_members | 完成 | `classes`、`class_members`；`uq_class_member` 唯一约束测试通过 |
| 2026-10-18 | T013 assignments / rubric_criteria | 完成 | 含 `length_mode/min/max/note`；`uq_criterion_position` 唯一约束测试通过 |
| 2026-10-18 | T014 submissions / submission_metrics | 完成 | `uq_submission_version`；`paragraphs_by_model` JSONB 落库验证 |
| 2026-10-18 | T015 grading_jobs / grading_job_items | 完成 | `uq_job_item_submission`；状态枚举就位 |
| 2026-10-18 | T016 reviews / audit_logs / notifications | 完成 | `review_state` 枚举拒绝非法值（测试通过）；审计索引 `ix_audit_entity` |
| 2026-10-18 | T017 迁移集成测试 | 完成 | `pytest` → 8 passed；其中 6 个真连 PostgreSQL：临时库建→迁移→验证→删除，dev 库未受影响 |
| 2026-10-18 | T020 AuthProvider 接口 | 完成 | `app/auth/base.py`：`ExternalIdentity`、`LoginChallenge`、`AuthProvider` Protocol、`make_external_id` |
| 2026-10-18 | T021 DevProvider | 完成 | `app/auth/dev.py`；生产环境注册表直接拒绝（测试 `test_dev_login_denied_outside_dev` 通过） |
| 2026-10-18 | T022 会话 | 完成 | 签名 Cookie + `sessions` 表；实测：登出后**回放原 cookie 仍 401**（服务端吊销生效） |
| 2026-10-18 | T023 依赖注入 | 完成 | `current_user` / `require_teacher` / `require_student` / `require_csrf`；未登录 401、缺 CSRF 403 |
| 2026-10-18 | T024 前端登录页 | 完成 | 经 Vite 代理实测 7 步全通过：页面渲染、login-options、未登录 401、dev-login、带 cookie 取 me、无 CSRF 登出 403、带 CSRF 登出 200 |
| 2026-10-18 | T030 班级 CRUD + 邀请码 | 完成 | 建班返回 8 位邀请码（去除 I/O/0/1 易混字符）；轮换后旧码 404、新码可用 |
| 2026-10-18 | T031 学生凭码加入 | 完成 | 重复加入幂等（成员数仍为 2）；邀请码停用后 404 |
| 2026-10-18 | T032 成员列表 + 进度聚合 | 完成 | 教师可见名单与 `submission_count`；学生访问返回 403 |
| 2026-10-18 | T033 班级隔离 | 完成 | 他人班级一律 **404 而非 403**（不可探测存在性）；学生视图 `invite_code=null` |
| 2026-10-18 | T034 前端班级页面 | 完成 | 列表/创建/加入/详情/成员表；实测：教师建班 → 学生凭码加入 → 教师看到 2 名成员 |
| 2026-10-18 | T040 作业 CRUD | 完成 | 草稿/已发布状态；学生只能看到已发布（草稿对列表与详情均 404） |
| 2026-10-18 | T041 rubric 评分项 + 总分校验 | 完成 | 评分项合计≠满分时 400 并回报差额 |
| 2026-10-18 | T042 篇幅口径字段 | 完成 | 段落/分钟模式必须同时给上下限，且下限≤上限 |
| 2026-10-18 | T043 发布后锁定 + 审计 | 完成 | 发布后改 rubric 需 `force=true`（否则 409）；发布动作写入 `audit_logs` |
| 2026-10-18 | T044 前端作业表单 | 完成 | 新建表单实时显示「评分项合计/满分」差额并阻止提交；实测：错分 400、正确创建、发布、学生仅见已发布、发布后无 force 加项 409 |

### 阶段 4 完成状态

T040–T044 全部完成，`pytest` → **45 passed**，`npm run build` 通过（90 模块）。

### 阶段 5 执行记录（部分完成）

| 日期 | 任务 | 结果 | 验证 |
| :--- | :--- | :--- | :--- |
| 2026-10-18 | T050 存储抽象 | 完成 | `LocalStorage` + `build_key`（摘要 + uuid，拒绝路径穿越） |
| 2026-10-18 | T051 上传端点 | 完成 | 类型白名单、100MB 流式上限（不整份进内存）、版本递增、旧版 `superseded_by` |
| 2026-10-18 | T053 文本提取 | 完成 | txt/md、docx、pdf；解析失败返回原因而非空字符串 |
| 2026-10-18 | T054 OcrProvider 接口 | 完成 | 区分 `OcrUnavailable`（环境不可用）与 `OcrError`（识别失败） |
| 2026-10-18 | T055 可行性验证 | **完成，结论为负** | Vision `.accurate` 报 `nilError`；`.fast` 输出不可用。改用 Tesseract，见 `.docs/env-report.md` §6 |
| 2026-10-18 | T056 OCR 实现 | 完成 | `TesseractOcr`（PDF 经 ocrtool `--render` 栅格化）+ `MacVisionOcr`（如实报告不可用） |
| 2026-10-18 | T057 状态流转 | 完成 | 无文本层 → `needs_ocr`；OCR 失败 → `needs_ocr` + 人工录入提示；**绝不静默计 0 分** |
| 2026-10-18 | T058 前端上传页 | 完成 | 学生上传控件；提交列表显示状态/OCR 来源；**边界敏感醒目提示**；教师可对 needs_ocr 手动录入 |

阶段 5 后端完成，`pytest` → **57 passed**（新增 12 个提交测试）。

**真实验证**：42MB / 6 页 iOS 扫描件 `75145.pdf` 全流程
**31.4 秒 → 20,542 字符，status=ready，ocr_provider=tesseract**，
中文标题与英文题目均可读（少量噪声由教师复核兜底）。

**与原计划的偏差**：默认 OCR 由 `macvision` 改为 `tesseract`，
因为 Vision 的 accurate 模型在本机不可用（机械二分 9 种配置后确认）。
可替换边界已按宪法第六条落地，切换只改一个环境变量。

### 阶段 6 执行记录（完成）

| 日期 | 任务 | 结果 | 验证 |
| :--- | :--- | :--- | :--- |
| 2026-10-18 | T060 移植四模型段落计数 | 完成 | `app/grading/length.py`，逻辑与原脚本逐字节等价 |
| 2026-10-18 | T061 回归基线 | 完成 | **与原始脚本在真实 10 份作业上逐一比对，四模型段数完全一致**；另钉住每份的期望段数 |
| 2026-10-18 | T062 词数/时长/类型 | 完成 | ffprobe 实测 90s 音频 → 1.50 分钟；无法判定时返回说明而不估算 |
| 2026-10-18 | T063 指标落库 | 完成 | `submission_metrics` 幂等 upsert；重交会重算 |

阶段 6 完成，`pytest` → **74 passed**。

**本轮修的问题**：`_out()` helper 开始读取 `metrics` 关系后，
手动录入文本的端点抛出 `MissingGreenlet`（`refresh()` 不加载关系）。
改为提交后用 `selectinload` + `populate_existing` 重新查询。
这是第二次踩同一个坑，说明「序列化层不得触达未预加载的关系」应作为约定。

### 阶段 7 执行记录（后端完成）

| 日期 | 任务 | 结果 | 验证 |
| :--- | :--- | :--- | :--- |
| 2026-10-18 | T070 GradingEngine 接口 | 完成 | `GradeRequest`/`GradeResult`/`build_prompt`；引擎可替换 |
| 2026-10-18 | T071 ScriptOnlyEngine | 完成 | 降级引擎**不伪造分数**，返回 `payload=None` + 说明 |
| 2026-10-18 | T072 DshHeadlessEngine | 完成 | 子进程 + NDJSON 解析 + 超时；实测真实批改 6.1 秒完成 |
| 2026-10-18 | T073 输出校验与重试 | 完成 | Pydantic + rubric 一致性校验（名称/满分/总分/缺失/重复），失败重试 1 次后报错 |
| 2026-10-18 | T074 任务创建 | 完成 | 无文本/不可读的提交被排除并回报原因，不送模型 |
| 2026-10-18 | T075 并发 worker | 完成 | 信号量并发；单份失败不影响整批 |
| 2026-10-18 | T076 SSE | 完成 | 连接即发快照 + 15s 心跳；**终态任务发完快照立即关闭** |
| 2026-10-18 | T077 重启恢复 | 完成 | 启动时把遗留 running 标为 `interrupted`；`resume` 重新排队（含 skipped） |
| — | T078 前端进度页 | 顺延 | 后端就绪 |

阶段 7 后端完成，`pytest` → **94 passed**。

**真实端到端验证（关键）**：
真实 `dsh headless` 批改一份作业 → **6.1 秒完成，1 次尝试，无错误**。
产出 review：`ai_total=10`、`state=pending`，三条 criteria 都带引用原文的理由
（例：Required Length 2 分，理由引用了 96 词与「问候语并入第一段」的计数规则）。
默认引擎已由 `script_only` 改为 `dsh_headless`。

**本轮修的两个隐蔽问题**：
1. **SSE 对终态任务永不关闭**。任务已完成时连接仍挂着，前端会永远显示「批改中」。
   改为发完快照即 return，并加了回归测试（若回归，该测试会挂 10 秒超时）。
2. **`resume` 不重排 skipped 项**。条目只为有文本的提交创建，所以 skipped
   意味着引擎产出不可用，正是最该重试的情况。

### 阶段 8 执行记录（完成）

| 日期 | 任务 | 结果 | 验证 |
| :--- | :--- | :--- | :--- |
| 2026-10-18 | T080 四态状态机 | 完成 | `pending → approved/minor_edit/major_edit/rejected`；非法流转 409（如 approved→approved） |
| 2026-10-18 | T081 编辑接口 | 完成 | 校验总分与满分；审计记录 before/after 完整载荷 |
| 2026-10-18 | T082 审计日志 | 完成 | 只追加：实测产生 `decide:approved` / `publish` / `unpublish` 三条；无 update/delete 接口 |
| 2026-10-18 | T083 发布/撤回 | 完成 | 未批准发布返回 409（宪法第一条）；已发布改内容返回 409，需先撤回 |
| 2026-10-18 | T084 批量批准 | 完成 | 每份独立写审计；默认**跳过边界敏感项**并回报被跳过数量 |
| 2026-10-18 | T085 边界敏感提示 | 完成 | 复核列表与详情均暴露 `boundary_sensitive` + 四模型段数 |
| 2026-10-18 | T086 复核工作台 | 完成 | 列表/筛选/详情/四态按钮/编辑/发布/撤回；批量批准按钮 |
| 2026-10-18 | T078 批改进度页 | 完成 | SSE 快照 + 实时刷新；进度条；逐份状态与失败原因 |

阶段 8 完成，`pytest` → **107 passed**。

**真实端到端验证**：真实 AI 批改一份 → 复核 → 发布全链路通过：
```
job completed done=1 failed=0 → review pending ai_total=10
publish before approval → 409
approve → 200 approved
publish → 200 published_at 已设置
edit after publish → 409
unpublish → 200
```
审计日志实测记录：`review decide:approved` / `review publish` / `review unpublish`。

**本轮修的问题**：`ReviewOut` 漏映射 `rejection_reason`（驳回原因显示为 null）。
是测试断言具体字段才发现的，只测「HTTP 200」会漏掉。

### 阶段 9 执行记录（完成）

| 日期 | 任务 | 结果 | 验证 |
| :--- | :--- | :--- | :--- |
| 2026-10-18 | T090 学生作业列表 | 完成 | 只列已发布作业；含提交状态、是否有反馈、分数、未读标记 |
| 2026-10-18 | T091 学生反馈详情 | 完成 | 未发布 → 404；他人提交 → 404（不泄露存在性） |
| 2026-10-18 | T092 通知 | 完成 | 发布时自动创建；列表/单条已读/全部已读 |
| 2026-10-18 | T093 学生端页面 | 完成 | `/my` 作业与通知、`/feedback/:id` 反馈详情 |

### 阶段 10 执行记录（完成）

| 日期 | 任务 | 结果 | 验证 |
| :--- | :--- | :--- | :--- |
| 2026-10-18 | T100 ZIP 导出 | 完成 | 逐份 `{姓名}_review.txt` + `class_summary.txt`；仅含已发布 |
| 2026-10-18 | T101 CSV 导出 | 完成 | 中文表头 + 每项得分 + 总分；带 BOM 便于 Excel 打开 |
| 2026-10-18 | T102 一致性 | 完成 | 未发布时导出返回 400；学生 403；他班教师 404 |

阶段 9–10 完成，`pytest` → **115 passed**。

**真实端到端验证**：
```
job completed
student: has_feedback=True unread=True score=10
notification: 《阶段9作业》有新的批改反馈 → read-all 后未读 0
ZIP: ['reviews/阶段9学生_review.txt', 'reviews/class_summary.txt']
     summary: Number of submissions reviewed: 1 / Average score: 10.0/10
CSV: 学生,文件名,状态... → 阶段9学生, total 10
```

**刻意保守的设计**：`class_summary.txt` 由**确定性计算**生成（人数、平均分、
分数分布、每项低于满分的次数），不生成"教学建议"式的叙述——那无法验证，
会违反宪法第十条。教学建议由教师自己补。

### 阶段 11 执行记录（部分完成）

| 日期 | 任务 | 结果 | 验证 |
| :--- | :--- | :--- | :--- |
| 2026-10-18 | T110 WeChatOpenProvider | 完成 | 授权 URL 构造、token/userinfo 交换、错误处理；HTTP 客户端可注入以便测试 |
| 2026-10-18 | T111 回调换 openid 建号 | 完成 | 签名 state（含角色）防伪造；实测回调建号 + 下发 cookie + `/me` 返回角色 |
| — | T112 真实扫码联调 | **阻塞** | 缺 AppID/AppSecret/回调域名（B-1/B-2），无法端到端验证 |

阶段 11 部分完成，`pytest` → **129 passed**。

**凭据缺失下的处理方式**：未配置微信凭据时 `/api/auth/wechat/login` 返回 **503**，
而不是静默回退到开发替身——静默回退会让生产环境变成"无鉴权"。

**角色如何确定**：角色写在**签名 state** 里随二维码一起走，回调时取出使用。
这样前端传的 role 无法被篡改。首次登录即确定角色（对应 AC-1.3 的修正）。

**唯一未验证的部分**：微信服务器能否真的回调到我们的域名。
这需要已备案域名与开放平台应用，属外部依赖，不是代码问题。

**本轮修的三个实际问题**：

1. **测试隔离**：`migrated_db` 是 session 级，班级数据在测试间泄漏（断言 1 个得到 4 个）。
   加 `_truncate_between_tests` autouse 夹具做 `TRUNCATE ... CASCADE`，未放宽断言。
2. **`MissingGreenlet`**：async SQLAlchemy 下 `db.refresh()` 不会加载关系，
   随后访问 `assignment.criteria` 触发惰性 IO 直接抛错。
   改为所有读路径用 `selectinload(Assignment.criteria)` 预加载。
3. **身份映射返回旧快照**：同一 session 内写入后再读，`selectinload` 不会覆盖
   已加载的集合，导致新增评分项不生效（criteria_points 仍是 10）。
   加 `.execution_options(populate_existing=True)` 强制刷新。

### 阶段 3 完成状态

T030–T034 全部完成，`pytest` → **33 passed**，`npm run build` 通过（87 模块）。

**本轮发现并修复的问题**：测试隔离。`migrated_db` 是 session 级夹具，
前一个测试建的班级会泄漏到后一个测试（断言 1 个班实际得到 4 个）。
没有放宽断言，而是加了 `_truncate_between_tests` autouse 夹具：
每个测试前 `TRUNCATE ... CASCADE`（保留 `alembic_version`），
既保证隔离又不必每个测试重跑迁移。

### 阶段 2 完成状态

T020–T024 全部完成，`pytest` → **19 passed**，`npm run build` 通过（84 模块）。

**本轮记录的两处设计修正（已回写文档）**：

1. **AC-1.3 修正**：原文「role 由邀请码决定」存在先有鸡先有蛋——教师必须先有账号才能建班发码。
   改为「角色在首次登录时选择，邀请码只决定入哪个班」。已更新 `spec.md`。
2. **外部身份存储**：未新增 `identities` 表，改为 `users.wechat_openid` 存 `provider:key`
   前缀值（`make_external_id`）。理由：v1 同时只有一个 provider 活跃。
   若将来需多 provider 并存，再拆表。已注明于 `plan.md` 数据模型段。

### 阶段 1 完成状态

T010–T017 全部完成。迁移链：`fc6af7429397` → `8ef09f54249e (head)`。
库中 **14 张表、7 个枚举类型**。全量实体链路（教师建班→学生加入→作业 rubric→提交指标→
批改任务→复核→审计→通知）在真库上插入成功。

### 阶段 0 完成状态

全部 6 个任务（T001–T006）已完成并验证，`make test` 绿。

### 已发现的实现偏差（均已回写文档）

1. **Python 解释器**：原计划用 DSH 运行时 3.12，实测因其 hardened runtime 签名无法加载
   `pydantic_core.so`（Team ID 不符）。改用 Homebrew 3.14.7，全依赖实测通过。详见 `.docs/env-report.md` 2.3。
2. **端口**：8000 被本机其他进程占用，后端改 8010（plan.md、vite 配置、tasks 已同步）。
3. **Vite 仅监听 IPv6**：已显式设 `host: "127.0.0.1"`。
4. **npm 缓存不可写**：改用项目内 `.npm-cache`。
