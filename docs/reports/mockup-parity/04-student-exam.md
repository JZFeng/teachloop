# 学生考试与反馈区域（exambrief / exam / assignment / feeds / feedback / overview-s）对等性审计

审计基线：`specs/002-v2-platform/mockups/interface.html:1201-1439`（6 个 `.screen`）。
实现基线：`web/src/exam/**`、`web/src/student/**`、`web/src/App.tsx`、`web/src/api/types.ts`。
后端基线：`teachloop-sitting`（`ExamSessionEndpoint` / `OfferingEndpoint`）、`teachloop-paper`、`teachloop-shared`（`Ports`）。

判定口径（本文件统一执行）：
- mockup 有该行/控件/能力，实现里**完全没有渲染** → P1（若后端无字段/接口，同时标「阻塞」）。
- mockup 有，实现里**在但被简化**（少列、少分母、少入口、少页脚） → P2。
- 文案、配色、默认值、位置差异 → P3。

## 0. 汇总

| 屏 | 路由 | 结论 | P0 | P1 | P2 | P3 |
| --- | --- | --- | --- | --- | --- | --- |
| exambrief · 考前须知 | `/exams/:offeringId` | 部分缺失 | 0 | 0 | 1 | 4 |
| exam · 考试作答 | `/exams/:offeringId/live` | 部分缺失 | 0 | 3 | 2 | 2 |
| assignment · 作业提交 | `/assignments/:offeringId` | 部分缺失 | 0 | 4 | 1 | 4 |
| feeds · 我的反馈列表 | `/feedback` | 部分缺失 | 0 | 1 | 2 | 5 |
| feedback · 反馈详情 | `/feedback/:offeringId` | 部分缺失 | 0 | 5 | 1 | 6 |
| overview-s · 班级概览（学生视图） | 无独立路由 | 缺失 | 1 | 0 | 0 | 0 |

合计：P0 1、P1 13、P2 7、P3 21。其中 14 条标注了「阻塞：缺接口/缺字段」，同源合并后对应第 7 节的 13 项。

**overview-s 的判定**：不是并入 feedback 第 ④ 段就算落地的独立屏。mockup 这一屏的主体是一张**逐人表（姓名/是否提交/尝试/提交时间/状态/分数）**加「公布他人分数」开关；实现里没有任何路由或导航入口承接它（`web/src/App.tsx:64-92` 全量路由无此路径；`web/src/app/Shell.tsx:25-29` 学生导航只有首页/班级/我的反馈），只在 feedback ④ 里落了「应交/实交/全班均分」三个聚合数（`web/src/student/FeedbackDetailPage.tsx:157-171`），逐人表与开关规则整块缺失。后端亦无学生端概览接口（见第 6 节证据）。

---

## 1. exambrief · 考前须知

- 路由与入口：`/exams/:offeringId`（`web/src/App.tsx:88`，`<Shell><ExamEntryPage/></Shell>`）。导航可见性：学生端二级页，从学生首页卡片点入（`web/src/student/HomePage.tsx:78`）或交卷后的「再考一次」；侧栏无直达项。
- 主操作可达性：能。`canStart` 时渲染「开始答题」（`ExamEntryPage.tsx:213-219`），点击 `POST /start` 或 `/attempts` 后跳 `/live`（`ExamEntryPage.tsx:48-65`）。

### 功能点对照

| # | mockup 要求（interface.html:NNN） | 实现情况 | 证据 | 级别 |
| --- | --- | --- | --- | --- |
| 1 | 屏标题「考前须知」（1202） | `PageHeader` 用 `exam.entryTitle`=「考前须知」 | `ExamEntryPage.tsx:86`；`exam/locales.ts:3` | OK |
| 2 | 副标题「点『开始答题』后才开始计时。开始之前可以随时离开。」（1203） | 副标题=「点「开始答题」后才开始计时；到点由服务端自动交卷。」；「开始之前可以随时离开」挪到开始按钮上方 `startHint` | `ExamEntryPage.tsx:87`、`:215`；`exam/locales.ts:4`、`:35` | OK |
| 3 | 试卷名 + 参与班级「三年级 1、2、3 班」+「终结性」（1205） | 只有试卷名 + 固定「终结性考试」；无班级名 | `ExamEntryPage.tsx:96-101`；`OfferingView.classes` 仅 `classId`，无班级名 `teachloop-sitting/.../SittingDtos.java:26-31` | P3（阻塞：缺字段） |
| 4 | 时长「90 分钟（个人计时：从你点开始答题起算）」（1208） | 「{{minutes}} 分钟 · 个人计时：从你点开始答题起算」；fixed_window 换另一句 | `ExamEntryPage.tsx:105-111`；`exam/locales.ts:11-13` | OK |
| 5 | 题量与总分「77 题 · 150 分（客观 120 + 主观 30）」（1209） | 只有「{{items}} 题 · {{points}} 分」，**无客观/主观拆分** | `ExamEntryPage.tsx:112-115`；`entry` 无拆分字段 `SittingDtos.java:57-61` | P2（阻塞：缺字段） |
| 6 | 可开始时间「10-20 10:00 起，10-24 22:00 前必须开始」（1210） | `windowValue` 逐字对应 | `ExamEntryPage.tsx:116-123`；`exam/locales.ts:17` | OK |
| 7 | 一旦开始「按完整 90 分钟走，可以跨过截止」（1211） | `onceStartedValue` 逐字对应 | `ExamEntryPage.tsx:124-127`；`exam/locales.ts:20` | OK |
| 8 | 到点「系统自动交卷，以最后一次自动保存为准；不需要你点提交」（1212） | `autoSubmitValue` 逐字对应 | `ExamEntryPage.tsx:128-131`；`exam/locales.ts:22` | OK |
| 9 | 作答方式「试卷本体就是表单：选项就地选，完形/语法空格就地填，写作就地写」（1213） | 文案写成「选项就地选，填空就地填，写作就地写」，但「完形/语法空格就地填」在作答页并未实现（见第 2 节 #23、#24） | `exam/locales.ts:24`；`ExamPaper.tsx:89-108` | P3 |
| 10 | 提示：刷新、换设备、断网重连都不重置时长，剩余时间以服务端为准（1216-1217） | 服务端固定注意事项第 2 条逐字覆盖；渲染为列表项 | `ExamSessionEndpoint.java:36-40`；`ExamEntryPage.tsx:141-148` | OK |
| 11 | 提示：已答题在目录里变蓝色实心，标记题琥珀色（1218） | 作答页确实这样配色，但**考前须知页没有这条图例** | `exam.css:130-137`（配色在）；`grep -n "蓝色\|琥珀\|legend" web/src` 无命中 | P3 |
| 12 | 「开始答题」主按钮（1220） | `exam.start`，`starting` 态禁用 | `ExamEntryPage.tsx:213-219`；`exam/locales.ts:33-34` | OK |
| 13 | 「返回」按钮（1221） | 页头「返回首页」跳 `/`（不是返回上一屏） | `ExamEntryPage.tsx:89-92`；`exam/locales.ts:40` | P3 |
| 14 | —（mockup 无）状态卡片 | 额外实现：已交卷/自动交卷/作答中+剩余/被拒收+理由，均带接口数据 | `ExamEntryPage.tsx:151-235` | OK（超出 mockup，不计差异） |
| 15 | —（mockup 无）注意事项列表 | 额外实现：4 条含「考试进行中不显示标准答案与解析」（对应 AC-21.9） | `ExamEntryPage.tsx:141-148`；`ExamSessionEndpoint.java:40` | OK（超出 mockup） |

