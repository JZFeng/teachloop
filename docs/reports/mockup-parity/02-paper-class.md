# paper-class 对等性审计（班级 / 布置 / 新建试卷 / 校对导入 / 试卷编辑）

审计范围：mockup `#class`（interface.html:645–700）、`#offering`（703–751）、`#newflow`（754–786）、
`#import`（790–841）、`#editor`（845–881）；实现 `web/src/teacher/{ClassesPage,PapersPage,PaperWizardPage,ImportPaperPage,OfferingCreatePage,common}.tsx`；
后端 `teachloop-classroom`、`teachloop-paper`、`teachloop-sitting`。

搜索词（每条「缺失」结论均以此为准，中英 + i18n key 三写）：`待办|pendingCount|todo`、`加入截止|joinDeadline|join_deadline`、
`过期|expiresAt|expires_at|inviteExpires`、`停用|disableInvite|archived`、`宽限期|graceHours|grace`、`最多尝试|maxAttempts`、
`反馈语言|feedbackLanguage`、`班级概览|showPeerOverview`、`公布他人分数|publishScores|scores/publish`、
`置信|confidence`、`部分数|sections|节`、`汇总|summary`、`存草稿|saveDraft|draft`、`判分|scoringStrategy`。

## 0. 汇总

| 屏 | 路由 | 结论 | P0 | P1 | P2 | P3 |
| --- | --- | --- | --- | --- | --- | --- |
| class · 班级 | `/classes` | 部分缺失 | 0 | 6 | 9 | 2 |
| offering · 布置给班级 | `/offerings/new` | 对齐（含细节偏差） | 0 | 0 | 1 | 5 |
| newflow · 新建试卷五步 | `/papers/new` | 部分缺失 | 0 | 2 | 5 | 2 |
| import · 校对导入结果 | `/papers/import` | 部分缺失 | 0 | 4 | 4 | 4 |
| editor · 试卷编辑 | `/papers/:paperId` | 部分缺失 | 0 | 6 | 6 | 5 |

结论口径说明：本区域没有「整屏不存在」的页面——五屏都有路由（App.tsx:70–74），
所以 P0 全为 0；但 `class` 的第二张卡（班级作业与试卷表）与 `editor` 的两张基本信息/可见性卡
在实现里没有落点，只是数据在别的页面能部分取到，因此记 P1 而非 P0（逐条在差异说明里写清）。

**忽略的原型占位**（按协议第五节，不计为功能缺失）：主题切换条、二维码占位、mockup 里的示例数据数字
（「3 个」「32/30/42」「77 题 150 分」「K7QP-3M2X」等）、`.proto` 提示条。

## 1. class · 班级

- 路由与入口：`/classes`（App.tsx:70），教师与学生导航均可见（Shell.tsx:19、Shell.tsx:27）；组件 `ClassesPage.tsx`。
- 主操作可达性：能建班、能看/轮换邀请码、能看成员数；**不能**在该页看到某个班的作业与试卷、
  **不能**看到待办数/加入截止、**不满足**邀请码过期与停用（后端无此能力）。成员面板打开后渲染崩页（见 1.14）。

### 功能点对照

