# Markdown 编辑器选型：从自研 textarea 到 Typora 式所见即所得（2026-10-22）

## 0. 这份调研回答什么

`/assignments/new` 的「题目要求」与「评分标准」两个输入框，现在用的是**仓库自己写的** Markdown 编辑器
（`web/src/components/MarkdownEditor.tsx`，无第三方依赖）：一个 textarea + 工具条 + 编辑/预览两个 tab。
教师反馈「窗口太小」（已在本轮用放大/全屏解决，见 `docs/reports/t337-editor-expand/`），并希望换成
**Typora 那样边写边渲染的所见即所得**。

这份文档只回答一件事：**如果换，换成哪一个，代价是多少，会破坏什么**。结论以可核对的数字和文件路径为准，
不写"体验更好"这种无法验收的话。

## 1. 现状（先认清在替换什么）

| 事实 | 位置 |
| :--- | :--- |
| 编辑器是自研组件，没有 npm 依赖 | `web/src/components/MarkdownEditor.tsx`（约 560 行，含自维护撤销栈、快捷键、放大态） |
| 只读渲染也是自研，逐字解析成 React 元素，不用 `dangerouslySetInnerHTML` | `web/src/components/Markdown.tsx`（357 行，白名单子集：标题/列表/引用/表格/代码块/粗斜体/行内码） |
| 当初不引第三方编辑器，理由是依赖带来的 XSS 面与升级负担 | `web/src/components/RichTextEditor.tsx:10-16`、`web/src/components/Markdown.tsx:10-18` |
| AI 判分读的是 **Markdown 原文**，不是渲染后的 HTML | `docs/reports/first-feedback-2026-10-22/README.md` 第 8 条 |
| 评分标准由后端逐行解析：标题 `#`–`####`、`-`/`*`/`+` 列表、`1.`/`1)`/`1、` 编号；每项必须带分值 | `teachloop-sitting/src/main/java/com/teachloop/sitting/service/RubricValidator.java:29-31` |
| 前端有一份与后端逐条对齐的只读预览解析器 | `web/src/components/rubric.ts:1-20` |
| 界面文案必须 zh/en 对齐，`.ts` 里不许出现中文字面量 | `scripts/check-i18n.py`（本轮基线：前端 zh/en 各 1158 键） |
| 样式受 `check-ui` 约束：颜色只能来自令牌、不许 inline 颜色、antd 只能从 `ui/` 引 | `scripts/check-ui.py` |
| 当前打包体积（基线，2026-10-22 `pnpm build`） | 业务 chunk `index` 412.45 kB / gzip 112.84 kB；antd chunk 945.47 kB / gzip 299.90 kB |

**关键约束：** 编辑器必须始终交出「后端能解析的 Markdown」。所见即所得编辑器在内部换一种模型
（ProseMirror / Lexical / 自有 DOM）之后会**重新序列化** Markdown，因此"换编辑器"真正的风险不是外观，
而是**往返保真**：教师写的「一条评分项一行 + 行内分值」不能在保存/回写时被合并、改写或丢行。

## 2. 候选与实测

测量方法：在仓库外的临时目录 `/tmp/editorsize` 用 esbuild 0.25 打包每个库的**最小可用入口**
（React 外置，`--bundle --minify --format=esm`），再 `gzip -9`。数字是"这个库拉进来要付多少下载体积"，
不含业务代码；同一方法下当前编辑器为 0（无依赖）。

