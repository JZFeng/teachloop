# T422 作业页两个字段都能导入 .md / .txt

日期：2026-10-24 · 页面：`/assignments/new`（`web/src/teacher/AssignmentCreatePage.tsx`）

## 用户提的问题

1. 内容都是 Markdown，导入文件与复制粘贴的 effort 差不多，还有必要提供 import 吗？这反而多了代码，还可能因千奇百怪的输入出错。
2. 一致性问题：rubric 能导入，题目要求为什么不能？

## 结论（用户 2026-10-24 拍板）

- 两处都保留导入，并修掉覆盖无确认等 4 个小问题。
- rubric 仍为必填；后端 `DEFAULT_ASSIGNMENT_RUBRIC` 只作 API 层防御，不从 UI 启用。

依据：本页的导入从来不是解析器。改动前它只有 6 行 `file.text()`，真实风险是三处：静默覆盖、无大小上限、读失败无提示。真正易错的是 rubric 解析器（前端 `rubric.ts` 272 行镜像后端 `RubricValidator`），它由「AI 将看到」在每次输入时运行，与导入来源无关。

## 改了什么

| 项 | 改动 | 位置 |
|---|---|---|
| 一致性 | 题目要求新增同款「导入 .md / .txt」按钮与隐藏 file input | `AssignmentCreatePage.tsx` |
| 共用读取 | 新增 `readMarkdownFile`：1 MB 上限、CRLF/CR → LF、空文件拒绝、读失败返回 reason 不抛异常 | `web/src/teacher/markdownImport.ts` |
| 覆盖确认 | 目标字段非空时弹 `useConfirm`，说明「会替换、不能撤销」；取消不动内容 | `AssignmentCreatePage.tsx` |
| 文案与 accept 对齐 | 按钮文案改「导入 .md / .txt」（accept 本来就收 `.txt`，课程 rubric 有 `.txt` 文件） | `zh-CN.json` / `en.json` |
| 测试 | 读取逻辑 5 条单测（CRLF、超限、空白、抛错、默认上限） | `web/tests/markdown-import.test.mjs` |

未做：撤销栈修复。导入是程序化改值，Vditor 会清撤销栈；在「覆盖前确认」之后这是可接受的取舍，写在这里备查。

## 验证

**已做（界面入口，QA/dev 5173，教师 `teacher@teachloop.local`）**

- 两个字段都出现「导入 .md / .txt」（截图 `01-both-fields.png`）。
- 空字段导入 `.md`：内容进编辑器、CRLF 归一、成功提示「已导入「…」。」（`05-import-filled.png`）。
- 非空字段再导入：弹「替换已有内容？」；取消后原文保留；确定后替换为新文件内容（`06-overwrite-confirm.png` / `07-replaced.png`）。
- 超过 1 MB：提示「文件超过 1 MB，请拆分后再导入。」，原内容不动。
- 只有空白的文件：提示「这个文件里没有内容。」，原内容不动。
- `.txt` 可导入，rubric 预览同步重算（「AI 将看到」合计 10 分）。

**结构性验证**

- `pnpm typecheck` / `pnpm build` 通过；`pnpm test` **38/38**（新增 5 条）。
- `check-i18n` 1558 键对齐；`check-ui` 无新增违规；`check-boundaries` 通过。

**未验证**

- 端到端：没有真的用导入的内容发布一场作业（需要班级与截止时间，会在 dev 库留数据）。导入只写 `instructions` / `rubricMarkdown` 两个已存在的 state，提交路径未改动。
- 英文界面与暗色下的视觉效果未逐张截图。

## 已知的、与本改动无关的存量不一致

试卷向导里写作题的逐题 rubric（`PaperWizardPage.tsx`）也是 Vditor + 同一个预览，但没有导入按钮；本次按用户范围只改作业页，未扩散。
