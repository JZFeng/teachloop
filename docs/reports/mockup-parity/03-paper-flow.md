# 试卷录入（paper-flow）原型 ←→ 实现 对等性审计（2026-10-25）

审计范围：
- 原型 `docs/mock/paper-flow-2026-10-25/index.html`（四屏 + 七种状态，数据由 `build-data.py` 从真实卷抽取）
- 实现 `web/src/teacher/PaperWizardPage.tsx`（重写）、`PapersPage.tsx`、`App.tsx`、`web/src/teacher/paperFlow.ts`
- 后端 `teachloop-paper/src/main/java/com/teachloop/paper/api/PaperEndpoint.java`、`V065__paper_meta_and_content.sql`
- 需求 `specs/002-v2-platform/spec.md` AC-4.9、AC-4.24–4.29

口径：原型是**需求工具**，不是像素基准。判「对等」看的是「这一屏要回答的问题有没有答案」，
不是控件长相；颜色/间距差异不计（沿用仓库 `docs/reports/mockup-parity/README.md` 的协议）。

## 0. 汇总

| 屏 | 原型 | 实现 | 结论 | P0 | P1 | P2 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| 试卷库 | 只剩「新建试卷」 | 只剩「新建试卷」，`/papers/import` 路由已删 | 对齐 | 0 | 0 | 0 |
| ① 基本信息 | 标题 / 时长 / 命题人 / 审题人 / 目标总分 | 同五个字段；命题人默认当前教师；生成后回填空白 | 对齐（1 处交互差异，见 2.3） | 0 | 1 | 0 |
| ② 内容 | 编辑器 + 文件 + 唯一按钮 + 进度 + 只读预览 + 七状态 | 同；确认框是 antd 弹窗（原型是页内卡片） | 对齐 | 0 | 0 | 1 |
| ③ 评分项 | 一题一 tab + 编辑器 + 合计预览 + 无范文 + 空态 | 同；合计预览复用 `RubricPreview` | 对齐 | 0 | 0 | 0 |
| ④ 预览发布 | 四个统计 + 评分项列「无/N/A」+ 两按钮 + 四状态 | 同；另显示旧卷遗留评分项金额 | 对齐（多一处提示） | 0 | 0 | 0 |

P0 = 屏不存在或主流程走不通；P1 = 需求点缺失；P2 = 表现/次要交互差异。

## 1. 逐条对照

