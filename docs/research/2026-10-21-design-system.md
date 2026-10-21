# 设计系统调研：12 个可比系统的令牌、边界与迁移路径

日期：2026-10-21　用途：为已定的视觉 / 组件 / 迁移方案（Ant Design 6.6.7、蓝主色、亮暗两套、5 屏先迁、check-ui、/ui-kit、21 屏截图基线）找外部依据，并写清哪些做法不采纳。

## 三条结论（先看这里）

1. **令牌分层照 Ant Design 的 seed → map → alias**，但别学 GitHub Primer 的 9 套主题：Primer 把 light / dark / high-contrast / colorblind / dimmed 全做成 token 文件，我们只需要 2 套，多的每一套都是 21 屏基线的回归成本。
2. **"暗色 = 换令牌值，不另做组件"在给了暗色方案的系统里是共识**：Ant、Arco、Semi、Element Plus、Radix Themes、Polaris、Primer、shadcn、Mantine 全部走这条（Semi 甚至在 `body` 上同时挂两套色盘），差别只在切换方式（属性 / class / 变量作用域），没有一家重做组件；Geist、M3、Linear 的暗色机制本次未核实。
3. **迁移没有一家是"一把切"**：Ant 提供 `antd lint` 检查 + 分版本迁移指南，Shopify 直接给 Polaris 发 codemod（token 改名可自动改），Polaris 用"变量挂到子树"实现局部替换。我们的 952 行手写 CSS 只能按屏切，但可以先写规则、再自动化。

## 口径

- 所有"官方做法"都来自官方文档 / 官方仓库源码，文末列 URL；没查到的一律标「未核实」，不当结论用。
- **本项目数字一律按 git HEAD 口径**（调研期间工作区已出现并行的迁移改动，见本节末）：`web/src/styles/mockup.css` 296 行、`web/src/styles/app.css` 183 行、`web/src/teacher/teacher.css` 250 行、`web/src/exam/exam.css` 223 行，合计 952 行；265+ 处内联 `style={{}}`；127 处 `className="btn…"`。
- 版本事实：`web/node_modules/antd/package.json` 为 **6.6.7**（实际安装版本）；HEAD 的 `web/package.json` **还没有任何 UI 库依赖**，`web/src/` 下 0 处 `antd` import —— 即"库已定、代码未接"。
- 并行改动提示：调研过程中工作区出现了 `web/src/ui/`、`web/src/styles/tokens.css`、`scripts/check-ui.py`、`web/src/app/UiKitPage.tsx` 等未提交文件，`web/package.json` 也加了 `antd` / `@ant-design/icons` / `@ant-design/v5-patch-for-react-19`。本文的现状描述不覆盖这些工作区改动；迁移落地情况以工作区与后续提交为准。

## 项目起点（决定调研要问什么）

- 组件层现在是零：952 行手写 CSS 靠语义类名（`.btn.pri`、`table`、`th`）撑起全部 21 屏，页面里 265+ 处内联 `style={{}}` 绕过样式表 —— 样式已经有 CSS 文件 / 内联两个来源在互相打，接组件库就是第三个来源。
- 令牌层有雏形但没接上：`mockup.css` 定义了 saas / academic / eyecare / dark 四套主题约 30 个 CSS 变量，但 `web/index.html:8` 写死 `data-theme="saas"`，React 侧零引用 —— 另外 3 套是死代码。
- 视觉资产要推翻：主按钮是紫色渐变 + 彩色投影（`mockup.css:112`，`--accent:#4f46e5` → `--accent-2:#7c6cf5` 的 `linear-gradient` + `box-shadow: ... var(--accent)`），与"教育行业稳重"的蓝主色、实边框冲突。
- 所以调研的六个问题不是学术题：**令牌怎么分层**决定 30 个变量怎么收敛；**组件层边界**决定 265 处内联样式退不退得掉；**表格与表单**决定试卷列表 / 复核工作台 / 组卷向导三块主战场能不能一次换掉。

## 12 个系统

### 1. Ant Design 6（我们选的）

