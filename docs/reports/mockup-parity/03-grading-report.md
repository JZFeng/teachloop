# 批改区域（home / grading / grading-detail / review / summary / report / audit）对等性审计

审计对象：`specs/002-v2-platform/mockups/interface.html:593-1200`（7 个 screen）
实现基线：`web/src/App.tsx`、`web/src/teacher/*`、`web/src/components/*`、`web/src/api/types.ts`
后端基线：`teachloop-grading`、`teachloop-report`、`teachloop-sitting`、`teachloop-observability`（只读）
方法：逐屏列控件 → 在实现里找 DOM/字段 → 缺的再查 Controller/DTO，分「页面缺 / 功能缺 / 接口缺」。

## 0. 汇总

| 屏 | 路由 | 结论 | P0 | P1 | P2 | P3 |
| --- | --- | --- | --- | --- | --- | --- |
| home（教师首页） | `/`（`web/src/App.tsx:69`，教师分支 `:38-41`） | 部分缺失 | 0 | 0 | 3 | 4 |
| grading（批改总览） | `/grading`（`web/src/App.tsx:76`） | 部分缺失 | 0 | 0 | 6 | 3 |
| grading-detail（作业批改详情） | `/grading/:offeringId`（`web/src/App.tsx:77`） | 部分缺失 | 0 | 3 | 1 | 3 |
| review（复核工作台） | `/review`（`web/src/App.tsx:78`） | 部分缺失 | 0 | 5 | 5 | 6 |
| summary（班级汇总） | `/offerings/:offeringId/summary`（`web/src/App.tsx:80`） | 部分缺失 | 0 | 1 | 7 | 2 |
| report（报表查询） | `/reports`（`web/src/App.tsx:79`） | 部分缺失 | 4 | 2 | 7 | 4 |
| audit（审计时间线） | 无独立路由，抽屉（`web/src/components/AuditDrawer.tsx:7`） | 部分缺失 | 0 | 6 | 2 | 3 |
| 合计 | — | — | 4 | 17 | 31 | 25 |

页面级结论：7 个屏幕全部有落点，没有整屏缺失；但 report 的「勾选 → 已选 → 导出」整条主链路不存在（4 条 P0，实为同一根因），audit 的 7 类事件只覆盖 2 类（6 条 P1）。

---

## 1. home · 教师首页

- 路由与入口：`/`（`web/src/App.tsx:69`，`HomeRouter` 在 `:38-41` 按角色分流），左侧一级导航「首页」可见（`web/src/app/Shell.tsx:19`）。
- 主操作可达性：能。看跨班统计、按班级筛选、进作业详情/复核均可达；问题只在口径与状态细分。

### 功能点对照

| # | mockup 要求（interface.html:NNN） | 实现情况 | 证据 | 级别 |
| --- | --- | --- | --- | --- |
| 1 | 标题「首页」+ 副标题「我教的所有班级汇总在一起 · 不需要先选班级」（593-595） | 一致 | `web/src/teacher/locales.ts:70-71` | OK |
| 2 | 统计卡「待复核 18 / 3 个班」可点进复核（598） | 数字取 `/api/grading/queue` 全量 PENDING 数，点击进 `/review?status=PENDING` | `HomePage.tsx:85,180-186` | OK |
| 3 | 统计卡「批改中」可点进批改总览（599） | 取 `/progress` 的 running 累加，点击 `/grading?state=running`，目标页能识别 `state=running` | `HomePage.tsx:79,187-192`；`GradingOverviewPage.tsx:310-314` | OK |
| 4 | 统计卡「待发布 / 复核已完成」（600） | 口径是「未公布成绩的作业里 done 份数之和」，不是「复核已完成的作业」，且副标题写「已批改、成绩未公布」 | `HomePage.tsx:80-83,193-198`；`locales.ts:78` | P3 |
| 5 | 统计卡「未提交 / 接近截止」（601） | 取 `expected - submitted` 累加，副标题「按班统计的应交未交」；无「接近截止」的判断 | `HomePage.tsx:84,199-204`；`common.tsx:151` | P3 |
| 6 | 四张卡的副标题「3 个班 / 共 60 份」等（598-601） | 副标题改成了状态释义，没有「涉及几个班」「共几份」 | `locales.ts:72-81` | P3 |
| 7 | 班级筛选卡标题 +「可多选 · 不选等于全部」（604-605） | 一致，chips 来自 `/api/classes` | `HomePage.tsx:207-228`；`locales.ts:87-88` | OK |
| 8 | 班级 chip 带人数「高一 (3) 班 (3)」（607-609） | 一致：`{c.name} ({c.memberCount})` | `HomePage.tsx:222` | OK |
| 9 | 不选 = 全部（604-605） | 一致：`selectedClasses.size === 0` 时不过滤 | `HomePage.tsx:68-71` | OK |
| 10 | 切换筛选不重置排序与分页（611） | 一致：只改 `classes` 参数，不动 `sort/dir/page`；提示文案也在 | `HomePage.tsx:50-61`；`locales.ts:89` | OK |
| 11 | 「最近作业」+「默认按截止时间倒序」（615） | 一致：`useListState({key:'dueAt',dir:'desc'}, 20)` | `HomePage.tsx:34-37,236-242` | OK |
| 12 | 表列 班级（618） | 有，单元格内追加「N 个班」；班级名是逗号拼接 | `HomePage.tsx:88-101` | OK |
| 13 | 表列 作业（618） | 有，单元格内追加类型（考试/作业） | `HomePage.tsx:102-113` | OK |
| 14 | 表列 截止（618） | 有，`fmtDateTime` | `HomePage.tsx:114-120` | OK |
| 15 | 表列 提交「30 / 32」（619,623） | 有，`submitted / expected`（来自 `/class-summary` 汇总） | `HomePage.tsx:121-135`；`common.tsx:140-151` | OK |
| 16 | 状态含细分「批改中 12/30」「待复核 18」「可发布」（624,628,632） | 只有「有失败 / 批改中 / 待处理 / 可发布 / 成绩已公布 / 待提交 / 进行中」；没有进度分母，也没有「待复核」态 | `common.tsx:207-233`；`locales.ts:37-43` | P2 |
| 17 | 表列 状态可排序（619） | 有，按派生等级排序（失败 0 → 批改中 1 → …） | `HomePage.tsx:136-152,264-271` | OK |
| 18 | 行内操作按状态给「详情 / 复核 / 汇总」（625,629,633） | 固定「详情 + 汇总」两个按钮，没有「复核」；点行进详情 | `HomePage.tsx:154-168,252` | P2 |
| 19 | 每列可排序（616-619） | 列头 `⇅` 可点，排序写 URL | `web/src/components/DataTable.tsx:60-86` | OK |
| 20 | 分页「共 N 条 / ‹ 1 › / 20-50-100 每页」（636-640） | 有总数 + 上一页/下一页 + 20/50/100；页大小选择器放在标题行右侧，不是分页条内；无页码按钮 | `DataTable.tsx:104-110`；`common.tsx:236-262`；`HomePage.tsx:241` | P3 |
| 21 | 空态（作业为空时） | 有「还没有已发布的作业」+ 去试卷库的动作 | `HomePage.tsx:230-234`；`locales.ts:91-92` | OK |
| 22 | 「我名下全部班级」的数据范围（595） | 不成立：`GET /api/offerings` 对非学生返回全部 PUBLISHED 作业，不按当前教师过滤；统计卡与表是全平台口径 | `teachloop-sitting/.../OfferingEndpoint.java:144-152` | P2 |

