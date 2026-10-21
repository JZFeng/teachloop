# 默认 rubric 复核（2026-10-21）

> 2026-10-21 已按本结论落地：前后端默认 rubric 都替换为课程 2/4/4；`RubricCriteria` 改为标题优先。
> 仍缺服务端分值之和校验与独立预览 API（见 T258 部分完成）。

结论：**现有两份默认 rubric 都不合格**，应统一为课程 `docs/rubrics/Rubric.md` 的 2/4/4 版本；
作业自己的 rubric 永远优先，系统 rubric 只在作业未提供时兜底（D-18 / AC-28.1、AC-28.6）。

## 1. 现在有两份，而且不一致

| 位置 | 内容 | 问题 |
| :--- | :--- | :--- |
| 前端 `web/src/locales/zh-CN.json` / `en.json` → `teacher.assignmentCreate.defaultRubric` | `# 评分标准\n- 内容要点：\n- 语言准确性：`（英文：`Content coverage / Language accuracy`） | 无分值、无判定规则、无总分；两条目名称泛化且重叠 |
| 后端 `OfferingEndpoint.DEFAULT_ASSIGNMENT_RUBRIC` | `# 评分标准\n- 内容要点：切题，观点明确，结构完整。\n- 语言准确性：语法、用词与拼写准确。` | 仍无分值；只有描述，没有「是/否」或分档；与前端两份不一致 |

前端在创建页预填一份，后端在 rubric 为空时兜底另一份。教师看到的默认值与真正进 AI 的默认值
可能不同——这本身就是质量事故的温床。

## 2. 为什么它一定会拉低判分质量

1. **无分值 → 代码静默均分。** `RubricCriteria.parse` 在缺分值时会按剩余满分均分
   （10 分 → 5/5），教师从没确认过这个权重；而 `Rubric.md` 的真实权重是 2/4/4。
2. **无判定规则 → 模型只能自由发挥。** `内容要点` 没有「是/否」边界，模型可能给出
   `3/4` 这种 rubric 不存在的中间分；二元 rubric 本应只给 0 或满分。
3. **无总分绑定 → 同一份 rubric 可用于 10 分或 20 分作业。** 分值之和与作业总分没有校验，
   换一个总分就悄悄改变每项权重。
4. **没有篇幅项。** 这门课明确要求 1–4 段（`Rubric.md` 第 1 项，2 分），默认 rubric 不含它，
   AI 不会判篇幅；学生也不知道会被按篇幅扣分。
5. **名称泛化且重叠。** `内容要点` 与 `语言准确性` 没有覆盖「是否回应题目要求」「是否有支持细节」
   这两个课程目标；对英语写作课，`Clear Position` / `Explanation and Supporting Details`
   比「内容要点」更可判。
6. **语言不匹配。** 英文写作课的默认 rubric 是中文，且反馈语言另有一套默认；
   学生界面语言为 en 时会得到中文 criterion 名称（如果 rubric 是中文默认）。
7. **前后端各写一份。** 改一处忘另一处，默认值会继续漂移。

## 3. 建议的 fallback（直接采用课程 Rubric.md）

```markdown
# Overall Assignment Rubric

## Assignment
Essential Question Peer Review

## Statement
> "Anyone who speaks the language can teach the language."

## Scoring Rubric

### 1. Required Length — 2 points
Was it between 1–4 paragraphs (the greeting is merged into the opening paragraph)?
- Yes: 2 points
- No: 0 points

### 2. Clear Position — 4 points
Did the learner clearly express an opinion about the statement?
- Yes: 4 points
- No: 0 points

### 3. Explanation and Supporting Details — 4 points
Did the learner explain the opinion and incorporate relevant details supporting it?
- Yes: 4 points
- No: 0 points

## Maximum Score
10 points
```

- 三项、2/4/4、二元判定、总分 10；`RubricCriteria` 已能解析出
  `Required Length / Clear Position / Explanation and Supporting Details`（本地验证）。
- 这份内容已经在 `docs/rubrics/Rubric.md`，也是 10 份 golden set 的人工 review 使用的标准。
- 系统只保留**一份**来源：后端 classpath 资源（`docs/rubrics/Rubric.md` 的构建副本）通过
  `GET /api/rubrics/default` 给前端；前端不再硬编码第二份。

## 4. 录入时必须做的校验（AC-28.2/28.3/28.4）

- 解析出每个评分项：名称、满分、判定规则（二元/分档）。
- 名称非空且不重复；每项必须有明确分值；各项之和必须等于作业总分。
- 缺分值、重复、和不等、只有描述没有判定规则 → 阻止保存并列出具体项。
- 创建页展示「AI 将看到」：题目要求 + rubric + 评分政策 + 输出契约；教师保存前能发现缺口。
- 支持导入 `.md`；导入后同样校验，不因为「文件导入」而跳过。

## 5. 不在本轮做的事

- 不为其他学科写第二套通用模板（本期部署是英语写作课；等有第二门课再加模板库）。
- 不做「AI 从题目要求起草 rubric」；那会把标准制定权从教师手里拿走，且需要额外评估。