- **令牌**：seed → map → alias 三层派生，seed 经 `theme.algorithm` 算出 map，alias 是"批量控制共性组件"的别名层；组件另有 Component Token，可在 `theme.components` 里单独覆盖。（customize-theme）
- **组件层边界**：页面直接 `import { Table, Form } from 'antd'`，官方给的覆盖口子是 `classNames` / `styles` 语义化插槽（CLI 有 `antd semantic Table` 专门查这层结构）。⚠️ v6 明确改了大量组件的内部 DOM，**针对组件内部节点的自定义选择器升级后要手改** —— 我们 952 行 CSS 里若有 `.ant-*` 选择器，这条会直接踩中。
- **中文与密度**：seed 里 `fontSize: 14`、`controlHeight: 32`、`sizeStep: 4`、`borderRadius: 6`、`colorPrimary: '#1677ff'`；默认字体栈是 `-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'Noto Sans', sans-serif`，**不含 PingFang SC / 微软雅黑**，中文要自己补（我们 `mockup.css` 已经补了）。密度走 `theme.compactAlgorithm`，与暗色算法可叠加。
- **暗色**：`theme.darkAlgorithm`，换的是 token 值，组件同一套。
- **表格与表单**：Table 有 `size: large | medium | small`（默认 large），并分别对应 `cellPaddingBlock/Inline`、`cellPaddingBlockMD/InlineMD`、`cellPaddingBlockSM/InlineSM`、`cellFontSize/MD/SM` —— **密度是真的三档 token，不是一处 padding**。Form / Form.Item 负责表单域与校验。
- **迁移**：官方不提供逐页 codemod，给的是 v5 → v6 迁移清单 + `@ant-design/cli`（`antd lint ./src` 查废弃 API、`antd migrate 4 5` 生成迁移提示、`antd doctor` 诊断配置）。v6 要求 React ≥ 18，**React 19 不再需要 `@ant-design/v5-patch-for-react-19`**，官方文档写明可直接移除。

### 2. Arco Design（字节）

- **令牌**：双层——构建期 Less 粒子变量（示例 `arcoblue-6` 改成 `#f85959`）+ 运行期 CSS Custom Properties；官方明说底层就是 CSS 变量，所以不支持 IE。主题定制主要靠"风格配置平台"生成主题包（`@arco-themes/theme-line`），用 `ArcoWebpackPlugin` 的 `theme` 字段接进去。
- **组件层边界**：React / Vue 组件 + less；换肤发生在构建期（换主题包），运行时改主题要靠 CSS 变量覆盖。
- **中文与密度**：Table 尺寸四档 `default | middle | small | mini`，`border` 可细化到 `wrapper / headerCell / bodyCell / cell`，另有 `stripe` 斑马纹。中文排版规范：未核实。
- **暗色**：暗色样式挂在 `body` 的 `arco-theme=dark` 属性上，所有 CSS 变量定义在 `.arco-theme` 里；局部暗色要改 Less 变量 `arco-theme-tag` 再重新编译 —— **换主题这件事被编译期绑住了**。
- **表格与表单**：表格档位比 Ant 多一档（mini），对"高信息密度"更友好；表单密度（Form size / label 布局）细节未核实。
- **迁移**：仓库有 v2 迁移文档（`site/docs/migrate_to_v2.zh-CN.md`），正文内容未核实。

### 3. Mantine（文档页当前为 v9.7.3）

- **令牌**：**一个扁平 theme 对象**（`colors`、`primaryShade`、`spacing`、`radius`、`fontSizes`、`lineHeights`、`headings`…），没有 seed→map 派生这回事；组件样式由 CSS Modules 产出，同时暴露 `--mantine-*` 变量。
- **组件层边界**：组件自带样式，页面直接用；深度定制走 Styles API（`classNames` / `styles` / `defaultProps`）。
- **中文与密度**：`fontFamily` 默认系统字体（"system fonts by default"），无 CJK 专门处理；表格密度靠 `horizontalSpacing` / `verticalSpacing` 取 `theme.spacing` 的值手调 —— 是旋钮，不是档位。
- **暗色**：`MantineProvider` 管 colorScheme 上下文（`light | dark | auto`），`ColorSchemeScript` 在 hydration 前把 `data-mantine-color-scheme` 写到 `<html>`；`useComputedColorScheme` 处理 auto 场景。
- **表格与表单**：表格能力弱于企业级库（无固定列 / 虚拟滚动 / 编辑态的整套方案证据，未核实）。
- **迁移**：未核实（没找到 codemod 证据）。

