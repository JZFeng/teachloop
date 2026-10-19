# Example: A Complete Batch-Grading Run

这套示例可直接运行，用来演示本 Skill 的完整输入与输出，也可作为 few-shot 参考。

## 目录内容

| 路径 | 说明 |
| :--- | :--- |
| `Rubric.md` | 评分标准（3 条标准，共 10 分） |
| `Grading_Prompt.md` | 批改指令，含长度政策 |
| `Submissions/` | 3 份学生作业：Akshay、Maria、Mark |
| `reviews/` | 3 份个体反馈 + `class_summary.txt` |

这三份作业是特意挑选的，覆盖了本流程的三种典型情况：

| 学生 | 特点 | 长度判定 |
| :--- | :--- | :--- |
| Akshay | 结构清晰，3 段，引用课程概念 | 各模型一致，3 段 |
| Maria | 使用 bullet 列表列出 5 个因素 | **边界敏感**：严格计数 8 段，默认政策 4 段 |
| Mark | 问候语独立成段，共 5 段 | **边界敏感**：严格计数 6 段，默认政策 5 段 |

Maria 与 Mark 正是“边界敏感”的两个实例，因此本示例也用于演示
`validate_reviews.py` 的 `[TEACHER REVIEW]` 提示。

## 运行方法

```bash
cd assets/example

# 1. 清点作业（生成 manifest）
python3 ../../scripts/collect_submissions.py \
    --submissions ./Submissions \
    --manifest ./_grading/submissions.json

# 2. 批改：由 Agent 读取 Rubric.md 与 Grading_Prompt.md，逐份写入 ./reviews/

# 3. 校验
python3 ../../scripts/validate_reviews.py \
    --submissions ./Submissions \
    --reviews ./reviews \
    --manifest ./_grading/submissions.json
```

预期结果：`RESULT: PASS`，并输出 2 条边界敏感提示（Maria、Mark）。

## 预期输出摘要

| 学生 | 分数 | 说明 |
| :--- | :--- | :--- |
| Akshay | 10/10 | 三项标准均满足 |
| Maria | 10/10 | 三项标准均满足；长度判定边界敏感 |
| Mark | 8/10 | `Required Length` 为 0/2，超出 1–4 段限制 |

平均分：9.33/10

## 备注

- 示例中的学生姓名来自公开教学案例，如用于实际批改请按需替换为学号。
- `_grading/` 目录由脚本生成，不纳入版本管理。
