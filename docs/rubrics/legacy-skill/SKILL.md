---
name: ai-batch-grading
description: 按 Rubric 批量批改学生作业，逐份生成个体反馈文件与班级汇总，并校验结果一致性。当用户要求“批量批改作业”“批改所有学生作业”“用 AI 跑一遍作业评分”“批量给学生反馈”“batch grade submissions”“grade all student assignments against the rubric”“review all student writing”时使用。
---

# AI 批量作业批改（ai-batch-grading）

用同一个 Rubric 与同一套判定政策，一次性批改多名学生的作业，产出**逐份个体反馈**与**班级汇总**，
并自动校验格式、算术与判定一致性。

定位：**AI 只做第一轮批改，老师做最终决定。** 本 Skill 的价值在于消除重复劳动，
以及消除“同一个标准对不同学生松紧不一”这类漂移。

## 何时使用

- 用户说“批量批改作业”“帮我批改所有学生作业”“跑一遍作业评分”“给全班写反馈”时使用。
- 用户说“按 Rubric 批改”“batch grade”“review all submissions”时使用。
- 当出现以下场景时使用：
  - 有一个包含多份学生作业的目录（`Submissions/`）。
  - 有一份评分标准（`Rubric.md`）。
  - 希望产出内容与格式一致的多份反馈，而不是逐份手写。
- **不适用场景**（避免误触发）：
  - 只有一份作业 —— 直接批改即可，不需要本流程。
  - 没有 Rubric —— 先让老师提供或起草标准；**禁止**凭常识临时编一套标准打分。
  - 需要自动把成绩发布给学生或写入教务系统 —— 本 Skill 只产出本地反馈文件，发布需老师确认。
  - 主观创作类评价（如作文比赛排名）—— 单一 Rubric 无法承载，需要人工主导。

## 前置检查

执行前必须确认：

- [ ] 作业目录存在，且至少包含一份作业文件（`Submissions/` 或用户指定的目录）。
- [ ] 评分标准存在（`Rubric.md`），且每条标准为**二元判定**（得分或 0 分）。
- [ ] 批改指令文件存在（`Grading_Prompt.md`）；若缺失，用 `references/GRADING_PROMPT_TEMPLATE.md` 生成并请老师确认。
- [ ] 长度要求明确：段落区间（如 1–4 段）或视频时长区间（如 1–4 分钟）。
- [ ] 输出目录（`reviews/`）已确定；若其中已有上次结果，**先问老师**是覆盖还是换目录。
- [ ] 已选定段落计数模型（默认 `policy_default`，见 `references/LENGTH_COUNTING_POLICY.md`）。
- [ ] 已确认当前解释器的可选依赖可用：运行 `python3 scripts/collect_submissions.py --check-deps`。
      缺 `pypdf` 则 PDF 无法读取；缺 `python-docx` 则 `.docx` 无法读取；缺 `ffprobe` 则无法判定视频时长。
- [ ] **缺少任何一项则停止执行**，提示老师补齐，不要用默认值猜。

批量运行前先跑一次清点：

```bash
python3 scripts/collect_submissions.py --submissions ./Submissions --manifest ./_grading/submissions.json
```

## 输入

| 输入 | 来源 | 是否必需 | 说明 |
| :--- | :--- | :--- | :--- |
| 学生作业 | `Submissions/` 目录 | 必需 | `.txt` / `.md` / `.docx` / `.pdf` / 视频 / 音频；文件名即学生标识 |
| 评分标准 | `Rubric.md` | 必需 | 各条标准与分值，必须与 prompt 中的分值一致 |
| 批改指令 | `Grading_Prompt.md` | 必需 | 角色、任务、背景、要求、约束、输出格式 |
| 长度政策 | `references/LENGTH_COUNTING_POLICY.md` | 必需 | 段落计数模型；决定 `Required Length` 如何判定 |
| 输出目录 | 默认 `reviews/` | 可选 | 个体反馈与班级汇总的存放位置 |
| 匿名化开关 | 用户指定 | 可选 | 是否需要把姓名字段替换为学号 |
| 清单文件 | `collect_submissions.py --manifest` 产出 | 可选 | 提供后可交叉校验篇幅分数 |

## 执行流程

1. **读取输入与前置检查**
   - 读取 `Rubric.md` 与 `Grading_Prompt.md`，确认两者分值一致、标准为二元判定。
   - 若 `Grading_Prompt.md` 缺失或 rubric 非二元，按 `references/TROUBLESHOOTING.md` 处理，必要时停止。

