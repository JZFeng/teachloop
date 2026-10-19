# Rubric Template (save as `Rubric.md` in the assignment folder)

评分标准文件。AI 只依据这份文件打分，因此**分值与判定条件必须写清楚，不留模糊空间**。

关键原则：**每条标准都应是二元的（得分或 0 分）**。若允许部分分（如 1/4），
`validate_reviews.py` 会给出 warning 要求老师确认——因为 AI 给部分分时最容易不可复现。

---

```markdown
# Overall Assignment Rubric

## Assignment

[assignment name, e.g. Essential Question Peer Review]

## Statement

> "[the statement or question students responded to]"

## Scoring Rubric

### 1. Required Length — 2 points

[exactly one of the following, chosen for this assignment:]

- If the learner uploaded a video, was it between 1-4 minutes?
- If the learner typed the response or uploaded a document, was it between 1-4 paragraphs?

- **Yes:** 2 points
- **No:** 0 points

### 2. Clear Position — 4 points

Did the learner clearly express an opinion about the statement?

- **Yes:** 4 points
- **No:** 0 points

### 3. [Criterion name] — 4 points

[the question the grader answers, phrased as a yes/no test]

- **Yes:** 4 points
- **No:** 0 points

## Maximum Score

**10 points**

## Optional Feedback

Provide brief, constructive feedback that is useful to the learner.
```

---

## 写 rubric 的检查清单

- [ ] 每条标准都能回答“是/否”，不依赖主观程度词（如 “good”、“clear enough”）。
- [ ] 每条标准的总分与 `Grading_Prompt.md` 中写明的分值完全一致。
- [ ] 段落/时长要求在 `Grading_Prompt.md` 的 Length Policy 中同步说明。
- [ ] 标准数量与总分之和一致（校验脚本会核对）。
- [ ] 没有把“观点是否正确”写进标准——只评价支持的质量。

## 常见问题

| 问题 | 后果 | 修正 |
| :--- | :--- | :--- |
| 标准里出现 “mostly”、“somewhat” 等程度词 | AI 判定不可复现，同份作业两次运行结果不同 | 改写成二元的“是/否”测试 |
| rubric 分值与 prompt 不一致 | 校验脚本报 rubric drift | 两处同步修改 |
| 标准总数超过 3–4 条 | AI 容易在单条上漂移，老师复核成本上升 | 拆成多次小作业，或合并标准 |
| 把语言准确性混入内容标准 | 反馈变得笼统，学生不知道改什么 | 每条标准只评一件事 |

## 适配其他作业类型

同一套骨架可直接用于：essay、discussion post、reflection、lesson plan、教学设计。
只需替换标准 2 与标准 3 的判定问题，例如：

- 语法作业：`### 2. Target Structure Accuracy — 4 points`（目标结构是否正确使用）
- 组织性作业：`### 2. Organization — 4 points`（是否有清晰的开头/主体/结尾）
- 词汇作业：`### 2. Vocabulary Range — 4 points`（是否使用了本单元目标词汇）

若改用匿名学号，把 `Student:` 字段填写为学号即可；文件夹中的文件名也相应改为学号。
