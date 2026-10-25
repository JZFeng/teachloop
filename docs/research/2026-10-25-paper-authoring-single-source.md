# 试卷「单源文本 → 结构化题目」调研：同类系统怎么做，替换重建踩什么坑（2026-10-25）

## 0. 结论

1. **「单源文本 → 解析成结构化题目」是成熟做法，但成熟的是受限 DSL，不是自由 Markdown**：Moodle 的 GIFT 自 2003 年就在做，Canvas 走 QTI 包，问卷星、ClassMarker 各有一套自己的标记法。
2. **重导的主流是"追加"，不是"替换"**：Moodle 每导一次就新建一批题行；Canvas 默认不覆盖，覆盖要显式打开 `overwrite_quizzes`；只有 ClassMarker 提供"按 Question ID 逐题覆盖"。
3. **解析预览不是行业标配**：Moodle 是"边解析边写库 + 遇错即停"，ClassMarker 没有导入前预览，Canvas 的"选择性导入"选的是包里的对象而不是逐题。不做预览的反例是 Google Forms——它干脆不解析。
4. **「整卷删了重建」在试卷/题库类产品里没有查到先例**；有先例的是"重新编译"类工具（LaTeX `exam`、quizdown），前提是源之外没有可编辑的数据副本。我们仓库现状恰好是相反语义：`PaperEndpoint.java:532-533` 是"题号已存在则跳过、不覆盖"，改成替换重建是**策略反转**，不是换个入口。
5. 风险集中在一处：题行上挂着界面内补的东西（`ObjectiveItem.rubricMarkdown`、`explanationState`、`SectionCriteria`），删行重建必然冲掉。缓解见 §3，共 5 条。

### 0.1 这份文档怎么核实的

- 每条结论都能回到一个**实际打开的 URL**（§5），或本仓库一个 `file:line`。
- 抓取失败的来源不拿来当证据，一律进 §4「未查到」：`guides.instructure.com`（跳 Okta 登录）、`support.kahoot.com`（Cloudflare 403）、`kaoshiyun.com` 帮助页（200 但正文空）。
- Moodle 的用户手册 `docs.moodle.org` 抓取时被其验证码页挡住（HTTP 403），因此改用**同产品的官方源码与开发者文档**（`moodledev.io` + `github.com/moodle/moodle`），并在 §5 标注哪条是源码、哪条是文档。
- 凡写"未查到"的地方，就是没查到，不做合理推测。

## 1. 四个问题

### Q1「单源文本 → 解析成结构化题目」是不是成熟做法？谁在用？限制是什么？

**成熟，且早于"AI 出卷"二十年。** 逐个确认到的形态：

- **Moodle（GIFT / Aiken / Moodle XML）**：GIFT 的类注释原文写着"designed as an easy to use method for teachers writing questions as a text file"，并列出语法：单选 `{=对 ~错}`、判断 `{TRUE}`、简答 `{=答案}`、数字 `{#1822:5}`、匹配 `{=Canada->Ottawa}`，`//` 是注释、`::题名::` 是可选题名。三类格式同属 qformat 插件家族（`public/question/format/gift/format.php`）。
- **Canvas（QTI）**：内容迁移接口允许的 `migration_type` 里包含 `qti_converter`，即导入 QTI 包。
- **问卷星（中文平台）**：帮助中心"从文本创建考试"给出整卷文本规则——判断题在题尾标（对）（错）或（√）（×），单选题把正确项用括号括起来、或写"答案：X"，多选题同理，填空题用 `{}` 把答案框在句中、或用下划线加"次行写答案"，简答题无特殊标记，可用"答案解析："补解析。
- **ClassMarker**：按题型各给一份 CSV 模板（选择/多选/判断/匹配/自由文本/语法/作文/问卷题），一行一题。
- **LaTeX `exam` 文档类**：回答的是另一半问题——文本即源、编译产物即试卷；题目没有独立数据库（CTAN 包页）。
- **quizdown（Markdown 出卷专门工具）**：Markdown 里写 `1. [x] Berlin`，前端即时渲染成交互题；README 自述"toy project"，仓库已于 2025-11-19 归档。

