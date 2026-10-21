# subjective-v2 质量评估（2026-10-21）

模型：deepseek-flash；样本：`docs/rubrics/samples` 10 份；语言：en；prompt：subjective-v2。

## 汇总

- 逐项 exact：29/30
- 总分 exact：9/10
- 非边界样本：6/10
- 契约失败：0
- 语言合规（英文）：10/10
- 证据引用：由 `GradeOutputValidator` v2 强制（comment 必须含正文逐字引用）；10/10 契约通过，0 编造引用
- 篇幅：服务端 `TextLengthCounter` 决定；JZ 的差异是已知人工参考与「问候语不计段」政策冲突（见 `known-inconsistency.md`），不是模型错误

## 逐份

| 学生 | 人工 | AI | 逐项 | 边界 |
| :--- | ---: | ---: | :--- | :--- |
| Akshay | 10 | 10 | {Required Length=2, Clear Position=4, Explanation and Supporting Details=4} → {Required Length=2, Clear Position=4, Explanation and Supporting Details=4} | 否 |
| Alejandro | 10 | 10 | {Required Length=2, Clear Position=4, Explanation and Supporting Details=4} → {Required Length=2, Clear Position=4, Explanation and Supporting Details=4} | 否 |
| Genesis | 10 | 10 | {Required Length=2, Clear Position=4, Explanation and Supporting Details=4} → {Required Length=2, Clear Position=4, Explanation and Supporting Details=4} | 是 |
| Iam | 10 | 10 | {Required Length=2, Clear Position=4, Explanation and Supporting Details=4} → {Required Length=2, Clear Position=4, Explanation and Supporting Details=4} | 否 |
| JZ | 8 | 10 | {Required Length=0, Clear Position=4, Explanation and Supporting Details=4} → {Required Length=2, Clear Position=4, Explanation and Supporting Details=4} | 是 |
| Maria | 10 | 10 | {Required Length=2, Clear Position=4, Explanation and Supporting Details=4} → {Required Length=2, Clear Position=4, Explanation and Supporting Details=4} | 是 |
| Mark | 8 | 8 | {Required Length=0, Clear Position=4, Explanation and Supporting Details=4} → {Required Length=0, Clear Position=4, Explanation and Supporting Details=4} | 是 |
| Regan | 10 | 10 | {Required Length=2, Clear Position=4, Explanation and Supporting Details=4} → {Required Length=2, Clear Position=4, Explanation and Supporting Details=4} | 否 |
| Sana | 10 | 10 | {Required Length=2, Clear Position=4, Explanation and Supporting Details=4} → {Required Length=2, Clear Position=4, Explanation and Supporting Details=4} | 否 |
| Zhangguan | 10 | 10 | {Required Length=2, Clear Position=4, Explanation and Supporting Details=4} → {Required Length=2, Clear Position=4, Explanation and Supporting Details=4} | 否 |
