# T320 — 答题页题干开头的孤立字母（2026-10-21）

## 现象

学生答题页每道阅读题开头多出一个字母：Q26 的 `B`、Q30 的 `C`。看起来像把标准答案印在卷面上。

## 根因（不是答案泄露）

- 这一场是 `offering e474ea70-ada1-46e1-935e-ecdfb179a653`（QA 库 `teachloop_qa`）。
  迁移前快照里 Q26 是 `"stem": "B\nZach Smith, an 18-year-old…"`，而 `"answerKey": "C"`；
  Q30 是 `"stem": "C\nMy studies once showed…"`，`"answerKey": "B"`。字母不是答案。
- 题号分组 21–23=`A`、24–27=`B`、28–31=`C`、32–35=`D`，对应四篇阅读材料（Passage A–D）的标签。
  23/27/31/34/35 的标签字母恰好与答案相同，才造成「答案泄露」的错觉。
- 学生端接口本来就不返回答案：`ExamSessionEndpoint.paper()` 的 DTO 不含 `answerKey`，
  `Phase2ExamTimingTest` 已断言 `containsKey("answerKey") == false`。
- 真正的 bug：`PaperTextParser` 识别到单独一行的材料标签后，把标签本身写进了 `material`
  （`PaperTextParser.java` 的 `interpretNumbered` 标签分支），`finish()` 又把材料拼到该篇每道题题干前面。

## 修复

1. `PaperTextParser`：标签只用于切分材料，不再写入 `material`。
2. `V032__paper_strip_passage_label.sql`：清理 `objective_items` 存量题干首行的单个 A–G 字母，
   仅处理剩余题干长度 > 80 的篇章（现存命中项最短 1146 字符）。
3. `V033__sitting_strip_passage_label_snapshot.sql`：就地清理 `offerings.paper_snapshot` 里
   `items[].stem` 的同一前缀；答案、选项、分值、题号顺序与快照其它键不动。

## 验证

- 迁移干跑（QA）：`V032` 命中并清理 30 条题干；`V033` 命中 1 场快照、15 条题干；
  快照结构字段（number/options/answerKey/points/questionType/title/totalPoints）diff 为 0，顺序不变。
- 部署 QA（`./scripts/env.sh deploy qa`）后 Flyway 至 `v033`，两处命中均为 0。
- 学生接口 `/api/offerings/{id}/paper`：Q21 以 “Welcome to join…”、Q26 以 “Zach Smith…”、
  Q30 以 “My studies once showed…” 开头，75 题全部无孤立字母，响应无 `answerKey`。
- 浏览器（localhost:5174，Maria 账号）：Q21–Q35 题干无字母；见 `q26-after.png`。
- 代码：`PaperTextParserTest#doesNotPutPassageLabelsIntoStems` 新增；
  `RealExamImportTest` / `RealExamWithAnswersTest` / `Phase2ExamTimingTest` 全绿；
  `make test` 212/0/0/0；`scripts/check-boundaries.py` 通过。

## 注意

- 材料仍然在每小题里各存一份（`docs/DELIVERY-STATUS.md` 的 D-12 已知限制），本任务不处理。
- 部署 QA 同时应用了工作区里已有的 V029–V031（AI 相关迁移）。