### 差异说明

- **#5 客观/主观拆分缺**：mockup 的「客观 120 + 主观 30」是学生判断这场有无写作题的入口。快照里 `ItemSpec` 带 `questionType` 与 `points`（`Ports.java:19-22`），后端可在 `/entry` 一并算出，前端无需多请求。
- **#3 班级名缺**：学生确认「这场是不是我班的」只能靠标题；后端 `OfferingClass` 只暴露 `classId`。
- **#11 图例缺**：mockup 用一句话把「蓝色=已答、琥珀=标记」教给考生，实现在考前须知页没有任何图例，学生进作答页才知道颜色含义。
- **#9 文案超前于实现**：考前须知承诺「完形/语法空格就地填」，而作答页是逐题卡片（详见第 2 节），属于承诺与实现不一致。
- **#13 返回**：mockup 的「返回」是回到上一屏（列表），实现回首页，多一次跳转。

---

## 2. exam · 考试作答（专注模式 + 目录树 + 计时）

- 路由与入口：`/exams/:offeringId/live`（`web/src/App.tsx:89`，`<ExamFocusLayout>`，在 `ShellLayout` **之外**，因此左侧全局导航整块不渲染）。导航可见性：仅专注模式，无侧栏。
- 主操作可达性：能。作答→自动保存→提交（含未答检查）→结果页全链路可用。

### 功能点对照

