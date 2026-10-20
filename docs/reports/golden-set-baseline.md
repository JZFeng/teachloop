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


## AI 实测（2026-10-20，deepseek-flash，temperature 0.2）

执行：`DEEPSEEK_API_KEY=... python3 scripts/golden-set.py`（同一 rubric，10 次真实调用）

| 学生 | 人工 | AI | 偏差 |
| :--- | ---: | ---: | ---: |
| Akshay | 10 | 10 | 0 |
| Alejandro | 10 | 10 | 0 |
| Genesis | 10 | 10 | 0 |
| Iam | 10 | 10 | 0 |
| JZ | 8 | 8 | 0 |
| Maria | 10 | 10 | 0 |
| Mark | 8 | 8 | 0 |
| Regan | 10 | 10 | 0 |
| Sana | 10 | 10 | 0 |
| Zhangguan | 10 | 10 | 0 |

- 平均偏差：**0.0**
- 最大绝对偏差：**0**
- 方向：**持平**（无系统性偏高/偏低）

样本量只有 10，且评分标准是 2+4+4 的离散三档；0 偏差说明当前 prompt 与 rubric 在这批样本上对齐，但不能外推到其他题型/分数段。

## 真机回归

`DEEPSEEK_API_KEY=... make test-ai` → exit 0；`AiGradingRealCallTest` 与 `SpringAiLlmRealCallTest` 均 1/1、Skipped 0。

