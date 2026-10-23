# 题级 rubric 的建模与判分：同类系统调研（2026-10-22）

> 取证方式：只读公开帮助文档、API 文档、产品页和厂商公告；文档站被验证码拦截、或产品没有公开说明时，正文直接写「未查到公开说明」。所有链接见文末来源清单。

## 1. 要回答的问题

一份试卷里可能有两道以上主观题（例如「应用文写作」15 分、「口语表达」15 分）。本次改动要让**每道写作题带自己的 rubric**（评分项 + 各项分值），AI 逐篇按各自 rubric 判分，并在导入时识别题面里的 `(5 points)`、`（5分）`、`（满分 15 分）` 等分值写法。因此要回答四件事：

1. rubric 在同类系统里挂在哪一级：题目、作业（assignment/item）还是课程；
2. 是否支持每题不同 rubric、评分项是否带分值；
3. 判分是逐题调用，还是整份试卷一次调用；
4. 参考范文要不要给 AI 看，以什么身份看。

本仓库现状可作对照：`Ports.WorkItem` 只带一份 `rubricMarkdown`，`SubjectiveGradeRequest.from` 把所有 `ESSAY` 题合并进一次请求，输出也只有一组 criteria/total；题项级字段（`ItemSpec.rubricMarkdown`、`ObjectiveItem.referenceMarkdown`）已经存在，但没有进入判分请求。也就是说，缺的不是字段，而是「按题切分调用、按题归属输出」。

## 2. 逐系统调研

### 2.1 Gradescope — rubric 就是「每题一份」