| # | mockup 要求（interface.html:NNN） | 实现情况 | 证据 | 级别 |
| --- | --- | --- | --- | --- |
| 1 | 专注模式：隐藏全局导航，只保留试卷与目录（1226-1227） | 路由挂在 `ExamFocusLayout` 下，不在 `Shell` 里；`ExamFocusLayout` 只包一层 div | `App.tsx:89`；`app/Shell.tsx:90-92` | OK |
| 2 | 目录树头部：试卷标题（1229） | `ExamTree` 头部 `title`，超长省略 + hover 全名 | `ExamTree.tsx:41-43` | OK |
| 3 | 目录树头部：「已答 0 / 77 · 标记 0」（1230） | `treeProgress` 同一句式，实时刷新 | `ExamTree.tsx:43`；`exam/locales.ts:46` | OK |
| 4 | 部分节点：部分名 + 名下完成度 + **「每小题 1.5 分」分值提示**（mockup `PARTS.hint`，interface.html:1541-1550） | 有部分名、折叠箭头、`已答 d/n`；**没有每题分值提示** | `ExamTree.tsx:52-63` | P3 |
| 5 | 题目节点四通道状态：已答=蓝色实心 / 标记=琥珀 / 当前=描边（1218、ac.test 颜色注释） | `.done` 实心蓝、`.marked` 琥珀、`.current` 描边、小圆点 | `exam.css:123-137` | OK |
| 6 | 点题号跳转到该题并聚焦输入（mockup `jumpTo`） | `jumpTo` 滚到节点 → 420ms 后 `focus` 到可编辑控件 → 关抽屉 | `ExamLivePage.tsx:297-312` | OK |
| 7 | 部分折叠/展开；当前题自动展开父级（mockup `foldPart`/`setActive`） | `onTogglePart` + `current` 变化自动 `expanded.add(key)` | `ExamLivePage.tsx:152-158`、`:329-336` | OK |
| 8 | 窄屏目录折叠为抽屉，由显示进度的按钮唤出（mockup `.treebtn`/`toggleTree`） | `exam-tree-toggle`（文案带 `done/total`）+ 背板点击关闭 | `ExamLivePage.tsx:431-438`、`:489-491` | OK |
| 9 | 顶栏剩余计时常驻（1237） | `remaining` tag，`fmtDuration` 插值，服务端秒数为准 | `ExamLivePage.tsx:440-446`、`:161-167` | OK |
| 10 | ≤5 分钟警示色并保留显示（AC-21.17） | `warn = remaining <= 300`，转红；到点换「时间到，已自动交卷」 | `ExamLivePage.tsx:424`、`:440-446`；`exam.css:142-143` | OK |
| 11 | 顶栏「已答 0 / 77」（1238） | `exam.progress` | `ExamLivePage.tsx:447` | OK |
| 12 | 顶栏「保存草稿」按钮（1239） | **没有该按钮**：只有 1.5s debounce 自动保存 + 「保存中…/已保存 HH:MM:SS」文字 | `ExamLivePage.tsx:30`、`:449-451`、`:249-263`；`grep -n "saveDraft" web/src/exam` 仅作业页用 | P2 |
| 13 | 顶栏「标记本题」（1241） | 顶栏 + 每题脚部各一个 `mark/unmark` 按钮 | `ExamLivePage.tsx:452-459`；`ExamPaper.tsx:154-165` | OK |
| 14 | 顶栏「提交试卷」（1242） | 顶栏 + 页面底部各一个，打开未答检查弹窗 | `ExamLivePage.tsx:460-462`、`:504-511` | OK |
| 15 | 顶栏「退出考试」回我的反馈（1243） | 「退出考试」先尽力保存再跳 `/`（首页，不是反馈列表） | `ExamLivePage.tsx:364-373`、`:463` | OK |
| 16 | 未答确认弹窗：标题带未答数+标记数、按部分分组列题号、点题号跳转并关弹窗、仍可「仍然提交」（1905-1930） | 全部具备：`checkTitle`/`checkMarked`/`mgroup`+`mchip`/`onJump` 关弹窗/`submitAnyway` | `SubmitCheckDialog.tsx:51-107` | OK |
| 17 | 弹窗：已标记题号单独一列（mockup 只内联 chip 标 `.mk`） | 额外单列「已标记的题」 | `SubmitCheckDialog.tsx:85-96` | OK（更强） |
| 18 | 全部作答时的弹窗：继续检查 / 确认提交（1909-1914） | `checkAllDone` + `keepChecking` / `confirmSubmit` | `SubmitCheckDialog.tsx:40`、`:100-107` | OK |
| 19 | 试卷按部分顺序、部分标题 + 满分（1609、1617、1627、1639） | `part.title` + 该部分分值合计 | `ExamPaper.tsx:34-39` | OK |
| 20 | 单选/多选控件（就地选、多选可勾多个）（1567-1576） | `qopt` 按钮，多选 `toggleLetter` 切换，多选提示文案 | `ExamPaper.tsx:110-130`；`examUtils.ts:93-98` | OK |
| 21 | 填空就地填（1579-1595） | 非选择题渲染 `blank-input`，占位符带题号 | `ExamPaper.tsx:132-142` | OK |
| 22 | 写作题就地写 + 实时字数「已写 N / 要求 80 词」（1642-1651） | `EssayAnswer` textarea，字数+建议区间，超限变红 | `ExamPaper.tsx:144-152`、`:220-244` | OK |
| 23 | 写作文本域高度按字数要求算（AC-21.12；mockup `rowsFor`） | `measure()` 按容器实宽算每行字数，夹 6 行~70vh，ResizeObserver 重算 | `ExamPaper.tsx:184-218` | OK |
| 24 | 完形/语法填空：**同一段文字内多个空就地填**，段落是滚动锚点（1579-1603、AC-21.11） | 每题渲染为独立卡片，只显示该题 `item.stem`；没有段落容器、没有段内多空、没有段落锚点 | `ExamPaper.tsx:89-108`；`ExamLivePage.tsx:266-295`（锚点是题号） | P1（阻塞：缺字段，见第 7 节 #3） |
| 25 | 阅读语篇正文（A–D 四篇）+ 七选五正文与 A–G 选项区（1618-1624） | 试卷没有任何「语篇」块，只有逐题卡片 | 同上；`Ports.ItemSpec` 无 passage 字段 `Ports.java:19-22`；`ObjectiveItem` 无材料列 `ObjectiveItem.java:12-48` | P1（阻塞：缺字段） |
| 26 | 自动保存节流 | `AUTOSAVE_DELAY=1500`，答案/标记变化 debounce 后 `PUT /answers` | `ExamLivePage.tsx:30`、`:249-263`、`:223-235` | OK |
| 27 | 断网重试（mockup hint 提「断网重连」；spec 自动保存要可重试） | 保存失败只 `setSaveError` 显示；**没有重试队列/退避**，要等下一次编辑才再触发（`dirtyRef` 不再变化则不发请求） | `ExamLivePage.tsx:237-246`、`:249-263` | P2 |
| 28 | 刷新/换设备不重置时长（1217） | 时长以 `/session` 剩余秒数为准，本地只插值；刷新后 `remaining` 从服务端取 | `ExamLivePage.tsx:112`、`:161-167`；`ExamSessionEndpoint.java:241-248` | OK |
| 29 | 刷新/换设备的作答内容恢复（断点续答，AC-21.7；mockup 只承诺时长） | 只从 **localStorage** 恢复草稿；服务端不返回已存答案。换设备/清缓存后内存 `answers={}`，任意一次编辑都会 `PUT /answers`，而服务端 `writeAnswers` 先 `deleteBySubmissionId` 再按请求重写 → **之前服务端已存答案被清空** | 恢复：`ExamLivePage.tsx:114-128`；覆写：`ExamSessionService.java:415-443`（`answers.deleteBySubmissionId` 在 418 行） | P1（数据丢失风险；阻塞：缺接口） |
| 30 | 到点自动交卷、以最后一次自动保存为准、不需要点提交（1212） | 本地归零即 `locked` 禁止输入；随后 3s 轮询 `/session`，服务端确认后切结果页；文案两种态 | `ExamLivePage.tsx:170-199`；`exam/locales.ts:68-70` | OK |
| 31 | 交卷后「客观题立即出分、主观待批改、错题清单」（mockup 无此屏，spec AC-21.8） | 结果页有客观/主观/尝试次数/迟交；错题清单明确写「未接入」 | `ExamResult.tsx:37-64`；`exam/locales.ts:96` | OK（超出 mockup；错题清单为已知缺口） |
| 32 | 题型覆盖：单选/多选/完形/语法填空/写作/文件与视频上传类 | 领域枚举只有 `SINGLE_CHOICE, MULTIPLE_CHOICE, FILL_BLANK, ESSAY`，无上传题型；mockup 试卷也无上传题，故不作为本屏差异 | `QuestionType.java:3`；`ExamPaper.tsx:79-152` | OK（不适用） |
| 33 | 计时器配色：mockup 用琥珀 tag 常驻（1237） | 实现默认灰底、≤5 分钟转红（更接近 AC-21.17，但与 mockup 观感不同） | `exam.css:141-143` | P3 |

### 差异说明

- **#24/#25 段落与语篇不能渲染（最重）**：mockup 的完形、语法填空、七选五都是「一段文字里嵌多个空」，阅读是「先一篇 A–D 语篇再 4 题」。实现把每一题渲染成一张独立卡片，只用 `item.stem`（`ExamPaper.tsx:99-108`），数据模型里也没有语篇/材料字段（`Ports.ItemSpec`、`ObjectiveItem`）。后果：如果导入时没把语篇塞进每题 stem，第 21–55 题会变成「只有题干没有材料」的裸题，学生无法作答。**待确认**：`teachloop-paper/.../importing/PaperTextParser.java` 的导入格式是否把语篇并入 `stem`（当前后端无独立字段，只能靠约定）。
- **#29 换设备会覆盖服务端作答（数据风险）**：`ExamLivePage` 明确注释「服务端不返回已保存答案，只能以本机草稿恢复」（`:119-120`）；而 `writeAnswers` 每次都是「全删再写」（`ExamSessionService.java:418`）。因此换设备继续作答 = 用空答卷覆盖旧答卷。mockup 只承诺「时长不重置」，但 spec AC-21.7「支持保存草稿与断点续答」要求跨设备可用，此处不满足。
- **#12 保存草稿按钮缺**：自动保存覆盖了能力，但 mockup 的显式按钮是学生「我现在就存一下」的心理锚点；顶栏只有小字「已保存」，且失败时只有红条提示。
- **#27 断网无重试**：保存失败后 `dirtyRef` 仍为 true，但 effect 依赖 `[answers, marked, ...]` 不再变化 → 不会自动重发；学生断网期间的作答在恢复网络后若不继续输入就不会上送，到点会按旧快照定稿。
- **#33 计时器配色**由灰直接跳红，mockup 是常驻琥珀，属观感差异。