| # | mockup 要求（interface.html:NNN） | 实现情况 | 证据 | 级别 |
| --- | --- | --- | --- | --- |
| 1.1 | 「我的班级」表，列：班级/学生数/待办/邀请码/加入截止（651–652） | 表存在，列为 班级/学生数/邀请码/操作（缺的列在 1.4、1.8 单列） | ClassesPage.tsx:137-160 | OK |
| 1.2 | 班级名（655） | 有 | ClassesPage.tsx:138-144 | OK |
| 1.3 | 学生数（656） | 有（`memberCount`） | ClassesPage.tsx:145-151；ClassView 字段 ClassEndpoint.java:46 | OK |
| 1.4 | 待办数（656、663、670） | 无此列、无此数据；`/api/classes` 不返回任何待办聚合 | ClassesPage.tsx:137-160（列里没有）；ClassView 仅 4 字段 ClassEndpoint.java:46；搜 `待办\|pendingCount\|todo` 在 `web/src/teacher` 只命中学生首页 HomePage.tsx:45 | P1 |
| 1.5 | 邀请码文本（657） | 有 | ClassesPage.tsx:152-158 | OK |
| 1.6 | 邀请码剩余有效期标签「2 天过期 / 18 小时过期」（657、664） | 无；无过期字段、无剩余时间计算 | ClassView 无到期字段 ClassEndpoint.java:46；ClassEntity 只有 `inviteCode`/`archived`（ClassEntity.java:21、24），无 `expiresAt`；搜 `expiresAt\|inviteExpires\|过期` 在 `web/src` 无命中 | P1 |
| 1.7 | 已停用态「已停用」tag（671） | 无停用能力：无接口把邀请码/班级置停用；实体有 `archived` 字段但无任何端点写它 | ClassEndpoint.java:98-103（只有 regenerate）；`setArchived` 无调用方（grep `archive` 在 classroom 无命中） | P1 |
| 1.8 | 加入截止列（652、658） | 无该列、无该字段 | ClassView/ClassEntity 均无 joinDeadline（ClassEntity.java:9-28）；搜 `加入截止\|joinDeadline\|join_deadline` 在 `web/src`、`teachloop-classroom` 无命中 | P1 |
| 1.9 | 行内「汇总」按钮（659、666、673） | 无；汇总只在按布置维度提供（`/offerings/:id/summary`），班级维度无入口 | ClassesPage.tsx:114-135（操作列只有 成员/轮换）；汇总入口在 GradingOverviewPage.tsx:198、HomePage.tsx:163 | P2 |
| 1.10 | hint「邀请码默认 3 天过期，由服务端控制；可随时轮换或停用」（677） | 文案只剩「默认 3 天过期…可随时轮换」，且与服务端不符（无过期实现）；「停用」被删 | locales.ts:334（createHint）、locales.ts:359-360（inviteInfo） | P3 |
| 1.11 | 可排序列 班级/学生数/待办/加入截止（651） | 可排 班级/学生数/邀请码（多了邀请码，少了待办/加入截止） | ClassesPage.tsx:142、148、156 | P3 |
| 1.12 | 「我的班级 3 个」计数（650） | 有 | ClassesPage.tsx:252-258 | OK |
| 1.13 | 轮换邀请码（677 文案隐含） | 有（mockup 未画按钮，实现额外提供，合理） | ClassesPage.tsx:125-132、84-95 | OK |
| 1.14 | （mockup 无，但为班级页既有能力）成员查看 | 打开成员面板即渲染崩溃：前端按 `string[]` 消费，后端返回 `MemberView` 对象数组，`{id}` 渲染对象会抛错且无 ErrorBoundary | ClassesPage.tsx:107（`api.get<string[]>`）、ClassesPage.tsx:292-296（`<code>{id}</code>`）vs ClassEndpoint.java:80、94-96（返回 `{studentId, displayName, submissionCount}`）；全局无 ErrorBoundary（grep `ErrorBoundary\|componentDidCatch` 无命中） | P1 |
| 1.15 | 第二张卡「高一 (3) 班 · 作业与试卷」整表（680–694） | 整块缺失：班级页没有班级维度的作业列表，班级名也不可点入 | ClassesPage.tsx 全文 314 行无 offering 相关请求；路由表无 `/classes/:id`（App.tsx:64-92） | P1 |
| 1.16 | 表列 名称 + 副标题「客观 120 + 主观 30 = 150」（685） | 班级页无此列；等价信息只在首页作业表（名称 + 类型副标题，没有客观/主观拆分） | HomePage.tsx:101-123 | P2 |
| 1.17 | 表列 类型 tag「试卷 / 作业」（686、690） | 班级页无；首页有 `kind` 文案 | HomePage.tsx:118 | P2 |
| 1.18 | 表列 截止（686、690） | 班级页无；首页有 `dueAt` | HomePage.tsx:121-131 | P2 |
| 1.19 | 表列 提交「30 / 32」（687、691） | 班级页无；首页有 `submitted / expected` | HomePage.tsx:131-146 | P2 |
| 1.20 | 表列 状态 tag「批改中 / 已发布」（687、691） | 班级页无；首页有派生状态 tag | HomePage.tsx:155-176（OfferingStateTag） | P2 |
| 1.21 | 行操作「详情 / 编辑」（688、692） | 班级页无；首页行点进去是 `/grading/:id`，试卷编辑在 `/papers` 行操作 | HomePage.tsx:262（onRowClick）、PapersPage.tsx:85-87 | P2 |
| 1.22 | 「新建作业」入口（696） | 班级页无；入口散布在试卷库（新建试卷）与试卷行「布置」 | PapersPage.tsx:108-115、PapersPage.tsx:88-90 | P2 |
| 1.23 | 「导入试卷」入口（697） | 班级页无；入口在试卷库 | PapersPage.tsx:110（文案「导入考卷」） | P2 |

### 差异说明

- **1.4 待办**：mockup 用它表达「这个班还有几件事等我处理」。实现里既没有列，也没有任何接口能给班级级待办；
  离得最近的是 `/api/classes/{id}/members` 的逐人 `submissionCount`（ClassEndpoint.java:94-96），要自己在前端累加且不区分「待办/已处理」。
- **1.6/1.7/1.8 邀请码生命周期与加入截止**：这不是「漏画」，是**后端完全没有**。`ClassEntity` 只有 5 个业务字段
  （ClassEntity.java:11-25），`join` 只按码查找（ClassEndpoint.java:66-75），所以「过期后加入返回 404」
  （spec AC-2.3、tasks.md:65 的 T040）在实现里不成立。UI 反而在 locales.ts:334 宣称「默认 3 天过期，由服务端控制」——
  文案与实现互相矛盾，属误导性文案，建议随实现一起修或先删。
- **1.15 班级作业表**：实现把「跨班作业列表 + 班级筛选 chips」放在了教师首页（HomePage.tsx:226-247 筛选、262 行点击），
  但班级页本身没有区块、班级名不可点（ClassesPage.tsx:143 只是 `<b>`）。因此「从班级进入该班作业」这条路径不存在，
  用户只能先去首页再勾选班级 chips。另有一个数据缺口：`/api/offerings` 只返回 `PUBLISHED`（OfferingEndpoint.java:145-153），
  草稿布置在任何页面都列不出来，`status` 只能显示「已发布」，mockup 期望的状态列无从做起。
- **1.14 成员面板**：这是本区域唯一会**崩页**的缺陷（无 ErrorBoundary，React 渲染对象直接抛错，整棵组件树卸载）。
  后端其实已经返回了 `displayName` 和 `submissionCount`，前端 types 却只声明了 `ClassView`，成员用 `string[]`，
  修起来很小：补 `MemberView` 类型 + 渲染姓名/提交数即可（顺带满足 mockup「教师可见成员」的意图）。

## 2. offering · 布置给班级