**为什么必然是一门受控语法，而不是自由 Markdown**：这六个对象里，没有一个是"教师随便写一段散文，系统猜出题号、选项和答案"的。原因很直接——**哪一段是题干、哪一行是答案，必须能无歧义地判出来**，否则解析失败率会高到没人敢用。所以它们各自发明了标记：Moodle 用花括号，问卷星用（对）（错）/（正确答案）/`{}`，ClassMarker 用固定列，Canvas 用 XML。这就是我们 `PaperTextParser` 要长期维护的那门方言：**它越像自然语言，越难保证"同一份原文永远解析出同一份题"**。

**对我们的真正成本**：不在"解析器写不写得出来"，而在**同一门方言要三处同步**——解析器（`PaperTextParser.java`，1359 行）、预览表、给教师看的语法说明。方言每加一个写法，三处都得改，且三处一旦不同步，就会出现"预览一套、写库另一套"。

### Q2 用户改了原文再导入时，成熟产品怎么处理已有题目？各自代价？

| 策略 | 产品 | 依据 | 代价 |
| :--- | :--- | :--- | :--- |
| **追加（默认，最常见）** | Moodle | `importprocess()` 分两个阶段：STAGE 1 解析文件、STAGE 2 逐题 `insert_record('question', ...)` 并新建 `question_bank_entries` / `question_versions`；从不按内容查重，同一 idnumber 撞了也只是 `unset($question->idnumber)` 把 id 丢掉 | 重复题、题库膨胀、靠人手工删；好处是永不误删 |
| **追加 + 可选题级覆盖** | Canvas | `settings[overwrite_quizzes]`＝"Whether to overwrite quizzes with the same identifiers between content packages"；官方文档只给到 quiz 级，向 Instructure 文档问答接口追问题级语义，答复明确说 docs don't establish question-level behaviour | 覆盖是"按标识符"，前提是先有稳定标识符；题级行为官方没承诺 |
| **逐题 diff 更新（按 ID）** | ClassMarker | 官方手册把导入定义成两件事："Upload new Questions from a CSV file" 与 "Update your existing Questions from a CSV file"；后者要求先导出既有题、保留 Question ID 与 Question Type、改完再导回 | 教师要做一次"导出→改→导入"往返；这是唯一既不出重复也不清零的路子，但把版本管理推给了文件 |
| **不允许重导** | Google Forms | 唯一"导入"是 2019 年 7 月的"从既有表单导入问题"（见 Q3），没有文本解析入口 | 没有重复与覆盖问题，代价是录入成本全在人身上 |
| **未查到** | 问卷星 | 帮助页只描述"从文本创建考试"的文本规则，未写重导行为 | — |

一句话：**行业用"追加"换安全，用"按 ID 覆盖"换准确，用"不解析"换零风险**；"整卷替换"不在这三档里。

这两条最容易被误读，先说清：

- **Moodle 的"追加"连 idnumber 都不救**。撞号的题不会更新，只会被你丢掉 idnumber——所以"重导更新"这件事在 Moodle 里根本不存在，别拿它当"覆盖"的依据。
- **Canvas 的 `overwrite_quizzes` 不是"覆盖题库"**。它按 content package 里的标识符覆盖 *quiz*，官方明确没有把语义降到单题。要"按题覆盖"就得自己设计标识符，这正是 ClassMarker 用 Question ID 做的事。

### Q3 解析预览/校验界面在成熟产品里承担什么职责？有没有反例？

先把"解析预览"分三档，否则不同产品的"预览"会被混为一谈：

