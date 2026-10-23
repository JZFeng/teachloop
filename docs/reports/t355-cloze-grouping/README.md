# 同一篇材料只渲染一次：空数 ≠ 题数时又退化成逐题重复（T355）

## 现象

学生答题页 `/exams/<offeringId>/live`（场次 `819141a4-33eb-4a85-a5c9-3274f4ca7ed7` / 试卷「AI立即」）里：

- 第 7 题、第 8 题各渲染了一整篇同样的七选五文章，各自的答案在文章里带题号的空上；
- 第 9、10 题（完形）把 `I walked into the room. 20 years have gone by...` 渲染了两次；
- 第 11、12 题（语法填空）把 `Entering senior high school is an exciting but...` 渲染了两次。

期望：整篇材料只出现一次，作答就在原文对应空位上——选择题点空弹选项、填空题行内输入。

## 先查提交历史

T321「共用材料只渲染一次」已经做过这件事，且没有被回退：

- `MaterialGrouper`（`teachloop-sitting/.../MaterialGrouper.java`）在 `ExamSessionEndpoint.paper`
  返回前把快照分成 READING / BLANK / SOLO 三种节点；
- 前端 `ExamPaper` 按 `groupKey` 渲染，`ExamPaperItemView` 带 `groupKey/material/questionStem/blankIndex`；
- 落盘提交：`2a9a2a1 fix(sitting): 空标记后台清洗…；并入 T320–T323 未提交改动`（2026-10-21）。

所以这次不是「改好的被删掉」，而是这一份卷子的形状落在它的校验之外，整组回退到「每题自带题干」。
T321 的回退规则写的是「宁可重复，也不把题拆错」，本条的取法是：能确定落位的就渲染一次。

## 真因（三条，同时命中才出现）

1. **空数 ≠ 题数 → 整组退回 SOLO。** 七选五原文有 5 个空，这份卷只导入了第 7、8 两题
   （标准答案也只有 `7=G`、`8=A`）。`validBlankGroup` 原来要求
   `blankCount(material) == group.size()`，5 ≠ 2，整组退回逐题渲染。
2. **兄弟题的题号提示不一致 → 两份材料不相等。** 快照里同一篇材料在每道题的题干里各存一份，
   导入器只给「本题自己的空」打 `___(N)___` 标记，别的空保留卷面提示：

   | 题 | 第 1 个空 | 第 2 个空 |
   | :--- | :--- | :--- |
   | 9 | `___(9)___` | `______ (10)` |
   | 10 | `______ (9)` | `___(10)___` |

   `BlankText.forGrouped` 只收 `___(N)___`，不处理 `______ (N)`；两份材料差一个括号，
   `material.equals(...)` 不成立，分组直接断在第 1、2 题之间。11/12 同理。
3. **前端按顺序配对。** `BlankGroup` 用游标把材料里的第 k 个空直接配给 `node.items[k]`，
   没有「空位序号」的概念，也没有渲染「没有对应题目」的空位；后端一放宽，多余的空会被吞掉。

## 改法

- 后端 `MaterialGrouper`
  - `normalize` 在 `BlankText.forGrouped` 之后，再洗掉「本卷真实题号」的空位提示
    （`______ (9)` → `______`）。只洗题号在卷内存在的括号，`______ (2019)` 这类正文括号不动。
  - `validBlankGroup` 改为：空数 ≥ 题数；每题的空位序号在 `[0, 空数)` 内、严格递增、不重复；
    选项形态一致；多选/主观题仍不参与。
- 前端
  - `examUtils.makeGroup` 同一口径：`空数 < 题数`、序号越界、序号重复 → 退回逐题渲染。
  - `ExamPaper.BlankGroup` 按 `blankIndex` 落位、不再按数组下标；没有对应题目的空位渲染成
    `.inline-blank-gap`（只是一条横线，没有作答控件）。
- 新增回归测试
  - `MaterialGrouperTest` 13/13：5 空 2 题、兄弟题号提示、语法填空括号提示词、一个空放两题、
    题比空多、正文数字括号不误洗。
  - `web/tests/exam-grouping.test.mjs` 6/6：空比题多、题空一一对应、重复序号、序号越界、
    题比空多、阅读组不受影响。

## 验证

- 真实快照（14 题）跑 `MaterialGrouper`：`g5`（阅读）/`g7`（5 空，控件在 0、1）/`g9`（2 空）/
  `g11`（2 空）/ 13、14 独立；材料里不再有残留的 `(9)`、`(10)` 提示。
- 接口：`GET /api/offerings/819141a4…/paper` 返回 `7→g7/blank 0`、`8→g7/blank 1`、
  `9→g9/0`、`10→g9/1`、`11→g11/0`、`12→g11/1`，材料只在组内第一条。
- 浏览器（CDP 真页面，`scripts/verify/cloze-grouping.mjs`，18/18 通过）：
  - 三篇材料各只出现一次；7、8 两个控件各一个；没有题目的 3 个空位只有横线；
  - 控件顺序 `7, 8, gap, gap, gap`；点第 7 个空弹出 A–G，选 G 后控件显示 `G`、自动保存成功。
- `pnpm test` 28/28、`pnpm typecheck` 0、`check-ui` 无新增违规、`check-i18n` 1200 键对齐。

截图（`docs/reports/t355-cloze-grouping/`）：

| 文件 | 内容 |
| :--- | :--- |
| `group-7-8-answered.png` | 七选五材料一次，第 7 空已选 G、第 8 空待答，其余 3 空是横线 |
| `group-7-8-popover.png` | 点空弹出的 A–G 选项层 |
| `group-9-10.png` | 完形材料一次，两个空各一个控件 |
| `group-11-12.png` | 语法填空材料一次，两个空是行内输入 |
| `live-full.png` | 答题页整体 |

## 未验证 / 遗留

- 七选五那 3 个「只有横线」的空：本卷没有对应题目（答案键只有 7、8），所以没有控件。
  如果它们本该是 9、10、11 题，那是**导入少落了题**，要另修导入器，不是这里的事。
- 行内填空题的「标记」旗标落在句子中间，一句话里有两个空时略挤；沿用 T321 的样式，未改。
- 只验证了 zh-CN + 亮色主题；英文界面与暗色未逐项截图。
- 验证时把这场考试的 `due_at` 临时延后 2 小时并用 `student@teachloop.local` 开了一次新作答，
  验证后已删除该 attempt、把原 attempt 恢复为 active、`due_at` 改回 `2026-10-23 03:00:00+00`。

## 复现命令

```bash
node scripts/verify/cloze-grouping.mjs      # 需要一场未交卷、窗口内的考试
```