- 路由与入口：`/offerings/new`（App.tsx:75），二级页，导航无直接入口；从试卷库行「布置」（PapersPage.tsx:88）、
  向导发布成功的「去布置」（PaperWizardPage.tsx:1322）进入。组件 `OfferingCreatePage.tsx`。
- 主操作可达性：能——选卷、选类型、多选班级、时间窗口、按班覆盖、发布或存草稿都能完成。

### 功能点对照

| # | mockup 要求（interface.html:NNN） | 实现情况 | 证据 | 级别 |
| --- | --- | --- | --- | --- |
| 2.1 | 标题「布置给班级」+ 说明「同一份试卷可以同时给多个班考…」（704–705） | 标题为「布置」，说明换成了「一场考试挂多个班；按班覆盖只影响该班」 | locales.ts:363-364；OfferingCreatePage.tsx:178 | P3 |
| 2.2 | 卡头试卷名（708） | 有 | OfferingCreatePage.tsx:192-194 | OK |
| 2.3 | 卡头 meta「77 题」（708） | 只有「150 分 · v1」，无题数；`PaperView` 也没有题数字段 | OfferingCreatePage.tsx:195-201；PaperView 无 itemCount（PaperEndpoint.java:100-102、types.ts:25-34） | P2 |
| 2.4 | 卡头 meta「150 分」（708） | 有 | OfferingCreatePage.tsx:197-199 | OK |
| 2.5 | 卡头 meta「默认 90 分钟」（708） | 未展示（字段有：`defaultDurationMinutes`，只用来填充时长输入） | OfferingCreatePage.tsx:60-64；未在卡头渲染 | P3 |
| 2.6 | （mockup 预选试卷）试卷下拉（708 隐含） | 实现额外提供下拉选择 | OfferingCreatePage.tsx:204-218 | OK |
| 2.7 | 类型 select「考试（终结性，有倒计时）/作业（形成性，无倒计时）」（710–711） | 有，标签简化为「考试/作业」 | OfferingCreatePage.tsx:220-227；locales.ts:45-48 | OK |
| 2.8 | 类型 hint「同一份试卷这次当考试、下次当作业…」（712） | 有等价文案 | OfferingCreatePage.tsx:226；locales.ts:370 | OK |
| 2.9 | 参与班级多选 chips（715–721） | 有，且额外显示每班人数 | OfferingCreatePage.tsx:238-250 | OK |
| 2.10 | 班级 hint「勾几个班就生成几条参与班级记录」（722） | 有 | OfferingCreatePage.tsx:251；locales.ts:372 | OK |
| 2.11 | 可开始时间（726） | 有（`datetime-local`） | OfferingCreatePage.tsx:255-262 | OK |
| 2.12 | 截止时间（727） | 有 | OfferingCreatePage.tsx:263-272 | OK |
| 2.13 | 截止 hint「考试：一旦开始就按完整时长走，可以跨过截止」（728） | 无此提示；截止字段下没有任何 hint | OfferingCreatePage.tsx:263-272（无 hint 节点）；locales.ts 无对应键 | P3 |
| 2.14 | 时长默认取试卷、本场可改（729–730） | 有：默认填充 + hint | OfferingCreatePage.tsx:60-64、273-283；OfferingEndpoint.java:88 | OK |
| 2.15 | 计时模式 个人计时/统一开考 + hint（731–733） | 有，hint 等价 | OfferingCreatePage.tsx:284-294；locales.ts:371 | OK |
| 2.16 | 按班覆盖表 班级/截止/时长（736–743） | 有，且直接内联输入 | OfferingCreatePage.tsx:297-342 | OK |
| 2.17 | 「设置」列的「单独设置」按钮（738–742） | 无此按钮，改为内联可编辑 + 空值显示「继承本场」 | OfferingCreatePage.tsx:318-336、324-326 | P3 |
| 2.18 | 「继承本场」显示（740–742） | 有（截止为空显示 flag，时长为 placeholder） | OfferingCreatePage.tsx:324-326、332 | OK |
| 2.19 | 快照说明「发布时给试卷内容做快照…」（746–748） | 有 | OfferingCreatePage.tsx:357-359、389-391；OfferingEndpoint.java:108-110（快照写入） | OK |
| 2.20 | 主按钮「发布并通知 3 个班」（749） | 按钮为「发布并通知」，不带班级数 | OfferingCreatePage.tsx:367-379；locales.ts:389 | P3 |
| 2.21 | 次按钮「存草稿」（750） | 有「仅存草稿」+ 发布/存草稿开关 | OfferingCreatePage.tsx:380-387、344-356 | OK |
| 2.22 | （mockup 无）立即发布开关 | 额外提供（合理） | OfferingCreatePage.tsx:344-356 | OK |

### 差异说明

- 本屏是五屏里最接近 mockup 的一屏：字段、默认值继承、按班覆盖、快照说明、发布/存草稿都在，
  且有真实校验（截止晚于开始、考试必须有开始时间与时长：OfferingCreatePage.tsx:92-114，后端同款校验 OfferingEndpoint.java:80-95）。
- 唯一的功能性缺口不在本屏控件上，而是**布置创建后不可编辑**：`OfferingEndpoint` 没有 `PUT/PATCH /api/offerings/{id}`，
  所以截止、时长、宽限期、尝试次数、反馈语言、可见性一旦创建就固定，只能新建一场。这条留给第 5 屏（editor）说明。
- 2.3 的题数需要 `PaperView` 增字段（后端本来就存题项，只是没投影出来），属于「缺字段」不是「缺页面」。

## 3. newflow · 新建试卷（5 步）