### 差异说明

- 待发布口径（#4）：数的是「份」，但卡名与 mockup 的「复核已完成」是作业级含义，老师按这个数去点会落到批改总览而看不到同样多的行。
- 未提交副标题（#5）：mockup 强调「接近截止」，实现只说明口径，没有任何按截止逼近的计算或提示。
- 卡片副标题（#6）：mockup 用「3 个班 / 共 60 份」让老师知道这个数覆盖多大范围，实现换成状态解释，信息量更少。
- 状态细分（#16）：这是首页最关键的差异——mockup 的「批改中 12/30」能看出还剩多少，「待复核 18」能看出该去复核；实现只有「批改中」三个字，也没有「待复核」，老师必须进详情才知道。
- 行内动作（#18）：mockup 按状态直接给「复核」入口；实现必须先进详情或从左侧导航进复核，多两步。
- 分页样式（#20）：整数页码按钮缺失，只能一页页翻；页大小选择器位置和 mockup 不一致。
- 数据范围（#22）：副标题承诺「我名下全部班级」，但 `/api/offerings` 对教师不按归属过滤（`OfferingEndpoint.java:144-152`），多教师环境下首页统计会混入别人的班。

---

## 2. grading · 批改总览

- 路由与入口：`/grading`（`web/src/App.tsx:76`），左侧一级导航「批改」可见（`Shell.tsx:22`）。
- 主操作可达性：能。四张卡能过滤、能进详情/汇总；差异在过滤结果的「行 vs 份」口径与状态覆盖。

### 功能点对照

| # | mockup 要求（interface.html:NNN） | 实现情况 | 证据 | 级别 |
| --- | --- | --- | --- | --- |
| 1 | 标题 + 副标题「我名下全部班级 · 点数字过滤下表，点行进作业详情」（885-886） | 一致 | `locales.ts:98-99` | OK |
| 2 | 卡「已完成 88」点数字过滤下表（889） | 数字 = 各作业 `progress.done` 累加（份级）；点击把 `state=done` 写 URL，表格按「该作业 done>0」过滤（作业级） | `GradingOverviewPage.tsx:71,81-82,217-223` | P2 |
| 3 | 卡「失败 3 / 需人工处理」（890） | `failed` 累加 + 副标题「需人工处理」 | `GradingOverviewPage.tsx:72,224-229`；`locales.ts:103` | OK |
| 4 | 卡「待处理 41」（891） | `pending` 累加，点击 `state=pending` | `GradingOverviewPage.tsx:73,230-234` | OK |
| 5 | 卡「未提交 7」（892） | 取 `expected - submitted` 累加，点击 `state=unsubmitted` | `GradingOverviewPage.tsx:75,235-240` | OK |
| 6 | 已完成卡无副标题（889） | 实现了副标题「另有 N 份正在判分」 | `GradingOverviewPage.tsx:221`；`locales.ts:105` | P3 |
| 7 | 表标题「作业批改状态 · 每行一个作业 · 默认按截止倒序」（896） | 一致 | `locales.ts:109-110`；`GradingOverviewPage.tsx:37-40` | OK |
| 8 | 表列 班级（899） | 无独立列，班级名放在「作业」单元格第二行 | `GradingOverviewPage.tsx:104-112` | P3 |
| 9 | 表列 作业（899） | 有（标题 + 班级副行） | `GradingOverviewPage.tsx:99-112` | OK |
| 10 | 表列 截止（899） | 有 | `GradingOverviewPage.tsx:129-135` | OK |
| 11 | 表列 总数「32」（899） | 无独立「总数」列，只在「已提交 30 / 32」的分母里出现 | `GradingOverviewPage.tsx:136-151` | P2 |
| 12 | 表列 已完成 / 失败 / 待处理 三个可排序数字列（899-900） | 合并成状态单元格里的四个 tag「完成 n / 批改中 n / 待处理 n / 失败 n」，不能按这些列排序 | `GradingOverviewPage.tsx:152-188`；`locales.ts:120-123` | P2 |
| 13 | 表列 状态「批改中 / 待复核 / 有失败」（906,911,916） | 有「有失败 / 批改中 / 待处理 / 可发布 / 成绩已公布」；没有「待复核」 | `common.tsx:217-232`；`locales.ts:37-43` | P2 |
| 14 | 行内「详情」或「复核」（907,912,917） | 固定「详情 + 汇总」，没有「复核」 | `GradingOverviewPage.tsx:189-203` | P2 |
| 15 | 点行进入作业详情（886） | 有 `onRowClick` | `GradingOverviewPage.tsx:300` | OK |
| 16 | 分页条文案「筛选：已完成 · 共 3 条」（921） | 改为独立的筛选 chip 行 + 「显示 x / y 条」+「过滤条件已写入 URL」提示 | `GradingOverviewPage.tsx:251-286`；`locales.ts:111-113` | P3 |
| 17 | 分页「‹ 1 › / 20-50 每页」（922-923） | 有上一页/下一页与 20/50/100；页大小选择器在标题行 | `DataTable.tsx:104-110`；`common.tsx:236-262` | OK |
| 18 | 空态 | 「还没有已发布作业」/「当前筛选下没有作业」 | `GradingOverviewPage.tsx:288-302` | OK |
| 19 | 副标题「我名下全部班级」（886） | 不成立：同一份不过滤教师的作业列表，页面上的「我名下全部班级」是文案承诺而不是数据事实 | `teachloop-sitting/.../OfferingEndpoint.java:144-152`；`locales.ts:99` | P2 |