---

## 3. assignment · 作业提交（富文本）

- 路由与入口：`/assignments/:offeringId`（`web/src/App.tsx:81`，在 `ShellLayout` 内）。导航可见性：学生端二级页，从首页作业卡片点入（`web/src/student/HomePage.tsx:78`）。
- 主操作可达性：能（先「开始作答」→ 有草稿后出现提交区 → 保存草稿 / 提交 / 上传文件交卷）。

### 功能点对照

| # | mockup 要求（interface.html:NNN） | 实现情况 | 证据 | 级别 |
| --- | --- | --- | --- | --- |
| 1 | 屏标题=作业名（1252） | `entry.paperTitle` | `AssignmentPage.tsx:209` | OK |
| 2 | 副标题：学生视图 · 截止 · 宽限 · 剩余次数（1253） | 页头副标题=「作业 · 截止 …」；截止/宽限/剩余次数改为 meta 一行标签 | `AssignmentPage.tsx:210`、`:227-241` | OK |
| 3 | 作业说明正文（写作题目要求，120–180 词）（1256-1257） | 只有后端 `notice`（固定句「本次为作业：没有倒计时…」），**没有作业题干**；下方 `entry.notes` 渲染的其实是**考试计时注意事项**（服务端对作业也返回同一组 NOTES） | `AssignmentPage.tsx:216-226`；`ExamSessionEndpoint.java:104-108`（作业也返回 `NOTES`）、`:417-419` | P1（阻塞：缺字段） |
| 4 | 说明下的三条要求清单（1258-1262） | 无（同上，`notes` 内容是考试规则） | 同上 | P1（与 #3 同源，同一条阻塞） |
| 5 | 评分标准：两个评分项 + 各自分值 + 一句说明（1265-1271） | 整块是「评分标准未接入：后端未提供 rubric 接口」 | `AssignmentPage.tsx:244-247`；`exam/locales.ts:104` | P1（阻塞：缺接口，见第 7 节 #6） |
| 6 | 评分标准副标题「教师会按这两项评」（1266） | 无 | `AssignmentPage.tsx:245` | P3 |
| 7 | 编辑器默认是富文本、带已写内容（1273-1284） | 默认 `mode='text'`（纯 textarea），要手动点「富文本」chip 才切到 contentEditable | `AssignmentPage.tsx:63`、`:329-333` | P3 |
| 8 | 工具栏：粗体 / 斜体 / 下划线 / 有序列表 / 无序列表 / 小标题（1276-1280） | **没有工具栏**：contentEditable 是裸 div，无任何格式化按钮，也没有 `execCommand`/编辑器库 | `AssignmentPage.tsx:344-354`；`grep -rn "toolbar\|execCommand\|bold\|italic\|underline" web/src` 仅命中 `exam/locales.ts:258` 文案 | P1 |
| 9 | 编辑器脚注「基础格式：粗体 / 斜体 / 下划线 / 列表 / 小标题」（1285） | 富文本模式下才有 `richHint`（措辞不同：「支持加粗、斜体、下划线、有序/无序列表」），纯文本模式无格式说明 | `AssignmentPage.tsx:343`；`exam/locales.ts:118` | P3 |
| 10 | 词数统计「词数 96」（1285） | `countWrittenWords`：英文按词、中文按字，随输入实时刷新 | `AssignmentPage.tsx:202`、`:366`、`:369`；`examUtils.ts:157-163` | OK |
| 11 | 「保存草稿」按钮（1288） | `saveDraft` → `PUT /answers`，成功后记录服务端时间 | `AssignmentPage.tsx:124-145`、`:377-380` | OK |
| 12 | 「提交」按钮（1289） | `submit` → `POST /submissions`，带迟交/待批改标签 | `AssignmentPage.tsx:147-171`、`:382-385` | OK |
| 13 | 最后保存时间「最后保存于 10-20 21:16」（1291） | 保存过显示「已保存 {{datetime}}」，未保存显示「草稿同时保存在本机，刷新后可恢复正文。」 | `AssignmentPage.tsx:387-389`；`exam/locales.ts:64`、`:133` | P3（未保存态文案不同，缺「最后保存于」字样） |
| 14 | 剩余次数（1253） | 「剩余尝试 {{left}} / {{max}} 次」 | `AssignmentPage.tsx:235-239` | OK |
| 15 | 截止与宽限 3 小时（1253） | 截止时间 + 「宽限期 N 小时只决定批改启动时间，不影响提交」 | `AssignmentPage.tsx:229-234`；`exam/locales.ts:107` | OK |
| 16 | 迟交提示（隐含：宽限期内可交） | 过期后显示琥珀「已过截止时间，提交会标记为迟交」；提交后按 `lateBySeconds` 打「迟交」 | `AssignmentPage.tsx:240`、`:281`、`:307` | OK |
| 17 | 附件上传（mockup 未画；spec AC-22.2 要求） | 有「文件提交」模式：`.pdf/.docx/.txt/.md` ≤100MB，`POST /submissions/file`；已提交可下载附件 | `AssignmentPage.tsx:334-340`、`:173-196`、`:314-318`；`ExamSessionEndpoint.java:284-296` | OK（超出 mockup） |
| 18 | 视频上传（spec AC-22.2 三形态之一；mockup 未画） | 前端**无入口**；后端已有 `POST /submissions/video`（mp4/mov/webm、ffprobe 校验） | `AssignmentPage.tsx:63`（mode 只有 text/file/rich）；`ExamSessionEndpoint.java:299-346` | P2（实现侧缺入口，后端已就绪） |
| 19 | —（mockup 无）已提交态 | 分数/客观/主观/待批改/迟交/自动交卷/下载附件/再交一次/查看反馈 | `AssignmentPage.tsx:289-324` | OK（超出 mockup） |

### 差异说明