2. **清点作业**
   - 运行 `scripts/collect_submissions.py`，得到文件清单、类型、词数、各计数模型的段数、以及需人工处理的文件。
   - 查看输出中的 **BOUNDARY** 与 **OUT OF RANGE** 两行；这是本流程最容易出错的地方。
   - 保存 manifest 供第 5 步使用。

3. **执行核心批改（逐份遍历）**
   - 对每一份作业：完整读取内容 → 按 `Rubric.md` 逐条判定 → 用作业中的**具体证据**写明理由。
   - 判定规则：
     - 只依据 `Rubric.md`；不给“印象分”，不因是否同意学生观点而加减分。
     - 只评价**支持的质量**，不评价观点对错。
     - 不虚构作业中不存在的内容；读不到就记为不可读，不猜。
     - `Required Length` 的 `Reason` 必须写明段数（或视频分钟数）。
   - 每份写入 `reviews/<原文件名>_review.txt`，格式见「输出要求」。
   - 单份失败（超时、无法解析）时跳过并记录，不要让它中断整批。

4. **生成班级汇总**
   - 汇总所有个体结果，写入 `reviews/class_summary.txt`：
     作业数量、平均分、共同优点、共同问题、3 条教学建议。
   - 平均分必须依据实际分数计算，不要估算。

5. **校验并修正**
   - 运行 `scripts/validate_reviews.py`（带上第 2 步的 manifest）。
   - 先修全部 **error**（结构、算术、覆盖缺口），再审 **warning**（边界敏感、部分分）。
   - 修改任何一份 review 后，**必须重算 `class_summary.txt` 的平均分**，否则校验会失败。
   - 反复运行直到输出 `RESULT: PASS`。

6. **通知用户**
   - 报告：已批改份数、平均分、输出位置、需老师确认的边界敏感判定、无法读取的作业列表。
   - 明确说明这是第一轮批改，最终判断权在老师。

## 输出要求

- 输出目录：`./reviews/`
- 个体文件命名：`<原文件名>_review.txt`（例：`Maria.txt` → `Maria_review.txt`）
- 班级汇总：`./reviews/class_summary.txt`
- 每份个体文件必须包含：
  - 三项标准的分数（`0` 或满分）与各自理由
  - `Total Score: X/10`（必须等于三项之和）
  - 一条**具体**优点（Strength）
  - 一条**可执行**建议（Suggestion）
- 格式：纯文本，字段名固定，便于脚本校验。完整模板见 `references/OUTPUT_FORMAT.md`。

```text
Student: Maria

Rubric:
1. Required Length: 2/2
Reason: The response is 4 paragraphs under the class length policy, within the 1-4 limit.

2. Clear Position: 4/4
Reason: States "I disagree" in the second paragraph.

3. Explanation and Supporting Details: 4/4
Reason: Lists five factors that teaching requires beyond speaking the language.

Total Score: 10/10

Strength:
Identifies concrete factors such as grammatical knowledge and cultural understanding.

Suggestion:
Turn the bulleted list into explained evidence by adding one example for each factor.
```

## 边缘情况

| 情况 | 表现 | 处理 |
| :--- | :--- | :--- |
| 缺少 Rubric | 找不到 `Rubric.md` | 停止，请老师提供；禁止临时编标准 |
| 缺少批改指令 | 找不到 `Grading_Prompt.md` | 用模板生成并请老师确认后再跑 |
| 作业目录为空 | `Submissions/` 无文件 | 停止，提示老师放入作业 |
| 隐藏文件 | `.DS_Store` 等 | 自动跳过，不当作作业 |
| 输出目录已有结果 | `reviews/` 非空 | 先问老师：覆盖还是换目录 |
| 篇幅判定边界敏感 | 不同计数模型 0/2 结论不同 | 按选定政策给分，**并提示老师确认**；脚本会输出 `[TEACHER REVIEW]` |
| 问候语独立成段 | 如 `Hello everyone, this is JZ.` | 默认并入开头段；若老师选严格计数，则全班一致执行 |
| 硬换行文本 | 学生按 50 字符手动折行 | 合并软换行后再计数，否则会把 1 段误判为 24 段 |
| bullet 列表 | `a) … e)` 逐条空行分隔 | 整份列表计为 1 段（默认政策） |
| 视频作业 | `.mp4` 等 | 按 1–4 分钟判定；`ffprobe` 不可用时请人工确认，不估算 |
| 扫描 PDF | 无文本层 | 记为需 OCR / 人工阅读；**不得**默认为 0 分 |
| PDF 解析库缺失 | 无法提取文本 | 报告说明，请老师转 txt 或 docx |
| 文件损坏 | 无法解析 | 记为不可读，在最终报告中单独列出 |
| 单份超时 | 长时间无响应 | 跳过该份并记录，继续下一份，不中断整批 |
| 总分不等于三项之和 | 手工累加错误 | 校验脚本报 error，必须修正 |
| 出现部分分 | 如 `2/4` | 校验脚本报 warning；确认老师是否允许，允许则需在 rubric 写明分档 |
| 覆盖缺口 | 有作业无 review，或有 review 无作业 | 校验脚本报 error，补齐或确认文件名 |
| 两次运行结果不一致 | rubric 含程度词 | 把标准改写为二元判定后整批重跑 |
| 需要整批重跑 | 改了 rubric 或长度政策 | **整批重跑**，不要只补改个别学生 |

