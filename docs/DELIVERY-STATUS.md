# TeachLoop v2 交付状态

日期：2026-10-20　分支：`main`　最近提交见 `git log -1`

## 一、可运行入口

```bash
make start-db          # Postgres 16 @ 55432
make run               # 后端 @ 8010
make web-dev           # Vite @ 5173（/api 代理到 8010）
make seed              # 可重复演示数据：1 班 + 30 学生 + 1 份 4 题试卷
```

本地账号：`teacher@teachloop.local` / `student@teachloop.local`，密码 `teachloop123`。
生产部署：`docs/deploy-aliyun.md`、`Dockerfile`、`docker-compose.prod.yml`、`.env.example`。

## 二、验收命令

```bash
make build             # 九模块编译 + 打包
make test              # Testcontainers 集成测试 + check-deps/check-boundaries/check-i18n
make web-build         # tsc + vite build
make status            # 最近提交/工作区/测试/进度
```

真实 AI：需要 `DEEPSEEK_API_KEY`，`make test-ai` 跑 2 个真机调用测试。
golden set：`DEEPSEEK_API_KEY=... python3 scripts/golden-set.py`。

## 三、已完成（按批次）

| 批次 | 内容 | 证据 |
| :--- | :--- | :--- |
| 骨架/边界 | 九模块、依赖守卫、端口守卫、迁移表归属 | `make check-*` |
| 认证/账号 | Dev/Admin/微信 provider、教师邀请码、管理员 CLI、CSRF（prod）、资料补全、角色守卫、限流 | `IdentityAuthTest`、`ProdAuthTest`、`ProfileCompletionTest`、`RateLimitTest` |
| 班级 | 建班/邀请码/加入、成员列表含姓名与提交数、访问控制 | `ClassAccessTest` |
| 试卷 | CRUD/导入/题型/匹配策略/发布锁/rubricVersion/答案表/75 题拆分/复制/归档 | `Paper*Test`、`MatchStrategyTest`、`PaperLibraryTest` |
| 布置/作答 | 快照、多班、按班覆盖、服务端计时、自动交卷、迟交/拒收、new attempt | `FlowIntegrationTest`、`Phase2ExamTimingTest`、`NewAttemptTest` |
| 提交形态 | 富文本消毒、文件（PDF/DOCX/TXT/MD）、视频（ffprobe 360s/1920x1080）、附件下载 | `SubmissionFormsTest`、`VideoRulesTest`、`VideoSubmitTest` |
| 批改 | 客观判分、AI 判分、调度/僵尸清扫、总分派生、改答案重算 | `GradingDispatchTest`、`ObjectiveRecomputeTest`、`make test-ai` |
| 复核 | 三态、编辑/发布/撤回/批量、审计、发布可见与通知 | `ReviewWorkflowTest` |
| 反馈/报表 | 错题/解析/班级概览、Top N、按作业/班级/学生聚合、XLSX/PDF 导出 | `AggregateReportTest`、`ReportWrongItemsTest`、`ExportTest` |
| 可观测 | 请求日志、前端事件、90 天清理、daily_stats | `ObservabilityTest` |
| 部署 | 镜像（Noto CJK/ffprobe）、compose、深健康探针、阿里云文档 | `DeepHealthTest` + 镜像实跑 |
| E2E | 旅程 A：3 班 + 77 题；旅程 B：富文本 + 3 次 attempt + 迟交/拒收 | `.docs/e2e-report-v2.md` |
| 压测 | 50 份提交、并发 10；1+1 p95 45ms/33.8s，2+2 p95 97ms/7.22s | `.docs/perf-report-v2.md` |
| 质量基线 | v1 四模型段落计数移植，10 份 parity 全一致 | `TextLengthCounterFixtureTest` |
| Golden set | 10 份人工均分 9.6；deepseek-flash 平均偏差 0.0、最大 0、方向持平 | `docs/reports/golden-set-baseline.md`、`make test-ai` |
| 管理员初始设置 | `/setup` 创建首个管理员（Key 可空）；`/admin/ai` 维护全局 provider 列表（Key AES-GCM 加密、只回掩码、测试连接、模型发现）并切换当前模型；教师 `/profile` 没有任何 AI 入口（D-33，2026-10-24 修订） | `AiSetupTest`；QA 浏览器 `scripts/verify/ai-model-settings.mjs` 12/12，截图 `docs/reports/t386-ai-model-settings/` |
| 角色导航矩阵（007） | 后端 `RoleNavMatrix` 唯一真源（**admin 1 项 / teacher 9 项 / student 5 项**；2026-10-27 起班级移出管理员，管理员只剩 AI 设置）；`/api/features` 按当前用户返回 key 集合；前端只保留菜单目录 + 路由守卫；后端方法级收口（报表/试卷/批改/复核/布置/班级对 ADMIN 403）。三角色浏览器验收通过（2026-10-25：admin 侧栏 2 项、教师 9 项、学生 5 项，截图 `docs/reports/role-nav-2026-10-25/`；2026-10-27 的 admin 1 项只有接口 + 结构校验） | `RoleNavMatrixTest`、`FeaturesEndpointTest`、`RoleAccessTest`、`vocab-nav.test.mjs` |

