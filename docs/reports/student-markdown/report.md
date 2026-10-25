# 学生作业页：Markdown 渲染修正 + 提交区简化

日期：2026-10-24 · 页面：`/assignments/:offeringId`（`web/src/exam/AssignmentPage.tsx`）
素材：用户给的 `写作3.md`、`评分标准3.md`，以及 QA 库中真实作业 `2150b208` / `a0d1349c`。

## 用户反馈

1. 学生页把老师的 Markdown 原样吐了出来：`*Instructions*` 带星号、四个事项各显示「1.」、`a./b.` 子项没有缩进、评分标准里第 3 条的编号子项跑到顶层。
2. 「我的提交」上面有「文本作答 / 富文本 / 文件提交」三个标签，多余；编辑框应直接是富文本；「文件提交」改成「上传」按钮。

## 根因（第 1 条）

不是 Markdown 库的问题，是自研只读渲染器 `web/src/components/Markdown.tsx` 的三处规则错，且因为解析直接产出 React 元素而无法单测：

| 现象 | 根因 |
|---|---|
| `***Instructions***` 显示成 `*Instructions` | 只认 `**`，`***` 被当成「`**` + 字面量 `*`」 |
| 四个事项都显示「1.」 | 列表项之间空一行就结束当前 `<ol>`，下一个事项另起一个列表，编号自然回到 1 |
| `a./b.` 子项浮到顶层 | 子层判定写成「缩进 ≥ 2 就是子项」；正确规则是「缩进 ≥ 父项正文起始列」 |
| 段内软换行被拆行 | 每个 `\n` 都渲染成 `<br>`；CommonMark 里软换行应合并为空格 |

## 改法

- 新增 `web/src/components/markdown/parser.ts`：纯数据解析（`MdBlock` / `MdInline` 树），不碰 React、不产生 HTML 字符串，安全边界不变（原生 HTML 仍是文字）。
  - 列表：空行不打断同层列表；子层按「父项正文起始列」判定；子列表递归解析；`0.` 之外还支持 `3.` 这类起始编号与 `a.` / `A.` 字母列表（`<ol type="a">`）。
  - 行内：`***粗斜***` > `**粗**` > `*斜*`，行内代码里的标记不再解析。
  - 换行：软换行合并空格；行尾两个空格或反斜杠才是硬换行。
- `Markdown.tsx` 只负责把树画成元素（约 150 行），不再包含解析规则。
- 新增 `web/tests/markdown-parser.test.mjs` 10 条：直接用用户两份 Markdown 的原文做夹具（粗斜体、空行分隔列表、6 空格 a./b. 子列表、评分标准嵌套编号、起始编号、软/硬换行、HTML 当文字）。

## 第 2 条：提交区

- 去掉 `文本作答 / 富文本 / 文件提交` 三个 chip，编辑框始终是 `RichTextEditor`。
- 底部动作行：`保存草稿`、`提交作业`、`上传`（新键 `exam.upload`）。点「上传」选文件后出现面板：`已选择「文件名」· 大小` + `上传并交卷` / `取消` + 允许的格式说明。
- 数据层：提交与保存草稿恒定发 `contentHtml`（编辑器 HTML）+ `contentText`（纯文本）；旧版纯文本草稿在加载时转成 `<p>` 段落灌进编辑器，不会丢；随后仍只在本机保存草稿。
- 删除不再使用的 `exam.textMode` / `exam.richMode` / `exam.fileMode` / `exam.assignmentPlaceholder` 四个键。

## 验证

- `pnpm test` **48/48**（新增 10 条）；`pnpm typecheck` 通过；`pnpm build` 通过。
- `check-i18n` 通过（zh/en 各 1521 键对齐）；`check-ui` 通过（存量 116，未新增）。
- **界面验收（真实 QA 数据）**：用临时 `vite preview`（5175，代理 8011）+ student01 登录，打开作业 `2150b208`：
  - 题目要求：粗斜体标题、1–4 连续编号、a./b. 缩进子项、Reminders 1/2（截图 `01-after-instructions.png`）；
  - 评分标准：4 条要点 + 第 3 条下两条编号子项（`02-after-rubric.png`）；
  - 作业 `a0d1349c`：段内软换行合并（`05-softbreak.png`）；
  - 提交区：富文本直出、三个按钮、上传面板、提交后回显（`08-final-ui.png`）。
- **未验证**：浅色主题逐张截图；英文界面；移动端；真实 AI 批改。

## 遗留 / 说明

- **QA 5174 是 `vite preview` 静态快照**，不会自动加载源码改动；需要在仓库里重新构建并重启：`./scripts/env.sh deploy qa`（或用 `pnpm build` 后把 `web/dist` 覆盖到 `.envs/qa/dist` 再重启 5174）。
- 我在 QA 库的作业 `a0d1349c`（TESOL Writing lesson plan）上做了一次真实提交（内容「QA 验证：提交界面与 Markdown 渲染。」）并留了一个空草稿（尝试 2/3），验证完没有删除；需要干净数据可以重建 QA 库。
- 源文件里「句号前换行」的写法会渲染成 `Template . Choose`（CommonMark 同样如此，软换行合并后保留空格）；这不是渲染错误。
