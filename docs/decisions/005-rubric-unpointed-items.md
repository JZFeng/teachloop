# 决策 005 — 没有分值的分组标题/说明不算评分项

日期：2026-10-22　状态：生效　相关：T258（rubric 校验）、T328（是否/长问句）、T340（本次）

## 问题

教师在 rubric 里用 `#` 标题同时写了**评分项**和**分组标题/说明**：

```
# Did the learner submit a lesson plan...? （2 points）
# Did the learner include all the components...? （6 points）
# Lesson Plan Components
# If any part is missing, you'll be asked to edit and resubmit.
# Did the learner use at least one intensive reading activity...? （3 points）
# Did the learner use at least one extensive reading activity...? （3 points）
```

三处解析器（`RubricValidator` / `RubricCriteria` / 前端 `rubric.ts`）都按「有标题就收标题」处理，
于是两条没有分值的标题变成了「缺分值的评分项」：

- **发布被拒**：后端对两条标题报 `error.rubric.missingPoints`，`valid=false`，这段 2/6/3/3 的 rubric 发不出去；
- **判分错位**：`RubricCriteria.allocate()` 遇到「明示合计 14 = 满分、但仍有缺分项」会整体均分，
  6 条变成 6×2.3 分；而且它的名称上限还是 80，那条 92 字符的 6 分问句会被直接丢掉；
- **界面误导**：「AI 将看到」把两条说明列成评分项，并提示「有评分项没写分值」。

## 决策

**在存在「带分值」条目时，只把带分值的条目当评分项；没有分值的条目归入 `ignored`，
只做界面提示，不阻断发布。**

选择规则（三处逐条对齐）：

1. 有带分值的 **heading** → 评分项 = 带分值的 heading；无分值 heading 与无分值列表项进 `ignored`。
2. 没有带分值的 heading、但有带分值的**列表项**（`- ` / `1. ` / `1 `）→ 评分项 = 带分值的列表项；
   无分值列表项与标题进 `ignored`。
3. 全文没有任何分值 → 维持旧行为（标题优先、否则列表），由判分侧均分；`ignored` 为空。

配套：三处统一名称上限 **500**（grading 从 80 提到 500）、`isDecision` 的「是否…」例外、
`cleanName` 规则；夹具 `docs/rubrics/fixtures/rubric-cases.json` 三处共用。

## 为什么不是别的做法

| 备选 | 为什么没选 |
| :--- | :--- |
| 只在界面上过滤掉没分值的行 | 后端仍会 400、判分侧仍会 6×2.3；界面好看但发不出去 |
| 用启发式判断「标题/说明 vs 真评分项」（问句、是否、长度） | 语言与写法太杂；你这段里「If any part is missing...」与真评分项同为 `#` 标题，无法稳定区分 |
| 缺分值一律阻断（旧行为） | 正是这次的问题：分组标题不是评分项，却被当成缺分项 |
| 让教师用别的 Markdown 结构（列表写评分项、标题写分组） | 现有 rubric 存量与教师习惯都在标题上；要求改写法不现实 |

## 代价

- 「真的忘了给某一项写分值」不再被阻断，而是进 `ignored` 并在「AI 将看到」底部提示
  「另有 N 行没有分值，未计入评分项」（悬停可看名称）。这是有意的取舍：文本上无法可靠区分
  「分组标题」与「忘写分值的评分项」，阻断会重现这次的问题。
- 已发布的旧 rubric 不迁移；后续判分会按新规则解析，带无分值标题的旧 rubric 的分配结果会变化（属于修 bug）。
- 三处仍各有一份实现；本轮用共享夹具 + 注释锁住，彻底消除漂移需要把前端摘要切到
  `POST /api/rubrics/preview`（下一步单独评估）。

## 影响面

- `RubricValidator`：`Result` 增 `ignored`；选择规则；缺分项不再阻断；API 响应增 `ignored`。
- `RubricCriteria`：同一选择规则；名称上限 500；「是否」例外。
- `web/src/components/rubric.ts`：`RubricSummary` 增 `ignored`；同一选择规则。
- 「AI 将看到」：编号清单、缩进、分值右对齐；ignored 不列出，只提示数量；「合计一致」不再写
  「可以发布」（本地不查重名，不能替后端承诺）。
- 测试：`docs/rubrics/fixtures/rubric-cases.json` + `RubricValidatorCasesTest` + `RubricCriteriaTest`
  + `web/tests/rubric-summary.test.mjs`。

## 验证

- 同一段 rubric：`POST /api/rubrics/preview` → `valid=true`、4 项 2/6/3/3、合计 14、`ignored` 2 条；
- 判分侧 `RubricCriteria.parse` → 4 项 2/6/3/3（不再 6×2.3，92 字符问句保留）；
- 页面：4 条编号、无两条说明、合计 14/14、提示「另有 2 行没有分值」；
- `make test`（含新增用例）与 `check-i18n` / `check-ui` 通过。

## 2026-10-25 追记（T470）：有序列表行不再强制要求「点」

现象：试卷向导第 3 步的评分项编辑器是 Vditor 的即时渲染视图；老师手打「1. 要点」时编辑器把它接成有序列表，
回写的 Markdown 是「1 要点（10 分）」——**数字后面没有点**。三处解析器都要求 `\d+[.)、]\s+`，
于是这一行在预览、校验、判分里全都不算评分项（老师看到的「（10 分）」等于没写）。

修法：`NUMBERED` 放宽成 `^\s{0,3}\d+(?:[.)、]\s+|\s+)(.+?)\s*$`，三处逐条同步
（`RubricValidator`、`RubricCriteria`、`web/src/components/rubric.ts`），夹具新增
`ordered-list-without-dot-vditor`（3 项各 5 分、合计 15），三套测试一起跑。

边界：`LEADING_NUMBER`（剥行首编号）保持原样，只认带点的写法——放宽它会误伤「5 分题」这类名字；
耐裸数字行本身已由 `NUMBERED` 消费掉编号，不影响名称。纯分值行（`15 分`）在有条目时进 `ignored`；
全文无分值时它才可能成为评分项，属于既有边界，未扩大处理。