### 差异说明

- **卡片数字与过滤结果口径不一致（#2，已知问题）**：mockup 与 `docs/reports/visual-review-2026-10-19.md:54-67` 记录的同一条问题，实现侧的选择是「卡片数字保持份级累加，点击后按作业级筛选」——即点「已完成 88」得到的是「有已完成份数的作业」若干行，行内 tag 加起来才是 88；既不是 88 行，也不显示 88 这个合计。老师会以为筛选坏了。这是 P2 而非 P3，因为过滤结果与卡片数字无法互相校验。
- 总数/已完成/失败/待处理列（#11、#12）：四个数字被压进一个单元格，信息还在，但失去了 mockup 的「按列排序」能力，且「总数」完全消失。
- 状态（#13）：mockup 用「待复核」区分「批改完了但老师还没复核」，实现没有这个态，导致批改总览无法回答「哪些作业在等我复核」。
- 行内动作（#14）：同 home，无「复核」。
- 分页文案（#16）：实现多了 URL 持久化说明，少了 mockup 的固定文案，属于交互改进，记 P3 仅因位置/形态不同。
- 数据范围（#19）：同首页，作业列表不过滤教师，所以「我名下全部班级」只是文案。

---

## 3. grading-detail · 作业批改详情

- 路由与入口：`/grading/:offeringId`（`web/src/App.tsx:77`）；二级页，左侧无独立导航项，从 home/grading 的行进入；类内还可跳 `/offerings/:id/summary`。
- 主操作可达性：不能完整完成。逐份表里没有「未提交」学生行，也没有行内「复核 / 重试」，mockup 的三态与行内动作缺两项。

### 功能点对照

| # | mockup 要求（interface.html:NNN） | 实现情况 | 证据 | 级别 |
| --- | --- | --- | --- | --- |
| 1 | 面包屑「批改总览 / 第一次月考（整卷）」（930） | 有，回 `/grading` 可点 | `GradingDetailPage.tsx:347-350` | OK |
| 2 | 标题 + 副标题「高一 (3) 班 · 32 人 · 逐份提交」（931-932） | 有（班级名 + expected 人数） | `GradingDetailPage.tsx:357-365`；`locales.ts:138` | OK |
| 3 | 头部 tag「已完成 30 / 失败 1 / 待处理 1」（936-940） | 有 5 个进度 tag（完成/批改中/待处理/失败/未提交）+ 状态 + 类型，是超集 | `GradingDetailPage.tsx:371-392`；`locales.ts:140-144` | OK |
| 4 | 「重试失败项」（942） | 有，`progress.failed === 0` 时禁用，tooltip 说明只退 FAILED | `GradingDetailPage.tsx:403-411`；`locales.ts:148` | OK |
| 5 | 「立即派发」（943） | 有，附派发回执（runId / 首次派发 / 入队数）与失败提示 | `GradingDetailPage.tsx:412-419,462-476` | OK |
| 6 | 「进入复核」（944） | 有链接 `/review?offeringId=...`，但复核页完全不读该参数，队列不按作业过滤 | `GradingDetailPage.tsx:428-430`；`ReviewPage.tsx:40-61`（无 `useSearchParams`） | P2 |
| 7 | 表标题「逐份状态 · 默认按提交时间倒序」（950） | 一致 | `locales.ts:159-160`；`GradingDetailPage.tsx:48-51` | OK |
| 8 | 表列 学生（952） | 有（姓名 + 学号前 8 位） | `GradingDetailPage.tsx:178-189` | OK |
| 9 | 表列 尝试（952） | 有 | `GradingDetailPage.tsx:190-197` | OK |
| 10 | 表列 提交时间（952） | 有 | `GradingDetailPage.tsx:198-204` | OK |
| 11 | 表列 客观（953） | 有，未交显示「—」 | `GradingDetailPage.tsx:230-241` | OK |
| 12 | 表列 主观（953） | 有 | `GradingDetailPage.tsx:242-253` | OK |
| 13 | 表列 状态：完成 / 失败 / 未提交（955-971） | 有完成/失败/待处理/批改中/已拒收 + 未提交文案，但**没有未提交行**（见 #16） | `common.tsx:181-205` | OK（态本身） |
| 14 | 表列 操作：复核 + 审计（958-964） | 只有「拒收 + 审计」，没有「复核」 | `GradingDetailPage.tsx:281-343` | P1 |
| 15 | 失败行操作「重试」（968） | 只有全局「重试失败项」，失败行内没有「重试」 | `GradingDetailPage.tsx:286-306` | P1 |
| 16 | 未提交学生行「陈静 / 尝试 0 / — / 未提交」（969-972） | 不显示。`/submissions` 只返回已有提交，页面提示「未开始作答的学生不在逐份表中」 | `teachloop-sitting/.../OfferingEndpoint.java:198-217`；`GradingDetailPage.tsx:57`；`locales.ts:163` | P1 |
| 17 | 迟交「10-20 18:20 + 迟交 tag」（965） | 有，但做成独立「迟交」列（值 `1h 12m` 这类），不在提交时间后 | `GradingDetailPage.tsx:205-216`；`common.tsx:88-96` | P3 |
| 18 | 分页「共 32 条 · 当前第 1 页 / ‹ 1 2 › / 20-50 每页」（975-979） | 有「共 N 条 · 第 x/y 页」+ 上一页/下一页 + 20/50/100；无页码按钮 | `DataTable.tsx:104-110`；`common.tsx:236-262` | P3 |
| 19 | 页脚提示「审计不再占左侧一级导航——从这一行的审计进入」（980） | 行为一致（`Shell.tsx:18-26` 无审计项；行内 `AuditDrawer`），但这句提示文案未出现在页面 | `Shell.tsx:18-26`；`GradingDetailPage.tsx:299-305` | P3 |
| 20 | （mockup 无）拒收 + 理由 | 实现新增「拒收」行内表单，写 `submissions/{id}/reject` | `GradingDetailPage.tsx:146-176,290-337` | OK（超集） |
| 21 | （mockup 无）生成错题解析 / 汇总入口 | 实现新增两个按钮 | `GradingDetailPage.tsx:420-433` | OK（超集） |
| 22 | 过滤 chip 行（mockup 无，位于表上方） | 实现新增全部/完成/批改中/待处理/失败/已拒收 chip + 计数 + URL 提示 | `GradingDetailPage.tsx:487-510` | OK（超集） |

### 差异说明