- **#3/#4 作业说明整块是错的**：这是本屏最容易被误判为「样式问题」的地方——`AssignmentPage` 确实渲染了 `entry.notes`，但服务端对作业也返回考试用的 `NOTES`（`ExamSessionEndpoint.java:104-108`，`NOTES` 常量在 `:36-40`），所以学生在「作业说明」下看到的是「开始答题后开始计时…考试窗口…」这类规则，而**题目要求（120–180 词的写作任务）一个字都没有**。根因是 `Offering` 表没有作业说明列（`Offering.java:15-73` 只有 paper_snapshot/rubric_markdown 等）。
- **#5 评分标准**：后端其实有两个可用来源——`offerings.rubric_markdown`（`Offering.java:63-64`）和 paper 的 `section_criteria`（name/max_points/description，`SectionCriteria.java:13-31`），但 `/entry` 与 `/api/offerings/{id}` 都不返回（`SittingDtos.java:26-31`、`:57-61`），且 criteria 未进 `PaperSnapshot`（`Ports.java:47-48` 只有 `rubricMarkdown`）。前端只能写「未接入」。
- **#8 工具栏缺**：`contentEditable` 存在、`richHint` 承诺支持加粗/斜体/下划线/列表，但页面上没有任何按钮。学生无法产出 mockup 里那种带 `<strong>` 的富文本；服务端 `RichTextSanitizer` 已具备消毒能力（`teachloop-sitting/.../service/RichTextSanitizer.java`），缺的只是前端工具栏。
- **#7 默认形态**：mockup 打开即是富文本编辑器；实现打开是纯文本框，需点 chip 切换，且切换后 `richHtml` 与 `content` 是两条数据线（`:349-353`），在两种模式间来回切会丢格式。
- **#18 视频**：spec 与后端都在位，前端只做了 file，属「后端已就绪但入口缺」。

---

## 4. feeds · 我的反馈列表

- 路由与入口：`/feedback`（`web/src/App.tsx:82`，在 `ShellLayout` 内）。导航可见性：**学生侧栏直达**（`app/Shell.tsx:28`），学生首页也有「我的反馈」按钮（`student/HomePage.tsx:59-61`）。
- 主操作可达性：能。卡片/表格可切、点卡片进详情、分页可用。

### 功能点对照

| # | mockup 要求（interface.html:NNN） | 实现情况 | 证据 | 级别 |
| --- | --- | --- | --- | --- |
| 1 | 标题「我的反馈」（1298） | `feedbackTitle` | `FeedbackListPage.tsx:139`；`student/locales.ts:20` | OK |
| 2 | 副标题「按发布时间倒序，最新的在最上面」（1299） | 副标题写成「默认按提交时间倒序；卡片/表格视图的选择会被记住」 | `student/locales.ts:21` | P3 |
| 3 | 卡片/表格视图切换按钮（1302-1305） | 页头两个 `chip`，`on` 态互斥 | `FeedbackListPage.tsx:142-160` | OK |
| 4 | 切换后状态保持（任务要求） | `localStorage['teachloop.feedbackView']`，读时兜底 `card` | `FeedbackListPage.tsx:19`、`:27-33`、`:59-65` | OK |
| 5 | 卡片：作业名（1312） | `row.offering.paperTitle` | `FeedbackListPage.tsx:186` | OK |
| 6 | 卡片：发布时间 + 「第 2 次尝试」（1313） | 显示 `提交时间 · 第 N 次尝试`（提交时间替代发布时间） | `FeedbackListPage.tsx:187-189` | P3 |
| 7 | 卡片：总分/满分（1314） | `totalScore / entry.totalPoints` | `FeedbackListPage.tsx:191-196` | OK |
| 8 | 卡片：客观/主观分标签（1316） | 「客观 x · 主观 y」一个灰标签 | `FeedbackListPage.tsx:201-206` | OK |
| 9 | 卡片：「错题 2」标签（1316） | **无**：`AttemptView` 没有错题数，列表也不拉 `/my-feedback` | `FeedbackListPage.tsx:198-207`；`types.ts:264-278` | P1（阻塞：缺字段） |
| 10 | 卡片：作业型显示「无客观题」（1323） | 无此分支，客观分为 0 时会直接显示「客观 0」 | `FeedbackListPage.tsx:202-205` | P3 |
| 11 | 表格列「作业」（1336） | 列 `作业 / 考试` | `FeedbackListPage.tsx:84-89` | OK |
| 12 | 表格列「发布时间」（1336） | 列是「提交时间」（`submittedAt`），无发布时间字段 | `FeedbackListPage.tsx:101-107` | P2（阻塞：缺字段） |
| 13 | 表格列「尝试」（1336） | **缺该列**（卡片里有，表格没有） | `FeedbackListPage.tsx:83-129` | P2 |
| 14 | 表格列「总分」（1336） | `136 / 150` | `FeedbackListPage.tsx:108-118` | OK |
| 15 | 表格列「查看」按钮（1339） | 无按钮，整行可点进详情 | `FeedbackListPage.tsx:239`；`DataTable.tsx:90-93` | P3 |
| 16 | （mockup 无）类型列 | 多一列「类型」= 考试/作业标签 | `FeedbackListPage.tsx:90-100` | OK（超出 mockup） |
| 17 | 排序：默认最新在上（1299）；表头可排序（1336 `data-sort`） | 卡片固定按 `submittedAt` 倒序；表格表头点击排序，默认 `submittedAt desc` | `FeedbackListPage.tsx:42`、`:73`；`DataTable.tsx:60-64`、`:74-84` | OK |
| 18 | 分页：共 N 条 + 页码 + 「20 / 页」（1348-1352） | 只有「共 N 条 · 第 p/q 页」+ 上一页/下一页；**无页码按钮、无每页条数选择**（固定 10） | `FeedbackListPage.tsx:44`、`:212-224`；`DataTable.tsx:104-110` | P3 |
| 19 | 空态 | `feedbackEmpty`「还没有已提交的反馈。」 | `FeedbackListPage.tsx:163-164` | OK |

### 差异说明

- **#9 错题数标签缺**：mockup 卡片用「错题 2」把「有没有要看的错题」放在列表层；实现要进详情才知道。`/my-feedback` 已经返回 `wrongItems`（`ExamSessionEndpoint.java:160-174`），但要按场次再请求 N 次，列表页没做。
- **#12/#13 表格两列**：`发布时间`在整个学生侧 DTO 里都不存在（`AttemptView` 只有 `submittedAt`，`types.ts:264-278`；`MyFeedbackView` 也没有 `publishedAt`，`SittingDtos.java:130-133`），`尝试`列则是纯 UI 漏做（数据 `attempt.attemptNo` 就在手里）。
- **#18 分页**：mockup 有页码与 20/页；`DataTable` 是通用组件，只有上一页/下一页（`DataTable.tsx:104-110`），本屏 `pageSize=10` 写死。
- **#10 无客观题**：作业类场次若客观分为 0，学生看到「客观 0」会误以为做错，mockup 用「无客观题」区分「没有这类题」与「这类题得 0 分」。

