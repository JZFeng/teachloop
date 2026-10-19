# 长度判定政策（Length Counting Policy）

## 为什么需要这份文件

本流程中最容易产生争议、也最容易导致分数不一致的一条标准，是 **Required Length（篇幅要求）**。
“1–4 个段落”看起来客观，实际判定时会遇到四种常见情况：

| 情况 | 例子 | 争议点 |
| :--- | :--- | :--- |
| 硬换行 | 学生把一段话按 50 字符手动折行 | 24 行到底是 1 段还是 24 段？ |
| 问候语 | `Hello everyone, this is JZ.` 单独成行 | 算独立段落，还是并入第一段？ |
| 项目符号列表 | `a) grammatical errors` … `e) good strategies` | 5 个 bullet 算 5 段还是 1 段？ |
| 句中空行 | 句子写到一半空了一行再接着写 | 算 2 段还是 1 段？ |

在原始教学案例（10 份作业）中，正是因为**没有事先规定**这些规则，出现了两次可争议的 0/2 判定：
JZ 与 Mark 因为问候语单独成段而被扣分，而同样问候语独立成段的 Maria 却按 “4 paragraphs/blocks” 拿到 2/2。
本政策的目的就是**消除这种逐人漂移**。

## 四个计数模型

`scripts/collect_submissions.py` 会同时对每份作业计算四个模型，并把结果并排列出。
这样做的原因：篇幅判定本身没有唯一正确答案，与其假装客观，不如**暴露分歧点**，交给老师决定。

| 模型 | 规则 | 倾向 |
| :--- | :--- | :--- |
| `strict_blocks` | 每个空行分隔的块 = 1 段；问候语独立成段；bullet 逐条计数 | 最严格 |
| `wraps_merged` | 合并软换行；问候语独立成段；整个 bullet 块 = 1 段 | 中等 |
| `policy_default` | 合并软换行 + 问候语并入开头段 + 连续 bullet 合并为 1 段 | **默认** |
| `lenient` | 在 `policy_default` 基础上，再合并“句中空行” | 最宽松 |

规则细节：

- **软换行合并**：若上一行以句末标点（`. ! ? " )` 等）结尾，且下一行以大写字母开头，则视为真正的段落边界；否则视为折行，合并。
- **问候语判定**：单行、不超过 25 词，且以 `Hi/Hello/Hey/Good morning/Dear` 开头，或以 `My name is / I am X / This is X` 开头。超过 25 词的段落即使以 `Hello` 开头也算正常段落。
- **列表判定**：以 `-`、`*`、`•`、`a)`、`1.` 等标记开头的行。连续的被空行分隔的单个 bullet 会被识别为一整个列表。
- **句中空行合并**（仅 `lenient`）：上一块没有句末标点，下一块以小写字母开头。

## 强制执行的两条规则

无论选哪个模型，以下两条都必须遵守：

1. **全班用同一个模型。** 不允许某位学生用 `lenient`、另一位用 `strict_blocks`。
   在同一个班级里混用模型，等价于给不同学生不同标准。
2. **把计数结果写进 review 文件。** 在 `Required Length` 的 `Reason` 中写明段数，
   例如 `The response is 3 paragraphs (counted with the class length policy), within the 1–4 limit.`
   这样老师可以逐条复核，而不是只能看到一个 0/2。

## 边界敏感：必须交给老师

脚本会把**模型选择会改变 0/2 结果**的作业标记为 `BOUNDARY`。
判定方式：四个模型的 `in_range` 结论只要不一致，即为边界敏感。

对这类作业：

- `validate_reviews.py` 会输出 `[TEACHER REVIEW] Boundary-sensitive length scoring: ...`
- 该生的 `Required Length` 分数**必须在最后交付时单独提示老师确认**。
- 不要默默选一个模型给分然后不说明。

在原始 10 份作业上，脚本标记出的边界敏感作业为：
`Genesis`（strict 24 段 vs policy 3 段）、`JZ`（strict 5 段 vs policy 4 段）、
`Maria`（strict 8 段 vs policy 4 段）、`Mark`（strict 6 段 vs policy 4–5 段）。

其中 JZ 一项尤其值得注意：原始 review 给了 `0/2`，而默认政策计数为 4 段（在 1–4 范围内），
`validate_reviews.py` 会自动把这条列出来要求复核。

## 视频作业

若作业是视频，篇幅标准改为 **1–4 分钟**，不使用段落计数：

- 脚本在 `ffprobe` 可用时自动读取时长，写入 manifest 的 `duration_minutes`。
- `ffprobe` 不可用时，脚本会写入 warning，要求人工确认时长。
- 视频作业的 `Required Length` 依据**分钟数**判定，并在 `Reason` 中写明具体时长。

## 如何为一段作业记录长度

推荐的 `Reason` 写法（便于老师审计）：

```text
Reason: 3 paragraphs under the class length policy (soft-wrapped lines merged,
greeting merged into the opening paragraph, bulleted list counted as one) - within the 1-4 limit.
```

只要模型或计数方式有争议，就把计数过程写出来。理由越具体，老师复核越快。