## 四、部分完成 / 已知偏差

1. **T174 判分引擎**：1+1 与 2+2 拓扑都跑过（批改 33.8s → 7.22s），但压测用 stub 引擎，不代表真实 DeepSeek 吞吐与成本。
2. **PDF 中文字体**：使用 OpenPDF 内置 STSong-Light，非嵌入；视觉正常，复制/检索中文受限。
3. **OSS**：只有 `StorageBackend` 骨架，`STORAGE_BACKEND=oss` 当前运行期 503。
4. **解析自动生成（AC-18.5）**：目前教师按需触发，不是提交后自动生成。
5. **CSRF/资料补全**：只在 `prod` profile 强制；开发/测试关闭。
6. **管理员豁免资料补全**：CLI 创建的管理员无手机号，避免锁死；与「所有角色」字面要求有偏差。
7. **T007**：中文字面量检查采用「基线 + 新增即失败」，存量 275 处未迁移到消息 key。
8. **新建/导入试卷第 2 步（2026-10-20 已修，T230）**：22 个 i18n key 补齐、新建态免同步上传/粘贴、向导内粘贴导入、部分与题项行内编辑/删除；后端补 `PUT/DELETE /api/papers/{id}/sections/{sectionId}` 与 `DELETE /api/papers/{id}/items/{number}`。仍在的缺口：解析不产出部分/节与置信度（T217），评分项只能追加。
9. **Mockup 对等性缺口（2026-10-20 审计）**：21 个 mockup 屏中 20 屏有落点，
   **学生「班级概览」整屏未实现**（spec US-13 有、后端无学生端接口）；
   报表页未接任何导出按钮（后端 `/api/reports/offerings.xlsx|pdf` 已有）；
   复核工作台缺客观分/错题/正文指标/分项分；审计时间线 7 类事件只覆盖 2 类。
   逐屏明细见 `docs/reports/mockup-parity/README.md`，修复任务见 `tasks.md` 阶段 18（T211–T229）。
10. **班级成员面板**：前端把 `/api/classes/{id}/members` 当 `string[]` 消费，
   后端返回 `MemberView` 对象 → 打开成员面板会渲染崩溃（P1，待 T223 一并修）。
11. **邀请码文案与实现不符**：页面写「默认 3 天过期」，后端 `classes` 无过期字段、`join` 不校验；

12. **真实试卷导入（2026-10-20，T231）**：用户提供的真实高一英语月考整卷（Markdown）已能导入——75 题 / 4 个部分（20/20/35/0）/ 总分 120 分，逐题分值 1.5 / 2.5 / 1 / 1.5 / 1 正确。仍有两条落地约束：① 系统没有「材料/篇章」字段，阅读与完形材料**并入每道题的题干开头**（同一段材料在该组的 4 道题里各存一份，材料长的题题干会达 1KB 量级），补 `material` 字段后应改为独立引用（D-12）；**学生答题页已按材料组只渲染一次（T321），数据层仍每题重复**；② 源文件没有标准答案，75 题全部落在 `missingAnswers`，第 5 步按既有规则「有题项缺答案，禁止发布」，需先补答案（第 3 步逐题填或粘贴答案表）。③ 写作部分（应用文 15 + 口语 15）是主观题，只落了空部分，需要教师在第 4 步补评分要点。截图：`docs/reports/import-real-exam/`。   已由 D-9 拍板补实现（T213）。**2026-10-21 T320 修复**：材料前的 A–D 标签不再拼进题干，V032/V033 已清理存量题干与快照，学生答题页不再出现孤立字母。

