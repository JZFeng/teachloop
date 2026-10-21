# Mockup ↔ 实现 对等性总报告

日期：2026-10-20　基线：`specs/002-v2-platform/mockups/interface.html`（21 个 `.screen`，已 review）
实现：`web/src/**`（React + Vite）＋ `teachloop-*/src/main/java/**`
分区域明细：`01-auth-admin.md`、`02-paper-class.md`、`03-grading-report.md`、`04-student-exam.md`
方法：`00-protocol.md`（逐控件对照、级别定义、证据要求）

## 摘要

- **页面没有整屏漏做**：21 个 mockup 屏，20 个有对应路由/抽屉；**唯一整屏缺失是学生端「班级概览」（`#overview-s`）**。
- **功能缺口集中在「信息不足以完成任务」**，不是「按钮没画」：复核工作台看不到学生姓名/客观分/正文指标/分项分；报表页没有勾选与导出；审计时间线 7 类事件只覆盖 2 类。
- 合计 **P0 6 / P1 53 / P2 74 / P3 85**（按分区域表格统计，明细见各区域文件）。
- 发现 **5 处「文档/文案与实现相反」**，其中 2 处会误导用户（见第五节）。
- 另有 **1 个必崩页缺陷**（班级成员面板），由本轮审计顺带发现，不属于 mockup 对等项但优先级最高。

## 一、页面级对照（21/21 已核对）

| # | mockup 屏 | 类型 | 实现落点 | 结论 |
| --- | --- | --- | --- | --- |
| 1 | login · 登录 | 一级 | `/login` → `web/src/app/LoginPage.tsx:8` | 部分缺失（P0：生产无登录方式） |
| 2 | profile · 资料补全 | 一级 | `/profile` → `web/src/app/ProfilePage.tsx` | 部分缺失（拦截不生效） |
| 3 | admin-ai · 初始化 / AI 设置 | 一级 | 拆为 `/setup` + `/admin/ai`（`App.tsx:66,85`） | 部分缺失（`/setup` 无入口） |
| 4 | home · 教师首页 | 一级 | `/`（`App.tsx:69`，`Shell.tsx` 教师分支） | 部分缺失（状态细分/范围） |
| 5 | class · 班级 | 一级 | `/classes`（`App.tsx:70`） | 部分缺失（班级作业表整块缺） |
| 6 | offering · 布置给班级 | 二级 | `/offerings/new`（`App.tsx:75`） | 基本对齐 |
| 7 | newflow · 新建试卷五步 | 二级 | `/papers/new`（`App.tsx:72`） | 部分缺失（第 2 步上传/校对断链） |
| 8 | import · 校对导入结果 | 二级 | `/papers/import`（`App.tsx:73`） | 部分缺失（无条件/置信度/确认闸门） |
| 9 | editor · 试卷编辑 | 二级 | `/papers/:paperId`（`App.tsx:74`，向导 edit 模式） | 部分缺失（基本信息/可见性两张卡落空） |
| 10 | grading · 批改总览 | 一级 | `/grading`（`App.tsx:76`） | 部分缺失（口径错位） |
| 11 | grading-detail · 作业批改详情 | 二级 | `/grading/:offeringId`（`App.tsx:77`） | 部分缺失（未交行/行内复核/失败重试） |
| 12 | review · 复核工作台 | 一级 | `/review`（`App.tsx:78`） | 部分缺失（判断依据缺 5 项） |
| 13 | summary · 班级汇总 | 二级 | `/offerings/:offeringId/summary`（`App.tsx:80`） | 部分缺失（统计卡/主观项均分） |
| 14 | report · 报表查询 | 一级 | `/reports`（`App.tsx:79`） | **核心任务做不完**（P0 4 条） |
| 15 | audit · 审计时间线 | 行内 | 抽屉 `web/src/components/AuditDrawer.tsx:7` | 部分缺失（7 类事件只有 2 类） |
| 16 | exambrief · 考前须知 | 二级 | `/exams/:offeringId`（`App.tsx:88`） | 部分缺失（客观/主观拆分缺字段） |
| 17 | exam · 考试作答 | 专注 | `/exams/:offeringId/live`（`App.tsx:89`） | 部分缺失（段落/换设备续答） |
| 18 | assignment · 作业提交 | 二级 | `/assignments/:offeringId`（`App.tsx:81`） | 部分缺失（说明/评分标准/工具栏） |
| 19 | feeds · 我的反馈 | 一级 | `/feedback`（`App.tsx:82`） | 部分缺失（错题数/发布时间） |
| 20 | feedback · 反馈详情 | 二级 | `/feedback/:offeringId`（`App.tsx:83`） | 部分缺失（正确率/分项/排名） |
| 21 | **overview-s · 班级概览（学生）** | 一级 | **无路由、无导航**（学生导航仅 3 项，`Shell.tsx:25-29`） | **整屏缺失（P0）** |