- **未提交行（#16）**：mockup 的三态里「未提交」是最容易被忽略的一类，它是催交名单的来源；当前实现靠 `expected - submitted` 只给出数字和一句 hint，行级证据缺失，老师无法从这一屏定位到具体学生（班级汇总页另有未交名单，但需要换页）。
- **行内「复核」（#14）**：mockup 让老师从某一份提交直接进复核；实现必须先去全局复核工作台，而复核工作台又不按作业过滤（#6），两个缺口叠在一起会显著增加误操作。
- **失败行「重试」（#15）**：目前只能整批重试；mockup 允许单份重试，重试范围更可控。
- **进入复核不按作业过滤（#6）**：`/review?offeringId=` 被传但被忽略，复核页只有一个全局 PENDING 队列；从作业 A 进去可能复核到作业 B 的提交。
- 迟交列（#17）、分页形态（#18）、提示文案（#19）：均属展示层差异，功能等价。

---

## 4. review · 复核工作台

- 路由与入口：`/review`（`web/src/App.tsx:78`），左侧一级导航「复核」可见（`Shell.tsx:23`）；也可从 home/grading/detail 跳入。
- 主操作可达性：不能完整完成。可以做「原样通过 / 改后通过（保存修改）/ 弃用重批 / 发布」，但看不到客观分、错题、正文指标、分项分与当前总分，复核判断缺依据。

### 功能点对照

| # | mockup 要求（interface.html:NNN） | 实现情况 | 证据 | 级别 |
| --- | --- | --- | --- | --- |
| 1 | 面包屑「批改总览 / 复核工作台」（986） | 无面包屑、无返回入口 | `ReviewPage.tsx:136-151` | P3 |
| 2 | 副标题「逐份人工判断 · 30 份 · 12 份已处理 · AI 原样通过率 58%」（988） | 只统计当前待复核数（一张 StatCard），无已处理数、无原样通过率 | `ReviewPage.tsx:139-141` | P3 |
| 3 | 左栏「待复核」列表，含姓名（993-996） | 排队表用 `submissionId` 前 8 位代替姓名；`ReviewView` 里没有学生姓名 | `ReviewPage.tsx:129-134`；`web/src/api/types.ts:346-358` | P1 |
| 4 | 队列四态「待复核 / 已通过 / 已驳回 / 修改后通过」（993-996） | 后端队列只返回 `PENDING`，所以只有一种态；渲染也用同一个 amber tag | `teachloop-grading/.../ReviewEndpoint.java:110-115`；`ReviewPage.tsx:131` | P2 |
| 5 | 四态用不同颜色 tag（993-996） | 统一 `tag amber`，状态原文直出（`PENDING/APPROVED/...`） | `ReviewPage.tsx:131` | P3 |
| 6 | 提示「边界敏感的提交单独标出，批量通过默认跳过」（998） | 无边界敏感标记，无该提示；后端注明该能力尚未实现 | `ReviewEndpoint.java:252-258`；`ReviewPage.tsx:142-145` | P2 |
| 7 | 提交正文展示（1001-1011） | 有（`contentText`，`pre-wrap`） | `ReviewPage.tsx:165-167` | OK |
| 8 | 正文指标「词数 96 / 段落 3 / 在范围内」（1012） | 完全没有；`ReviewDetail` 不返回 metrics，后端只在判分 prompt 内部计算 | `ReviewEndpoint.java:102-107`；`teachloop-grading/.../SubjectiveGradeRequest.java:62-80` | P1 |
| 9 | 客观得分「118 / 120」+「错题 2 道」+ 错题号（1014-1020） | 显示「客观分 —」，并提示「当前端口暂不返回该分数」；无错题清单 | `ReviewPage.tsx:168-174`；`ReviewEndpoint.java:99-107,135` | P1 |
| 10 | 客观分标注「代码判分 · 提交时已出分」（1015） | 无该说明 | `ReviewPage.tsx:165-174` | P3 |
| 11 | AI 草稿「分项打分（内容要点 10/15、语言准确性 8/15）+ 分项评语」可编辑（1021-1030） | 只有一个 AI 主观总分 + 一个主观分输入框 + 评语/优点/建议三个文本域；没有分项结构与分项分 | `ReviewPage.tsx:195-210`；`teachloop-sitting/.../SittingLedgerImpl.java:62-88`（`CriterionScore` 未落库） | P1 |
| 12 | 「当前总分 118 + 18 = 136」实时更新（1031-1034） | 无总分展示，也没有把客观分/主观分相加的逻辑 | `ReviewPage.tsx:168-172` | P1 |
| 13 | 状态标记「状态：待复核 / 已通过」（1033） | 无当前提交复核状态的 tag（只有排队时间） | `ReviewPage.tsx:159-163` | P2 |
| 14 | 「原样通过」（1037） | 有 `APPROVED` 按钮 | `ReviewPage.tsx:214` | OK |
| 15 | 「修改后通过」（1038） | 没有独立按钮；靠「保存修改」把状态置 `EDITED`，再点「发布反馈」，语义分散 | `ReviewPage.tsx:213`；`Review` 域 `edit()` 置 EDITED（`domain/Review.java:100-106`） | P2 |
| 16 | 「驳回」（1039） | 有，但按钮文案是「弃用重批」，与 mockup 的「驳回」不同 | `ReviewPage.tsx:18,215` | P3 |
| 17 | 审计留痕说明「系统自动记录改没改分，教师不需要判断小改还是大改」（1035） | 无该说明；后端确实记了 before/after 与文本 diff | `ReviewPage.tsx:212-228`；`ReviewEndpoint.java:325-347` | P3 |
| 18 | 批量通过（mockup 只在提示里隐含） | 有「批量通过当前队列」按钮 + 结果回执（通过/跳过/总数） | `ReviewPage.tsx:113-127,143-145` | OK |
| 19 | 题项与作答表（mockup 无） | 实现新增题号/作答/标准答案/分值表 | `ReviewPage.tsx:176-192` | OK（超集） |
| 20 | 发布 / 撤回（mockup 无） | 实现新增发布与撤回按钮、发布状态说明 | `ReviewPage.tsx:219-227` | OK（超集） |
| 21 | 评分标准展示（mockup 无） | 实现以 `<details>` 折叠 `rubricMarkdown` | `ReviewPage.tsx:193` | OK（超集） |
| 22 | 审计入口（mockup 从行内进） | 复核页有「审计」按钮打开抽屉 | `ReviewPage.tsx:222` | OK |
| 23 | 队列范围（mockup 是某场作业的 30 份，988） | 全局 PENDING 队列，不按教师、不按作业过滤，也没有份数/班级抬头 | `ReviewEndpoint.java:110-115` | P2 |