13. **主观作业链路（2026-10-20，T232/T233）**：布置 3 个写作作业 → 学生作答 → 批改 → 复核 → 反馈已跑通；作业页能看到「题目要求」与「评分标准」，学生反馈页显示评语 + 优点 + 建议。同轮修掉 `create` 带 `publish=true` 只改内存不落库（学生看不到作业）的缺陷。截图：`docs/reports/critical-case-subjective/`。
    **D-14（2026-10-20 拍板，T236–T241 已完成，见第 16 条）**：作业与考试在数据层分开——作业永远自带题目要求/总分/评分标准、不引用试卷；试卷只用于考试。此前「新建作业必须先建一份试卷」的流程废止；存量作业回填为 `paper_id = null, paper_version = 0`，内容仍在该场快照里。
14. **批改卡在 RUNNING（T234，2026-10-20 已修）**：提交被 claim 成 RUNNING 后若那次 tick 没走完，原实现只有定时清扫（5 分钟阈值）会退回队列，且没有上限与手动入口。现在：领满 `grading.max-attempts`（默认 3）的退回 FAILED 并写原因；教师端「重试失败/卡住项」会额外回收卡住超过 1 分钟的 RUNNING，返回 `{retried, reclaimed}`。
    **根因（2026-10-20 复查已定位）**：`scheduling-1` 抛 `java.lang.NoClassDefFoundError: com/teachloop/shared/port/Ports$CriterionScore`（`StubGradingEngine.grade`），起因是开发机上另一个后端进程跑着**被原地重编覆盖过的 jar**，类懒加载失败；`Error` 不被旧 `catch (Exception)` 捕获，于是行停在 RUNNING。**运维口径：重编 jar 后必须重启 dev 栈，不要在运行中覆盖 jar。**

15. **会话唯一键冲突（T234，2026-10-20 已修）**：`spring_session_attributes_pk` 冲突发生在 Spring Session 对同一 session 重复 INSERT 属性时。V025 加 BEFORE INSERT 触发器，行已存在就覆盖 `attribute_bytes`，`SessionAttributeUpsertTest` 锁住行为。注意这仍不是多实例会话方案（会话共享仍靠同一个库）。

16. **D-14 作业与考试在数据层分开（2026-10-20 完成）**：作业永远自带题目要求/总分/评分标准、不引用试卷；试卷只用于考试。`offerings.paper_id` 可空，`offerings_kind_content_ck` 保证 `(kind='ASSIGNMENT') = (paper_id IS NULL)`；V026 把 13 条存量作业回填为 `paper_id=null`（快照内容保留）。新增「作业」导航与 `/assignments/new` 单页表单；布置页只保留基于试卷的考试。验证：`make test` 173/0/0 退出码 0；浏览器跑通「新建作业→学生提交→派发→复核→发布→学生反馈」，截图与数据断言见 `docs/reports/standalone-assignment/`。已知限制：存量作业快照可能含客观题（学生作业页无客观题作答，历史数据）；AI prompt 仍不含题目要求（待有 Key 重跑基线后单独立项）；复核工作台未按 offeringId 过滤队列。

17. **D-15 人工判分（AI 可选，2026-10-20 完成）**：老师可从「作业批改详情」的任意已交卷行进入判分；没有 AI 草稿也能填主观分、通过、发布，AI 只是可选辅助。AI 不得覆盖人工分：worker 在认领前、AI 结果落地前、失败路径三处检查 `teacher_id` / 已发布；复核详情显示客观分、当前主观分、AI 草稿三个数；批量通过跳过「AI 未参与且未填分」的行。验证：`ManualGradingTest` 5/5；`make test` 203/0/0（退出码 0）；浏览器不派发 AI 直接判 17.5 → 发布 → 学生反馈可见 17.5 与评语，截图见 `docs/reports/manual-grading/`。已知限制：仅打开详情会懒建 PENDING 复核进入队列；驳回后 AI 不会自动重批（既有语义）；复核工作台列表仍未按 offeringId 过滤。