实现侧多出的页面（不在 mockup，属扩展，不计缺口）：`/papers` 试卷库、`/notifications` 通知、学生首页 `/`、`/setup` 与 `/admin/ai` 拆分。

## 二、计数

| 区域 | 屏数 | P0 | P1 | P2 | P3 |
| --- | --- | --- | --- | --- | --- |
| 01 登录 / 资料 / 管理员（含导航、i18n 横切） | 5 | 1 | 5 | 11 | 21 |
| 02 班级 / 布置 / 选题 / 导入 / 编辑 | 5 | 0 | 18 | 25 | 18 |
| 03 首页 / 批改 / 复核 / 汇总 / 报表 / 审计 | 7 | 4 | 17 | 31 | 25 |
| 04 学生考试与反馈 | 6 | 1 | 13 | 7 | 21 |
| **合计** | **21** | **6** | **53** | **74** | **85** |

## 三、P0（6 条，2 个根因）

1. **生产环境教师/学生无任何登录方式**：前端只有邮箱密码表单（`web/src/app/LoginPage.tsx:42-56`），prod 后端只放行管理员密码登录（`teachloop-identity/.../AuthEndpoint.java:68-80`）；微信接口存在（`:84-90`）但前端零调用。mockup 的「微信扫码为主」整条路径未实现。
2. **学生端班级概览整屏缺失**：无路由/导航（`App.tsx:64-92`、`Shell.tsx:25-29`），只在反馈详情第 ④ 段落了「应交/实交/均分」三个聚合数（`FeedbackDetailPage.tsx:157-171`）。mockup 的主体——逐人表（姓名/是否提交/尝试/提交时间/状态/分数）与「别人显示已批改、自己始终可见」——整块没有；后端也确实没有学生端概览接口（`/class-summary` 对学生 403，`OfferingEndpoint.java:270-272`）。
3. **报表页主链路（勾选 → 已选 → 导出）完全不存在**：学生列表、题目列表、全选、已选统计、导出三格式都没有（`ReportsPage.tsx:325-333`），页面自己写「未接入」（`locales.ts:487`）。
4. 报表 Top N 缺筛选：分数段、提交状态无参数（`ReportEndpoint.java:117-136`），日期区间后端支持但前端不传。
5. 报表副标题自认「只做按作业 × 班级汇总」（`ReportsPage.tsx:220`），与 mockup 的「先筛选再勾选导出」不是同一任务。
6. （3、4、5 同源，按分区域报告记 4 条 P0，此处合并为「报表主链路」一个根因。）

## 四、P1 归并（53 条 → 10 个主题）

### A. 复核工作台判断依据（5 条，最影响核心业务）

