# Class Summary Format (class_summary.txt)

`class_summary.txt` 放在 reviews 目录中，供老师快速了解全班情况。
它必须可被 `validate_reviews.py` 解析，因此请保持以下字段名。

## 必需字段

| 字段 | 说明 | 校验规则 |
| :--- | :--- | :--- |
| Number of submissions reviewed | 已批改的作业数 | 必须等于 review 文件数量 |
| Average score | 平均分 | 必须等于实际平均分（容差 0.05） |
| Common Strengths | 共同优点 | 必须出现 `Common Strength` 字样 |
| Common Weaknesses | 共同问题 | 必须出现 `Common Weakness` 字样 |
| Teaching Points for a Future Lesson | 3 条教学建议 | 必须为 3 条编号项 |

## 模板

```text
Class Summary

Number of submissions reviewed: 10

Average score: 9.6/10
(8 students scored 10/10; 2 students scored 8/10)

Common Strengths:
- Every response clearly stated a position.
- Most responses grounded the opinion in course concepts such as i+1, Teacher Talk, and scaffolding.

Common Weaknesses:
- Two responses exceeded the 1-4 paragraph limit because a greeting line was written as its own paragraph.
- Several responses name concepts without explaining them, so support is present but underdeveloped.

Teaching Points for a Future Lesson:
1. Paragraph structure and length: show how a greeting line merges into the opening paragraph.
2. Developing support: turn listed points into explained evidence with a definition or example.
3. Applying course terminology: weave terms like i+1 and scaffolding into the argument accurately.
```

## 写作要求

- **只写全班层面的模式**，不要罗列每个学生的分数。个体反馈属于各自的 review 文件。
- **优点与问题都要有证据**：用“几位学生/多数学生”而不是“所有人”，除非确实覆盖全班。
- **教学建议必须是可执行的**：写成“下一节课可以做什么”，而不是“学生应该注意”。
- **分数分布**可以写进 `Average score` 下面一行，便于老师一眼看到异常。
- 如果某条建议来自边界敏感判定（见 `LENGTH_COUNTING_POLICY.md`），请在这一节明确说明，
  例如：“Note: two length scores are boundary-sensitive; confirm before returning.”

## 常见错误

| 错误 | 后果 |
| :--- | :--- |
| 平均分手算错误 | 校验脚本直接报 error，需重算 |
| 只写 2 条教学建议 | 校验脚本报 warning |
| 把学生姓名与具体分数写进 summary | 不属于班级层面，且不利于匿名化 |
| 数字与实际 review 数量不一致 | 校验脚本报 error |