---

## 5. feedback · 反馈详情（四段式）

- 路由与入口：`/feedback/:offeringId`（`web/src/App.tsx:83`，在 `ShellLayout` 内）。导航可见性：学生端二级页，从列表卡片/行点入。
- 主操作可达性：能。`GET /api/offerings/{id}/my-feedback` 一次取齐四段数据（`FeedbackDetailPage.tsx:27`）。

### 功能点对照

| # | mockup 要求（interface.html:NNN） | 实现情况 | 证据 | 级别 |
| --- | --- | --- | --- | --- |
| 1 | 面包屑「我的反馈 / 试卷名」（1357） | 无面包屑，页头右侧「返回列表 / 返回首页」按钮 | `FeedbackDetailPage.tsx:47-60` | P3 |
| 2 | 标题 + 副标题「第 2 次尝试 · 发布 10-21 09:13」（1358-1359） | 标题=试卷名；副标题=「第 N 次尝试 · 提交于 <时间>」（无发布时间） | `FeedbackDetailPage.tsx:48-51` | P3 |
| 3 | ① 总分 136 / 150（1361-1364） | `score-big` 总分/满分 | `FeedbackDetailPage.tsx:68-73` | OK |
| 4 | ① 客观 118 / 120、主观 18 / 30（**带各自满分**，1366-1367） | 只有「客观 118」「主观 18」，**没有分项满分分母** | `FeedbackDetailPage.tsx:74-87`；DTO 无 `objectiveMax/subjectiveMax`（`SittingDtos.java:126-133`） | P2（阻塞：缺字段） |
| 5 | ① 段（mockup 无）：批改状态行 | 额外：已批改/待批改/自动交卷/迟交/被拒收/未发布提示 | `FeedbackDetailPage.tsx:89-102` | OK（超出 mockup） |
| 6 | ② 标题「我错的题与解析 <small>先看这里</small>」（1372-1373） | 标题「② 我错的题与解析」，无副标题 | `FeedbackDetailPage.tsx:107` | P3 |
| 7 | ② 错题题号 chips + 「共 2 道」（1374-1377） | 无 chips、无「共 N 道」；改为有序列表逐题展开 | `FeedbackDetailPage.tsx:113-133` | P3 |
| 8 | ② 每题头部「第 41 题 · 完形填空 + 正确率 23%」（1379） | 有「第 N 题 · x 分 · 题干」，**没有题型、没有班级正确率** | `FeedbackDetailPage.tsx:116-119`；`FeedbackWrongItem` 无 `correctRate`（`SittingDtos.java:117-119`） | P1（阻塞：缺字段） |
| 9 | ② 每题解析正文（1380-1381） | `explanation` 有则 `pre-wrap` 显示；无则明示「解析未生成：逐题 AI 解析属于 T115」 | `FeedbackDetailPage.tsx:126-130`；`exam` 侧同款文案 `student/locales.ts:63` | OK |
| 10 | ② （mockup 无）你的作答 / 正确答案 | 额外：`你的作答`/`正确答案` 对照 + 快照来源说明 | `FeedbackDetailPage.tsx:120-125`、`:134` | OK（超出 mockup） |
| 11 | ③ 标题「写作评语」（1391-1392） | 标题「③ 主观评语」（措辞不同） | `FeedbackDetailPage.tsx:141`；`student/locales.ts:69` | P3 |
| 12 | ③ 分项评分：内容要点 10/15、语言准确性 8/15（1393-1396） | **整块缺失**：只渲染一段总评 `review.comment`，没有按 rubric 项的分值与评语 | `FeedbackDetailPage.tsx:143-153`；DTO 无按项分（`SittingDtos.java:121-123`） | P1（阻塞：缺字段） |
| 13 | ③ 「优点」「建议」两行（1397-1398） | **未渲染**：`strengths`/`suggestions` 在后端与前端类型里都有，JSX 里完全没用 | `SittingDtos.java:121-123`；`types.ts:318-324`；`grep -rn "strengths" web/src --include=*.tsx` 无命中 | P1（数据已有，纯前端漏做） |
| 14 | ④ 标题「全班情况 <small>教师开启后可见</small>」（1401-1402） | 标题「④ 全班概览」，无「教师开启后可见」副标题 | `FeedbackDetailPage.tsx:157-158` | P3 |
| 15 | ④ 班级平均 131.2 / 150（1403） | 「全班均分」，`classAverage==null` 显示 `—` | `FeedbackDetailPage.tsx:162-165` | OK |
| 16 | ④ 「我的排名」「教师未公布」（1404） | **整行缺失**：无 rank 字段也无该行 | `FeedbackDetailPage.tsx:157-171`；`FeedbackOverview` 无 rank（`SittingDtos.java:126-128`） | P1（阻塞：缺字段） |
| 17 | ④ 「这道题的班级正确率 23%（第 41 题）」（1405） | **整行缺失**；教师端有 `itemAccuracy`，`/my-feedback` 不返回 | `FeedbackDetailPage.tsx:157-171`；`types.ts:126-131`（教师 `ClassSummaryView.itemAccuracy`） | P1（阻塞：缺字段） |
| 18 | ④ 教师开关关闭时的表现（1418、1436；`togglePub`：别人显示「已批改」，自己始终可见） | 关闭时 `peersVisible=false` → 显示「未开放：教师关闭了本场的全班概览」，均分不给；自己的分数（①段）始终可见 | `FeedbackDetailPage.tsx:167-169`、`:68-73`；`ExamSessionEndpoint.java:202`、`:209-210` | OK |
| 19 | ④ 隐私说明「看不到同学的正文、附件与评语；你永远能看到自己的分数」（1406） | `peerPrivacy` 逐字对应 | `FeedbackDetailPage.tsx:170`；`student/locales.ts:77` | OK |
| 20 | —（mockup 无）未发布态 | 未发布时 ①②③④ 各段都显示 `feedbackMessage`（「反馈尚未发布」） | `FeedbackDetailPage.tsx:96`、`:108-109`、`:142`；`ExamSessionEndpoint.java:212-213` | OK（超出 mockup） |

### 差异说明