- 路由与入口：`/papers/new`（App.tsx:72），导航无直接入口；从试卷库「新建试卷」（PapersPage.tsx:111）进入。组件 `PaperWizardPage.tsx`（create 模式）。
- 主操作可达性：能走完 5 步并发布；但 mockup 的第 2 步「上传整卷」在新建态不可用（见 3.4），「下一步：校对」不存在（见 3.11）。

### 功能点对照

| # | mockup 要求（interface.html:NNN） | 实现情况 | 证据 | 级别 |
| --- | --- | --- | --- | --- |
| 3.1 | 五步 stepper：基本信息/内容/标准答案/评分项/预览发布（758–764） | 五步齐全，名称一致 | PaperWizardPage.tsx:774-780、805-815 | OK |
| 3.2 | 副标题「五步，每步可存草稿。这里不选班级」（756） | 「这里不选班级」只在第 1 步卡内出现；页头副标题是「5 步向导；中途退出重进不丢内容；流程中没有选班级」 | PaperWizardPage.tsx:852；locales.ts:211、230-231 | P3 |
| 3.3 | 每步「存草稿」按钮（783） | 无按钮；只有 localStorage 自动保存 + 第 5 步「同步并校验」才落服务端 | PaperWizardPage.tsx:345-353（自动保存）、1492（提示文案）；搜 `saveDraft` 在 `web/src/teacher` 无命中 | P2 |
| 3.4 | 第 2 步「上传整卷」drop 区（768–771） | 有文件上传卡，但**新建态不可用**：必须先在别处（第 5 步同步）把试卷创建出来才有 `paperId`；且卡片 10 个 i18n key 未定义，标题/按钮显示为 `teacher.wizard.importTitle` 之类的原始 key | 卡在 PaperWizardPage.tsx:939-999；`draft.paperId === null` 分支 PaperWizardPage.tsx:942-943；`paperId` 只在 `syncDraft` 里产生 PaperWizardPage.tsx:532-542；缺失键见本文 §3 差异说明 | P1 |
| 3.5 | 「或直接粘贴文字」（769） | 向导内无粘贴入口；粘贴只在独立的 `/papers/import` | 搜 `textarea` 在 PaperWizardPage 无命中；ImportPaperPage.tsx:116-118 | P2 |
| 3.6 | 支持 .docx / .md / .xlsx（770） | 支持，另有 .txt | PaperWizardPage.tsx:950-953；DocumentTextExtractor.java:32-38（后端同样支持） | OK |
| 3.7 | 解析统计「4 个部分 8 节 · 77 题 · 75 题已识别答案」（772–774） | 只有「已导入 N 题」+ 缺答案题号列表；无「部分/节」统计（解析器一次只灌一个部分） | PaperWizardPage.tsx:963-966；ImportResult 只有 `imported/missingAnswers/items/warnings`（PaperEndpoint.java:129-130）；`createItems` 只写 `resolveSection` 的第一个/指定部分（PaperEndpoint.java:408-416） | P2 |
| 3.8 | 已解析文件标签「75145_试卷.docx · 已解析」（773） | 不显示文件名与「已解析」状态 | PaperWizardPage.tsx:960-967（只有数量 tag） | P3 |
| 3.9 | inline-note「题目和答案写在一起也能用…不需要拆两个文件」（776–779） | 向导内注释节点引用的 i18n key 未定义，实际渲染成 key 原文；等价说明只存在于 `/papers/import` | PaperWizardPage.tsx:958（`teacher.wizard.importRequirements`）；该 key 不在 locales.ts（见差异说明）；等价文案 ImportPaperPage.tsx:113 | P1 |
| 3.10 | 「手工添加部分」（781） | 有（部分标题 + 每题分值 + 添加按钮） | PaperWizardPage.tsx:868-894 | OK |
| 3.11 | 「下一步：校对」（784） | 无此跳转；第 2 步的「下一步」直接进第 3 步标准答案，校对屏只能从试卷库另行进入，且不携带当前试卷 | PaperWizardPage.tsx:1493-1495（goNext）；App.tsx:73（`/papers/import` 无参数） | P2 |
| 3.12 | 第 3 步标准答案 / 第 4 步评分项（761–762） | 有，逐题录入 + 评分项录入 | PaperWizardPage.tsx:1027-1187、1189-1314 | OK |
| 3.13 | 第 5 步预览发布（763） | 有（总分四联、结构表、rubric、同步/发布） | PaperWizardPage.tsx:1316-1486 | OK |
| 3.14 | （mockup 无）批量改题型卡 | 额外控件，但 8 个 i18n key 未定义，整卡标题/字段名/按钮渲染为 key 原文 | PaperWizardPage.tsx:1001-1023；缺失键见差异说明 | P2 |

### 差异说明

- **3.4/3.9/3.14 共同的根因：i18n key 未定义**。用脚本把 `PaperWizardPage.tsx` 里所有 `t('...')` 与
  `locales.ts` 的键定义比对，命中 22 个未定义键（`importTitle`、`importHint`、`importNeedPaper`、`importLocked`、
  `importRun`、`importRequirements`、`importImported`、`importMissing`、`importNoAnswer`、`colStem`、
  `batchTitle`、`batchHint`、`batchFrom`、`batchTo`、`batchApply`、`batchInvalid`、`batchEmpty`、`batchLocal`、
  `batchApplied`、`scoringStrategy`、`strategyAllOrNothing`、`strategyPartial`）。
  后果：第 2 步的「导入整卷」卡与「批量改题型」卡**整块没有可读文案**，第 3 步多选判分策略下拉是三个 key。
  这不是「控件缺失」，而是新写的控件没有配套文案——按 P1 记一次（3.4、3.9），批量卡按 P2 记。