- 来源：[Grading submissions with rubrics](https://guides.gradescope.com/hc/en-us/articles/22249389005709-Grading-submissions-with-rubrics)
- ① 挂载层级：**题目级**。原文写明 “Gradescope rubrics are **created for each individual question**”；同一作业的不同题各有 rubric。
- ② 每题不同 / 分值：支持。list 视图每个 rubric item 自带 points 与描述；grid 视图行是评分项、列是档位分值，另有正分/负分、floor/ceiling 设置。
- ③ 判分粒度：人工逐题。AI 只用于「答案分组」（选择题、填空、单行手写文本），公开文档没有「按 rubric 判作文」。
- ④ 可借鉴：rubric 的主键就是 question；分值是一等字段；rubric 可跨作业导入复用。不可借鉴：它的 AI 不读 rubric，不能拿它证明 AI 逐题判分可行。

### 2.2 Canvas — rubric 挂在作业/测验，criteria 与 ratings 两级分值

- 来源：[Canvas Rubrics API](https://developerdocs.instructure.com/services/canvas/resources/rubrics)
- ① 挂载层级：**作业/课程/账户级**。`RubricAssociation.association_type` 只允许 `Assignment`、`Course`、`Account`，没有 question 关联；测验也只能整份挂一份 rubric。
- ② 每题不同 / 分值：一份作业一份 rubric；`RubricCriterion.points` 是评分项上限，每个 `RubricRating.points` 是档位分；`RubricAssessment` 按 criterion 记 points 与 comments。
- ③ 判分粒度：人工；公开的 AI 能力是生成 rubric（IgniteAI），未查到按 rubric 自动判分的说明。
- ④ 可借鉴：criterion → rating 的两级分值模型，以及「评分结果按 criterion 落点」的审计粒度。不可借鉴：粒度是作业；多作文试卷会被迫共用一份 rubric。

### 2.3 Crowdmark — 按 question label 组织的 comment 库，带分值

- 来源：[Using a rubric](https://www.crowdmark.com/help/using-a-rubric/)
- ① 挂载层级：**题目级**。rubric 由 comment library 实现，每条 comment 带 Label（题目）、Version、Points。
- ② 每题不同 / 分值：支持按题不同；分值挂在 comment 上，可按题导出/导入 CSV。但没有 criterion/档位结构，也没有「评分项上限」。
- ③ 判分粒度：人工/团队逐题，把 comment 拖到学生作答上；未查到 AI 判分说明。
- ④ 可借鉴：用 question label 做归属键，CSV 可批量运维。不可借鉴：comment 库不是 rubric 合同，无法表达「内容 5 分、语言 5 分」并校验总分。

### 2.4 Turnitin Feedback Studio — 一份 assignment 一份 rubric

- 来源：[Creating a rubric or grading form in a submission](https://guides.turnitin.com/hc/en-us/articles/22048301253389--Classic-Creating-a-rubric-or-grading-form-in-a-submission)
- ① 挂载层级：**作业级**。rubric 通过 “Attach to Assignment” 挂到整份作业，不是单题。
- ② 每题不同 / 分值：一份作业一份 rubric；Standard 类型下 criteria 以百分比计且总和必须为 100%，每个 scale 档位可设分值；另有 Qualitative / Custom / Grading form 四种形态。
- ③ 判分粒度：人工打分；公开的 AI 能力是写作检测，不是按 rubric 判分。
- ④ 可借鉴：百分比权重 + 档位描述 + 挂载开关，适合课程级统一标准。不可借鉴：作业级粒度；多题试卷无法逐题不同。

### 2.5 CoGrader — 作业级 rubric，AI 按 rubric 逐篇判

- 来源：[How to Add a Rubric](https://intercom.help/cograder/en/articles/11706304-how-to-add-a-rubric-in-cograder)、[Why Are All My Students Getting the Same Score?](https://intercom.help/cograder/en/articles/15859954-why-are-all-my-students-getting-the-same-score)
- ① 挂载层级：**作业级**（Rubric Library 里的 rubric 被选进一次 assignment）。
- ② 每题不同 / 分值：criteria × performance levels（Advanced / Proficient / Developing / Beginning），每个 level 有分值；公开文档没有「一份作业内多道写作题各自 rubric」的说明。
- ③ 判分粒度：AI 逐篇读 rubric 判分；官方明确说 rubric 只有一个档位时会给所有人满分——这是 rubric 结构导致的系统性偏差。
- ④ 可借鉴：判分前的 rubric 质量闸门（每个 criterion 至少三档、必须有描述），以及 rubric 版本回滚。不可借鉴：它以「一次作业 = 一个写作任务」为前提。

### 2.6 EssayGrader.ai — rubric 库 + 作业级 rubric，逐篇批改

- 来源：[Create a rubric](https://help.essaygrader.ai/en-us/article/step-4a-create-a-rubric-uploadimport-or-build-from-a-template-1i6j5h6/)、[Upload essays and grading settings](https://help.essaygrader.ai/en-us/article/step-5-upload-essays-add-source-materials-and-choose-grading-settings-1nhih8j/)
- ① 挂载层级：**作业级**。rubric 存在库里，一次作业选一份；一次作业配一个 writing prompt。
- ② 每题不同 / 分值：criteria 的每个 level 有分值，最终分是各 criterion 得分之和；支持小数和不同上限，但不支持给 level 设分值区间（文档明确说目前做不到）。
- ③ 判分粒度：AI 逐篇判；rubric 可从 PDF/DOC/图片导入并 OCR，导入后进入 builder 让人改 criteria、descriptor、scoring levels。
- ④ 可借鉴：rubric 导入必须经过「解析 → 人工确认」；writing prompt 与 source materials 被明确声明「不能替代 grading criteria」。不可借鉴：一份作业一个 prompt，装不下多题试卷。

### 2.7 Khanmigo / Khan Academy Writing Coach — 有题目上下文，没有 rubric 和分数

- 来源：[Introducing Khan Academy's Essay Feedback Tool](https://blog.khanacademy.org/new-essay-feedback-tool/)
- ① 挂载层级：**无 rubric**。学生贴入作文，选择年级、作文类型，并贴上教师的 essay instructions。
- ② 每题不同 / 分值：没有评分项与分值；反馈覆盖结构、论据、开头结尾、语气等维度，但不打分。
- ③ 判分粒度：逐篇反馈，学生可追问、看 exemplar、改稿再提交；不产生分数。
- ④ 可借鉴：题目要求是判分上下文的一部分；反馈按维度组织、可追问。不可借鉴：没有 rubric/分值契约，不能用于出分。

### 2.8 Google Classroom — 作业级 rubric，Gemini 只生成不判分

- 来源：[Create or reuse a rubric for an assignment](https://support.google.com/edu/classroom/answer/9335069)
- ① 挂载层级：**作业级**。rubric 建在某一项 assignment 上，可复用、可导出到 Sheets 再导入。
- ② 每题不同 / 分值：一份作业一份 rubric；每份 rubric 最多 50 个 criterion、每个 criterion 最多 10 个 level，level 带 points，总分自动累加；可设 scored / unscored。
- ③ 判分粒度：教师手动打分；Gemini 负责生成/转换 rubric，不按 rubric 自动判作文。
- ④ 可借鉴：导入/转换后必须人工复核；criterion 可绑学习目标。不可借鉴：作业级粒度，题目之间不能各挂一份。

### 2.9 Moodle — 活动级 advanced grading，level 分值求和

- 来源：[gradingform_rubric 源码](https://raw.githubusercontent.com/moodle/moodle/MOODLE_405_STABLE/grade/grading/form/rubric/lib.php)、[Moodle PHP 文档](https://phpdoc.moodledev.io/5.0/d6/d84/group__gradingform__rubric.html)
- ① 挂载层级：**活动级**。rubric 是活动（assignment/forum 等）的一种 advanced grading method，按 grading area 存一份。
- ② 每题不同 / 分值：criteria 下挂 levels，每个 level 有 score；最终分是所选 level 分数之和再按量表换算；支持 lock zero points 等口径。
- ③ 判分粒度：人工逐份；未查到按 rubric 自动判分的公开说明。
- ④ 可借鉴：level score 是唯一真值；改 rubric 会分级触发重判（只改文字、加档位、改分值、删档位对应不同重判范围）。不可借鉴：活动级粒度，一次活动一份 rubric。

### 2.10 Blackboard — 评分项级 rubric，支持 points / percentage 四种口径

- 来源：[Blackboard Rubrics](https://help.blackboard.com/Learn/Instructor/Ultra/Grade/Rubrics)
- ① 挂载层级：**评分项级**。文档写明可关联 assignments、discussions、journals；行是 criteria，列是 achievement levels。
- ② 每题不同 / 分值：支持 points、points range、percentage、percentage range 四种类型；行/列数量不限。对 15 分写作题可直接用 points 型。
- ③ 判分粒度：人工按 rubric 打分；未查到「测试内每道题各挂一份 rubric」的公开说明，也未查到 AI 判分。
- ④ 可借鉴：同一套 criteria 可切换 points / percentage 两种口径，便于课程统一又保留题级总分。不可借鉴：评分项级仍不是题级；多题试卷的逐题归属没有公开做法。

### 2.11 智学网 — 按题型 AI 评卷，rubric 模型未公开

- 来源：[科大讯飞智学网智能评卷系统培训报道](http://szsy.com.cn/aspx/wap/news_show.aspx?id=15926)、[智学网产品入口](https://www.zhixue.com/)
- ① 挂载层级：**题目/题型级**。公开报道称系统可对语文作文、英语作文、英语翻译、文综简答、英语填空做计算机智能评卷，并做空白题检测。
- ② 每题不同 / 分值：按题给分；rubric 挂在哪一级、评分项是否带分值，**未查到公开说明**。
- ③ 判分粒度：按题型评卷、作文按题给分，属于生产规模的 AI 评卷；同篇作文还会做「与题干/外部范文高相似」的异常答卷检测。
- ④ 可借鉴：按题 AI 评卷在规模化学校中已落地；异常相似检测值得作为独立信号。不可借鉴：闭源产品，无法确认其 rubric 契约与分值模型。

### 2.12 七天网络 / 云阅卷 — 作文专用模型逐篇给分，rubric 未公开

- 来源：[智能阅卷系统](https://www.7net.cc/build/product/yuejuan/index.html)、[七天助教发布公告](https://www.7net.cc/build/home/news/index.html)
- ① 挂载层级：**题目/题型级**。阅卷按题组织、扫描评阅；作文由专用模型处理。
- ② 每题不同 / 分值：厂商称作文模型「从内容、结构、语言表达等维度给出评分参考、逐篇点评和修改建议」；rubric 配置与评分项分值**未查到公开说明**。
- ③ 判分粒度：逐篇（逐生逐题）给分与点评，教师复核修改；厂商宣称主观题与作文批阅准确率 96%，这是厂商口径，未见第三方验证。
- ④ 可借鉴：逐篇 + 维度化反馈 + 教师终审的产品形态；分数、错题、错因留痕。不可借鉴：没有公开 rubric 合同，无法照抄其分值模型。

## 3. 横向看：三种粒度，一个共同点

- **题目级**：Gradescope、Crowdmark、智学网、七天网络（按题/题型）。rubric 或评分动作直接绑在题上。
- **评分项级**：Canvas、Google Classroom、Turnitin、Blackboard、Moodle、CoGrader、EssayGrader。它们的 assignment / activity 通常就等于「一个写作任务」，所以不是反例，而是把「题」和「作业」合并了。
- **无 rubric**：Khanmigo，只给反馈不给分。
- 共同点：本次调研中带评分能力的 AI 作文产品，都以「一个题目 + 一份 rubric + 一篇作文」为调用单元；公开文档里没有「整卷多题 + 一份 rubric 一次判」的做法。凡公开说明分值的产品，分值都放在结构化字段里，导入走 CSV / Sheets / OCR + 人工复核；在查到的公开文档里，没有从题干纯文本自动解析分值并直接落库的先例。

## 4. 结论与对本项目的建议

1. **rubric 挂题目级，试卷级只做默认值与回退。** 依据：Gradescope 明确「每题一份」（§2.1），Crowdmark 用 question label 归属（§2.3）；Canvas/Turnitin/Google/Moodle 的作业级、Blackboard 的评分项级做法成立，是因为它们的 assignment/item 等于一个任务（§2.2、§2.4、§2.8–§2.10），而我们的试卷包含多道写作题。落地时：`ESSAY` 题优先读题级 rubric；为空才回退试卷级，并在教师端标出「正在使用试卷默认 rubric」，避免旧卷静默失效；总分按题汇总后与试卷总分对账。

2. **分值语法在导入解析阶段处理，解析结果必须可见、可改，不静默落库。** 依据：凡公开说明分值的产品，都把分值放在 rubric 的结构化字段里（§2.1、§2.2、§2.8），导入都经过 CSV / Sheets / OCR + 复核（§2.6、§2.8），没有从题干文本直接取分的公开先例。建议把语法分成两类：`（满分 15 分）` / `总分: 15 points` 归**题分**；评分项行尾的 `（5分）` / `(5 points)` 归 **criterion 上限**。导入时做三方对账（题面题分、rubric 各项之和、题项目分值），不一致进 warnings 而不是阻断；保留原文与行号，教师确认后才写库。

3. **逐篇判分，但按「题」组织批量，不要整卷一次。** 依据：CoGrader、EssayGrader 都是「一题一 rubric 一篇作文」一次判（§2.5、§2.6），智学网、七天网络按题/逐篇处理（§2.11、§2.12）；没有整卷多题一次判的公开做法。整卷一次的真实风险不是成本，而是**串题**：一组 criteria/total 无法归到具体题，好作文会掩盖差作文，评语也无法定位。建议以「题」为外层循环：同一道写作题的所有学生作答成批送判，每次调用只带该题 rubric、该题题目要求和该生该题正文；rubric 可缓存，失败只重试该题，分数与评语都落到 `itemId`。调用数随题数线性增加，单次 prompt 变短，公开资料没有成本基准，需本地实测。

4. **参考范文默认不给 AI；要给就作为题级、教师确认的锚点，且不能替代 rubric。** 依据：EssayGrader 明确 source materials「不能替代 grading criteria」，AI 仍按 rubric 判（§2.6）；Khanmigo 的 exemplar 是给学生看的示范，不是评分锚点（§2.7）；公开产品文档里未查到「把教师范文当评分锚点」的说明。建议 v1 不启用；若启用，必须由教师逐题挂载、标注分数档、可关闭、带版本，并且只作为上下文，不写成新的评分项。仓库已有 `referenceMarkdown` 字段但未进入判分请求，正好可以先保持关闭。

5. **判分前加 rubric 闸门，判分后加按题契约，rubric 改动要能重判。** 依据：CoGrader 的单档 rubric 会让全班同分（§2.5），EssayGrader 只有一个 criterion 时结果只能是 100%（§2.6），Moodle 对 rubric 改动分了多级重判影响（§2.9），Canvas 把评分落到 criterion（§2.2）。建议校验：每个 criterion 有正分值、至少两个档位、各项之和等于题分（不等则警告）、每道 `ESSAY` 题必须有题级 rubric 或显式回退；输出契约按题返回 criteria/total，服务端校验「每题 total = 各 criterion 之和」「试卷总分 = 各题之和」；题级 rubric 编辑沿用 `rubric_version` 的审计思路，改动后提示影响范围。

## 5. 来源清单

1. https://guides.gradescope.com/hc/en-us/articles/22249389005709-Grading-submissions-with-rubrics
2. https://guides.gradescope.com/hc/en-us/articles/24838908062093-AI-assisted-grading-and-answer-groups
3. https://developerdocs.instructure.com/services/canvas/resources/rubrics
4. https://www.crowdmark.com/help/using-a-rubric/
5. https://guides.turnitin.com/hc/en-us/articles/22048301253389--Classic-Creating-a-rubric-or-grading-form-in-a-submission
6. https://intercom.help/cograder/en/articles/11706304-how-to-add-a-rubric-in-cograder
7. https://intercom.help/cograder/en/articles/15859954-why-are-all-my-students-getting-the-same-score
8. https://help.essaygrader.ai/en-us/article/step-4a-create-a-rubric-uploadimport-or-build-from-a-template-1i6j5h6/
9. https://help.essaygrader.ai/en-us/article/step-5-upload-essays-add-source-materials-and-choose-grading-settings-1nhih8j/
10. https://blog.khanacademy.org/new-essay-feedback-tool/
11. https://support.google.com/edu/classroom/answer/9335069
12. https://raw.githubusercontent.com/moodle/moodle/MOODLE_405_STABLE/grade/grading/form/rubric/lib.php
13. https://phpdoc.moodledev.io/5.0/d6/d84/group__gradingform__rubric.html
14. https://help.blackboard.com/Learn/Instructor/Ultra/Grade/Rubrics
15. http://szsy.com.cn/aspx/wap/news_show.aspx?id=15926
16. https://www.zhixue.com/
17. https://www.7net.cc/build/product/yuejuan/index.html
18. https://www.7net.cc/build/home/news/index.html

> 文档日期 2026-10-22，检索于 2026-10-23。Gradescope / Canvas / Crowdmark / Turnitin / CoGrader / EssayGrader / Khanmigo / Google Classroom / Blackboard 的页面可公开访问；Moodle 文档站有验证码，正文依据其官方源码与 PHP 文档；智学网、七天网络的 rubric 内部模型未公开，正文已逐处标注。
