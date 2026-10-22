# 决策 004 — 「题目要求 / 评分标准」的编辑器换成 Vditor IR（试点）

日期：2026-10-22　状态：试点（只覆盖新建作业两处）　相关：T338 调研、T339 实现、
`docs/research/2026-10-22-markdown-editor.md`

## 问题

新建作业页的两个长文本字段（题目要求、评分标准）原来是仓库自研的 Markdown 编辑器
（`web/src/components/MarkdownEditor.tsx`：textarea + 工具条 + 编辑/预览两个 tab，无第三方依赖）。
教师提了两个问题：

1. **窗口太小**——已由 T337 用放大态解决：覆盖右侧内容区、默认并排、可再进原生全屏。
2. **不是所见即所得**——要 Typora 那种「边写边看」。当前编辑器语法标记始终可见，教师仍要懂
   `**`、`#`、`-`。

这不是纯外观问题：AI 判分与评分标准校验读的都是 **Markdown 原文**，后端
`RubricValidator` 按行解析标题 / 列表 / 编号（`teachloop-sitting/.../RubricValidator.java:29-31`）。
一个所见即所得编辑器如果每次改动都重新序列化 Markdown、合并行或改写列表，教师会在点「发布」时才被
400 打回。所以选型的硬指标是**往返保真**，不是「看起来好看」。

## 决策

**引入 Vditor 4.0.1，用 IR（即时渲染）模式替换这两个字段的编辑器；源码兜底改成一个纯 Markdown textarea
（浏览器原生撤销，不用它的 SV 分屏面板）；先在新建作业试点，学生端只读渲染不动。**

| 项 | 决定 |
| :--- | :--- |
| 编辑器 | `vditor@4.0.1`（MIT，2026-10-18 发布） |
| 顶部控件 | 只有一个动作按钮，不出现「所见即所得」这类术语 |
| 默认视图 | IR：光标行显示 Markdown 标记、其余行实时渲染（Typora 同款模型） |
| 源码视图 | 纯 Markdown textarea：全宽、等宽字体、无预览、无工具条；顶部按钮「源码」↔「返回编辑」 |
| 放大态 | 保留 T337：覆盖右侧内容区（侧栏可见），可再进浏览器原生全屏，Esc 收起 |
| 落地范围 | 只有「新建作业」的题目要求与评分标准两个字段；`AssignmentPage` 等其它输入不动 |
| 学生端 | 继续用 `web/src/components/Markdown.tsx` 只读渲染（纯 React 元素、无 `dangerouslySetInnerHTML`） |
| 加载方式 | `React.lazy` 懒加载，Vditor 单独成 chunk；只在打开这一页时下载 |
| 资源 | `scripts/copy-vditor.mjs` 把 lute / i18n / icons / css 复制到 `public/vditor/dist`（gitignored），`cdn: '/vditor'` |
| 关掉的 Vditor 能力 | 上传 / 表情 / 导出 / 大纲 / 内置全屏 / 预览区的 Desktop、Tablet、Mobile、微信、知乎按钮 / 代码高亮 / 公式 |
| 往返测试 | 新增 `web/tests/rubric-roundtrip.test.mjs`，`make web-test` 跑，并接入 `make test` |

### 为什么是 Vditor

- **IR 是 Typora 的确切模型**。纯 WYSIWYG（Milkdown、MDXEditor）是「标记永不出现」；Typora 是
  「光标行露出标记、可改源码」，只有 Vditor 的 IR 按这个模型做。
- **中文内置**。Vditor 自带 `zh_CN`；MDXEditor 包内 0 处中文（实测），要自己填几十条词表；
  Milkdown 工具条文案要逐项配。
- **源码视图不依赖它的 SV 面板**：实测 Vditor SV 的撤销按 800ms 批量入栈，输入后立刻 Cmd+Z 没反应；
  所以源码视图用非受控 textarea 走浏览器原生撤销（另一个坑：React 每次重渲染回写 `defaultValue`
  会把撤销退化成「一次退一个字符」，所以挂载时把初始值冻结成常量）。
- **体积可控且可懒加载**。主 chunk 72.2 kB gzip + CSS 8.2 kB；Lute 引擎 3.74 MB（gzip 592 kB）
  只在编辑器初始化时加载，且只在打开这一页时发生。

### 实测代价（必须说清）

| 项目 | 数字 |
| :--- | :--- |
| 编辑器 chunk | 295.48 kB / gzip **72.26 kB** |
| 编辑器 CSS | 40.54 kB / gzip **8.15 kB** |
| Lute 引擎（`dist/js/lute/lute.min.js`） | 3.74 MB / gzip **592 kB**，编辑器初始化时加载 |
| 静态资源目录 `public/vditor/` | 3.7 MB（生成物，不入库） |
| 该页首开合计（约） | **672 kB gzip**，之后走浏览器缓存 |
| 对照：业务 chunk / antd chunk | 110.6 kB / 299.9 kB gzip |

