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
| 管理员初始设置 | `/setup` 创建首个管理员；`/admin/ai` 配置 AI（Key AES-GCM 加密、只回掩码、测试连接、保存后重启生效） | `AiSetupTest` + 浏览器截图 `setup-admin.png`、`admin-ai-settings.png` |

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

12. **真实试卷导入（2026-10-20，T231）**：用户提供的真实高一英语月考整卷（Markdown）已能导入——75 题 / 4 个部分（20/20/35/0）/ 总分 120 分，逐题分值 1.5 / 2.5 / 1 / 1.5 / 1 正确。仍有两条落地约束：① 系统没有「材料/篇章」字段，阅读与完形材料**并入每道题的题干开头**（同一段材料在该组的 4 道题里各存一份，材料长的题题干会达 1KB 量级），补 `material` 字段后应改为独立引用（D-12）；② 源文件没有标准答案，75 题全部落在 `missingAnswers`，第 5 步按既有规则「有题项缺答案，禁止发布」，需先补答案（第 3 步逐题填或粘贴答案表）。③ 写作部分（应用文 15 + 口语 15）是主观题，只落了空部分，需要教师在第 4 步补评分要点。截图：`docs/reports/import-real-exam/`。   已由 D-9 拍板补实现（T213）。

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

18. **UI 标准（2026-10-21，`feat/ui-standard` 分支）**：令牌层 + 组件层 + 守卫三层落地。
    视觉：蓝主色 `#1D5FBF`（白字 6.10:1）、实边框、圆角 12→6、中文行高 1.65、表头去 uppercase、
    主按钮去渐变与彩色投影；亮/暗两套主题，开关在侧栏底部，选择记在本机。
    组件：`web/src/ui/` 14 个文件（Button/Field/Input/Alert/PageHeader/StatCard/Tag/DataTable/States/
    ThemeProvider/ThemeToggle/tokens/ui.css/index），页面只从 `ui/` 引；`/ui-kit` 是唯一参考页。
    守卫：`scripts/check-ui.py`（5 条规则 + 145 处基线）与 `scripts/ui/contrast.py`（11 组对比度 +
    两处令牌一致）已进 `make test`。反证：插入 `#ff0000` 时退出码 1。
    迁移：登录、教师首页、试卷列表、新建作业、复核 5 屏已改用组件层；其余 16 屏靠令牌层继承视觉。
    验证：`make test` 退出码 0（54 类 / 206 tests / 0 failures）；`pnpm build` 0（antd chunk gzip 293.82 kB）；
    `check-ui`/`check-i18n`（1060 键对齐）通过。截图 9 张见 `docs/reports/ui-standard-2026-10-21/`。
    **未完成**：21 屏 × 亮暗截图基线（T308，需装 Playwright）；剩余 16 屏组件化（T309）；
    跨浏览器未验；视觉「好不好看」待用户确认。


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