| 候选 | 版本 / 发布 | 许可 | 引擎与编辑模型 | 周下载 | 实测 gzip（JS + CSS） | 中文 UI | 源码视图 | 结论 |
| :--- | :--- | :--- | :--- | ---: | ---: | :--- | :--- | :--- |
| **MDXEditor** | 4.3.3 / 2026-10-13 | MIT | Lexical；Markdown 原生（mdast 进出），内置工具条 | 780,721 | **176.6 kB + 8.9 kB** | 有 `translation` 接口，需自带词表 | 有（source / diff） | **均衡候选**：React 组件、开箱即用、体积可控 |
| **Milkdown Crepe** | 7.22.3 / 2026-10-10 | MIT | ProseMirror；Markdown 原生，最接近 Typora | 285,617 | **925.0 kB**（依赖含 katex、codemirror、vue） | 工具条文案逐项可配，但无内置词表 | 无（需另装） | Typora 手感最好，**但体积接近现有 antd chunk 的 3 倍** |
| **Vditor** | 4.0.1 / 2026-10-18 | MIT | 自有引擎（Lute）；IR 模式＝光标行显示源码、其余实时渲染，最像 Typora | 22,496 | **71.0 kB + 8.3 kB**，另加 Lute 引擎 **3.74 MB / gzip 592 kB**（初始化时加载）；合计约 672 kB gzip | **内置 zh_CN** | 有（sv 模式＝源码 + 预览） | 中文与 IR 手感最对口；代价是资源自托管、命令式 API，以及 Lute 这 592 kB |
| **TipTap 3 + @tiptap/markdown** | 3.32.0 / 2026-10-13 | MIT | ProseMirror；官方 Markdown 扩展（依赖 marked） | 11.3M（@tiptap/react） | **142.4 kB** | 无（全部自己写） | 需自建 | 控制力最强、体积居中，但工具条/菜单/快捷键/翻译全要自己拼 |
| **BlockNote** | 0.56.0 / 2026-10-14 | MPL-2.0 | 块模型（Notion 式），Markdown 只是导出格式 | 451,003 | 未测（不进主方案） | 有 | 无 | 块模型不是 Markdown 原生，往返会重写结构，排除 |
| **Toast UI Editor** | 3.2.3 / 2023-02-17 | MIT | 自有引擎，有 WYSIWYG 模式 | 55,274 | 未测 | 有 | 有 | 3 年未发版、peer 锁 React 17，排除 |
| **ByteMD** | 1.22.0 / 2025-02-12 | MIT | Svelte 封装 + CodeMirror；分栏预览 | 5,089 | 188.9 kB + 3.5 kB | 有 | 无 | 不是所见即所得，且停更 20 个月 |
| **@uiw/react-md-editor** | 4.1.3 / 2026-10-20 | MIT | CodeMirror 6；分栏预览 | 450,771 | 362.5 kB + 5.9 kB | 有 | 有 | 只是更好的分栏预览，不是 Typora |
| **Lexical / ProseMirror 裸用** | 0.52.0 / 3.x | MIT | 编辑器内核，不提供 Markdown 编辑器 | — | — | — | — | 等于自己造 MDXEditor，排除 |
| **保持自研 + 并排实时预览** | 本仓库 | — | textarea + 现有渲染器 | — | **0** | 现成 | 天然就是源码 | **零依赖兜底**：解决"看不到结果"，不解决"看不到语法" |

（Milkdown 的 925 kB 里 katex 与 codemirror 占大头，`@milkdown/crepe` 的 `package.json` 依赖里还有 `vue`；
这是"开箱即用"的代价，不是打包方式造成的误差。）

## 3. 往返保真实验：Markdown 进 → Markdown 出

用编辑器生态里最主流的 mdast 解析/序列化栈（MDXEditor 直接依赖它；Milkdown 底层同为 remark）
对仓库里两份真实 rubric 做往返，看结构会不会坏：

| 输入 | 变化 | 对后端解析的影响 |
| :--- | :--- | :--- |
| `docs/rubrics/Rubric.md`（39 行，英文课程 rubric） | 无序列表标记 `-` 被统一成 `*`；其余逐行一致 | **无影响**：`RubricValidator.BULLET` 接受 `-*+`，39 行进出后仍是 3 个评分项 |
| `docs/rubrics/Overall_Assignment_Rubric.txt`（32 行，编号 + 缩进说明） | 编号项下的说明行被缩进 3 空格、表格外空行增减 | **无影响**：评分项行仍在行首、缩进 3 空格仍在正则允许的 `{0,3}` 内；未新增/丢失评分项 |
| 教师手写「一条一行、不带列表符号」的 rubric | 普通连续行在 Markdown 里本来就是**同一段**；softbreak 是否保留取决于编辑器 | **有风险**：ProseMirror/Lexical 这类模型对"段内换行"表达不同，可能把 3 行并成 1 行 → 后端只看到 1 条评分项 |

结论：**mdast 系（MDXEditor / Milkdown）在真实 rubric 上结构安全；TipTap 这类 ProseMirror 系必须实测
softbreak，不能假设。** 换编辑器时验收标准必须是"金标 rubric 往返后评分项、分值、合计不变"，
而不是"看起来没变"。

## 4. 排序（按"这个仓库现在需要什么"）

1. **MDXEditor** — React 原生组件、开箱即用工具条、Markdown 原生、176.6 kB gzip、有源码视图兜底；
   中文要自己填一份 `translation`。适合"要 Typora 式体验，但不想自己拼编辑器"。
2. **Vditor（IR 模式）** — 最接近 Typora 的即时渲染，内置中文，主包只有 71 kB gzip；代价是自托管
   23 MB 资源目录、命令式 API 与一套自有 CSS 变量，和现有 `check-ui` 令牌体系要做一次对齐。
3. **Milkdown Crepe** — 手感最像 Typora，Markdown 原生；**925 kB gzip**，对已经 300 kB 的 antd chunk
   来说是要认真解释的增量，且内部依赖 Vue。
