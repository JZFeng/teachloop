# T339 题目要求 / 评分标准换成 Vditor IR（2026-10-22）

决策见 `docs/decisions/004-markdown-editor.md`，选型依据见
`docs/research/2026-10-22-markdown-editor.md`，上一轮的放大/全屏见 `docs/reports/t337-editor-expand/`。

## 做了什么

- 引入 `vditor@4.0.1`，新增 `web/src/components/VditorMarkdown.tsx`：IR（即时渲染）为默认视图，
  顶部只有一个「源码」按钮（源码模式下变「返回编辑」）；放大态覆盖右侧内容区（侧栏保留）、可再进原生全屏、Esc 收起。
- `AssignmentCreatePage` 的两处字段改成懒加载 `VditorMarkdown`（`React.lazy` + `Suspense`），
  Vditor 单独成 chunk，不进首屏。
- `web/scripts/copy-vditor.mjs`：把 lute / i18n / icons / css 复制到 `public/vditor/dist`（gitignored），
  `cdn: '/vditor'` 走本地，不走 unpkg。
- 关掉用不上的能力：上传 / 表情 / 导出 / 大纲 / 内置全屏 / 预览区 Desktop·Tablet·Mobile·微信·知乎 /
  代码高亮 / 公式。
- 新增 `web/tests/rubric-roundtrip.test.mjs`（5 条金标往返），`make web-test` 并接入 `make test`。
- nginx 部署片段补 `gzip on`（Lute 单文件 3.74 MB）。

## 验证（QA 5174，Playwright + 本机 Chrome）

| 检查 | 结果 |
| :--- | :--- |
| 两个编辑器初始化 | Vditor IR 2/2；工具条中文（`标题 <⌘H>`、`粗体 <⌘B>`、`斜体 <⌘I>`、`删除线 <⌘D>`） |
| 文案 | 页面不再出现「所见即所得」「源码 + 预览」；按钮只有「源码」/「返回编辑」 |
| 输入 → 父组件状态 | 在评分标准里输入 `## 评分标准` + 编号项后，「AI 将看到」显示 `内容 6 / 结构 4`、合计 10 |
| 源码视图 | 切「源码」后是纯 Markdown textarea：宽 920px（占满字段）、等宽字体、无预览、无工具条；Vditor 实例被销毁 |
| 源码撤销 | 输入 `abc 123` 后立刻 `Cmd+Z` 一次撤销整段（非受控 textarea 原生撤销） |
| 放大态 | `.md-overlay` `x=238, 1202×1000`；富文本 Vditor 或源码 textarea 都铺满（源码 1176×807）；Esc 收起 |
| 导入 .md | 导入 `docs/rubrics/Rubric.md` 后编辑器与「AI 将看到」同步（2/4/4，合计 10） |
| 后端接受编辑器输出 | 把 Vditor 序列化出的 Markdown POST `/api/rubrics/preview`：`valid=true`、3 项 2/4/4、合计 10 |
| 暗色主题 | `html[data-theme=dark]`，两个编辑器都有 `vditor--dark` |
| 工具条图标 | headings/bold/italic/strike 是实心字形、其余是线性图标，同为 15px 盒子时前者大一号；统一压到 12px（放大态同规则），见 `f5/f6` |
| 资源 404 | 没有任何 `/vditor/` 资源 404（只剩登录前的 `/api/events`、`/api/auth/me` 401） |

## 自动化

- `cd web && pnpm test`：5/5（真实 Rubin.md 往返逐字节相同；Overall rubric 评分项不变；手写一行一条不被合并；
  中文问句 rubric；粗体/行内码/表格）。
- `make test`：退出码 0；surefire **243 tests / 0 failures / 0 errors / 0 skipped** + 前端 5 条；
  `check-deps` / `check-boundaries` / `check-i18n`（1163 键）/ `check-ui` 全过。
- 改名 / 源码视图改造之后重跑过：`pnpm typecheck`、`pnpm build`、`pnpm test`（5/5）、`check-i18n`、`check-ui`；
  后端没有改动，因此没有再跑一遍 Maven。

## 体积（实测）

| 项 | 大小 |
| :--- | :--- |
| Vditor chunk | 295.48 kB / gzip 72.26 kB |
| Vditor CSS | 40.54 kB / gzip 8.15 kB |
| Lute 引擎（懒加载，初始化时） | 3.74 MB / gzip 592 kB |
| 该页首开合计 | 约 672 kB gzip |

## 未验证 / 边界

- 表单「发布」全流程没有重新点一遍（值来源与旧编辑器相同，且 `/api/rubrics/preview` 已验编辑器输出可被后端接受）。
- 英文界面没有实际切过去看（`lang` 逻辑：zh → `zh_CN`，其余 → `en_US`）。
- Safari / iPad 的浏览器原生全屏未逐端验证。
- 模式切换会重建 Vditor（富文本侧撤销栈清空）；源码侧用浏览器原生撤销。
- 源码视图没有语法高亮（用户明确要纯文本编辑；加高亮要再引编辑器内核）。
- QA 的 `vite preview` 不做 gzip，所以 QA 上 Lute 是 3.74 MB 原文件；生产必须按部署文档开 gzip。

## 截图

| 文件 | 内容 |
| :--- | :--- |
| `f1-normal.png` | 默认富文本视图（导入 `Rubric.md` 后） |
| `f2-source.png` | 点「源码」后的纯源码视图：全宽、等宽字体、无预览 |
| `f3-overlay-source.png` | 源码态的放大视图铺满右侧 |
| `f4-dark.png` | 暗色主题下的富文本视图 |
| `f5-toolbar-before.png` | 修之前：H / B / I / S 实心字形明显大一号 |
| `f6-toolbar-after.png` | 修之后：四个字形压到 12px，与列表/链接图标对齐 |