| 缺口 | 证据 | 需要什么 |
| --- | --- | --- |
| 队列无学生姓名，只显示 `submissionId` 前 8 位 | `ReviewPage.tsx:129-134`；`ReviewView` 无 `studentName`（`ReviewEndpoint.java:82-86`） | 队列接口加 `studentName` |
| 客观得分与错题号恒为空 | `ReviewEndpoint.java:99-107,135`（`null, null` 硬编码） | `WorkItem` 补 objectivePoints + wrongNumbers |
| 正文「词数/段落/是否在范围」缺失 | `SubjectiveGradeRequest.java:62-80` 内部算了但不暴露 | 端口暴露 metrics |
| AI 分项打分与分项评语被丢弃 | `SittingLedgerImpl.java:62-88` 丢弃 `Ports.CriterionScore` | criteria 落库 + `ReviewDetail.criteria` |
| 当前总分 118 + 18 = 136 不显示 | `ReviewPage.tsx:168-172` | 依赖上面两项 |

### B. 学生反馈的信息密度（5 条）

分项评分（内容要点 10/15）与分项满分、单题班级正确率、我的排名、优点/建议、发布时间。

- **最低成本一条**：`strengths`/`suggestions` 后端已透传（`SittingDtos.java:121-123`）、前端类型已声明（`types.ts:321-322`），JSX 里从未渲染 —— 两行代码。
- 其余需后端补字段（`FeedbackOverview` 无 rank、`FeedbackWrongItem` 无 correctRate）。

### C. 批改详情页（3 条）

未提交学生行缺失（`OfferingEndpoint.java:198-217` 只回已有提交）、行内「复核」按钮缺失（`GradingDetailPage.tsx:281-343` 只有拒收 + 审计）、失败行「重试」缺失（`:403-411` 只有全局重试）。

### D. 班级页（6 条）

待办列无数据、邀请码无过期、邀请码无停用、加入截止无字段、**班级作业与试卷表整块缺失**（`ClassesPage.tsx` 全文不请求 offering，班级名不可点 `:143`）、成员面板**渲染对象必崩页**（`ClassesPage.tsx:107` 按 `string[]` 消费，后端返回 `MemberView` 对象 `ClassEndpoint.java:80`，`<code>{id}</code>` 渲染对象抛错且无 ErrorBoundary）。

### E. 试卷编辑（6 条）

editor 屏的基本信息卡 5 项（截止/宽限期/最多尝试/反馈语言/标题已改）+ 可见性卡 2 开关全部无落点（`PaperWizardPage.tsx:821-859` 只有标题与默认时长）。根因是 `OfferingEndpoint` 没有 `PUT/PATCH /api/offerings/{id}`，且创建请求一个字段都不传（`OfferingCreatePage.tsx:146-155`，后端有默认值 `SittingDtos.java:15-20`）。其中「公布他人分数」更严重：`POST /offerings/{id}/scores/publish` 存在（`OfferingEndpoint.java:179-197`）但全前端零调用 → **成绩永远发不出去**。

### F. 新建/导入试卷（6 条）

向导第 2 步「上传整卷」在新建态不可用（必须先在第 5 步同步出 `paperId`，`PaperWizardPage.tsx:532-542,942-943`）；「下一步：校对」不存在；解析结果无「部分/节」统计（`ImportResult` 无 sections）；无置信度（`PaperTextParser.ParsedItem` 无 confidence）；无「确认导入」闸门（解析即落库 `PaperEndpoint.java:374-405`）；**22 个 i18n key 未定义**（`import*` 10、`batch*` 9、`scoringStrategy/strategy*` 3），导入卡与批量卡整块显示原始 key。

> **2026-10-20 进展（T230，已完成）**：22 个 key 补齐（zh/en 各 144）；第 2 步导入前自动建卷与部分，新建态即可上传/粘贴；向导内新增粘贴导入与「导入到部分」；部分与题项支持行内编辑/删除（后端补 `PUT/DELETE sections/{id}`、`DELETE items/{number}`）。
> 仍未做：解析不产出「部分/节」与置信度（T217）、无暂存确认闸门；评分项（criteria）仍只能追加。