### 4. shadcn/ui

- **令牌**：CSS 变量语义对——`background/foreground`、`primary/primary-foreground`、`muted`、`accent`、`border`、`input`、`ring`、`radius`，值用 `oklch()`；暗色靠 `.dark` 选择器覆盖**同名同值**。
- **组件层边界**：官方原话"**This is not a component library. It is how you build your component library.**"——组件源码直接进仓库，你可以改任何一行；依赖的是无样式底层（文档提到 `@base-ui/react`）。边界等于没有边界。
- **中文与密度**：无密度规范、无 CJK 处理；样式体系强绑 Tailwind（`bg-background`、`@theme inline`）。
- **暗色**：`.dark` 类覆盖 token，同一套组件。
- **表格与表单**：表格是"数据表 + TanStack Table"的组合，没有分页 / 固定列的默认实现（未核实细节）。
- **迁移**：CLI 分发组件，没有"从手写 CSS 迁过来"的路径——它假定你一开始就用 Tailwind。

### 5. Radix Primitives / React Aria

- **令牌**：Primitives **无样式**（"Components ship without styles"，只做 WAI-ARIA、焦点管理、键盘导航）；带样式的层是 Radix Themes：12 级色阶 ×（light / dark / alpha 三套），`accentColor`、`grayColor`、`radius`、`scaling` 四个开关配主题。
- **组件层边界**：Primitives 是"设计系统的底座"（官方定位），样式必须自建；React Aria 同理，50+ 组件"ready for your styles"，含国际化。
- **中文与密度**：Radix Themes 的 `scaling="95%"` 是全局缩放档（真·密度旋钮）；Table `size: "1" | "2" | "3"`（默认 `"2"`）。中文排版：未核实。
- **暗色**：`<Theme appearance="dark">`，light/dark 都是内置。
- **表格与表单**：Table 只有 `size` / `variant` 两档旋钮，没有固定列、虚拟滚动、行编辑 —— 主战场不够用。
- **迁移**：未核实。

### 6. GitHub Primer

- **令牌**：命名分三类 —— **base**（`base-size-4`、`base-color-green-5`，直接映射原始值）、**functional**（`bgColor-inset`、`borderColor-default`，全局 UI 语义）、**component/pattern**（`button-primary-bgColor-hover`，只允许组件 CSS 用）。全部以 CSS 变量消费：`var(--fgColor-default)`。
- **组件层边界**：`@primer/primitives` 只发 CSS 变量文件，组件在 `@primer/react`；token 与组件解耦得很干净。
- **中文与密度**：size 一类 token 里，functional control 有 x-small → x-large 五档，尺寸是 token 不是组件 prop。中文排版：未核实。
- **暗色**：三组 data 属性 `data-color-mode`（auto/light/dark）、`data-light-theme`、`data-dark-theme`；官方发的主题文件有 light、light-high-contrast、light-colorblind、light-tritanopia、dark、dark-dimmed、dark-high-contrast 等 9 个。
- **表格与表单**：有 DataTable 组件与 Forms 模式页（未核实密度细节）。
- **迁移**：未核实。

### 7. Shopify Polaris