## 安全与权限

- **不执行破坏性操作**：不删除学生作业、不清空目录、不覆盖已有 `reviews/` 结果（除非老师明确确认）。
- 唯一允许自动创建的是输出目录 `reviews/`。
- **隐私**：不在反馈或汇总中复述个人敏感信息；只在评分需要时引用最短的相关原句。
  如需匿名化，用学号替换姓名字段，并同步修改文件名。
- **不越权**：不自动把成绩发布给学生、不写入教务系统、不发送邮件；这些操作必须由老师确认后执行。
- **不虚构**：不声称任何未实际执行的操作（如“已验证”“已提交”）；无法读取的作业如实标记。
- **最终决定权在老师**：所有输出都是第一轮草稿，必须在报告中明确说明。
- 详细错误处理见 `references/TROUBLESHOOTING.md`。

## 参考文件

- `scripts/collect_submissions.py`：清点作业，输出多模型段落计数、边界敏感标记与 manifest；`--check-deps` 可查可选依赖。
- `scripts/validate_reviews.py`：校验 review 格式、二元评分、算术、覆盖缺口与班级汇总，并交叉核对篇幅分数。
- `references/LENGTH_COUNTING_POLICY.md`：段落/时长判定政策，四个计数模型与边界敏感处理。
- `references/GRADING_PROMPT_TEMPLATE.md`：`Grading_Prompt.md` 模板（六段式，可直接复制修改）。
- `references/RUBRIC_TEMPLATE.md`：`Rubric.md` 模板与二元标准撰写检查清单。
- `references/OUTPUT_FORMAT.md`：`class_summary.txt` 与个体 review 的格式要求。
- `references/TROUBLESHOOTING.md`：错误处理、边界情况与交付前检查清单。
- `assets/example/`：一套完整可运行的示例（3 份作业 + 对应反馈 + 班级汇总），可作为 few-shot 参考。

## 运行示例

```bash
# 0. 确认可选依赖（读 docx / pdf / 视频时长）
python3 scripts/collect_submissions.py --check-deps

# 1. 清点作业并生成清单
python3 scripts/collect_submissions.py \
    --submissions ./Submissions \
    --manifest ./_grading/submissions.json

# 2. 按 Grading_Prompt.md 与 Rubric.md 逐份批改，写入 ./reviews/
#    （由 Agent 阅读 rubric、逐份生成 review 文件与 class_summary.txt）

# 3. 校验结果
python3 scripts/validate_reviews.py \
    --submissions ./Submissions \
    --reviews ./reviews \
    --manifest ./_grading/submissions.json
```

## 依赖说明

脚本仅用 Python 标准库即可运行；以下为**读取特定文件类型**所需的可选依赖。
用 `--check-deps` 可查看**当前解释器**的实际情况（不同解释器结果可能不同）：

| 依赖 | 用途 | 缺失时的行为 |
| :--- | :--- | :--- |
| `python-docx` | 读取 `.docx` 作业 | 回退到内置 `zipfile` 解析；仍失败则标记为不可读 |
| `pypdf`（或 `PyPDF2`） | 读取有文本层的 `.pdf` | 标记该作业需转文本或人工阅读，**不猜内容、不默认为 0 分** |
| `ffprobe` | 读取视频/音频时长（1–4 分钟判定） | 标记该作业需人工确认时长，不估算 |

本机已就绪状态（2026-10-16 实测）：

- DSH 运行时 Python 3.12：`python-docx` 1.2.0、`pypdf` 6.21.0、`ffprobe` 均可用。
- Homebrew `python3` 3.14：`python-docx` 1.2.0（用户级 site-packages）、`pypdf` 6.16.2、`ffprobe` 均可用。

注意：扫描件 PDF（无文本层）即使装了 `pypdf` 也读不出文字，仍需 OCR 或人工阅读——
`pypdf` 只能提取**已经存在**的文本层。本流程会如实标记，不会因此给 0 分。