### G. 考试作答数据模型（3 条）

- 完形/语法段内多空与阅读语篇无法渲染：`Ports.ItemSpec`/`ObjectiveItem` 均无 passage/材料字段，只能逐题卡片。
- **换设备续答会覆盖服务端已存答案**：`/session` 不返回已保存答案（注释承认 `ExamLivePage.tsx:118-119`），前端只从本机草稿恢复并立刻回写（`:126-128`）。
- 断网保存无重试。

### H. 作业提交（4 条）

作业说明整块渲染的是**考试注意事项**（`ExamSessionEndpoint.java:104-108` 对作业也返回 NOTES；`Offering` 无 instructions 列）；评分标准显示「未接入」（`AssignmentPage.tsx:244-247`，但 `offering.rubric_markdown` 有列却不返回）；富文本工具栏 6 个按钮全缺（`contentEditable` 裸 div，`AssignmentPage.tsx:344-354`）；视频提交后端已就绪、前端无入口。

### I. 审计时间线（6 条）

7 类事件只覆盖 2 类：发布/派发/客观判分写在 offering 目标上（`OfferingEndpoint.java:403`、`OfferingGradingDispatcher.java:132`、`ObjectiveRecomputeImpl.java:88`），解析生成与 AI 批改完成完全不写审计（`ExplanationEndpoint.java:53-93`、`GradingWorker.java:55-70`）；行内没有「谁」（`AuditDrawer.tsx:46-53` 连 actorId 都没渲染）；事件名以英文 action 直出；detail 是 `Map.toString()` 单行。

### J. 登录/初始化/导航（5 条）

生产登录缺口（已列 P0）、`/setup` 全站无入口（`grep '/setup'` 只命中路由定义）、资料补全无前端拦截（`ProfileCompletionFilter` 仅 prod 开启，前端不处理 403）、「管理员仍可看班级」不成立（`ClassEndpoint.java:59-62` 按 `teacher_id` 过滤，管理员恒空）、侧栏无复核角标。

## 五、跨屏系统性问题

1. **数据范围不是「我名下的班」**：`/api/offerings` 对所有教师返回全部 PUBLISHED（`OfferingEndpoint.java:144-152`），`/grading/queue` 同理（`ReviewEndpoint.java:110-115`）。首页文案「我教的所有班级汇总在一起」因此不成立，也是越权读取他人教学数据的口子。
2. **审计接口无归属校验**：`AuditEndpoint.java:21-25` 任何登录用户可用任意 `targetType/targetId` 读审计（非对等项，安全建议）。
3. **文档/文案与实现相反（5 处）**：
   - `DELIVERY-STATUS.md` 写「反馈/报表 … XLSX/PDF 导出」已完成，前端报表页却无任何导出按钮；后端 `offerings.xlsx|pdf` 存在但只按布置聚合。
   - `ReportsPage.tsx:43-47` 注释称「Top N、跨班聚合、导出均无后端接口」，实际 `ReportEndpoint` 四个接口 + `ExportEndpoint` 五个格式都在。
   - `teacher/locales.ts:334` 宣称「邀请码默认 3 天过期，由服务端控制」，后端 `ClassEntity` 无过期字段、`join` 不校验。
   - `ReportsPage` 用「正确率」展示 mockup 的「错误率」，数字含义相反（77% vs 23%）。
   - `student/locales.ts:76 peerMissing`（「后端未提供学生端概览接口」）属实但从未渲染，用户看不到这句解释。
4. **兜底文案代替实现**：`ReportsPage`「未接入」、`ReviewPage`「客观分 —（当前端口暂不返回）」、`AssignmentPage`「评分标准未接入」。这些是诚实的，但它们标记的正是 mockup 要求的能力，应当收敛。
5. **i18n 兜底失败**：`teacher.wizard.import*` 等 22 个 key 未定义，页面直接渲染 key 原文；登录/资料/初始化三页整页硬编码中文（`web/src/i18n.ts` 之外），语言偏好也不写回 `profile.locale`。