### 差异说明

- **队列无姓名（#3）**：只显示 submissionId 前 8 位（如 `3f2a91c0`），老师无法判断「这排的是谁」；批量通过的语也失去意义。这是本屏第一个 P1。
- **正文指标（#8）**：mockup 用「词数 / 段落 / 是否在范围内」帮助判断篇幅分；后端在判分时已算过（`SubjectiveGradeRequest.from` 里 `countWords/countParagraphs`），但没有通过 `WorkItem`/`ReviewDetail` 暴露，前端无从显示。
- **客观分与错题（#9）**：`ReviewEndpoint.ReviewDetail` 把 `objectivePoints`/`subjectivePoints` 恒置 null 并注释「当前端口无法读取」，页面自己打了提示；复核的核心依据「客观分 + 错题号」因此拿不到。
- **分项打分（#11）**：AI 返回的 `Ports.CriterionScore`（含 name/points/maxPoints/comment）在 `SittingLedgerImpl.apply` 里没有被持久化，`reviews` 表也只有 `original_subjective` 一个总分字段；分项编辑无从实现。
- **当前总分（#12）**：依赖 #9 与 #11，客观分缺失导致总分无法计算。
- **四态队列（#4）**：`GET /api/grading/queue` 只查 `PENDING`，已通过/已驳回/修改后通过的提交一旦决策就从队列消失，mockup 的四色队列无法呈现。
- **边界敏感（#6）**：后端代码注释明写「AC-11.8 篇幅判定边界敏感项默认跳过当前无法实现」，实现侧也没有任何 UI 提示，老师不知道批量通过会通过哪些「临界」提交。
- **修改后通过（#15）**：做法能达成同一结果（保存修改 → EDITED），但按钮语义与 mockup 不一致；而且「保存修改」不会自动发布，容易以为已经通过。
- **队列范围（#23）**：队列既不过滤作业也不过教师（`ReviewEndpoint.java:110-115`），从某一作业「进入复核」与直接点左侧导航看到的完全一样，mockup 的「30 份 · 12 份已处理」这类上下文无法成立。
- **标签/说明（#1、#2、#5、#10、#13、#17）**：交互与文案细节偏差，不影响能否完成判断，但让页面比 mockup 少了解释与状态反馈。

---

## 5. summary · 班级汇总

- 路由与入口：`/offerings/:offeringId/summary`（`web/src/App.tsx:80`），二级页；入口在 home/grading 行内与 detail 头部。
- 主操作可达性：部分能。看数、看未交、导出都能做，但 mockup 的 4 张统计卡与「主观项均分」没有，PDF 导出对象也不对。

### 功能点对照

| # | mockup 要求（interface.html:NNN） | 实现情况 | 证据 | 级别 |
| --- | --- | --- | --- | --- |
| 1 | 面包屑「批改总览 / 班级汇总」（1048） | 有，且中间多一层作业链接 | `ClassSummaryPage.tsx:124-130` | OK |
| 2 | 标题 + 副标题「第一次月考（整卷）· 高一 (3) 班 · 与逐份反馈同源」（1050） | 副标题只有作业名 + 「按班分别统计，不混算」；无班级名（一页多班） | `ClassSummaryPage.tsx:131-138`；`locales.ts:409` | P3 |
| 3 | 统计「应交 / 实交 30 / 32」（1053） | 无汇总卡；只有「按班汇总」表里的 expected/submitted 列 | `ClassSummaryPage.tsx:54-79` | P2 |
| 4 | 统计「平均分（仅已提交）131.2」（1054） | 无汇总卡；只有按班平均分列 | `ClassSummaryPage.tsx:110-121`；`locales.ts:420` | P2 |
| 5 | 统计「最高 144.5」（1055） | 无。`ClassSummaryView` 里没有 max/min 字段 | `web/src/api/types.ts:133-145`；`OfferingEndpoint.java:268-380` | P2 |
| 6 | 统计「最低 108」（1056） | 无，同上 | `types.ts:133-145` | P2 |
| 7 | 各题正确率「按正确率升序」，行含「第 41 题 · 完形」（1061-1071） | 有「逐题正确率」表，但按「班级 × 题号」原序输出，未升序；行里只有题号，没有部分名 | `ClassSummaryPage.tsx:198-229`；`locales.ts:427-428` | P2 |
| 8 | 正确率以条形图展示（1064,1066,1068,1070） | 表格纯数字，无条形 | `ClassSummaryPage.tsx:204-227` | P3 |
| 9 | 主观项均分「内容要点 10/15、语言准确性 8/15」（1073-1076） | 完全没有。`ClassSummaryView` 无 criteria 均分字段 | `types.ts:133-145`；`OfferingEndpoint.java:268-380` | P1 |
| 10 | 未交名单「陈静 / 赵敏 未提交」（1077-1079） | 有，按班列出姓名 + 人数，空态「所有学生都已提交」 | `ClassSummaryPage.tsx:231-250`；`locales.ts:434-436` | OK |
| 11 | 提示「未交与失败不参与平均分计算」（1080） | 文案写成「未交与拒收不参与平均分」，且后端平均分把 FAILED 也算进分母 | `locales.ts:446`；`OfferingEndpoint.java:292-309`（只排 rejected，未排 grading_state=FAILED） | P2 |
| 12 | 导出 Markdown（1085） | 有，`/export/summary.md` | `ClassSummaryPage.tsx:255-257`；`ExportEndpoint.java:44-55` | OK |
| 13 | 导出 PDF（1086） | 链接到 `/export/feedback.pdf`（逐份反馈合集），不是班级汇总 PDF；后端也没有 `summary.pdf` | `ClassSummaryPage.tsx:269-271`；`ExportEndpoint.java:44-98` | P2 |
| 14 | 导出 Excel（1087） | 有 `/export/summary.xlsx` | `ClassSummaryPage.tsx:258-260`；`ExportEndpoint.java:57-69` | OK |
| 15 | （mockup 无）DOCX / 逐份反馈 ZIP | 实现新增 | `ClassSummaryPage.tsx:261-272` | OK（超集） |
| 16 | （mockup 无）分数分布表 | 实现新增按班分桶 | `ClassSummaryPage.tsx:173-196` | OK（超集） |
| 17 | 未发布成绩时的提示 | 有「未公布成绩，平均分显示为未发布」 | `ClassSummaryPage.tsx:145-147`；`locales.ts:422-423` | OK |