- **3.4 的流程顺序问题**：mockup 的顺序是「第 2 步上传 → 解析 → 校对 → 后续」，实现必须先把试卷同步到后端
  才有 `paperId`（PaperWizardPage.tsx:532-542、942-943），于是新建态第 2 步只剩「手工添加部分」，
  上传路径被推到 `/papers/import`（那条路会先建卷 + 建一个「综合」部分，再灌题）。两条路都能走到终点，
  但 mockup 期望的「第 2 步一次搞定上传」不存在。
- **3.11 的副作用**：`/papers/import` 导入完只有「去向导补评分项」一个出口（ImportPaperPage.tsx:140），
  而向导第 2 步再进「导入」会因题号已存在被跳过（后端 `createItems` 对重复题号只发 warning，PaperEndpoint.java:385-388），
  所以「先向导、后导入」这条顺序会静默丢题（只出现在 warnings 列表里）。

## 4. import · 校对导入结果

- 路由与入口：`/papers/import`（App.tsx:73），导航无入口；从试卷库「导入考卷」（PapersPage.tsx:110）进入。组件 `ImportPaperPage.tsx`。
- 主操作可达性：能上传/粘贴、能逐行改题干选项答案并保存；但**没有「确认导入」闸门**（解析即落库），
  也看不到部分、状态、置信度、分页。

### 功能点对照

| # | mockup 要求（interface.html:NNN） | 实现情况 | 证据 | 级别 |
| --- | --- | --- | --- | --- |
| 4.1 | 页标题「校对导入结果」+ 副标题「黄色行是低置信度 · 所有字段都可编辑」（791–792） | 有「校对导入结果」h2；副标题换成上传说明，未提低置信度/可编辑 | ImportPaperPage.tsx:84-88、134 | P3 |
| 4.2 | 统计「已从文档抽取 4 个部分 8 节 · 77 题 · 75 题有答案」（795） | 只有「已导入 N 题」；部分/节计数不存在（单部分导入） | ImportPaperPage.tsx:136；ImportResult 无 sections（PaperEndpoint.java:129-130） | P2 |
| 4.3 | inline-note「解析器按题号切分…低置信度的行标黄，不会偷偷用错误内容」（796–799） | 无此说明；无低置信度概念 | ImportPaperPage.tsx:131-169（无对应节点）；搜 `置信\|confidence` 在 `teachloop-paper`、`web/src` 无命中 | P3 |
| 4.4 | 表列 题号（801） | 有 | ImportPaperPage.tsx:148-150、155 | OK |
| 4.5 | 表列 部分（801、803、813、818） | 无此列；导入时只建一个「综合」部分，所有题都进同一部分 | ImportPaperPage.tsx:148-151（表头无部分）；ImportPaperPage.tsx:36-38（只建一个 section） | P1 |
| 4.6 | 表列 题干，可编辑（804） | 有 | ImportPaperPage.tsx:156 | OK |
| 4.7 | 表列 选项，可编辑（805） | 有（textarea） | ImportPaperPage.tsx:157 | OK |
| 4.8 | 表列 答案，可编辑，缺答案 placeholder（806、826） | 有输入框，无 placeholder | ImportPaperPage.tsx:158 | P3 |
| 4.9 | 表列 状态 tag「已配对 / 待确认 / 缺答案」（807、817、827） | 无状态列；「缺答案」只在表头 tag 里以题号列表出现 | ImportPaperPage.tsx:148-151；ImportPaperPage.tsx:137-139 | P1 |
| 4.10 | 低置信度行标黄 `tr.conf-low`（813） | 无；后端解析器不产出置信度 | 无 `conf-low` 类（grep `conf-low` 在 `web/src` 无命中）；`PaperTextParser.ParsedItem` 只有 number/sourceLine/stem/options/answerKey（PaperTextParser.java:28） | P1 |
| 4.11 | （mockup 无）逐行保存 | 额外提供「保存」按钮，调 `PUT /items/{number}` | ImportPaperPage.tsx:68-80、159-163 | OK |
| 4.12 | 底部计数 tag「1 行待确认 / 1 行缺答案」（831） | 只有「缺答案：75, …」一个 amber tag；无待确认计数 | ImportPaperPage.tsx:136-139 | P2 |
| 4.13 | 「发布前必须补齐」（832） | 本页无此句；阻断逻辑在向导第 5 步（`missing > 0` 禁用发布） | PaperWizardPage.tsx:1475、1482；locales.ts:290、305 | P2 |
| 4.14 | 主按钮「确认导入」（834） | 语义缺失：解析即写入数据库，没有暂存/确认环节；按钮变成「去向导补评分项」 | ImportPaperPage.tsx:39-56（解析时直接 `createItems` 落库）、140；后端 `confirm` 参数只是「已发布试卷允许改」的开关（PaperEndpoint.java:515-525） | P1 |
| 4.15 | 分页「共 77 条 / 页码 / 20 或 50 每页」（836–839） | 无分页：77 行全量渲染（含 textarea），无每页条数选择 | ImportPaperPage.tsx:148-167（裸 `<table>`） | P2 |
| 4.16 | 可排序 题号 / 状态（801） | 无排序 | ImportPaperPage.tsx:148-151 | P3 |
| 4.17 | （mockup 隐含）先建卷再导入 | 实现有：标题/部分名/每题分 + 创建并解析 | ImportPaperPage.tsx:90-127 | OK |

