# 写作题级 rubric 与答案区导入修复 — 验证报告

日期：2026-10-22　任务：T360–T367　决策：D-23~D-27　决策文档：
`docs/decisions/007-essay-per-item-rubric.md`

## 1. 用户报的两个问题

| 输入 | 修复前 | 修复后（实测） |
| :--- | :--- | :--- |
| `整个考卷含写作.md` | 第 191 行报「无法解析的答案行「15. 应用文写作示范范文：」」 | 0 报错；4 部分 16 题：14 道客观题 + 15/16 两道 `ESSAY`（各 15 分）；两篇范文分别绑到 15、16；答案区标题行不报错 |
| `paper and answer.md` | 能导入，但第四部分写作 0 题；总分 120（卷面 150）；rubric 只有一份 | 77 题：75 道客观题 + 76/77 两道 `ESSAY`（各 15 分）；总分 **150**；`missingAnswers` 为空 |

复现与验证方式：用仓库里的真实解析链路
`DocumentTextExtractor.extract(...)` → `PaperTextParser.parse(...)` 跑两份原始文件（`.md` 先过 `MarkdownSyntax`），
再用 `FlowIntegrationTest.twoEssaysAreGradedPerItem` 走 HTTP 全链路。

## 2. 改了什么（按层）

- **数据**：`V036` 给 `objective_items` 加 `rubric_markdown` / `reference_markdown`（可空、无索引、不进总分）。
- **解析**：写作/口语部分建 `ESSAY` 题项——有「第X节」按节一题，否则按带题号的写作任务行一题；
  节内 `1. 表达理解；` 这类编号行是内容要点，不拆题；分值接受 `满分 N 分` / `(N points)` / `（N分）` /
  `N pts`，小节接受 `每小题 1.5 points` 与 `1.5 points each`。
- **参考范文**：答案区里以写作题题号开头的行进入范文态，直到下一个写作题号或一条可解析的客观答案行；
  范文里以数字开头的行不再报错；没有题号只提醒、不猜归属。范文只存题项、只给教师端。
- **判分**：每道作文一次调用（空白不调用）；正文取 `answers[]` 该题号的作答（仅单篇作业回退
  `content_text`）；本题 rubric 优先，空回退试卷级；合并后主观分求和、逐项评分带 `itemNumber`、
  评语按题分段；prompt 升 `subjective-v3`，system 顶部写「本题：第 N 题（满分 X 分）」。
- **前端**：向导第 4 步每道写作题一块 rubric 编辑器 + 参考范文编辑器；导入页写作题提示；
  复核页 AI 评分项按题分组、写作题 rubric 可展开。

## 3. 自动化证据

| 检查 | 结果 |
| :--- | :--- |
| `make test` | 退出码 0；surefire **286 tests / 0 failures / 0 errors / 0 skipped**（paper 81 / sitting 38 / grading 65 / app 91 / shared 11） |
| 前端金标 | `pnpm test` 28/28 |
| 依赖/边界 | `check-deps` 通过；`check-boundaries` 25 表 / 13 外键 / 7 端口 |
| 文案 | `check-i18n` 通过：zh/en 各 1219 键、新增中文字面量 0 |
| 界面标准 | `check-ui` 通过（无新增违规） |
| 导入回归 | `WritingImportTest` 5/5、`RealExamImportTest` 9/9、`RealExamWithAnswersTest` 4/4、`PaperTextParserTest` 28/28、`AnswerTableParserTest` 13/13 |
| 端点 | `PaperImportEndpointTest` 10/10（含写作题落库、非 ESSAY 传 rubric 被拒） |
| 判分 | `AiGradingEngineTest` 8/8（两篇两次调用、prompt 不串题、空白 0 调用、`answers[]` 取正文） |
| 快照兼容 | `SnapshotCodecTest` 2/2（旧快照没有 `rubricMarkdown` → null；新快照往返一致） |
| 全链路 | `FlowIntegrationTest.twoEssaysAreGradedPerItem`：建卷 → 布置 → 学生按 `answers[]` 交两篇 → 逐篇判分 → 复核 `ai_criteria` 含 `itemNumber` 15/16，主观分 22.6（stub 0.75 × 30） |