- **令牌**：CSS 自定义属性 `--p-*`（`--p-color-text`、`--p-border-width-1`）；`CustomProperties` 组件把一整套变量挂到子树，`AppProvider` 传 color scheme（light / dark），**可以在任意层级嵌套覆盖**——这是"局部换主题"最干净的做法。
- **组件层边界**：官方现在把 Polaris 描述为"unified UI framework built on **web components**"，覆盖 App Home / Admin / Checkout / POS 等表面。
- **中文与密度**：未核实（IndexTable 的密度能力没查到可引用正文）。
- **暗色**：换 color scheme → 换整套 CSS 变量值；嵌套 `CustomProperties` 做局部暗色。
- **表格与表单**：IndexTable 面向 Shopify Admin 的高密度列表（未核实具体档位）。
- **迁移**：**有 codemod**。v10 → v11 的 token 改名配套 `polaris-migrator`，例如 `v11-styles-replace-custom-property-color` 自动把 `--p-text-subdued` 换成 `--p-color-text-subdued`。这是 12 家里唯一把"token 改名"工程化到可自动跑的。

### 8. Material Design 3 / Material Web

- **令牌**：三层且关系写死——**component token 映射到 system token，system token 有具体的 reference 值**；Web 上 token 就是 CSS 自定义属性，可以被任意选择器作用域化（示例 `--md-filled-button-container-shape: 0px`）。
- **组件层边界**：Material Web 是 Web Components，组件内部走 `--md-*` 变量；页面能用，但视觉语言（大圆角、动态取色）不可回避。
- **中文与密度**：中文排版未核实；**density 一节未核实**——`m3.material.io` 是前端渲染页，本次没取到可引用正文。
- **暗色**：通过 system color role（`--md-sys-color-primary` 等）在亮/暗两套值之间切换；具体切换属性未核实。
- **表格与表单**：M3 没有企业级 Table 规范（材料里是 data table 设计指导，不是组件能力）。
- **迁移**：未核实。

### 9. Element Plus（Vue）

- **令牌**：SCSS map 变量（`$colors` 里 `primary.base` 等）+ 运行期 CSS 变量；官方明确说"大范围换色别一个个覆盖，改变量"，并给了 SCSS `@forward ... with` 与 CSS 变量两条路。
- **组件层边界**：BEM 结构 + CSS 变量，页面直接用组件、覆盖 `--el-*`。
- **中文与密度**：Table `size: '' | large | default | small`；Form 有 `label-position: left | right | top`，且 **Form / FormItem 的 `size` 会向下继承**给内部控件——表单密度是"一次设、全员继承"，比逐控件设 size 好维护。中文排版规范：未核实。
- **暗色**：`html.dark` + `element-plus/theme-chalk/dark/css-vars.css`（2.2.0 起），再覆盖 `--el-bg-color` 之类即可。
- **表格与表单**：档位 3 档、表单继承式尺寸，和 Ant 接近，但生态在 Vue 侧。
- **迁移**：未核实。

### 10. Vercel Geist

- **令牌**：10 条色阶（`backgrounds`、`gray`、`gray-alpha`、`blue`…），每条 10 步，**每一步绑定一个用途**：100 默认背景 / 200 hover / 300 active / 400 默认边框 / 500 hover 边框 / 700 高对比背景 / 900 次级文本 / 1000 主文本；变量前缀 `--ds-*`（`--ds-gray-400`）。materials 层给半径与阴影预设（surface 6px、menu 12px、modal 12px、fullscreen 16px）。
- **组件层边界**：组件以 npm 包 `@vercel/geistcn` 发布；文档每页可加 `.md` 拿 Markdown（对 agent 友好）。
- **中文与密度**：无密度规范；中文排版未核实。
- **暗色**：colors 文档正文未写切换机制，未核实。
- **表格与表单**：未核实。
- **迁移**：未核实。

### 11. Linear（产品级克制样本，不是组件库）

- **令牌**：没有可公开引用的设计系统 / token 文档，**架构未核实**。
- **组件层边界**：无公开组件库。
- **中文与密度**：其 redesign 复盘把目标写成"reduce visual noise, maintain visual alignment, and **increase the hierarchy and density of navigation elements**"——密度来自层级，不是把字号调小。
- **暗色**：未核实。
- **表格与表单**：未核实。
- **迁移**：复盘里明确说导航改造会牵动大量工程、被判定为"unnecessary risk"而**主动排除在本次重设计之外**——这是"控制迁移范围"最直接的一手案例。