### 差异说明

- **4.5/4.9/4.10 是一组缺口，根因在后端解析能力**：`PaperTextParser` 只在**一个 section 内**按题号切分
  （PaperEndpoint.java:396-398 取 `resolveSection`），`ParsedItem` 没有 part/section 与 confidence 字段
  （PaperTextParser.java:28），`ImportResult` 也没有部分计数。所以 mockup 的「4 个部分 8 节」「部分列」
  「待确认/已配对状态」「低置信度标黄」在现有接口下**做不出来**，不是前端漏写。缺的是解析器的多部分切分 + 置信度输出。
- **4.14 确认导入**：这是本屏最重要的行为差异。mockup 把校对当「审阅 → 确认」两步；实现是「解析即落库、
  逐行改、改完算改」。因为后端没有题项删除接口（只有 `PUT /items/{number}`，PaperEndpoint.java:248-273），
  一旦解析进错（例如把答案段当题干吃进去 30 行），教师**无法删掉**，只能改到对为止或弃卷重建。
- **4.15 分页**：77 题一次渲染 77 个受控输入 + textarea，且每行独立保存，改动多行时容易漏点保存；
  mockup 的 20/50 每页在这里不是装饰，是可用性需求。
- 附注（P3，同屏）：本页文案全部硬编码中文（ImportPaperPage.tsx:28-30、86、113、136、140 等），
  与 AC-15.3「代码中不得硬编码用户可见文案」冲突；页面也没有包 `TeacherPage` 外壳，与其余教师页结构不一致。

## 5. editor · 试卷编辑

- 路由与入口：`/papers/:paperId`（App.tsx:74），导航无入口；从试卷库行「编辑」或行点击进入（PapersPage.tsx:85、142）。组件 `PaperWizardPage.tsx`（edit 模式）。
- 主操作可达性：能改部分/题项/评分项（只能追加）、能看总分校验、能发布试卷；
  **不能**改标题与默认时长（控件禁用 + 后端无更新接口）、**不能**设截止/宽限期/尝试次数/反馈语言/两个可见性开关。

### editor 与 newflow 第 5 步是否合并 —— 是，但合错了方向

实现里 `/papers/new` 与 `/papers/:paperId` 是**同一个组件**（App.tsx:72、74 → `PaperWizardPage`），
由 `paperIdParam` 切成 create/edit 两种模式（PaperWizardPage.tsx:254-256、93-131）。
所以「editor 屏」并不是一张单页，而是**复用五步向导的 edit 模式**；mockup 的「第 5 步预览发布」也就是这个向导的最后一步。
逐条说明 editor 屏的控件落空情况：

| editor 控件（interface.html:NNN） | 落点 | 结论 |
| --- | --- | --- |
| 基本信息 · 标题（851） | 向导第 1 步，但 edit 模式 `disabled` | 有控件、不可编辑（P2） |
| 基本信息 · 截止时间（852） | 无 | 落空（P1，属布置字段，见 §6-4） |
| 基本信息 · 宽限期（853） | 无 | 落空（P1） |
| 基本信息 · 最多尝试次数（854） | 无 | 落空（P1） |
| 基本信息 · 反馈语言（855） | 无 | 落空（P1） |
| 可见性 · 允许学生查看班级概览（859） | 无 | 落空（P1） |
| 可见性 · 公布他人分数（860） | 无 | 落空（P1） |
| 可见性 · 规则说明与「概览可见/不可见」kv（861–863） | 无 | 落空（P3） |
| 试卷结构表 部分列（869） | 向导第 2 步部分表 | 有（OK） |
| 结构表 类型列（869–875） | 无 | 落空（P2，无 section 类型字段） |
| 结构表 判分列「代码 / AI + 教师」（871–875） | 无 | 落空（P2） |
| 结构表 题项「20 题 × 1.5」（871） | 第 2 步只有题项数 | 简化（P3） |
| 结构表 行内「题目 / 评分项」按钮（871–875） | 无（只能切 stepper） | 落空（P2） |
| 「合计 150，与卷面一致」（878） | 第 5 步 stats + inline-note + 阻断 | 有（OK，位置搬到第 5 步） |
| 「添加部分」（878） | 第 2 步 | 有（OK） |
| 「发布并开始批改」（880） | 「发布试卷」+ 发布后「去布置」 | 语义不同（P2） |
| 「保存草稿」（880） | 无按钮，仅自动 localStorage | 落空（P2） |

### 功能点对照