18. **三处实测反馈修复（T248，2026-10-20 完成）**：① 作业页「已提交」卡片直接回显学生自己的正文（`/my-submissions` 增加 `contentHtml` / `contentText`，富文本是写入时已消毒的 HTML）与附件链接，刚交完先用本页内存副本兜底；② 资料页改为「我的资料」+「偏好设置」，界面语言改为下拉框（默认中文，选项由 `web/src/locales/*.json` 自动生成，新增语言只需加一份 json），顶栏移除中/EN 快捷按钮；③ 审计抽屉把事件代号、参数、时间翻成中文（`submission:submit` → 提交作业；`{late=0, attempt=1}` → 按时提交 · 第 1 次提交），`fmtDateTime` 按界面语言格式化。验证：`SubmissionFormsTest` 4/4（含新增正文回显断言）、全模块 `test-compile` 退出码 0、`tsc --noEmit` / `pnpm build` / `check-i18n`（976 键对齐）通过；浏览器实测资料页语言下拉（中↔英、刷新后保留）与审计抽屉可读，截图 `docs/reports/t248-ux-fixes/`。**已实测**（T250 期间重启 8010 后）：`/my-submissions` 返回 `contentHtml`/`contentText`，作业页「已提交」卡片直接显示「我提交的内容」，截图 `docs/reports/t250-ux-fixes/submitted-content.png`。

19. **第二轮实测反馈（T250，2026-10-20 完成）**：① 顶部「补全资料」只在手机号或邮箱为空时出现，保存后立即消失；侧栏用户名常驻 `/profile`，资料页随时可改；必填项用红色 `*`、红框、字段级红字和顶部红条提示，未填时禁止保存。② 作业页富文本换成带工具条的真编辑器（粗体 / 斜体 / 下划线 / 有序 / 无序列表 / 小标题 / 正文 + 占位提示），本机草稿同时保存纯文本、HTML 与输入形态，刷新可恢复。③ 复核页「学生正文」按写入时已消毒的 HTML 回显（`Ports.WorkItem` / `ReviewDetail` 增 `contentHtml`）；操作区收敛为「保存并发布反馈」主按钮 +「仅保存草稿」+「退回重批」+「撤回」+「审计」，发布后隐藏保存类按钮，禁用按钮统一半透明，队列列名与状态中文化。验证：`pnpm typecheck` / `pnpm build` / `check-i18n`（1009 键对齐）/ `make test` 均通过；浏览器实测资料红框→保存→顶部按钮消失、富文本输入加粗并刷新恢复、复核页富文本按段落回显、发布→撤回往返成功。截图：`docs/reports/t250-ux-fixes/`。**运维注意**：重建 jar 必须重启后端；本轮曾因 jar 被覆盖出现 `NoClassDefFoundError: ThrowableProxy`，重启后恢复。

20. **富文本与 AI 绿色通道（T252，2026-10-20 完成）**：① 富文本编辑器补 Cmd/Ctrl + B/I/U 快捷键；粘贴按后端同一份白名单清洗（外部灰底、字号、颜色、span/div 壳子都丢掉，b→strong、i→em）；编辑区固定白底、聚焦不再加阴影。② 草稿恢复改为「本机优先，本机没有就用服务端 `contentHtml`/`contentText`」，换浏览器/换端口/清 localStorage 都不丢格式。③ 批改详情逐份行新增「让 AI 先批」：`POST /api/offerings/{id}/submissions/{submissionId}/grading/dispatch` 只把这一份推进 AI 队列，同场其他提交不动，run 按场次幂等，写 `submission:ai_dispatch` 审计；整场按钮改名「全部交给 AI 批改」。验证：`GradingDispatchTest` 10/10、`pnpm typecheck` / `pnpm build` / `check-i18n` 通过；浏览器实测快捷键、粘贴清洗、清 localStorage 后格式恢复、行内派发→stub 判完→复核页出现 AI 草稿。截图 `docs/reports/t252-ux-fixes/`。

21. **AI 结果可见与复核页收敛（T253，2026-10-20 完成）**：AI 判分不再只有一个分数——`reviews.ai_criteria` / `ai_feedback` 落库，复核页新增「AI 评估」区（建议分、逐项得分与意见、优点/建议、「采用 AI 建议分」）；复核页标题去重、题项与评分标准折叠；审计「复核改分」改写成「主观分 7.5 → 8 · 评语：… · 优点：… · 建议：…」（去尾零、null 不显示）；顶栏右侧用户信息可点击进 `/profile`。验证：`GradingDispatchTest` 10/10（含 AI 逐项意见断言）、`make test` 209/0/0/0、`pnpm typecheck` / `pnpm build` / `check-i18n`（1029 键）通过；用用户提供的 `Rubric.md` + `Maria.txt` / `Mark.txt` 跑通「建作业 → 两人提交 → AI 先批 → 教师复核发布 → 学生反馈」。**注意**：当前 dev 用 stub 引擎、未配置 DEEPSEEK_API_KEY，AI 分数与意见不是真实评分。