结论：比 MDXEditor（约 186 kB gzip 全量）重，但换来 IR 手感与内置中文；只影响这一页、可懒加载，
所以接受。生产环境必须对 `/vditor/` 开 gzip（已写进 `docs/deploy-aliyun.md` 的 nginx 片段）。

## 代价与对策

| 代价 | 对策 |
| :--- | :--- |
| 模式切换会销毁重建编辑器（Vditor 没有公开的 `setMode`），富文本侧的撤销栈清空 | 内容由父组件持有，重建只丢撤销栈；源码侧用非受控 textarea 的浏览器原生撤销，Cmd+Z 立即生效 |
| Lute 3.74 MB 首次下载 | 懒加载 + 本地托管 + 生产 gzip；关闭代码高亮/公式/预览工具条，避免再拉其它资源 |
| Vditor 自带 42 个工具按钮，大部分用不上 | `toolbar` 白名单只留 12 个（标题/粗斜体/删除线/列表/任务/引用/分隔线/代码/链接/表格/撤销重做） |
| Vditor 的 CSS 变量与仓库 `tokens.css` 两套 | 外壳仍用 `.md-editor` 的边框与圆角；暗色走 `setTheme('dark')`；`check-ui` 无新增违规 |
| 编辑器内部渲染 Markdown，存在 XSS 面 | 学生端渲染不变；编辑器内链接不自动打开（`link.isOpen=false`）；粘贴图片被拦下并提示 |
| 两个悬浮的确认/错误提示由 Vditor 自带 i18n 提供 | `lang` 跟随界面语言（zh_CN / en_US）；我们自己的文案全部走 `locales/*.json` |
| 资产复制脚本新增一步构建 | `pnpm dev` / `pnpm build` 自动执行；缺 `node_modules/vditor` 时脚本直接报错，不会静默白屏 |

## 验收证据（2026-10-22，QA 5174）

- **金标往返**：`web/tests/rubric-roundtrip.test.mjs` 5/5 通过（真实 `Rubric.md`、`Overall_Assignment_Rubric.txt`、
  手写「一条一行」、中文问句 rubric、含粗体/行内码/表格的 rubric；评分项与合计逐项相等）。
- **端到端**：在编辑器里导入 `docs/rubrics/Rubric.md`，切成源码视图读出 Vditor 序列化后的 Markdown，
  `POST /api/rubrics/preview` 返回 `valid=true`、3 项 2/4/4、合计 10。
- **浏览器**：两个编辑器 IR 初始化成功；工具条中文（`标题 <⌘H>`、`粗体 <⌘B>`）；按钮只有「源码 / 返回编辑」，
  页面不再出现「所见即所得」「源码 + 预览」；源码视图全宽 920px、等宽字体、无预览、无工具条；
  输入后 `Cmd+Z` 一次性撤销整段；放大态 `x=238 / 1202×1000`，源码态 textarea 1176×807；Esc 收起；
  导入 .md 后内容与「AI 将看到」同步；暗色主题生效；没有 `/vditor/` 资源 404。
- **守卫**：`pnpm typecheck` 0、`pnpm build` 0、`check-i18n`（1162 键）、`check-ui` 无新增违规；
  `make test` 见执行记录。
- **未验证**：表单「发布」全流程没有重新点一遍（值来源与旧编辑器相同，且预览接口已验）；
  英文界面没切过去看（`lang` 逻辑是 zh→zh_CN、其余 en_US）；Safari / iPad 原生全屏未逐端验证；
  模式切换后的撤销栈行为只在代码层确认（重建即清空）。

## 影响面

- 新增：`web/src/components/VditorMarkdown.tsx`、`web/scripts/copy-vditor.mjs`、`web/tests/rubric-roundtrip.test.mjs`
- 修改：`web/src/teacher/AssignmentCreatePage.tsx`（懒加载 + 两个字段）、`web/src/components/markdown.css`、
  `web/src/ui/icons.tsx`、`web/src/locales/{zh-CN,en}.json`、`web/package.json`、`web/vite` 构建脚本、
  `Makefile`（`web-test`）、`docs/deploy-aliyun.md`（nginx gzip）
- 保留：`web/src/components/MarkdownEditor.tsx` 不删——它是零依赖回退路径；组件头注释已标明现状
- 回退方式：把 `AssignmentCreatePage` 两处 `VditorMarkdown` 换回 `MarkdownEditor`、移除依赖与
  `public/vditor` 生成物即可；数据层没有迁移