4. **TipTap 自拼** — 体积 142 kB、生态最大，但工具条/快捷键/翻译/源码模式都要自己写，工作量最大。
5. **保留自研 + 并排实时预览** — 零依赖零风险，只用放大态就能"边写边看"，但语法标记仍然可见，
   不等于 Typora。

## 5. 无论选哪个，都必须同时满足

- **源码兜底**：至少保留一个切换，能直接编辑 Markdown 原文（后端解析的唯一真源）。
- **往返测试**：把 `docs/rubrics/Rubric.md` 与 `docs/reports/first-feedback-2026-10-22/` 里的两份真实
  评分标准做成前后端共用的金标用例，替换前后评分项与合计必须逐项相等。
- **i18n**：编辑器自带文案也要过 `check-i18n`（中文只能来自 `locales/*.json`，不能硬编码在组件里）。
- **XSS**：学生端只读渲染仍用现有 `Markdown.tsx`；编辑器不得把原始 HTML 当可信内容渲染。
- **体积**：在 `vite.config.ts` 里单独成 chunk，业务代码改动不重下编辑器包。

## 6. 来源

- MDXEditor 仓库与文档：https://github.com/mdx-editor/editor · https://mdxeditor.dev/
- Milkdown 文档：https://milkdown.dev/ · 仓库：https://github.com/Milkdown/milkdown
- TipTap 文档与 Markdown 扩展：https://tiptap.dev/ · 仓库：https://github.com/ueberdosis/tiptap
- Vditor 仓库与文档：https://github.com/Vanessa219/vditor · https://vditor.b3log.org/
- BlockNote：https://github.com/TypeCellOS/BlockNote
- Toast UI Editor：https://github.com/nhn/tui.editor
- ByteMD：https://github.com/bytedance/bytemd
- @uiw/react-md-editor：https://github.com/uiwjs/react-md-editor
- 版本、发布日期、周下载与 unpacked size：npm registry（2026-10-22 读取）
- 体积：本机 esbuild 0.25 实测，脚本与产物在 `/tmp/editorsize`（临时目录，不入库）

## 7. 2026-10-22 追加：拍板与实测修正

用户拍板 **Vditor IR**（决策 004，`docs/decisions/004-markdown-editor.md`），试点只覆盖新建作业的
「题目要求 / 评分标准」两个字段。拍板时补测了一个第 2 节没量到的数字，必须记在这里：

- **Lute 引擎**：`vditor/dist/js/lute/lute.min.js` 3.74 MB，gzip **592 kB**，编辑器初始化时才加载。
  之前表里的 71 kB 只是 Vditor 的主 chunk，不含它。所以 Vditor 这页首开约 **672 kB gzip**
  （72.2 主 chunk + 8.2 CSS + 592 Lute），不是 80 kB。
- **中文词表实测**：MDXEditor 4.3.3 打包产物里 0 处中文字符串，工具条默认 `Bold / Undo`；
  Milkdown 默认 `Bold / Italic`；Vditor 自带 `dist/js/i18n/zh_CN.js`。
- **往返实测**：用 Lute 的同一套 IR 转换（`Md2VditorIRDOM → VditorIRDOM2Md`）跑真实 rubric：
  `Rubric.md` 往返**逐字节相同**；`Overall_Assignment_Rubric.txt` 有缩进/空行变化但评分项仍是 2/4/4；
  教师手写「一条一行、不带列表符号」的 rubric 往返后原文不变（这种写法后端本来就不解析）。
  这组断言已固化为 `web/tests/rubric-roundtrip.test.mjs`（5 条），由 `make web-test` 跑。
- **代价重估**：Vditor 的 672 kB gzip 比 MDXEditor 的约 186 kB 重 3.6 倍，仍是四个里最轻的中文/Typora
  方案；因为只在打开这一页时懒加载，用户可以接受（决策 004 的「代价与对策」已列）。

### 2026-10-22 追加：源码视图不用 Vditor 的 SV 面板

拍板时用户还要求「源码就只编辑源码，不要再并排预览」。实测 Vditor 自带 SV 面板的两个问题：

1. 它本身就是「源码 + 预览」分屏，预览面板要去掉得靠 CSS 隐藏；
2. 更关键的是撤销：Vditor 的 undoDelay 默认 800ms，输入后立刻 Cmd+Z 没有反应（工具栏撤销按钮也不动），
   对我们这种「刚敲完就后悔」的编辑场景不可接受。

因此源码视图改用非受控 textarea：浏览器原生撤销、全宽、等宽字体、无工具条、无预览。
实现上还有一个 React 的坑：动态 `defaultValue` 会打断浏览器对连续输入的撤销合并（一次只退一个字符），
把初始值冻结成常量后才恢复「一次撤销整段」——这条已写进 `VditorMarkdown.tsx` 的注释与报告。