- **A. 范围选择**：导入前告诉你"包里有什么"，你选要哪些（不展示逐题字段）。
- **B. 题级预检**：逐题展示"系统将要写入的题干/答案/分值"，可改可弃，再提交。
- **C. 结果回显**：先写库，再把写进去的东西列出来。

各家的落点：

- **Moodle：C，没有 A/B**。解析与写库在同一次请求里（`importprocess()` 的 STAGE 1 → STAGE 2），过程中逐条回显题目文本，解析错误用 `error()` 打印成 `importerror` 块，配一个 stop-on-error 开关（`setStoponerror`）。它的职责分工是：**"预览"就是导入结果页**。
- **Canvas：A**。`selective_import` 让迁移停在 `waiting_for_select`，再 `GET .../selective_data` 列出包内内容（quizzes、assessment_question_banks 等），然后 `PUT` 执行。职责是**选哪些对象**，不是逐题看字段。
- **ClassMarker：没有 A/B**，导完才在题目列表里看到（相当于 C）。
- **反例（不做预览，且不做解析）**：Google Forms 只支持"从既有 Google 表单导入问题"这一种导入，没有 docx/txt 解析入口——它用"取消解析"把风险整块删掉。
- **对我们的意思**：我们要的是 **B**，而 B 在三家里一个都没有。这不代表 B 错，代表 B 更严；但 B 的正当性必须自己挣：它替代了 Moodle 的 stop-on-error（把坏数据挡在写库前）。所以预览表必须与写库**共用同一次解析结果**，否则它只是个装饰——而且是个会骗人的装饰。

### Q4 我们选的「替换重建」有没有先例？会踩什么坑？

**没有查到题库类产品的先例。** 查到"重建安全"的场景都是编译器型：LaTeX 每次重编译 PDF、quizdown 每次由 Markdown 重渲染——共同点是**没有第二份可编辑数据副本**。一旦派生数据可以被人在界面上改，重编译就变成"覆盖"，必须先回答"改了的那份怎么办"。

我们不是这个场景。当前模型里，题行上挂着界面内补的东西：

- `ObjectiveItem.rubricMarkdown`（`ObjectiveItem.java:52`）——"第 4 步一题一份 rubric"就落在这里；
- `ObjectiveItem.explanationState`（`ObjectiveItem.java:43`，默认 `"none"`）——AI 解析状态；
- `SectionCriteria`——通过 `paperSectionId`（NOT NULL）挂在"部分"上，是独立表。

删行重建 = 这三样一起归零；而 `SectionCriteria` 还会因为 section 换新 id 变成孤儿或错挂。再加上 `ObjectiveItem.number` / `position` 是重建时重算的派生字段，任何靠题号引用它的地方（报告、批改记录）都会漂移。

还有一条容易被忽略的事实：**现状是反方向的**。`PaperEndpoint.java:532-533` 现在做的是"题号已存在，已跳过（不覆盖已有题项）"——即 Moodle 式追加。改成替换重建，得先承认这是有意反转，而不是顺手加个按钮。

## 2. 对比表