### 12. Semi Design（字节）

- **令牌**：基础色盘 16 个色相、共 160 色（由品牌色动态生成），功能色（primary / secondary / tertiary / info / success / warning / danger / AI）从基础色引用；品牌色在 **Semi DSM** 里配，动态生成新色盘。官方强调：**要用颜色变量，暗色才能一键切换**。
- **组件层边界**：React 组件 + CSS Token（`--semi-color-bg-0`、`--semi-color-text-0`）；页面可用组件，业务组件从 `body` 继承默认底色/文本色。
- **中文与密度**：Table 有 `size` 属性；虚拟化表格（`VirtualizedItemSize`，ref 指向 react-window 的 `VariableSizeList`）——大列表是真做了。中文排版规范：未核实。
- **暗色**：`body[theme-mode='dark']`，body 上**同时挂两套色盘**，支持局部亮/暗。
- **表格与表单**：虚拟滚动是它相对 Ant 的差异化能力（我们试卷列表首屏可能就是几百行）。
- **迁移**：未核实。

## 横向对比

| 系统 | 令牌分层 | 页面直引组件 | 表格密度档 | 暗色机制 | 迁移工具 |
|---|---|---|---|---|---|
| Ant Design 6 | seed → map → alias + 组件 token | 可以（`classNames`/`styles` 覆盖） | 3 档（large/medium/small） | `darkAlgorithm` 换 token 值 | `@ant-design/cli` 检查，无逐页 codemod |
| Arco Design | Less 粒子变量 + CSS 变量 | 可以（构建期换主题包） | 4 档（default/middle/small/mini） | `body[arco-theme=dark]` | v2 迁移文档，内容未核实 |
| Mantine v9 | 扁平 theme 对象 + CSS Modules | 可以 | 手调 spacing | colorScheme 上下文 + data 属性 | 未核实 |
| shadcn/ui | CSS 变量语义对 | 代码进仓库 | 无 | `.dark` 覆盖同名 token | CLI 分发，依赖 Tailwind |
| Radix / React Aria | 12 级色阶 × light/dark/alpha | 只有行为，样式自建 | `size 1/2/3` + `scaling 95%` | `<Theme appearance>` | 未核实 |
| GitHub Primer | base / functional / component | 可以（primitive 只发变量） | control x-small→x-large | 3 个 data 属性 + 9 套主题文件 | 未核实 |
| Shopify Polaris | `--p-*` 变量挂子树 | Web Components | 未核实 | 嵌套 CustomProperties 换 color scheme | **polaris-migrator codemod** |
| Material 3 | reference → system → component | Web Components | 未核实 | system color role 换值 | 未核实 |
| Element Plus | SCSS map → CSS 变量 | 可以 | 3 档 | `html.dark` + `dark/css-vars.css` | 未核实 |
| Vercel Geist | 10 色阶 × 10 步（步绑用途） | `@vercel/geistcn` | 无规范 | 未核实 | 未核实 |
| Linear | 无公开文档 | 无组件库 | 靠层级 | 未核实 | 无 |
| Semi Design | 160 色基础盘 + 功能色 + DSM | 可以 | `size` + 虚拟滚动 | `body[theme-mode=dark]` 双色盘 | 未核实 |

## 对本项目的结论（对应 7 个已定决策）

**① 视觉走"教育行业稳重"（蓝主色、实边框、更高信息密度）**
照 Geist 的"色阶每一步绑一个用途"，把我们要用的语义一次定死（默认底 / hover 底 / active 底 / 默认边框 / 主文本 / 次文本）；照 Primer 的 functional 命名，让页面只消费语义 token。**改**：`mockup.css:112` 的主按钮紫色渐变 + 彩色投影，换成实色主色 + 1px 实边框（Ant `lineWidth: 1`、`lineType: 'solid'` 默认就是实线）；`--accent` / `--accent-2` 这套紫（`#4f46e5` / `#7c6cf5`）收敛为 Ant `colorPrimary` 蓝色系（Ant 默认 `#1677ff`）。**不动**：现有布局类、间距刻度、表格行高之外的排版，迁移期不动版式，只换表皮。