### 差异说明

- **四张统计卡（#3–#6）**：mockup 第一眼就给「应交/实交、平均分、最高、最低」，实现把这些信息拆进按班表，一页多班时老师要自己做加法；最高/最低后端根本没给。
- **主观项均分（#9）**：mockup 用它回答「这次写作普遍丢在语言还是内容」；实现没有这个能力，也没有替代视图。
- **正确率升序（#7）**：mockup 把最差的题排最前（23%、31%、34%…），实现是题号顺序，老师要自己扫一遍；同时也缺「部分」标签，看不出是完形还是听力。
- **失败参与平均分（#11）**：mockup 明确「失败不参与」，后端把「已提交且未拒收」全算进分母，判分失败的提交会以 0 主观分拉低平均；文案也写成了「拒收」。
- **PDF 导出（#13）**：点「导出 PDF」拿到的是一份份学生反馈，不是班级汇总；对「拿汇总去开会」的老师是错误交付物。

---

## 6. report · 报表查询

- 路由与入口：`/reports`（`web/src/App.tsx:79`），左侧一级导航「报表」可见（`Shell.tsx:24`）。
- 主操作可达性：不能。mockup 的主操作是「先筛选 → 勾选学生/题目 → 导出」，实现只有筛选（部分）+ Top N 错题，没有可勾选列表、没有已选统计、没有任何导出按钮。

### 功能点对照

| # | mockup 要求（interface.html:NNN） | 实现情况 | 证据 | 级别 |
| --- | --- | --- | --- | --- |
| 1 | 标题 + 副标题「跨班级 + 跨时间段 · 先筛选再勾选导出」（1093-1094） | 标题在，副标题改成「先用已接入的班级汇总做按作业 × 班级统计」——等于承认主操作没做 | `ReportsPage.tsx:220`；`locales.ts:450` | P0 |
| 2 | 筛选「作业 / 试卷」下拉（1099） | 有，`offering` 写 URL | `ReportsPage.tsx:230-240` | OK |
| 3 | 筛选「开始日期 / 结束日期」（1100-1101） | 无。后端 `wrong-items` 已支持 `from/to`，前端不传 | `ReportsPage.tsx:124-138`；`ReportEndpoint.java:117-136` | P2 |
| 4 | 筛选「分数段 最低–最高」（1102-1105） | 无；后端也没有分数段参数 | `ReportsPage.tsx:227-259`；`ReportEndpoint.java:117-136` | P1 |
| 5 | 筛选「提交状态：全部/已提交/未提交/批改失败」（1106） | 无；后端无该参数 | `ReportsPage.tsx:227-259`；`ReportEndpoint.java:117-136` | P1 |
| 6 | 筛选「Top N 错题 10/20/30」（1107） | 有，但移到 Top N 卡片内、选项是 5/10/20/50 | `ReportsPage.tsx:295-300` | P3 |
| 7 | 班级多选「默认全选」（1110-1115） | 有 chips；语义是「不选等于全部」（等价），hint 也写明了 | `ReportsPage.tsx:242-256`；`locales.ts:454-455` | P3 |
| 8 | 「查询」+「重置」按钮（1117） | 都没有；筛选即生效，无重置 | `ReportsPage.tsx:227-259` | P2 |
| 9 | Top N 表列 题号（1123） | 有 | `ReportsPage.tsx:141` | OK |
| 10 | Top N 表列 来源试卷（1123） | 无。`WrongItemView` 不含来源试卷 | `ReportsPage.tsx:140-147`；`ReportDtos.java:13-27` | P2 |
| 11 | Top N 表列 部分（1123） | 无。`WrongItemView` 不含 section/部分 | `ReportDtos.java:13-27` | P2 |
| 12 | Top N 表列 错误率（1123） | 有列，但口径是「正确率」且按正确率升序 | `ReportsPage.tsx:144`；`WrongItemsReportService.java:105` | P2 |
| 13 | Top N 表列 最常错选（1123） | 有，`最常错选 × 人数` | `ReportsPage.tsx:146` | OK |
| 14 | Top N 表列 解析（已生成/未生成）（1123,1130） | 无解析状态列，后端也不返回 | `ReportsPage.tsx:140-147`；`ReportDtos.java:13-27` | P2 |
| 15 | 提示「跨班聚合出的错题榜，就是你的讲评清单」（1133） | 无该提示；换成了「共 N 道有作答的客观题…」 | `ReportsPage.tsx:309`；`locales.ts:481` | P3 |
| 16 | 学生列表「104 条」+ 全选 + 列 姓名/班级/总分 + 滚动 + 分页（1137-1151） | 整块不存在。后端 `/api/reports/students` 只有 displayName/expected/submitted/averageScore | `ReportsPage.tsx:325-333`；`ReportEndpoint.java:90-96`；`AggregateReportService.java:21-24` | P0 |
| 17 | 题目列表「225 条」+ 全选 + 列 题号/错误率/最常错选 + 滚动（1152-1165） | 整块不存在（Top N 表不可勾选） | `ReportsPage.tsx:287-323` | P0 |
| 18 | 分页「共 104 条 / ‹ 1 2 3 › / 20-50」（1168-1172） | 只有主汇总表有分页；两个勾选列表不存在 | `ReportsPage.tsx:274-285` | P2 |
| 19 | 已选统计「已选 学生 1 人 · 题目 2 道」（1174-1175） | 无 | `ReportsPage.tsx:325-333` | P3 |
| 20 | 导出 Excel / PDF / 打包反馈 ZIP（1176-1178） | 三个按钮全部没有；页面反而写「报表导出…（未接入）」 | `ReportsPage.tsx:325-333`；`locales.ts:487` | P0 |
| 21 | （mockup 无）按作业 × 班级汇总表 | 实现新增主表（作业/班级/截止/应交/实交/迟交/按时率/平均分）+ 排序分页 | `ReportsPage.tsx:149-216,261-285` | OK（超集） |

### 差异说明

