# T341 补齐剩下的编辑框：试卷向导 rubric + 学生富文本放大（2026-10-22）

## 调查：全站还有哪些长文本编辑框

| 位置 | 字段 | 性质 | 本轮结论 |
| :--- | :--- | :--- | :--- |
| `/papers/:id` 试卷向导 | 评分标准 `paper.rubricMarkdown` | Markdown（考试判分用） | **换 Vditor + 解析预览** |
| `/papers/:id`、`/papers/import` | 粘贴整卷、答案表、每题选项 | 原始结构化文本（喂解析器） | 保持 textarea，禁止富文本化 |
| `/papers/:id` | AI 提示词模板 | 含占位符的 prompt 原文 | 保持 textarea |
| `/assignments/:id` 学生作业 | 富文本作答（自研 contentEditable，存消毒 HTML） | HTML，不是 Markdown | **保持富文本，只加放大/全屏** |
| `/assignments/:id`、考试作答 | 文本作答 / 作文 | 纯文本 | 不动 |
| `/review` 复核页 | 评语 / 优点 / 建议 | 纯文本（学生端 pre-wrap） | 不动 |
| `/assignments/new` | 参考范文（锚点） | 纯文本（进 AI prompt） | 不动 |

## 改了什么

### 1. 抽出共享的 `RubricPreview`（`web/src/components/RubricPreview.tsx`）

- 从 `AssignmentCreatePage` 原样搬出，新增 `showTotalCheck` 与 `title`：
  - 作业页保持原行为（合计/总分、一致/不一致、ignored 提示）；
  - 试卷页传 `showTotalCheck={false}` → 只显示「合计 X 分」+ ignored 提示 + 一行说明，
    **不判断合计等于某个总分、不写「可以发布」**（考试 rubric 可能只是主观题评分项）。
- 作业页改为引用共享组件，T340 的编号清单/ignored 提示行为不变。

### 2. 试卷向导的评分标准换成 Vditor（`PaperWizardPage.tsx`）

- `lazy` 加载 `VditorMarkdown`（与新建作业共用 chunk），`Suspense` 占位。
- 支持所见即所得 / 源码、放大 / 原生全屏；`locked`（已发布试卷）时只读。
- 下方接 `RubricPreview`（`showTotalCheck={false}`）。
- **顺带修了一个既有 bug**：`GET /api/papers/{id}` 的 `PaperDetail` 原来不返回
  `rubricMarkdown` / `gradingPromptTemplate`，导致向导打开后评分标准永远是空的、保存过的内容看不到。
  现在 `PaperDetail` 带这两个字段，前端 `fromDetail` 与「重新拉详情」都回填；`rubricTouched`
  为 true 时不被覆盖。

### 3. 学生富文本作答加放大 / 全屏（`RichTextEditor.tsx`）

- 新增 `expandable`；`AssignmentPage` 富文本模式传 `true`。
- 放大只是外层 CSS `position: fixed` 铺满右侧内容区（侧栏保留，窄屏铺满），**不移动 DOM**，
  所以正文、选区和撤销栈都保留。
- 工具栏右侧：默认「放大」；放大后「全屏 / 退出全屏」+「收起」。
- 放大期间锁页面滚动，Esc 收起，焦点回到「放大」按钮；原生全屏监听 `fullscreenchange`。
- 存储、AI 输入、草稿逻辑都没动。

## 验证

| 检查 | 结果 |
| :--- | :--- |
| 试卷详情回填 | `GET /api/papers/{id}` 返回 `rubricMarkdown`；向导第 5 步编辑器显示已有 rubric |
| 试卷预览 | 3 项 2/4/4、合计 10；底部「按行解析，仅供检查；试卷发布不校验这里的合计」；无「可以发布」 |
| 试卷源码视图 | 切「源码」得到 Markdown 原文；已发布试卷只读（`contenteditable=false`） |
| 学生放大 | `.rich-editor--expanded` `x=238 / 1202×1000`；`body.overflow=hidden`；Esc 后 class 消失、正文保留、焦点回到「放大编辑」 |
| 学生全屏 | 进入后 `document.fullscreenElement` = 正在编辑的 rich editor；退出后为 null |
| 边界 | 试卷已发布 => 只读；学生验收时新建了一次 attempt（未提交）留在 QA |
| 全量 | `make test` 退出码 0：surefire **260 / 0 / 0 / 0**，前端 **22 / 22**；`check-i18n` 1200 键、`check-ui` 无新增违规 |

> 试卷是 PUBLISHED，无法在页面里直接输入。验收时通过 API 临时写入 `Rubric.md` 验证回填/预览/源码，
> 随后已把 rubric 还原为空（已复查）。编辑写入路径与新建作业共用同一个 `VditorMarkdown`（T339 已验证）。

## 截图

| 文件 | 内容 |
| :--- | :--- |
| `paper-rubric-editor.png` | 试卷向导：Vditor + 只读态 + 下方解析预览 |
| `paper-rubric-preview.png` | 试卷解析预览：编号、合计 10 分、说明 |
| `student-rich-expanded.png` | 学生富文本作答放大态：铺满右侧、工具栏含全屏/收起 |

## 未验证 / 边界

- 试卷已发布，页面内输入未跑；编辑路径依赖共用的 `VditorMarkdown`（T339 已验证）。
- 移动端 / 触屏的放大与原生全屏未逐端验证。
- 学生验收留下了 1 次未提交 attempt（QA 测试台）。
- 原始文本输入（整卷/答案表/选项/提示词）刻意没有富文本化：解析器要求原文。
