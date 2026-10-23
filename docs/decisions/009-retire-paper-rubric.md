# 决策 009 — 试卷级 rubric 退役（界面移除，考试不再读取）

日期：2026-10-23　状态：已实现　相关：T370、D-29、AC-27.17 / AC-27.20、FR-086、
决策 008（默认 80/20）

## 问题

用户在确认「默认 80/20」后问：「为啥还有试卷级？这个在哪里设置？」并拍板：

> 拿掉吧，没必要了。我们有 fallback，只是 fallback 要确保，直接把题目塞给 ai，
> 然后 20% 是语法+拼写。然后 configurable。

也就是说：写作题**只保留题项级 rubric**，没有就一律走默认（题目全文 + 内容/语法两项、比例可配），
不要再有第三个能覆盖它的层级。

## 决策

| 项 | 决定 |
| :--- | :--- |
| 界面 | 向导第 4 步删除「试卷默认 rubric 与 AI 提示词（兜底）」整块（rubric 编辑器 + 提示词模板输入框） |
| 考试判分 | 只认题项级 `objective_items.rubric_markdown`；没有就按 D-28 合成默认两项。**不再读取** `papers.rubric_markdown` / `offerings.rubric_markdown`（`SittingLedgerImpl` 对 `EXAM` 传 null） |
| 独立作业 | 不变：作业布置自带 rubric（`offerings.rubric_markdown`，创建时必填），它是作业的唯一评分标准 |
| 兼容 | `papers.rubric_markdown`、`offering.rubric_markdown` 列与 `PUT /api/papers/{id}/rubric`、`PaperDetail.rubricMarkdown` 保留，供旧客户端/导出/排障；考试链路不再消费 |
| 快照 | `PaperCatalogImpl` 生成的 `PaperSnapshot.rubricMarkdown` 恒为 null；历史快照里的旧文本不迁移、不读取 |

## 为什么

- 三个层级（题项 / 试卷 / 默认）让「这份作文到底按什么判」不可预测；老师改了一处以为生效，实际被另一处盖住。
- 默认路径已经足够：题目全文 + 内容 80%（可配）+ 语法与拼写 20%，老师不需要再维护一份全卷 rubric。
- 需要更细的评分项时，题项级编辑器仍然在，且只影响那一道题。

## 后果

- 存量考试如果曾经设置过试卷级 rubric，之后判分改为默认 80/20（题项级 rubric 仍优先）。要恢复自定义口径，
  在该题第 4 步写题项级 rubric。
- 复核页考试不再显示「试卷级 rubric」块；写作题的题项级 rubric 仍在题目下可展开。
- `PUT /api/papers/{id}/rubric` 仍可写入，但只对独立作业之外的历史数据留档，不影响考试判分。

## 验收

- `FlowIntegrationTest.twoEssaysAreGradedPerItem`：故意写入试卷级 rubric 并把 `offerings.rubric_markdown`
  改成旧文本，`SittingLedger.load()` 返回的 `rubricMarkdown` 仍为 null；两道写作题按各自题项级 rubric 判分。
- `StandaloneAssignmentTest`：独立作业的 rubric 仍随快照进判分。
- `tsc` / `pnpm build` / `check-i18n` / `check-ui` 绿；`make test` 286/0/0/0。