| # | mockup 要求（interface.html:NNN） | 实现情况 | 证据 | 级别 |
| --- | --- | --- | --- | --- |
| 5.1 | 页头「第一次月考（整卷）」+ 副标题「试卷 · 编辑中 · 合计 150」（846–847） | 标题用试卷名；副标题是向导通用文案，没有「试卷 · 编辑中 · 合计 N」状态行 | PaperWizardPage.tsx:789-797；locales.ts:211 | P3 |
| 5.2 | 基本信息 · 标题可编辑（851） | edit 模式禁用，并注明「创建后不可修改（后端未提供更新接口）」 | PaperWizardPage.tsx:828-836；locales.ts:229 | P2 |
| 5.3 | 基本信息 · 截止时间（852） | 无此字段；这是布置（offering）字段，而实现没有任何「编辑已有布置」的页面/接口 | PaperWizardPage.tsx:821-859（step1 只有标题+时长）；OfferingEndpoint.java 无 `@PutMapping/@PatchMapping`（全部映射见 67/144/154/166/179/201/225/236/251/268 行） | P1 |
| 5.4 | 基本信息 · 宽限期（小时）（853） | 无；后端只在**创建布置**时接受 `graceHours`（默认 3），前端创建时根本不传 | PaperWizardPage.tsx 无该字段；SittingDtos.java:15-20（`graceHours` 入参）、OfferingEndpoint.java:112（默认值）；OfferingCreatePage.tsx:146-155（请求体没有 graceHours） | P1 |
| 5.5 | 基本信息 · 最多尝试次数（854） | 无（同上：只可创建时设，UI 从不传，默认 3） | SittingDtos.java:17；OfferingEndpoint.java:111；OfferingCreatePage.tsx:146-155 | P1 |
| 5.6 | 基本信息 · 反馈语言（855） | 无（同上，默认 zh-CN；学生端考试入口会显示该语种） | SittingDtos.java:18；OfferingEndpoint.java:113；AssignmentPage.tsx:232 | P1 |
| 5.7 | 可见性 · 允许学生查看班级概览（859） | 无任何 UI 设置 `showPeerOverview`；后端只在创建时接受（默认 true） | 搜 `showPeerOverview` 在 `web/src` 仅 types.ts:101 与读取方；无写入方；SittingDtos.java:18、OfferingEndpoint.java:114 | P1 |
| 5.8 | 可见性 · 公布他人分数（860） | 无任何 UI 设置 `publishScores`；且后端已有 `POST /api/offerings/{id}/scores/publish` 但**全前端无调用者**，等于成绩永远发不出去 | 搜 `publishScores` 在 `web/src` 只有读取；搜 `scores/publish` 在 `web/src` 无命中；接口在 OfferingEndpoint.java:179-197；spec AC-26.12（默认关） | P1 |
| 5.9 | 可见性规则文案 + 「概览可见 / 概览不可见」kv（861–863） | 无 | PaperWizardPage 无对应节点；locales.ts 无对应键（搜 `概览\|peer` 在 teacher locales 无命中） | P3 |
| 5.10 | 结构卡标题「试卷结构 总分 = 各部分之和」（867） | 第 2 步标题为「第 2 步 · 内容（部分）」，无「总分 = 各部分之和」 | PaperWizardPage.tsx:864；locales.ts:232 | P3 |
| 5.11 | 结构表 部分列（869） | 有 | PaperWizardPage.tsx:904-931 | OK |
| 5.12 | 结构表 类型列 客观/主观 tag（869–875） | 无；`PaperSection` 没有类型字段，只能由题项题型派生 | PaperSection.java:9-28（只有 title/position/pointsPerItem）；PaperWizardPage.tsx:906-911（表头无类型） | P2 |
| 5.13 | 结构表 分值列（部分合计，871–875） | 第 2 步无该列（只有「每题分值」）；第 5 步有「本部分分值」 | PaperWizardPage.tsx:908、920 vs 1386、1410 | P3 |
| 5.14 | 结构表 题项列「20 题 × 1.5 / 2 个评分项」（871–875） | 第 2 步只有题项数、评分项数两列，不显示「× 每题分」 | PaperWizardPage.tsx:909-910、921-922 | P3 |
| 5.15 | 结构表 判分列「代码 / AI + 教师」（871–875） | 无判分列 | PaperWizardPage.tsx:906-911 | P2 |
| 5.16 | 行内按钮「题目 / 评分项」（871–875） | 无行内跳转，只能靠 stepper 切步 | PaperWizardPage.tsx:915-931（无操作列） | P2 |
| 5.17 | 合计校验「合计 150，与卷面一致」（878） | 有：第 5 步四联统计 + 一致/不一致提示 + 不一致时禁用发布 | PaperWizardPage.tsx:1334-1366、1475、621-632 | OK |
| 5.18 | 「添加部分」（878） | 有 | PaperWizardPage.tsx:868-894 | OK |
| 5.19 | 主按钮「发布并开始批改」（880） | 实现是「发布试卷」；发布成功后只提供「去布置」，不会开始批改 | PaperWizardPage.tsx:1472-1479；发布成功卡 PaperWizardPage.tsx:1318-1330；`/api/papers/{id}/publish` 只改试卷状态（PaperEndpoint.java:446-458） | P2 |
| 5.20 | 次按钮「保存草稿」（880） | 无按钮；自动存 localStorage，服务端草稿只能靠第 5 步「同步并校验」 | PaperWizardPage.tsx:345-353、1463-1471、1492 | P2 |

### 差异说明

- **5.3–5.8 是同一类问题：布置级字段没有任何编辑入口**。mockup 把「截止/宽限期/尝试次数/反馈语言/两个可见性开关」
  画在试卷编辑页上，说明它把它们当成「这场布置的可调参数」。实现把这些字段正确地放在了 offering 上
  （SittingDtos.java:15-20），但**只有创建时有入口，而且前端只回填了其中三项之外的零项**：
  `OfferingCreatePage` 提交体只有 `paperId/kind/startAt/dueAt/durationMinutes/timingMode/classes/publish`
  （OfferingCreatePage.tsx:146-155），`graceHours/maxAttempts/feedbackLanguage/showPeerOverview/publishScores`
  全部走服务端默认值，之后没有任何接口能改（`OfferingEndpoint` 无 PUT/PATCH）。
  结论：这 6 个控件不是「mockup 画在试卷上所以算试卷的」，而是**在系统里完全不可配置**。
  尤其 5.8 更严重——即使后端默认 `publishScores=false`、且提供了 `/scores/publish`，
  前端没有任何按钮调它，所以「公布他人分数」这条 US-13/AC-13.2 在 UI 上是死路。