**② 组件层用 Ant Design 6.6.7**
照 Ant 的 seed → map → alias：seed 只放品牌色/圆角/字号/控件高度，map 与 alias 我们不手工维护（交给 `theme.algorithm`）。**改**：装 antd 6 时**不要**再引入 `@ant-design/v5-patch-for-react-19`（v6 迁移文档写明 React 19 已原生支持、该包可移除），已装则删；`main.tsx` 挂 `ConfigProvider`（`theme.algorithm: [compactAlgorithm, ...]` 视主题而定）。**不动**：4 个 CSS 文件整体保留，直到对应屏迁完；但禁止新增 `.ant-*` 选择器（v6 明确调过 DOM 结构，打在内部节点上的选择器会碎）。

**③ 主题保留亮/暗两套**
照 Semi / Element Plus（一套变量、两套值、根属性切换）+ Polaris（变量可挂子树）。**改**：`mockup.css` 的 4 套主题砍到 2 套，理由见下一节；`web/index.html:8` 的 `data-theme="saas"` 由 React 侧主题状态接管（现在是写死的、React 零引用）。**不动**：现有 dark 那套色值直接作为 alias 层的暗色值来源，不另调色。

**④ 先迁移 5 屏（登录、教师首页、试卷列表、新建作业、复核工作台）**
照 Linear 复盘里的范围控制（导航类改造被判"高工程成本 / 不必要风险"而移出范围），我们同样把 Shell / 导航留到最后，先换内容屏。**改**：`mockup.css` 按段拆，每迁完一屏就删掉该屏独占的段落，避免"新旧两套样式同时生效"。**不动**：其余 16 屏继续吃 `mockup.css` 与 `teacher.css` / `exam.css`，一行不碰。

**⑤ `check-ui` 静态检查进 `make test`**
照 Polaris 把规则工程化的思路（能自动改的就别靠人记），但先做"能自动判的"。**改**：按现有 `Makefile` 约定把检查写成 `python3 scripts/check-ui.py`（与 `check-deps` / `check-boundaries` / `check-i18n` 同构），并加进 `test:` 的依赖行（HEAD 上是 `test: check-deps check-boundaries check-i18n`）。首版只查三条：新增 `.ant-*` 选择器（0 容忍）、页面新增内联 `style={{}}`（存量 265+ 先冻结为基线、只准降）、`className="btn…"`（存量 127 处，迁移屏内必须降为 0）。**不动**：`check-deps` / `check-boundaries` / `check-i18n` 三条既有检查。

**⑥ `/ui-kit` 组件展示页**
照 Radix Themes 的"每个组件摆 size / variant / radius / scaling 的矩阵"，照 Ant 的 `theme.components` 组件 token 表。**改**：新增一页，只展示我们用到的组件（Button / Input / Select / Table / Form / Modal / Tabs / Tag / Pagination / Empty / Skeleton），每组件给"亮 / 暗 + 三档密度"两组截图。**不动**：业务页面不引这页的任何样式，它是验收工具不是 UI 依赖。

**⑦ 21 屏截图基线**
Ant v6 的 DOM 调整正是截图基线要抓的那类回归（官方原话：针对内部节点的自定义样式"升级后可能需要手动检查并调整"）。**改**：基线按"迁移后应有样子"生成，5 屏先立、剩余 16 屏在未迁移期间只在**非预期变化**时更新基线。**不动**：不为了截图好看去改版式；基线是回归护栏，不是设计稿。

## 不采纳的做法与理由