22. **AI 批改质量（2026-10-21，T255–T267 已实现，全量回归 212/0/0/0；T268 锚点未做）**：截图里的 `7.5 / 4.5/6 / 3/4` 已确认来自 `StubGradingEngine`；现在默认 `engine=ai`，`stub` 加 `@Profile("!prod")` 且只在测试 properties 显式启用。个人 Key 存在 `user_ai_settings`（AES-GCM 密文，GET 只回掩码），作业创建者 Key 优先、回退全局，保存后下一次调用生效；setup 首次必须填 Key，教师无 Key 时顶栏提示去 `/profile`。Prompt 升到 `subjective-v2`（题目要求 + 完整 rubric + 评分政策 + 原文引用 + `overall/strength/suggestion`，反馈语言取学生 locale）；`GradeOutputValidator` 增加引用/语言/二元分数/长度结论校验，长度由 `TextLengthCounter` 现算并覆盖模型的结构猜测。复核页用 AI 草稿预填分数/评语/优点/建议，并新增批量通过预览（跳过边界/失败/无草稿项）；默认 rubric 统一为课程 2/4/4。真实 AI 实测：Maria 10/10、Mark 8/10、Iam 10/10、Alejandro 10/10，英文评语带原文引用；E2E 建作业 09c99ee0… → 两学生提交 → 真实 AI → 发布 → 学生反馈可见，截图 `docs/reports/grading-quality/e2e/`。Golden set 评估：逐项 exact 29/30、总分 9/10、契约失败 0、语言合规 10/10（JZ 的差异是已知人工参考与长度政策冲突）。**已补**：服务端 `RubricValidator` + `POST /api/rubrics/preview`（课程 rubric 2/4/4=10；6+5 对总分 10 返回 400）；`ai_model`/`ai_key_source` 落库（重批实测 `deepseek-flash / subjective-v2 / user`）；锚点简化版（`offerings.anchor_examples`，非空才注入）。**仍缺**：按评分项编辑器、锚点自动匿名化、批量通过后一键发布；rubric 校验错误文案暂为后端中文字面量（已入 i18n 基线）。报告：`docs/reports/grading-quality/2026-10-21-subjective-v2.md`。

18. **UI 标准（2026-10-21，已合入 `main`）**：令牌层 + 组件层 + 守卫三层落地。
    视觉：蓝主色 `#1D5FBF`（白字 6.10:1）、实边框、圆角 12→6、中文行高 1.65、表头去 uppercase、
    主按钮去渐变与彩色投影；亮/暗两套主题，开关在侧栏底部，选择记在本机。
    组件：`web/src/ui/` 18 个文件（Button/Field/Input/Alert/Segmented/PageHeader/StatCard/Tag/DataTable/
    listState/useListState/States/ThemeProvider/ThemeToggle/tokens/ui.css/index/icons），页面只从 `ui/` 引；
    组件参考页收在资料页「偏好设置 → 界面标准」（2026-10-22 T356 从侧栏移入，旧链接 `/ui-kit` 跳到展开态）。
    **表格（2026-10-25，T441–T446）**：全仓只有 `ui/DataTable` 一处实现，手写表体、以「我的词库」原表格为真源
    （排序 / 页脚 10-20-50-100 / 选择列与批量条 / 空态，`total` 即服务端分页）；15 张列表表统一，旧
    `components/DataTable.tsx` 与 antd `Table` 退场（`tokens.ts` 的 Table 覆盖随之删除）；`sort/dir/page/size`
    写 URL 并读兼容旧 `pageSize`；antd chunk gzip 293.82 → 202.35 kB。报告 `docs/reports/2026-10-25-table-unify/`。
    侧栏导航：15 个图标统一经 `ui/icons.tsx` 出口（T319）；当前项暗色文字由 3.25:1 修到 11.14:1。
    守卫：`scripts/check-ui.py`（6 条规则 + 128 处基线，2026-10-25 新增 `RAW_TABLE`：页面不再新写 `<table>`）
    与 `scripts/ui/contrast.py`（16 组对比度 + 两处令牌一致）已进 `make test`。反证：插入 `#ff0000` 时退出码 1。
    迁移：登录、教师首页、试卷列表、新建作业、复核 5 屏已改用组件层；其余 13 屏靠令牌层继承视觉。
    验证：`make test` 退出码 0（209 tests / 0 failures / 0 errors）；`pnpm build` 0（antd chunk gzip 297.49 kB）；
    `check-ui` 125 处；`check-i18n`（1067 键对齐）；`contrast.py` 16 组。截图见
    `docs/reports/ui-standard-2026-10-21/`（含 T319 的 before/after 7 张）。
    **未完成**：21 屏 × 亮暗截图基线（T308，需装 Playwright）；剩余 13 屏组件化（T309）；
    跨浏览器未验；视觉「好不好看」待用户确认。
    **2026-10-22 更新（决策 006 / `specs/006-brand-visual/`）**：调色板与圆角被品牌层取代——
    主色 `#1A63E0`（白字 5.38:1）、暗色 `#2A6FD6` + 暗底 `#0A1120`、圆角 8/12/6、页标题 24px；
    新增四个功能位（Create / Assess / Practice / Insights）与判分三态两个命名空间、`--accent-text`
    （暗色 `#7DB1FF`，7.96:1）、`Brand` 组件、侧栏功能色胶囊、全站页头 eyebrow、资料页界面标准面板的品牌色板；
    品牌资产由 `scripts/brand/build-assets.py` 生成；`contrast.py` 16 → **40 组**，check-ui 基线 **123 处**；
    回归 `make test` **255/0/0/0** + 前端 **14/14**。真页面截图
    `docs/reports/brand-app-2026-10-22/`（30 张，含学生页）。
    未验证：考试作答 live 页、管理员 AI 设置、反馈详情、`/setup`、跨浏览器；口径见
    `specs/006-brand-visual/spec.md` 6.2。
    **2026-10-23 更新（导航着色修订）**：首页 = 空间海军蓝 `#0E3676`、班级 = 空间淡紫 `#5B3AC4`、
    Insights 从玫红改靛蓝 `#3F51B5`、Practice 接玫红；新增 `SPACE_*` 命名空间；`contrast.py` 40 → **44 组**；
    前端 **28/28**；真页面截图 `docs/reports/nav-color-2026-10-23/`（37 张）。


