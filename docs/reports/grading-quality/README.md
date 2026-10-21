# 评分质量交付（2026-10-21）

## 结论

**真实 AI 已经在 dev 环境跑起来，且不再用假分数兜底。**
截图里的 `7.5 / 4.5/6 / 3/4` 是 `StubGradingEngine` 的固定比例；现在默认 `engine=ai`，
`stub` 只在非 prod 且显式配置时存在。

## 关键证据

- 真实 DeepSeek（deepseek-flash，教师个人 DB Key，进程未设 `DEEPSEEK_API_KEY`）：
  Maria 10/10、Mark 8/10（篇幅 0）、Iam 10/10、Alejandro 10/10；
  英文评语带原文引用，篇幅理由由服务端确定性句子给出。
- 复核页预填：打开一份 AI 草稿，主观分 / 评语 / 优点 / 建议已填入，标记「AI 草稿 · deepseek-flash · subjective-v2」；
  教师保存后 AI 不再覆盖。
- 批量通过：先预览「可批 / 跳过」，再确认；边界敏感、AI 失败、无草稿、已发布的份会被跳过。
- 学生反馈：发布后学生看到 10/10 与英文评语；AI 逐项理由不自动发布。
- Golden set：逐项 exact **29/30**、总分 **9/10**、契约失败 **0**、英文语言合规 **10/10**；
  唯一差异 JZ 是已知人工参考与「问候语不计段」政策冲突。

## 文件

- `2026-10-21-subjective-v2.md`：10 份样本逐份报告。
- `known-inconsistency.md`：JZ 参考冲突与更正口径。
- `e2e/profile-ai-settings.png`：个人 Key 设置（掩码/来源/测试）。
- `e2e/review-ai-prefilled.png`：复核页 AI 评估区（逐项证据 + 总评/优点/建议）。
- `e2e/review-form-prefilled.png`：教师表单预填 + AI 溯源。
- `e2e/student-feedback-en.png`：学生反馈（英文）。
- `default-rubric-review.md`：默认 rubric 复核结论。

## 已补的闭环

- **服务端 rubric 校验**：`RubricValidator` + `POST /api/rubrics/preview`；课程 `Rubric.md` 解析为 2/4/4=10；`6+5=11` 对总分 10 直接 400。创建作业时同样校验，缺分值/名称重复/合计不符都拒绝。
- **AI 溯源**：真实重批后 `reviews.ai_model=deepseek-flash`、`ai_prompt_version=subjective-v2`、`ai_key_source=user`。
- **锚点（简化版）**：作业可填 0–3 份匿名参考范文（`offerings.anchor_examples`），非空才注入 prompt；作业创建页有输入框。未做自动匿名化检查与逐份结构化编辑。

## 仍缺（不声称完成）

- 服务端 rubric 校验的错误文案目前是后端中文字面量（已进 i18n 基线，未走消息 key）。
- 按评分项逐个编辑、锚点的自动匿名化/结构化编辑器。
- 批量通过后仍需单独发布。
- 本次 rubric/锚点/溯源改动：`RubricValidatorTest`、`PromptBuilderTest`、`AiGradingEngineTest` 与 `StandaloneAssignmentTest`/`GradingDispatchTest`/`MigrationTest`/`AiSetupTest`/`ManualGradingTest` 均通过；全量回归最后一次因同工作区另一个并发 Maven 构建被中断，未作为最终门禁结论。