- **不引入 Tailwind（即不采纳 shadcn 路线）**：shadcn 的整套值依赖 Tailwind 的 `@theme inline` / 工具类，采用它等于把 21 屏 JSX 的类名全部重写；我们现有 952 行语义 CSS（`.btn.pri`、`table th`）和 127 处 `btn` 类名是资产不是债，Ant 又自带样式，两套并存只会让 `check-ui` 无法收敛。
- **不保留 4 套主题，只留亮 / 暗**：academic / eyecare 是死代码（`index.html:8` 写死 saas，React 零引用）；Ant 的预设算法只有 default / dark / compact 三套，4 套主题 = 4 份 seed + 4 套组件派生 + 4 倍截图回归；academic 用衬线（`"Iowan Old Style" / Songti SC`）还会让表格里的数字失去等宽对齐。
- **不逐屏重写 16 个未迁移页**：16 屏 × 重写成本，加上 21 屏截图基线一次性全红，回归定位会从头再付一遍；线性迁移（每屏一个 PR、旧 CSS 段整段删）才能把"哪屏坏了"缩到一次提交。
- **不用 Radix Primitives / React Aria 当主组件层**：它们只给行为不给样式，40+ 组件的样式要自己写 —— 5 屏迁移期内不可能交付。
- **不换 Mantine**：扁平 theme + CSS Modules，本质是把我们 952 行手写 CSS 换个书写方式，表格 / 表单的企业级能力反而更弱。
- **不换 Arco / Semi / Element Plus**：Element Plus 是 Vue；Arco 的换肤绑在构建期 Less 编译上，运行时切主题要重编译；Semi 的虚拟表格能力最贴合我们的试卷列表，但换库收益抵不过已定的 Ant Design 6（React 19 兼容由 v6 官方文档确认）。
- **不抄 Polaris / Geist / Linear 的视觉**：三者都是英文 SaaS 语境、低密度、大留白；可抄的是方法（Polaris 的 codemod、Geist 的"每步色阶绑用途"、Linear 的范围控制），不是外观。
- **不采用 M3 的动态取色**：教育行业需要跨校稳定的蓝色主色，随壁纸变化的动态色会让 5 屏的主色不统一，也让截图基线失去意义。
- **不采用 Primer 的 9 套主题**：高对比 / 色盲 / 暗化三类是无障碍与场景变体，我们连亮暗都还没跑通，先做 2 套。

## 未核实清单（不要当结论引用）

- Arco v2 迁移文档正文、Arco 表单密度能力。
- Mantine / Radix / Primer / Geist / Element Plus / Semi 是否存在官方 codemod。
- Material 3 的 density scale 与暗色切换实现（`m3.material.io` 前端渲染，未取到正文）。
- Polaris IndexTable 的密度档位与表格能力。
- Geist 的暗色机制、表格与表单规范。
- 所有 12 家的**中文排版专项处理**：除 Ant 的字体栈与字号可由源码核对（14px、不含 CJK 字体名）外，本次核对过的官方文档里没有一家给出 CJK 行高 / 字距规范 —— 这属于"没查到"，不等于不存在，只能由我们自己在 `/ui-kit` 定。
- Linear 的令牌架构（官方无公开设计系统文档，只有 redesign 复盘文章）。

## 参考