- **四段顺序**：① 总分 → ② 错题 → ③ 评语 → ④ 全班，与 mockup 完全一致（`FeedbackDetailPage.tsx:66`/`:106`/`:140`/`:157`）。未发布 / 无提交两态都用 `Empty` 或 inline-note 兜住，没有编造数据。
- **#13 优点/建议是「白捡」的缺口**：后端 `Ports.ReviewNote` 已带 `strengths`/`suggestions`，`myFeedback` 已透传（`ExamSessionEndpoint.java:175-179`），前端类型也声明了（`types.ts:321-322`），只差两行 JSX。这是本区域成本最低、收益最高的修复点。
- **#12/#16/#17 三个「有数据源但学生拿不到」**：分项评分要 `section_criteria`（有表 `SectionCriteria.java:13-31`，未进快照 `Ports.java:47-48`）；排名要对全班的分数排序（后端 `myFeedback` 已经在遍历全班最新提交算均分，`ExamSessionEndpoint.java:189-207`，加一个名次是顺手的事）；单题正确率教师端已算好（`OfferingEndpoint.java:268` `/class-summary` 的 `itemAccuracy`），但该接口对学生 403（见第 6 节）。
- **#4 分项满分**：`136/150` 有了，`118`、`18` 没有分母，学生算不出「客观丢了几分」。DTO 加 `objectiveMax/subjectiveMax` 即可，快照里 `questionType`+`points` 足够算。
- **#8 正确率**：mockup 的「正确率 23%」是学生判断「这题是不是全班都错」的唯一线索，缺失后 ② 段只剩「我错了 + 解析」。
- 另注：`student/wrongMissing`（「错题与解析未接入…」）与 `commentError`/`loadingComment`/`peerMissing` 四个 key 已定义但**从未使用**（`student/locales.ts:58`、`:70`、`:73`、`:76`；`grep -rn "wrongMissing'"` 无命中），属文案死代码，不是功能缺失。

---

## 6. overview-s · 班级概览（学生视图）

- 路由与入口：**没有**。`web/src/App.tsx:64-92` 全部路由中无 `overview`/`class-summary` 相关学生路由；`web/src/app/Shell.tsx:25-29` 学生导航只有「我的学习 / 班级 / 我的反馈」。全目录检索：`grep -rn "overview\|概览" web/src --include=*.tsx --include=*.ts` 仅命中 feedback ④（`FeedbackDetailPage.tsx:10`、`:156-171`）与教师文案（`teacher/locales.ts:589`），无任何独立页面组件。
- 主操作可达性：不能（无页面）。

### 判定结论

**不是独立页面，而是部分并入 feedback 第 ④ 段**；并入的只有三个聚合数，mockup 这一屏的四类内容落点如下：

| mockup 内容（interface.html:NNN） | 落点 | 证据 | 级别 |
| --- | --- | --- | --- |
| 屏标题「班级概览」+ 副标题「只能看到概览，看不到任何内容」（1412-1413） | 无 | 无路由/组件 | P0 |
| 卡片标题=试卷名（1415） | ④ 段所在页的页头标题 | `FeedbackDetailPage.tsx:48` | P0（并入） |
| 「公布他人分数」开关（1417-1418） | 无学生侧开关；由教师 `showPeerOverview` 决定并只读呈现 | `ExamSessionEndpoint.java:202`；`FeedbackDetailPage.tsx:167-169` | P0 |
| 表格列「姓名」+ 「我」标记（1421、1423） | **无逐人表** | — | P0 |
| 表格列「是否提交」（1421） | 只有总数「实交人数」 | `FeedbackDetailPage.tsx:161` | P0（聚合替代逐人） |
| 表格列「尝试」（1421） | 无 | — | P0 |
| 表格列「提交时间」（1421） | 无 | — | P0 |
| 表格列「状态」（已发布/批改中）（1421、1426） | 无 | — | P0 |
| 表格列「分数」（1421） | 只有「全班均分」 | `FeedbackDetailPage.tsx:162-165` | P0（聚合替代逐人） |
| 开关关闭时别人显示「已批改」、自己始终可见（1436、`togglePub` 1940-1951） | 自己可见=①段分数；「别人显示已批改」无从表达（没有逐人表） | `FeedbackDetailPage.tsx:68-73`、`:167-169` | P0 |
| 分页「共 5 条 / 20 页」（1430-1433） | 无 | — | P0 |
| 教师开关控制可见性的规则（1418） | 规则在，粒度只到「全班能不能看均分」 | `ExamSessionEndpoint.java:202` | P0 |

学生实际能看到的「全班」信息只有：应交人数、实交人数、全班均分（`FeedbackDetailPage.tsx:159-166`）。

> 计数口径：下面这张表逐行列出 mockup 的每个内容项，级别一律标 P0；整屏缺失在汇总里只按 **1 条 P0** 计，避免同一根因重复计数。

### 后端是否真的没有学生端概览接口（grep 证据）

是，确实没有。三条检索结果：

1. 学生侧唯一带班级聚合的接口是 `/my-feedback`，其 `FeedbackOverview` 只有聚合字段：
   `teachloop-sitting/.../api/SittingDtos.java:126-128`
   `public record FeedbackOverview(long expected, long submitted, BigDecimal myScore, BigDecimal classAverage, boolean peersVisible)`
   → 没有逐人列表，没有姓名/尝试/提交时间/状态，没有排名，没有单题正确率。
2. 唯一能出逐人表的接口是教师端 `/class-summary`，且**显式拒绝学生**：
   `teachloop-sitting/.../api/OfferingEndpoint.java:268`（`@GetMapping("/{id}/class-summary")`）
   `teachloop-sitting/.../api/OfferingEndpoint.java:270-272`
   ```java
   if (me.is("STUDENT")) {
       throw ApiException.forbidden("学生不能查看班级汇总");
   }
   ```
3. 全班提交表 `/submissions` 同样对学生 403（`OfferingEndpoint.java:201-205`，`"学生不能查看全班提交表"`），学生只剩 `/my-submissions`（`ExamSessionEndpoint.java:356-377`，只返回自己）。

因此 `student/locales.ts:76` 的 `peerMissing` 文案（「全班概览未接入：后端未提供学生端概览接口」）在事实上是准确的，但该 key 目前**没有任何地方渲染**（`grep -rn "peerMissing" web/src` 只命中 locales 自身），也就是说连「这一屏不存在」都没有告诉学生。

---

## 7. 本区域阻塞项