## 六、拍板结果（2026-10-20，已回写 spec）

用户已逐条拍板；对应 `spec.md` §9 的 D-6…D-11 与 `tasks.md` 阶段 18 的 T211–T229。

| # | 决策 | 选择 | 落点 |
| --- | --- | --- | --- |
| 1 | 学生班级概览 | **独立二级页 + 新接口**，六列全给 | spec AC-13.1–13.7（D-6）；T211 |
| 2 | 报表导出 | **先接现有 `offerings.xlsx` 与 `.pdf`**；勾选导出（AC-17.13）保留为未完成验收，另行决定 | spec D-7、AC-17.13；T215、T216 |
| 3 | 布置可编辑 | **全字段可编辑**（内容除外）；开考后只能延长 | spec AC-26.14–26.16（D-8）；T212 |
| 4 | 邀请码生命周期 | **过期 + 停用 + 加入截止全做** | spec AC-2.3、AC-2.9、AC-2.10（D-9）；T213 |
| 5 | 解析粒度 | **部分/节与置信度都产出** | spec AC-4.13、AC-4.14（D-10）；T217 |
| 6 | 管理员范围 | **只读查看全部班级与作业、复核与报表** | spec §2 角色表、FR-045（D-11）；T214 |

> 注：第 6 条选项原文是「可查看全部班级与作业」；回写 spec 时按 spec 原意（管理员不代替教师做决定）
> 收紧为**只读**。若你希望管理员也能改班级/作业，请在 T214 开始前说一声，改回可写只需动一条 AC。

被选方案会改变的验收标准已同步进 spec：新增 AC-2.9、AC-2.10、AC-4.14、AC-13.6、AC-13.7、
AC-26.14、AC-26.15、AC-26.16，新增决策 D-6…D-11，新增 FR-045；AC 总数 206 → 214。

## 七、建议修复批次

**第 1 批（前端纯漏做，不动后端，1–2 天）**
成员面板崩页 → 反馈详情补 `strengths/suggestions` → 补 22 个 i18n key → 报表接现有导出 → 审计渲染 actor/中文事件名 → 复核「进入」按 `offeringId` 过滤 → 班级页补「新建作业 / 导入试卷」入口。

**第 2 批（后端小改，字段/参数，3–5 天）**
复核 `ReviewDetail` 透出客观分/错题/正文指标/分项分 → `/grading/queue` 加 `studentName`+`status`+`offeringId` → `/my-feedback` 加 rank/correctRate/分项分/发布时间 → `class-summary` 加 max/min/主观项均分 → `/entry` 加客观主观拆分与 rubrics → 审计事件补写与 actorName → `/api/offerings` 按教师归属过滤。

**第 3 批（产品决策落地，1–2 周）**
学生班级概览（T211）→ 报表勾选导出（T216）→ 布置可编辑（T212）→ 邀请码生命周期（T213）→ 登录微信路径与 `/setup` 入口（T226）→ 考试段落模型与换设备续答（T224，最大改动）。

> 第 1、2 批与 6 个决策对应的任务已按依赖拆到 `tasks.md` 阶段 18（T211–T229），
> 每条都带可独立测试的验收；先做哪批按 T211 之外的依赖关系可并行。

## 八、本次审计的边界

- 全部结论来自**静态代码/接口核对**，未启动服务、未跑浏览器、未跑测试；视觉与交互动效未验证。
- 「缺失」结论都附了检索词与 `文件:行号`，可逐条复核；不确定项在各区域报告里标了「待确认」。
- mockup 的原型占位（二维码、主题切换条、示例数据、计时演示按钮）不计为功能缺失。
- 级别定义与输出格式见 `00-protocol.md`；各区域明细表（含逐控件行号与级别）见 `01`–`04` 四份报告。