- Ant Design 定制主题（seed/map/alias、algorithm、组件 token）https://ant.design/docs/react/customize-theme
- Ant Design v5 → v6 迁移（React ≥ 18、CSS 变量默认、DOM 调整、可删 v5 patch）https://ant.design/docs/react/migration-v6
- Ant Design CLI（`antd lint` / `antd migrate` / `antd semantic`）https://ant.design/docs/react/cli
- Ant Design seed token 源码（fontSize 14、controlHeight 32、sizeStep 4、colorPrimary #1677ff、字体栈）https://github.com/ant-design/ant-design/blob/master/components/theme/themes/seed.ts
- Ant Design Table（`size: large|medium|small`）与表格 token 源码 https://github.com/ant-design/ant-design/blob/master/components/table/style/index.ts
- Arco Design 定制主题（Less 变量 + CSS Custom Properties、不支持 IE）https://github.com/arco-design/arco-design/blob/main/site/docs/theme.zh-CN.md
- Arco Design 局部暗色（`arco-theme`、`arco-theme-tag`）https://github.com/arco-design/arco-design/blob/main/site/docs/designlab/partial-dark.zh-CN.md
- Arco Design 主题包接入 https://github.com/arco-design/arco-design/blob/main/site/docs/designlab/use-theme-package.zh-CN.md
- Arco Design Table（四档 size、border、stripe）https://github.com/arco-design/arco-design/blob/main/components/Table/README.zh-CN.md
- Mantine Theme object（扁平 token 对象、默认系统字体）https://mantine.dev/theming/theme-object/
- Mantine Color schemes（colorScheme 上下文、ColorSchemeScript、data 属性）https://mantine.dev/theming/color-schemes/
- Mantine Table（horizontalSpacing / verticalSpacing）https://mantine.dev/core/table/
- shadcn/ui Theming（CSS 变量语义对、`.dark` 覆盖、oklch）https://ui.shadcn.com/docs/theming
- shadcn/ui 介绍（"not a component library"、open code、CLI 分发）https://ui.shadcn.com/docs
- Radix Primitives 介绍（unstyled、WAI-ARIA）https://www.radix-ui.com/primitives/docs/overview/introduction
- Radix Themes 暗色（`appearance`）https://www.radix-ui.com/themes/docs/theme/dark-mode
- Radix Themes 入门（`accentColor` / `grayColor` / `radius` / `scaling`）https://www.radix-ui.com/themes/docs/overview/getting-started
- Radix Themes 颜色（12 级色阶 × light/dark/alpha）https://www.radix-ui.com/themes/docs/theme/color
- Radix Themes Table（`size 1/2/3`，默认 2）https://www.radix-ui.com/themes/docs/components/table
- React Aria（50+ 组件、自带行为与国际化、样式自备）https://react-spectrum.adobe.com/react-aria/
- Primer Token names（base / functional / component 三类）https://primer.style/foundations/primitives/token-names
- Primer Primitives 入门（CSS 变量文件、`data-color-mode` 与 9 套主题文件）https://primer.style/foundations/primitives/getting-started
- Primer Size（functional control 五档）https://primer.style/foundations/primitives/size
- Polaris CustomProperties（变量挂子树、color scheme 可嵌套）https://raw.githubusercontent.com/Shopify/polaris-react-archive/c4047b30f5738dc293e7632e529fb57572b7b0d1/polaris.shopify.com/content/components/custom-properties/index.md
- Polaris v10 → v11 迁移（token 改名 + polaris-migrator codemod）https://github.com/Shopify/polaris-react-archive/blob/main/documentation/guides/migrating-from-v10-to-v11.md
- Polaris 文档（"unified UI framework built on web components"）https://polaris-react.shopify.com/design/tokens
- Material Web Theming（component → system → reference、CSS 自定义属性）https://material-web.dev/theming/material-theming/
- Element Plus 定制主题（SCSS map + CSS 变量）https://element-plus.org/en-US/guide/theming.html
- Element Plus 暗色模式（`html.dark` + `dark/css-vars.css`，2.2.0）https://element-plus.org/en-US/guide/dark-mode.html
- Element Plus Table（`size` 三档）https://element-plus.org/en-US/component/table.html
- Element Plus Form（`label-position`、`size` 继承）https://element-plus.org/en-US/component/form.html
- Vercel Geist 介绍（`@vercel/geistcn`、`.md` 文档）https://vercel.com/geist/introduction
- Vercel Geist Colors（10 色阶 × 10 步、每步用途、`--ds-*`）https://vercel.com/geist/colors
- Vercel Geist Materials（半径与阴影预设）https://vercel.com/geist/materials
- Linear：How we redesigned the Linear UI（范围控制、层级与密度）https://linear.app/now/how-we-redesigned-the-linear-ui
- Semi Design Tokens（160 色基础盘、功能色、DSM）https://semi.design/zh-CN/basic/tokens
- Semi Design 暗色模式（`body[theme-mode='dark']`、双色盘、局部亮暗）https://semi.design/zh-CN/start/dark-mode