| 对象 | 作者化方式 | 重导处理已有题目的语义 | 是否有解析预览 | 可复核来源 |
| :--- | :--- | :--- | :--- | :--- |
| Moodle（GIFT / Aiken / XML） | 纯文本文件，自有标记语法（`{=对 ~错}`、`{TRUE}`、`#1822:5`） | **追加**：每次导入都新建题行，不查重；同 idnumber 只丢 id 不合并 | **无预检**（C 档）：解析与写库同一次请求，逐条回显 + 遇错即停开关 | [GIFT 源码](https://raw.githubusercontent.com/moodle/moodle/main/public/question/format/gift/format.php)、[导入基类源码](https://raw.githubusercontent.com/moodle/moodle/main/public/question/format.php) |
| Canvas（QTI） | QTI 包（XML 打包） | **默认追加**；`overwrite_quizzes` 打开才按标识符覆盖，且文档只到 quiz 级 | 有**范围选择**（A 档），非题级预览 | [Content Migrations API](https://developerdocs.instructure.com/services/canvas/resources/content_migrations.md) |
| LaTeX `exam` | `.tex` 源码，编译出 PDF | 无此概念：没有题目库，每次重编译 | 无预览概念；PDF/编译日志即结果 | [CTAN: exam](https://ctan.org/pkg/exam) |
| 问卷星 | 整段文本，"在线考试 → 导入文本"，题尾标（对）（错）/（正确答案）/`{}` | **未查到**（帮助页只写创建流程） | **未查到** | [从文本创建考试](http://qywx.wjx.cn/help/help.aspx?helpid=252) |
| Google Forms（反例） | 只能在表单里逐题建 | 无文本重导；唯一"导入"是从既有表单导入 | 不适用（不解析） | [Workspace 更新 2019-07](https://workspaceupdates.googleblog.com/2019/07/import-questions-forms.html) |
| quizdown（Markdown 出卷） | Markdown 里写题，前端渲染 | 无此概念：Markdown 是唯一源，无库可写 | 不适用（渲染即所见） | [README](https://raw.githubusercontent.com/bonartm/quizdown-js/main/README.md) |
| ClassMarker | 每题型一份 CSV 模板，一行一题 | **两种模式并存**：导入新题（追加）／按 Question ID 覆盖既有题（须保留 ID 与题型） | 无导入前预览（只有 C 档） | [Importing Questions](https://www.classmarker.com/online-testing/manual/importquestions) |

## 3. 对我们的结论

**「替换重建」在试卷/题库类产品里没有先例**；能拿出的最接近参照只有两类：编译器型工具（LaTeX `exam`、quizdown，重建之所以安全是因为源之外没有可编辑副本），以及 ClassMarker 的"导出→改→按 ID 导回"（它证明：**要保住界面内的编辑，编辑就必须在文件里有锚点**）。我们既不打算让源之外没有编辑，也不打算给教师加一轮导出往返，所以只能靠下面 5 条把风险压住。

1. **重建前 diff 出"会丢失的界面内编辑"，并给出数字。** 至少要数三项：`rubricMarkdown` 非空的题数、`explanationState != 'none'` 的题数、`SectionCriteria` 行数。任一 > 0 才弹二次确认，文案里带具体数字（而不是"已有内容将被覆盖"这种无信息量的话）。**验收**：三项都为 0 时不弹；任一项为 1 时弹窗文案里必须出现该数字。
2. **把 rubric 也变成源的一部分。** 解析器已经能产出 `parsedItem.rubricMarkdown()`（`PaperEndpoint.java:547`），说明"rubric 写在 markdown 里"这条路技术上已通；第 4 步应要求一题一份 rubric 写进原文，界面上的 rubric 只读或"改完回写原文"。这是唯一能让替换重建自洽的做法，也是 LaTeX/quizdown 那类先例成立的真正前提。**验收**：构造一份带 rubric 的原文导两次，两次产出的 rubric 文本逐字相同。
3. **已发布卷禁止重建。** `PaperStatus` 已有 `PUBLISHED`（`PaperStatus.java:3`），规则定为"PUBLISHED 只能复制成新卷再重建"，把题号/分值漂移隔离在草稿态。**验收**：对 PUBLISHED 卷调生成接口返回拒绝，且库里 section/item 数量不变。
4. **重建前留一份可撤销快照。** 把将被删除的 section/item/criteria 序列化一份（JSON 或上一版原文）。Moodle 没做这件事、ClassMarker 靠"让教师先自己导出"兜底——我们不能指望教师会导出。**验收**：重建后能用快照把题数、rubric、评分项恢复成重建前的值。
5. **预览表复用同一次解析结果，并直接给差异。** 除既定的"部分/题号/题型/分值/题干/标准答案/状态"，再加"将删除 / 将新建"两列，让预览表回答"这次点下去会少什么"。这也顺带解释了为什么预览必须只读：可编辑预览等于开第二个写入口，单源当场失效（ClassMarker 的 CSV 往返就是这个代价的现存实例）。

### 3.1 这套结论反过来对解析器的要求

替换重建一旦成立，`PaperTextParser` 的角色就变了：它不再只是"往库里加题"的搬运工，而是**唯一的事实生产者**。因此它至少要能稳定输出：

- **稳定题号**（`ParsedItem.number`）——重建后同一份原文必须得到同一组题号，否则"将删除/将新建"的 diff 无意义；
- **每题的分值与题型**——否则重建会让卷面总分漂移（现有路径用 `section.getPointsPerItem()` 兜底，见 `PaperEndpoint.java:527-528`；原文不写分值时，重建前后必须仍取同一兜底值）；
- **写作题的 rubric 文本**——已具备（`PaperEndpoint.java:547`），但需要把"缺 rubric"从"允许为空"改成"预览里报错"。

## 4. 未查到（不猜，列清楚）

- **问卷星的重导语义**：帮助页只描述创建时的文本规则。
- **Canvas 的题级重导语义**：官方 API 文档只到 quiz 级；Instructure 的 Instructor Guides（`guides.instructure.com`）2026-10 访问时跳 Okta 登录页，正文无法复核。
- **考试云**：`https://www.kaoshiyun.com/help/044422.html` 返回 200 但正文为空，未取到内容。
- **超星学习通整卷导入**：未找到可访问的官方帮助文档。
- **Kahoot 的 XLSX 导入**：官方帮助页被 Cloudflare 拦截（HTTP 403）。
- **Anki 文本导入的"更新既有笔记／忽略"选项**：官方手册页可取到，但正文被页面导航截断，未作为证据使用。

## 5. 引用清单（访问日期均为 2026-10-25）

1. https://moodledev.io/docs/5.3/apis/subsystems/question —— 证明 Moodle 把题目导入/导出划为 qformat 插件子系统，是官方文档层面的入口说明。
2. https://raw.githubusercontent.com/moodle/moodle/main/public/question/format/gift/format.php —— 证明 GIFT 是给教师写文本用的语法，并给出 `{=对 ~错}`、`{TRUE}`、`{#1822:5}` 等具体规则（官方源码）。
3. https://raw.githubusercontent.com/moodle/moodle/main/public/question/format.php —— 证明导入是"解析→逐题 insert_record"，不查重、无覆盖，且无预检阶段（官方源码）。
4. https://developerdocs.instructure.com/services/canvas/resources/content_migrations.md —— 证明 Canvas 有 `qti_converter` 迁移类型、`overwrite_quizzes` 覆盖开关、`selective_import` 选择性导入。
5. https://developerdocs.instructure.com/services/canvas/resources/content_migrations.md?ask=When%20importing%20a%20QTI%20package%20into%20Canvas%20twice… —— 证明"题级覆盖语义官方未定义"，避免我把 quiz 级覆盖误写成题级覆盖。
6. https://ctan.org/pkg/exam —— 证明 LaTeX `exam` 是"文本即源、编译出卷"，题目分值等都在源码里声明。
7. http://qywx.wjx.cn/help/help.aspx?helpid=252 —— 证明问卷星有整卷文本导入入口及其标记规则（判断题尾标注、`{}` 填答案等）。
8. https://www.classmarker.com/online-testing/manual/importquestions —— 证明 ClassMarker 同时提供"导入新题"与"按 Question ID 覆盖既有题"两种模式。
9. https://workspaceupdates.googleblog.com/2019/07/import-questions-forms.html —— 证明 Google Forms 的导入只有"从既有表单导入问题"一种，没有文本解析（标题即结论）。
10. https://raw.githubusercontent.com/bonartm/quizdown-js/main/README.md —— 证明存在 Markdown 出卷工具，且其源即唯一内容源、项目已于 2025-11-19 归档。
