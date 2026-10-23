# T340 分组标题/说明不再算评分项，「AI 将看到」改成编号清单（2026-10-22）

决策见 `docs/decisions/005-rubric-unpointed-items.md`。

## 现象

教师 rubric 里同时用 `#` 写评分项和分组标题/说明：

```
# Did the learner submit a lesson plan...? （2 points）
# Did the learner include all the components...? （6 points）
# Lesson Plan Components
# If any part is missing, you'll be asked to edit and resubmit.
# Did the learner use at least one intensive reading activity...? （3 points）
# Did the learner use at least one extensive reading activity...? （3 points）
```

「AI 将看到」列出 6 条，其中 2 条是分组标题/说明（分值 `—`），于是提示「有评分项没写分值」——
但这段 rubric 合计 14 = 总分 14。更糟的是后端会因这两条 `missingPoints` 拒绝发布，
判分侧还会因为「明示合计已占满 + 仍有缺分项」整体均分（6×2.3）。

## 真因

同一套选择规则被抄了三份，且已漂移：

- `RubricValidator.java`：区域内有标题就只收标题，不看分值 → 两条说明变成缺分项 → 发布 400；
- `RubricCriteria.java`：同样规则；名称上限还是 **80**（92 字符的 6 分问句会被丢）；缺分时整体均分；
- `web/src/components/rubric.ts`：同样规则，把无分值项也返回给面板。

## 改法

- 三处统一选择规则：有带分值的标题 → 只取带分值的标题；否则有带分值的列表项 → 只取带分值的列表项；
  全文没有分值才走旧的「标题优先 / 均分」。没有分值的条目进 `ignored`，只提示、不阻断。
- 三处统一名称上限 500、`isDecision` 的「是否…」例外。
- 校验 API 响应增加 `ignored`；「AI 将看到」不再显示 ignored 行，只在底部提示数量；
  列表改成编号 + 缩进 + 分值右对齐；「合计一致」不再写「可以发布」（本地不查重名）。
- 共享夹具 `docs/rubrics/fixtures/rubric-cases.json`，三个测试共用。

## 验证

| 检查 | 结果 |
| :--- | :--- |
| 同一段 rubric 发布校验 | `POST /api/rubrics/preview` → `valid=true`、4 项 2/6/3/3、合计 14、`ignored` 2 条、issues 空 |
| 判分侧解析 | `RubricCriteria.parse` → 4 项 2/6/3/3（不再 6×2.3；92 字符问句保留） |
| 页面 | 4 条编号 1–4、缩进与分值右对齐、无两条说明、合计 14/14、底部「另有 2 行没有分值，未计入评分项」 |
| 文案 | 页面不再出现「有评分项没写分值」「可以发布」（这段 rubric 下） |
| 夹具测试 | 校验 7 例、判分 5 例、前端 7 例 + 2 条专项（共 14 条），全绿 |
| 全量 | `make test` 退出码 0：surefire **255 / 0 / 0 / 0**，前端 **14 / 14**；`check-i18n` 1164 键、`check-ui` 无新增违规 |

## 截图

| 文件 | 内容 |
| :--- | :--- |
| `panel-before.png` | 用户反馈的原始状态：6 条里夹着 2 条说明，提示缺分值 |
| `panel-after.png` | 修复后：4 条编号、分值右对齐，底部「另有 2 行没有分值」 |
| `page-after.png` | 页面整体（导入这段 rubric、总分填 14） |

## 边界

- 真的忘写分值的评分项不再阻断，而是被忽略并提示；文本上无法可靠区分「分组标题/说明」与
  「忘写分值的评分项」，阻断会重现本次问题。若以后要做成阻断，需要教师显式标注结构。
- 前端仍是本地摘要（不是 `POST /api/rubrics/preview`）；本轮用共享夹具锁规则，切换后端单一真源
  留作下一步。
- 旧 rubric 不迁移；下次判分按新规则解析，带无分值标题的旧 rubric 分配结果会变化。