- **主链路缺失（#1、#16、#17、#20）**：mockup 这一屏的主任务就是「筛选 → 勾两张表 → 导出」，实现把这一屏做成了「按作业 × 班级汇总 + 一个 Top N 错题查询」，勾选与导出完全不存在，属于 P0：核心任务在该页做不完。
- **筛选不全（#4、#5）**：分数段与提交状态是 mockup 用来「只看 60 分以下」「只看未提交」的关键筛子；后端无参数（分数段）或前端不传（日期），老师只能看全量。
- **日期区间（#3）**：后端已支持 `from/to`，属于「能做未做」，成本最低的一条。
- **查询/重置（#8）**：mockup 有显式查询动作，实现是即时过滤；影响在于无法「先填完再查」，也没有一键回默认。
- **Top N 列（#10、#11、#14）**：缺来源试卷、部分、解析生成状态；「解析」列尤其关键，它告诉老师哪些错题已经有可讲评的解析。`WrongItemView` 只有 itemId/number/stem/questionType/points/answered/wrong/correctRate/topWrongAnswer/topWrongCount。
- **错误率 vs 正确率（#12）**：页面显示「正确率 23%」，mockup 显示「错误率 77%」；数字含义相反，老师凭直觉读数会读反。
- **导出接口其实存在（#20 附带）**：`/api/reports/offerings.xlsx`、`/api/reports/offerings.pdf` 已实现（`ReportEndpoint.java:46-72`），页面却笼统标记「报表导出 Excel / PDF / ZIP（未接入）」，与后端事实不符；但这两个接口是按 offering 聚合，仍覆盖不了「按勾选的学生 × 题目导出」的需求。
- 已选统计（#19）：依赖列表，列表都没有，自然也无从统计。

---

## 7. audit · 审计时间线

- 路由与入口：无独立路由（`web/src/App.tsx` 无 `/audit`），从 grading-detail 行内「审计」打开抽屉（`GradingDetailPage.tsx:299-305`，`AuditDrawer.tsx:7`）；复核页也有入口（`ReviewPage.tsx:222`）。
- 导航可见性：不在左侧一级导航，符合 mockup:980 的要求。
- 主操作可达性：不能完整完成。时间线能打开，但 mockup 的 7 类事件只覆盖 2 类（学生提交、教师修改后通过），且没有「谁」。

### 功能点对照

| # | mockup 要求（interface.html:NNN） | 实现情况 | 证据 | 级别 |
| --- | --- | --- | --- | --- |
| 1 | 面包屑「作业批改详情 / 审计」（1184） | 抽屉形态，无面包屑；抬头是「姓名 · 审计」 | `AuditDrawer.tsx:38-42`；`GradingDetailPage.tsx:533` | P3 |
| 2 | 标题「审计时间线」+ 副标题「李娜 · 第一次月考（整卷）· 最新在最上面」（1185-1186） | 有标题（默认「审计时间线」）；抬头显示 `targetType · targetId`，不显示作业名，也不说明排序 | `AuditDrawer.tsx:39-42` | P3 |
| 3 | 事件「发布」（1189） | `offering:publish` 写在 `target=offering`，按 submission 查不到 | `OfferingEndpoint.java:403`；`AuditDrawer.tsx:21` | P1 |
| 4 | 事件「教师修改后通过」含分差「内容要点 8 → 10，总分 134 → 136」（1190） | 有 `review:edited`，detail 含 before/after 主观分与三段文本 diff；但没有总分差，detail 以 `Map.toString()` 单行输出 | `ReviewEndpoint.java:194,305-327`；`AuditPortImpl.java:25`；`AuditDrawer.tsx:52` | P2 |
| 5 | 事件「错题解析生成完成 · 23 道题」（1191） | 生成解析的接口完全不写审计 | `ExplanationEndpoint.java:53-93`（无 `audit.record`） | P1 |
| 6 | 事件「AI 批改完成（主观）· 1.9 秒 · deepseek-flash · 校验通过」（1192） | 批改 worker 完成时不写审计 | `GradingWorker.java:55-70`（无 `audit.record`） | P1 |
| 7 | 事件「到期派发 · 截止 18:00 + 宽限 3 小时」（1193） | `offering:grading_dispatch` 写在 `target=offering` | `OfferingGradingDispatcher.java:132` | P1 |
| 8 | 事件「客观题自动判分 · 118/120，错题 41、47」（1194） | `offering:objective_recomputed` 写在 `target=offering`，且 detail 只有 paperId | `ObjectiveRecomputeImpl.java:88` | P1 |
| 9 | 事件「学生提交 · 第 2 次尝试 · 客观 75 题 + 写作 2 题」（1195） | `submission:submit` 有，detail 含 attempt 与迟交秒数；无题量明细 | `ExamSessionService.java:225-226` | OK（事件） |
| 10 | 「谁」——教师 / 学生 / 系统（1189-1195） | 完全没有渲染 actor（既没有姓名也没有 id） | `AuditDrawer.tsx:46-53`；`AuditEndpoint.java:18-31` | P1 |
| 11 | 事件名中文（发布 / 修改后通过 / …） | 直接显示英文 action（`review:edited`、`submission:submit`） | `AuditDrawer.tsx:49` | P2 |
| 12 | 时间线列表形态（`ul.timeline`，1188-1196） | 每行一个卡片，无时间线轴 | `AuditDrawer.tsx:46-54` | P3 |
| 13 | 「最新在最上面」（1186） | 一致，后端按 createdAt 倒序 | `AuditEndpoint.java:23` | OK |
| 14 | 入口在行内、不占一级导航（980、1184） | 一致 | `Shell.tsx:18-26`；`GradingDetailPage.tsx:299-305` | OK |

### 差异说明

- **5 类事件缺失（#3、#5、#7、#8、#6）**：mockup 的审计时间线是一条完整生命周期（提交 → 判分 → 到期派发 → AI 批改 → 解析 → 教师复核 → 发布）。当前按 submission 只能查到「提交」和「复核」两类：发布/派发/客观判分写在 offering 目标上，解析生成与 AI 批改完成根本没写审计。时间线因此断成两截，无法用来自证一次批改做对了。
- **「谁」缺失（#10）**：`AuditView` 只有 `actorId`，`AuditDrawer` 连 id 都没渲染；mockup 每行都以「教师 / 系统 / 学生」落款，这是审计可信度的核心字段。
- **事件名（#11）与细节（#4）**：action 以英文键直出，detail 是 Java `Map.toString()`（`{beforeSubjective=8, afterSubjective=10, ...}`），既不可读也缺 mockup 的「总分 134 → 136」。
- **形态（#1、#2、#12）**：mockup 是带面包屑的二级页；实现是 420px 抽屉，可以接受，但去掉了「哪份作业」「最新在最上」这类上下文。
- 备注（非对等项，供 Lead 参考）：`AuditEndpoint` 没有做角色/归属校验，任何登录用户可用任意 `targetType/targetId` 读审计（`AuditEndpoint.java:21-25`），不在本报告的严重度统计内。