## 3.5 没有 rubric 时的默认判分（D-28）

用户当晚补充：「基本上就是把题目整个内容塞给 AI（80% 分数），再加一点基本的语法和拼写检查（20% 分数），
百分比做成 config。」

- 题项级 rubric 与试卷级 rubric 都为空时，判分侧按配置合成两项：**内容与任务完成**（X%）+
  **语法与拼写**（100−X%），默认 **80/20**；分值按本题满分拆（内容四舍五入取整：15 → 12+3，14 → 11+3，70% → 11+4），两项相加等于满分。
- 配置：`teachloop.grading.essay-default-rubric.content-percent`（application.yml，默认 80；env
  `TEACHLOOP_ESSAY_CONTENT_PERCENT`）。只留一个旋钮，语法取其余；1–99 之外启动失败。
- 老师写了 rubric（题项级或试卷级）→ 永远优先，不会被默认比例覆盖。题项级在同一步每道写作题里填
  （`PUT /items/{n}`），试卷级在同一步最下方「试卷默认 rubric」（`PUT /papers/{id}/rubric`）。
- 题目全文（`stem`）始终完整进 prompt 的「题目要求」，不截断；英文反馈时 rubic 文案用英文。
- 移除了 `PaperCatalogImpl` 里「伪造一份无分值默认 rubric」的旧逻辑（那会盖住默认并被 50/50 均分）。
- 向导第 4 步回显当前比例（`PaperDetail.essayContentPercent`），空 rubric 的题目提示「按系统默认
  内容 X% + 语法与拼写 (100−X)%」。
- 旧版系统伪造的无分值默认 rubric（「内容要点 / 语言准确性」）判分时被识别为「没有 rubric」，
  存量卷子直接享受新比例，不用重新布置。

验证：`EssayRubricWeightsTest` 6/6、`AiGradingEngineTest` 10/10（含 70% 配置与题目全文断言）、`AiGradingConfigTest` 5/5（70 可启动、0/100 启动失败）。

## 4. 没验证的（不许当成已验证）

1. **浏览器交互与视觉**：本轮没有重启 dev/QA 前端，向导第 4 步的写作题块、导入页提示、复核页分组
   只做了 `tsc --noEmit` / `pnpm build` / 静态检查，没有截图与点击证据。
2. **真实 DeepSeek 判分**：测试全部走 stub 引擎；`subjective-v3` 的 golden set（10 份人工基准）
   没有重跑，逐项一致率与 MAE 未知。
3. **无题号范文的自动归属**：`paper and answer.md` 的写作范文没有题号，按 D-25③ 只提醒、不保存；
   若老师希望自动绑定，需要再定规则（按「节标题关键词」匹配）。
4. **`整个考卷含写作.md` 的总分**：该文件没有写每道客观题的分值，导入后客观题为 0 分（写作 30 分），
   与文件头的「总分 28 分」不一致；这是文件本身缺信息，不是解析缺陷，老师需在向导里补分值。

## 5. 手测建议（下轮）

1. 重启 dev（或 `make run`）→ 导入 `整个考卷含写作.md` → 向导第 4 步应看到 15/16 两块 rubric 与参考范文。
2. 给 15/16 各填 4 条带分值的评分项（`(5 points)`、`（5分）`、`（5 points）`、`(3分)` 混写）→ 保存 → 刷新回填。
3. 布置成考试 → 学生交两篇作文 → 派发 → 复核页 AI 评分项按「第 15 题 / 第 16 题」分组。
4. 再导入 `paper and answer.md`，确认总分 150、第四部分两道写作题、范文提示为「没有题号，未保存」。