## 五、结论

`specs/002-v2-platform/tasks.md` 中原有的实现任务均已完成；剩余为上述已知偏差（OSS/PDF/AC-18.5 等）。
2026-10-20 的 mockup 对等性审计新增了 `tasks.md` 阶段 18（T211–T229），**这些任务尚未开始**；
同日拍板的 D-14（作业与考试在数据层分开）新增阶段 19（T236–T241），**已完成**（见第 16 条）。
人工判分（D-15）新增阶段 20（T243–T246），**已完成**（见第 17 条）。
用户实测反馈（T248）新增阶段 21，**已完成**（见第 18 条）。
第二轮实测反馈（T250）新增阶段 22，**已完成**（见第 19 条）。
第三轮实测反馈（T252）新增阶段 23，**已完成**（见第 20 条）。
第四轮实测反馈（T253）新增阶段 24，**已完成**（见第 21 条）。
阶段 18 仍不在「已完成」范围内。

## 六、2026-10-25 交付（试卷录入重构，D-34）

- **已完成，含浏览器验收**：四步向导（基本信息 / 内容 / 评分项 / 预览发布）、Markdown 单一内容源、
  「生成考题」替换重建（同事务快照 → 删 → 重建 → 存原文）、目标总分发布闸门、命题人/审题人、
  评分项一题一 tab、预览发布「无 / N/A」+ 写作题按题号分块；删除「导入考卷」按钮与 `/papers/import`。
- **验证**：`make test` EXIT=0（Maven 513 测试 / 111 类，0 失败 0 错误 1 跳过；四个守卫通过；前端 80/80）；
  V065 在 Postgres 16 上按版本序全量跑通；浏览器（dev 5173 + 8010，真实卷）走完四步并发布，
  目标不一致时 400（detail `total=150,target=140`）、已发布卷生成 409、编辑器只读。
- **文档**：决策 `docs/decisions/021-paper-single-source-authoring.md`、调研
  `docs/research/2026-10-25-paper-authoring-single-source.md`、报告
  `docs/reports/paper-authoring-2026-10-25/README.md`、对等性 `docs/reports/mockup-parity/03-paper-flow.md`。
- **已知缺口**：标题与时长创建后不可改（既有后端能力缺口）；重建会按原文重写第 3 步手填的 rubric（确认框已明示、有快照）。
