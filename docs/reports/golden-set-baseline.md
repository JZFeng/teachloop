# Golden set 基线（T175）

日期：2026-10-20　语料：`docs/rubrics/samples/submissions/`（10 份 v1 作业）
评分标准：`docs/rubrics/Rubric.md`（长度 2 + 立场 4 + 论据 4 = 10 分）

## 人工评分基线

| 学生 | 人工总分 |
| :--- | ---: |
| Akshay | 10 |
| Alejandro | 10 |
| Genesis | 10 |
| Iam | 10 |
| JZ | 8 |
| Maria | 10 |
| Mark | 8 |
| Regan | 10 |
| Sana | 10 |
| Zhangguan | 10 |

- 平均分：**9.6 / 10**
- 中位数：10
- 分布：2 份 8 分，8 份 10 分
- 每份的评分理由与评语见 `docs/rubrics/samples/reviews/*_review.txt`

## AI 偏差

**未跑**。当前环境没有 `DEEPSEEK_API_KEY`，无法调用真实模型；用 stub 结果算偏差没有意义，因此本文件只记录人工基线，不填造 AI 数字。

真机跑法（有 Key 时）：

```bash
DEEPSEEK_API_KEY=... make test-ai
# 或按 specs/002-v2-platform/plan.md §18.5 用同一 rubric 对 10 份样本逐份评分，
# 记录平均偏差、最大偏差、偏差方向（系统性偏高/偏低）
```

## 结论

- T175 的人工基线已就绪；AI 偏差待 Key 后补。
- 没有 Key 之前，T175 保持「部分完成」，不宣称 golden set 已校准。