| # | 问题 | 缺的是什么（接口 / 字段） | 建议处理 |
| --- | --- | --- | --- |
| 1 | 考前须知无「客观 x + 主观 y」拆分 | 字段：`EntryView` 缺 `objectivePoints/subjectivePoints`（`SittingDtos.java:57-61`）；快照 `ItemSpec` 有 `questionType`+`points`（`Ports.java:19-22`）可算 | `/entry` 增加两个字段，前端一行渲染 |
| 2 | 考前须知无参与班级名 | 字段：`OfferingClassView` 只有 `classId`（`SittingDtos.java:26-31`），无班级名 | `view(o)` 组装时经 ClassCatalog 端口补 `className` |
| 3 | 完形/语法/阅读：段落与语篇无法渲染 | 字段：`ItemSpec`（`Ports.java:19-22`）与 `ObjectiveItem`（`ObjectiveItem.java:12-48`）均无 passage/材料列；`ExamPaperItemView` 也不带（`SittingDtos.java:104-106`） | 数据模型加「材料块」（paper_material 或 item.passage），快照与 `/paper` 一并返回；作答页按段落渲染、按段落做滚动锚点 |
| 4 | 换设备继续作答会覆盖服务端已存答案 | 接口：`/session`、`/paper` 不返回已保存答案（`ExamSessionEndpoint.java:114-128`、`:241-248`）；`writeAnswers` 全删再写（`ExamSessionService.java:415-443`） | `/session` 或新增 `/draft` 返回已保存答案，前端以服务端为准合并；`writeAnswers` 改成 upsert 增量写 |
| 5 | 反馈列表无「错题 N」 | 字段：`AttemptView` 无错题数（`types.ts:264-278`、`SittingDtos.AttemptView`） | 列表页一次性拉 `/my-feedback`（或 `AttemptView` 加 `wrongCount`） |
| 6 | 反馈列表/详情无「发布时间」 | 字段：`MyFeedbackView`/`AttemptView` 无 `publishedAt`（`SittingDtos.java:130-133`、`:121-123` 有 review.createdAt，但发布时刻未透出） | `myFeedback` 把 `note.publishedAt()`（已在 `ExamSessionEndpoint.java:151-154` 取到）透出 |
| 7 | 反馈 ② 无单题正确率 | 字段：`FeedbackWrongItem` 无 `correctRate`（`SittingDtos.java:117-119`）；教师端 `ItemAccuracy` 已有（`types.ts:126-131`） | 复用同一统计，在 `/my-feedback` 里带上（仅自己错的题） |
| 8 | 反馈 ③ 无分项评分与满分 | 字段：`FeedbackReviewNote` 无按 rubric 项的分值（`SittingDtos.java:121-123`）；`section_criteria`（`SectionCriteria.java:13-31`）未进 `PaperSnapshot`（`Ports.java:47-48` 只有 rubricMarkdown） | 快照带 criteria 定义；review 带 per-criterion 分数 |
| 9 | 反馈 ④ 无「我的排名」 | 字段：`FeedbackOverview` 无 `rank/totalRanked`（`SittingDtos.java:126-128`） | `myFeedback` 已遍历全班最新提交算均分（`ExamSessionEndpoint.java:189-207`），顺带排序取名次；开关关闭时按 mockup 返回 null |
| 10 | 反馈 ④ 无「这道题的班级正确率」 | 接口/字段：`/class-summary` 的 `itemAccuracy` 对学生 403（`OfferingEndpoint.java:268-272`） | 在 `/my-feedback` 的 `wrongItems` 上挂 `correctRate`（只算自己错的题，不泄露他人） |
| 11 | 作业说明正文整块缺失，且渲染的是考试注意事项 | 字段：`Offering` 无 `instructions`（`Offering.java:15-73`）；`NOTES` 对作业也返回（`ExamSessionEndpoint.java:104-108`） | offerings 加 instructions 列（或从快照的 essay 题面取）；`notice()/notes` 按 `kind` 分支，作业不要回考试 NOTES |
| 12 | 作业评分标准显示「未接入」 | 接口：`offerings.rubric_markdown` 有列（`Offering.java:63-64`）但 `/entry`、`/api/offerings/{id}` 都不返回（`SittingDtos.java:57-61`、`:26-31`） | 两个 DTO 加 `rubricMarkdown`；若要 mockup 的「两项+分值」，需把 criteria 进快照 |
| 13 | overview-s 整屏无接口 | 接口：学生端概览接口不存在；`/class-summary` 学生 403（`OfferingEndpoint.java:270-272`） | 要么新增学生端 `/overview`（只返回姓名+是否提交+尝试+提交时间+状态+分数，且尊重 `showPeerOverview`/`publishScores` 脱敏），要么产品上明确删掉 mockup 这一屏并在 spec 里回写 |

---

## 8. 明确忽略的 mockup 内容

| 内容 | 位置 | 忽略原因 |
| --- | --- | --- |
| 主题切换条 `.theme-bar` / 三套主题变量 | `interface.html:1443-1466` | 原型演示控件，不是产品功能 |
| 计时器点击演示 `timerDemo`（4:59 → 0:03 → 自动交卷） | `interface.html:1237`、`1779-1785` | 原型演示入口，`title` 已注明「演示」，不算功能缺失 |
| 试卷里的「示例数据 · 非本卷题目」多选块 | `interface.html:1654-1659` | 原型用来说明「题型是每题属性」，真实实现按后端题型渲染即可 |
| overview-s 的「公布他人分数」开关被标注「这里供你预览两种效果」 | `interface.html:1418` | 开关本身是教师侧控件，学生侧只承担「两种效果」的展示规则，已在第 6 节按规则核对 |
| 卡片里的小圆点 `.dot`（1312） | `interface.html:1312` | 纯装饰，无行为与数据含义 |

---

## 9. 复核方式

- 前后端字段对齐：逐个比对 `web/src/api/types.ts`（`EntryView:192-211`、`AttemptView:264-278`、`MyFeedback:334-344`、`ExamPaperItem:227-235`）与 `teachloop-sitting/.../SittingDtos.java:26-133`，确认每个「缺字段」结论都指到了具体 record。
- 关键词三种写法检索（中文/英文/i18n key）已执行：`保存草稿`/`saveDraft`、`蓝色`/`amber`/`legend`、`toolbar`/`execCommand`/`bold`/`italic`/`underline`、`overview`/`概览`/`peerTitle`、`strengths`/`suggestions`、`wrongMissing`、`publishedAt`。未命中处均已在本文件注明检索词。
- 未做浏览器实跑，以上结论均为静态代码/接口核对结果。