| # | 原型要求 | 实现情况 | 证据 | 级别 |
| :--- | :--- | :--- | :--- | :--- |
| 1.1 | 试卷库只有一个主按钮「新建试卷」 | 有；「导入考卷」按钮与页面均已删除 | `PapersPage.tsx:112-118`、`App.tsx`（无 `papers/import` 路由） | OK |
| 1.2 | 第一步五个字段 | 有，且命题人预填当前登录教师显示名 | `PaperWizardPage.tsx` step 1 卡片 | OK |
| 1.3 | 目标总分只做闸门、不覆盖自动合计 | 有；合计仍由 `recalcTotal()` 算，发布时比对（容差 0.001） | `PaperEndpoint.java::publish`、`Paper.java::targetTotalPoints` | OK |
| 1.4 | 原文头部（命题人/审题人/总分）只回填空白字段 | 有；`PaperHeaderMeta` 解析 → `ImportResult.metaHints` → `applyMetaHints` | `PaperHeaderMeta.java`、`paperFlow.ts::applyMetaHints` | OK |
| 2.1 | 第 2 步只有编辑器 + 文件选择 + 一个「生成考题」 | 有；`VditorMarkdown` rich/src 互切 | step 2 卡片、`VditorMarkdown.tsx:19,127-147` | OK |
| 2.2 | 上传与文本走同一个按钮 | 有；`.md/.txt` 读进编辑器，`.docx/.xlsx` 上传后回填 | `readMarkdownFile` + `api.postForm('/import?replace=true')` | OK |
| 2.3 | 确认框列出「会丢的三类界面内编辑」+ 将删除数量 | 有：rubric 已填题数 / 错题解析状态非 none 题数 / 旧 criteria 条数；三项与题项数都写进文案 | step 2 `confirmDialog` 内容 | OK |
| 2.4 | 进度是不确定型（阶段 + 秒数，无百分比） | 有；四阶段 + 已用秒数，无百分比 | step 2 进度卡片 | OK |
| 2.5 | 只读预览表七列 + 「N 题 · 合计 · 缺答案」+ 断档提醒 | 有；断档只提醒不阻断 | step 2 预览表、`paperFlow.ts::previewSummary` | OK |
| 2.6 | 解析失败保留编辑器内容并给行号级错误 | 有；错误来自后端 detail（行号），编辑器不动 | step 2 错误面板 | OK |
| 2.7 | 已发布卷：编辑器与生成禁用 | 有；编辑器 `contenteditable=false`，生成按钮禁用；服务端 409（confirm 也不放行） | `importable()`、`locked` 分支、`VditorMarkdown` 的 disabled 修正 | OK |
| 3.1 | 一题一 tab（第 N 题 · X 分） | 有；tab 由 ESSAY 题项生成，按题号升序 | `paperFlow.ts::essayTabs` | OK |
| 3.2 | 每 tab 一个编辑器 + 合计对照本题满分 | 有；`RubricPreview` 复用 | step 3 `RubricPreview` | OK |
| 3.3 | 没有参考范文输入 | 无该输入（列为数据保留） | step 3 无 reference 字段 | OK |
| 3.4 | 没有写作题显示空态 | 有 | `noEssays` 文案 | OK |
| 3.5 | 保存只发 `rubricMarkdown` | 有；`PUT /items/{number}` 只带该字段 | `saveRubric()` | OK |
| 4.1 | 评分项列没有 `—`：无 rubric 显示「无 / N/A」 | 有；`criteriaNone` zh「无」/ en「N/A」 | `paperFlow.ts::rubricBlocks` | OK |
| 4.2 | 两道写作题按题号分块、块间空一行 | 有；CSS `.rubric-block` 留 10px 间距 | `teacher.css` | OK |
| 4.3 | 四个统计：题项合计 / 实际合计 / 目标总分 / 缺答案 | 有；旧卷有遗留 criteria 时额外多一行提示 | step 4 统计卡片 | OK |
| 4.4 | 目标不一致 / 缺答案分别拦发布并说明原因 | 有（前端置灰 + 提示；后端 400 复校验） | `publishCheck()`、`PaperEndpoint::publish` | OK |
| 4.5 | 保留「同步并校验」「发布试卷」 | 有；同步只取详情 + 比合计 + 给消息 | `sync()` | OK |

## 2. 偏差（都不影响主流程）

1. **P2 · 确认框形态**：原型画成页内卡片，实现用 `useConfirm()`（antd Modal）。
   理由：仓库已有弹层栈与 Esc 关闭约定，页内卡片要重复一套焦点管理。
2. **P1 · 标题与时长在创建后不可改**：原型第一步始终可编辑；实现里一旦建过卷（生成考题后）
   两个输入框禁用，并显示「后端未提供试卷更新接口」。这是**既有后端能力缺口**（`PUT /meta`
   按 AC-4.26④ 只改三列），不是本次回归。要实现「创建后改标题」需另立需求。
3. **P2 · 预览表样式**：原型用斑马/紧凑行；实现沿用 `.wizard-list` 表格，滚动区高度 420px。
4. **P2 · 状态切换器**：原型有「演示状态」按钮（空白/生成中/失败…），实现按真实状态渲染，
   没有调试开关（这是原型工具与产品页的合理差别）。

## 3. 原型里有、实现里没做的（明确记为不做）

- 原型第 2 步的「重新生成（替换重建）」单独按钮：实现里合成到唯一的「生成考题」上（同一动作，
  已有内容时先弹确认）。原型保留它是为了单独演示确认框。
- 原型第 3 步的 rubric「示例内容」：实现里来自服务端（`items.rubricMarkdown`），空就是空。

## 4. 结论

主流程四屏全部可走通，P0 = 0；唯一 P1 是**既有**的「创建后改标题」缺口，与本次重构无关，
已在 §2.2 写清。原型的七种状态里，实现覆盖了空白 / 生成中 / 成功 / 缺答案 / 失败 / 锁定六种；
「确认重建」在实现里是弹窗，不是独立状态。