---

## 8. 本区域阻塞项

| 问题 | 缺的是什么（接口 / 字段 / 组件） | 建议处理 |
| --- | --- | --- |
| 详情页没有未提交学生行 | `GET /api/offerings/{id}/submissions` 只返回已有 submission；未开始作答的学生不在返回里（`OfferingEndpoint.java:198-217`） | sitting 侧返回「学生 × 最新提交」全量行（未交行 state=未提交），或前端 merge `class-summary.unsubmitted` 生成占位行 |
| 复核页客观分恒为空 | `Ports.WorkItem` 无 objective 分；`ReviewDetail.objectivePoints` 硬编码 null（`ReviewEndpoint.java:99-107,135`） | 在 `SittingLedger.WorkItem` 增加 objectivePoints 与错题号列表，`ReviewDetail` 透出 |
| 复核页没有错题清单 | 同上，`WorkItem.answers()` 不含对错标记 | 端口增加 wrongNumbers（或 answers 增加 correct 标记），前端列出错题号 |
| 复核页没有正文指标 | `WorkItem` 无 wordCount/paragraphCount/boundarySensitive；这两个值只在 `SubjectiveGradeRequest.from` 内部算（`SubjectiveGradeRequest.java:65-79`） | 暴露 `submission_metrics`（词数/段落/边界敏感）到 `WorkItem` 与 `ReviewDetail` |
| 复核页没有分项打分与分项评语 | `Ports.CriterionScore`（name/points/maxPoints/comment）在 `SittingLedgerImpl.apply` 被丢弃（`SittingLedgerImpl.java:62-88`），`reviews` 表只有单个 `original_subjective` | 新增 criteria 落库表 + `ReviewDetail.criteria`，PUT 复核支持分项分 |
| 复核页没有「当前总分」 | 依赖客观分与分项分，两处都缺 | 随上面三项一起补，`ReviewDetail` 增加 objective/subjective/total |
| 批量通过没有「跳过边界敏感」 | `submission_metrics.boundary_sensitive` 未通过端口暴露（`ReviewEndpoint.java:252-258` 注释） | sitting 端口补字段后，在 bulk-approve 里跳过并计入 skipped |
| 复核队列没有学生姓名、只有 PENDING | `ReviewView` 无 studentName（`types.ts:346-358`）；`/grading/queue` 只查 PENDING（`ReviewEndpoint.java:110-115`） | 队列接口支持 `status`、`offeringId` 参数并返回 studentName / paperTitle |
| 「进入复核」不按作业过滤 | 复核页无 `useSearchParams`，`offeringId` 被忽略（`ReviewPage.tsx:40-61`） | 前端接参数 + 后端队列支持 offeringId 过滤 |
| 班级汇总缺最高/最低 | `ClassSummaryView` 无 max/min | `class-summary` 聚合增加 maxScore/minScore |
| 班级汇总缺主观项均分 | `ClassSummaryView` 无 criteria 均分 | 由 grading 侧按 offering 聚合 rubric 均分并透出 |
| 班级汇总缺「汇总 PDF」 | 只有 `summary.md/xlsx/docx` 与 `feedback.pdf`（`ExportEndpoint.java:44-98`） | 新增 `summary.pdf`（可复用 PdfFontProbe），前端换链接 |
| 班级汇总平均分把失败算进分母 | `class-summary` 只排除 rejected，未排除 `grading_state=FAILED`（`OfferingEndpoint.java:292-309`） | 平均分与 submitted 的统计口径明确排除 FAILED，或补一列 failed 并在文案里说明 |
| 报表缺分数段/提交状态筛选 | `/api/reports/wrong-items` 无 minScore/maxScore/status 参数（`ReportEndpoint.java:117-136`） | 扩展 wrong-items 参数；学生列表另加 submission 级筛选接口 |
| 报表 Top N 缺来源试卷/部分/解析状态 | `WrongItemView` 只有 itemId/number/stem/questionType/points/answered/wrong/correctRate/topWrongAnswer/topWrongCount（`ReportDtos.java:13-27`） | SQL join 出 paperTitle / sectionTitle，并 join `item_explanations` 给 hasExplanation |
| 报表学生列表字段不够 | `StudentRow` 只有 displayName/expected/submitted/averageScore（`AggregateReportService.java:21-24`），无班级、无单次总分 | 扩字段：className、latestTotal；或新开 submission 级列表接口 |
| 报表勾选导出（Excel/PDF/ZIP）整段缺失 | 现有导出只有 `/api/reports/offerings.xlsx|pdf`（按 offering 聚合）与 `/api/offerings/{id}/export/feedback.zip`（整场），没有「选中的学生 × 选中的题目」导出接口 | 新增 selection 导出接口（学生集 + 题目集 + 格式），ZIP 打包逐份反馈 |
| 审计时间线只覆盖 2/7 类事件 | `offering:publish`/`offering:grading_dispatch`/`offering:objective_recomputed` 目标为 offering（`OfferingEndpoint.java:403`、`OfferingGradingDispatcher.java:132`、`ObjectiveRecomputeImpl.java:88`）；解析生成与 AI 批改完成无审计（`ExplanationEndpoint.java:53-93`、`GradingWorker.java:55-70`） | 事件同时写 submission 目标（或审计查询支持按 submission 反查 offering 事件）；补 `explanation:generated`、`grading:ai_done` 审计 |
| 审计行没有「谁」 | `AuditView` 只有 actorId，无显示名（`AuditEndpoint.java:18-31`） | 接口返回 actorName/actorRole，或前端按 id 查 directory |
| 审计细节不可读 | detail 存 `Map.toString()`（`AuditPortImpl.java:25`），抽屉原样单行输出（`AuditDrawer.tsx:52`） | detail 存 JSON 字符串，前端按 action 渲染「8 → 10、总分 134 → 136」这类结构化摘要 |
| 首页/总览/复核的数据范围不含教师归属 | `/api/offerings` 与 `/grading/queue` 对教师不按归属过滤（`OfferingEndpoint.java:144-152`、`ReviewEndpoint.java:110-115`） | 两个查询加 teacherId（或按 offering→班级→教师）过滤；否则「我名下全部班级」无法兑现 |

---

## 附：未计分的原型占位项

- mockup 里的 `data-v` 排序属性、`.proto` 提示条、主题切换条属于原型工具痕迹，不作为对等项。
- 首页/grading/report 的空态、错误态、加载态在实现里统一走 `States.tsx`，与本区域无关，不再逐条列。