- **5.12/5.15 结构表的类型与判分**：mockup 的「类型：客观/主观」「判分：代码 / AI + 教师」是给教师看
  「这部分怎么判」的摘要。实现里这些信息是存在的（题项有 `questionType`，主观部分有评分项），
  只是没有投影到部分级；补法有两种：后端给 section 加 type，或前端按「该部分是否存在 ESSAY 题项 / 评分项」派生。
- **5.2 标题与默认时长不可改**：后端确实没有 `PUT /api/papers/{id}`（PaperEndpoint 只有 sections/items/criteria/rubric/publish 的写接口），
  前端在 locales.ts:229 已经如实标注「未接入」。这是「缺接口」而不是「漏画控件」。
- **5.19 发布语义**：mockup 的「发布并开始批改」其实要求「发布 → 生成布置 → 派发批改」一条链；
  实现把它拆成「发布试卷」+「新建布置」+「批改总览派发」三步，每一步都在，但没有 mockup 那条一键路径。
  属于流程简化（P2），不是功能缺失。
- **5.20 与 5.17 的取舍**：实现用「同步并校验」代替「保存草稿」，语义上更重（会真的写库），
  但 mockup 的「保存草稿」是教师的心智模型——不写库也能存。当前实现里，只要没点同步，关掉浏览器
  就只能靠 localStorage 恢复（PaperWizardPage.tsx:133-172），换设备/清缓存即丢失。

## 6. 本区域阻塞项

| 问题 | 缺的是什么（接口 / 字段 / 组件） | 建议处理 |
| --- | --- | --- |
| 邀请码无过期、无停用；「加入截止」不存在 | 字段：`classes` 表无 `invite_expires_at` / `invite_disabled` / `join_deadline`；接口：无停用端点，`join` 不校验过期（ClassEndpoint.java:66-112，ClassEntity.java:9-45） | 加迁移 + `join` 返回 404（对齐 AC-2.3/2.4、tasks.md T040）；UI 补剩余有效期 tag / 停用按钮 / 加入截止列 |
| 班级「待办」无数 | 接口：`/api/classes` 无待办聚合（ClassView 4 字段，ClassEndpoint.java:46）；只有逐人 `submissionCount` 端口（ClassEndpoint.java:94-96） | `ClassView` 增 `pendingCount`，或新增 `/api/classes/{id}/stats`；口径需先定义（待批改？待发布？） |
| 班级作业列表 / 草稿布置不可见 | 接口：`/api/offerings` 只返回 `PUBLISHED`（OfferingEndpoint.java:145-153），无 `classId` 过滤；无 `/api/classes/{id}/offerings` | 加 `GET /api/classes/{id}/offerings`（含 DRAFT），或 `/api/offerings?classId=&status=`；前端班级页补区块与钻取 |
| 布置创建后完全不可编辑 | 接口：`OfferingEndpoint` 无 `PUT/PATCH /api/offerings/{id}` | 加更新端点（截止/时长/按班覆盖可改；AC-26.11 需对已开始的班收紧）；UI 补布置编辑页，或把 editor 屏的 6 个字段挪到布置页 |
| 宽限期/尝试次数/反馈语言/两个可见性开关无 UI | 前端：`OfferingCreatePage` 创建请求不传这 5 个字段（OfferingCreatePage.tsx:146-155）；后端已支持（SittingDtos.java:15-20）；`/scores/publish` 无调用者 | 布置页加 5 个控件；作业详情加「公布成绩」按钮（`POST /api/offerings/{id}/scores/publish` 已存在） |
| 解析不产出「部分/节」与置信度 | 能力+字段：`PaperTextParser.ParsedItem` 无 part/section/confidence（PaperTextParser.java:28）；`ImportResult` 无 sections（PaperEndpoint.java:129-130）；`createItems` 单 section 写入 | 解析器输出 `section`/`confidence`，前端才能做「部分列」「待确认/已配对」「低置信度标黄」 |
| 导入无暂存/确认，且题项不可删 | 接口：`createItems` 解析即落库（PaperEndpoint.java:374-405）；无 `DELETE /items/{number}`、无 `DELETE /sections/{id}` | 加 staged import（先存草稿批次）或补删除接口；否则误导入无法回退 |
| 试卷/部分/题项/评分项只能追加，不能改删 | 接口：`PaperEndpoint` 无 `PUT /api/papers/{id}`、无 `DELETE`（创建/更新接口见 205/218/248/418 行） | 补试卷更新 + 部分/题项/评分项删除；否则 mockup 的「编辑」语义只能新建一份复制卷 |
| 成员面板渲染对象导致崩页 | 前端类型：`api.get<string[]>`（ClassesPage.tsx:107）vs 后端 `MemberView` 对象数组（ClassEndpoint.java:80、94-96）；无 ErrorBoundary | 补 `MemberView` 类型并渲染 `displayName`/`submissionCount`；建议顺带加全局 ErrorBoundary |
| 向导 22 个 i18n key 未定义 | 文案：`locales.ts` 缺 `wizard.import*`（10）、`wizard.batch*`（9）、`wizard.scoringStrategy/strategy*`（3） | 补齐 zh/en 两组文案；建议加一条「未定义 key 即测试失败」的校验 |
| 试卷缺题数（布置页/班级表需要「77 题」） | 字段：`PaperView` 无 `itemCount` / `objectiveCount` / `subjectivePoints`（PaperEndpoint.java:100-102） | `PaperView` 增题数与客观/主观分投影，供布置页 meta 与班级作业副标题使用 |
