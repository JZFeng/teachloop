# 品牌视觉落地调研：logo 色板、暗色与判分状态色

日期：2026-10-22　目的：给新 logo 的品牌色板定一套能进令牌、能过对比度脚本的落地规则。本文只写方案与依据，不改代码。**落地范围以 `specs/006-brand-visual/spec.md` 为准；第 8 节是建议清单，不是本轮任务。**

## 背景与口径

2026-10-21 的 `docs/research/2026-10-21-design-system.md` 已经调研了 12 个系统的令牌分层、暗色机制与迁移路径。本文不重复那三条结论，只补它没展开的一块：**品牌色板如何落地**，尤其是亮色、暗色、判分状态色三者的关系。

现状（本文的事实基线）：

- 令牌有两个出口：`web/src/ui/tokens.ts` 给 antd 组件，`web/src/styles/tokens.css` 给 16 个未迁移的旧页面；两处同值，`scripts/ui/contrast.py` 断言相等并跑 16 组对比度（正文 4.5:1，边框与图标 3:1）。
- 宪法第十五条把对比度定为硬指标：正文与小字 ≥ 4.5:1，输入框边框与图标 ≥ 3:1，亮暗两套都要过；数值靠脚本算，不靠肉眼。
- 新 logo 是位图 `docs/brand/teachloop-logo-source.png`（1254×1254）。同版还有四功能位展示页 `docs/brand/teachloop-brand-sheet.png`（参考图，不参与构建；两个源图的用途与 sha256 见 `docs/brand/README.md`）。已取样：深海军蓝 `#0E3676`（字标）、亮蓝 `#1F8DFD → #2F7CFD`（循环渐变）、青绿 `#20B998`（铅笔与循环收尾）、黄 `#FEBF41`（铅笔与火花）、玫红 `#A63960`（Insights 柱）、四个粉彩底 `#E8EFFD` / `#DBF4EE` / `#EAE0FD` / `#FDE2E7`。2026-10-22 更新的 logo 去掉了 Words 栏，黄色只留在铅笔与火花，不再分配给功能入口。
- 白底实测：亮蓝 3.34:1、青绿 2.48:1、黄 1.65:1，都不到 4.5，不能做正文或主按钮；深海军蓝 11.62:1、玫红 6.22:1 可以。主操作因此压深一档为 `#1A63E0`，白字 5.38:1。
- 现有令牌的主色仍是 `#1D5FBF`（白字 6.10:1），暗色 `#2A6FD6`（白字 4.84:1）。本文给的是替换值，不是已完成的改动。
- 约束：只做亮/暗两套；不引 webfont（中文 webfont 1–5MB）；不换组件库（antd 6.6.7）；不重写 21 屏，靠令牌继承。

口径与方法：

- 对比度用 WCAG 2.x 相对亮度公式复算，和 `contrast.py` 同式；下文括号里的数字都是白底、`#141414` 暗底或指定粉彩底上的实测值。
- 只采信官方文档、官方仓库与 W3C 规范；查不到的一律标「未核实」，不当结论用。转述成自己的话，不整段翻译。
- 一手资料取不到的，换一家可核实的；换掉的记在文末「未核实」。

## 三条结论

### 1. 品牌亮色不进文字层：用「填充 / 浅底 / 深字」三档与 WCAG 共存

亮蓝、青绿、黄留在界面里，但只以三种身份出现：`fill` 是原色，只做 logo、插画、装饰图形；`tint` 是粉彩底，只做容器背景；`ink` 是深一档，负责文字与图标。三档不是三个近似色，是三种用途，token 名里写死。logo 底部的四个模块入口已经是这个结构：粉彩圆底加深色图标。

这样能共存有两个依据。第一，WCAG 1.4.3 明确豁免 logo 与字标文字（“Text that is part of a logo or brand name has no contrast requirement”），所以 logo 里的 1.65:1 黄不违规；同一份文件也写明，除 logo 外的品牌色不因企业规范而豁免，界面正文不能跟着 logo 走。第二，界面用 ink 档而不是原色：ink 在自己的粉彩底上是 5.11（Create 蓝）/ 5.15（Assess 青）/ 5.82（Practice 紫）/ 5.10（Insights 玫红），全部过 4.5。

带语义的图形不适用「亮色填充」。WCAG 1.4.11 要求「理解内容所必需的图形」对相邻色 ≥ 3:1；青绿对白 2.48、黄对白 1.65，直接做图表柱或状态点不达标。图表改用 ink 档，亮色只做装饰。`#1F8DFD` 对白 3.34 刚过 3:1，也只能做不承载信息的图形。

### 2. 功能色与状态色分三个命名空间，靠场景与形状分开，不靠色相

`brand.*` 表身份，`action.*` 表操作，`status.*` 表判分；三者不互相 alias。撞色三对先用明度、再用形状与文字分开：白底上青绿 2.48 对绿 6.13、黄 1.65 对琥珀 5.93，明度差够大；玫红 6.22 对红 6.49 明度接近，主要靠形状与文字（WCAG 1.4.1），颜色只做冗余信息。

判分屏是收口点。任何显示判分结果的屏幕，青/黄/玫红三支图标退到海军蓝（蓝色保留），这三支只保留粉彩底或退灰；状态只用 `status.*` 的实心小胶囊，里面是图标 + 文字。这样青↔绿、黄↔琥珀不会在同一视区相邻。细节见「功能色与判分状态色」。

### 3. 迁移分三刀：令牌 → 组件 → 5 屏，不做 codemod

第一刀只动令牌：`tokens.ts` 与 `tokens.css` 加同一组 brand/status 键，`contrast.py` 从 16 组扩到 40 组，antd seed 主色换 `#1A63E0`，旧 `--accent` 的键名不变、只换值。页面一行不改，主按钮先变蓝。

第二刀动 `web/src/ui/` 的 Tag、Alert、StatCard 三个组件与报告页图表；它们接状态三态与品牌三档，`/ui-kit` 加一页「品牌 × 状态」对照。

第三刀只迁 5 屏。品牌色只出现在导航、空态与插画，判分区域只用 status；其余 16 屏靠令牌继承，不重写。每刀都能单独回滚，没有「一次性全绿」的迁移。

## 建议落地值

下表是本文建议进令牌的值，亮色与暗色各一套。`fill` 只做图形，`ink` 只做文字与图标，`tint` 只做容器底。

| 功能 | fill（只图形） | tint（浅底） | ink（字/图标） | ink on tint | 暗色 tint / ink |
|---|---|---|---|---|---|
| Create 蓝 | `#1F8DFD` | `#E8EFFD` | `#1957E7` | 5.11 | `#14233B` / `#6FB4FF` |
| Assess 青绿 | `#20B998` | `#DBF4EE` | `#0E7066` | 5.15 | `#0F2C28` / `#3FD6B4` |
| Practice 淡紫 | `#0E3676`（logo 里的图标色） | `#EAE0FD` | `#5B3AC4` | 5.82 | `#1E1A3A` / `#A78BFA` |
| Insights 玫红 | `#A63960` | `#FDE2E7` | `#A63960` | 5.10 | `#33141E` / `#E58BB0` |

说明：Practice 的紫色是 logo 粉彩底（`#EAE0FD`）的同族延伸，不是原图取样值；logo 里该图标本身是海军蓝。其余 ink 都由原色压深或取样得到，压深幅度以过 4.5:1 为准。

暗色下字标 `#0E3676` 对 `#0A1120` 只有 2.14；位图 logo 不能直接放暗底。落地做法是：亮色大字位用位图整标，暗色与侧栏小尺寸改用图形标 + 文本字标（Teach 用正文色，L 用 `#6FB4FF`，oop 用 `#3FD6B4`），不另做位图反色版。

动作与判分状态单独一组，暗色沿用现有状态色，不另造色相：

| 语义 | 亮色（白底 / 胶囊底） | 暗色（`#101A2C` / 胶囊底） |
|---|---|---|
| `action.primary` | `#1A63E0`（白字 5.38） | `#2A6FD6`（白字 4.84） |
| `action.link` | `#1A63E0`（5.38） | `#7DB1FF`（7.96） |
| `status.pass` | 字 `#17703F` / 底 `#E6F5EC`（5.44） | 字 `#6EE7A5` / 底 `#0C2A1D`（9.99） |
| `status.partial` | 字 `#8A5A00` / 底 `#FFF3D6`（5.37） | 字 `#FFC85C` / 底 `#2A2110`（10.33） |
| `status.fail` | 字 `#B42338` / 底 `#FDE6E8`（5.45） | 字 `#FF7D8A` / 底 `#331218`（6.89） |

## 7 个可比系统

### 1. Khan Academy Wonder Blocks（2026-03-23 重建色板）

**做法。** 先定少量核心色相，直接对白、黑、常规灰底测 4.5:1；不达标的颜色在色板铺开前先做小幅调整，避免色板定完再返工。全量 primitives 按 HSL 明度 0–100 编号，0 最深、100 最浅。语义四层：Domain（Core / Learning / Component）→ Layer（Background / Border / Foreground / Shadow）→ Context（Base / Instructive / Neutral / Disabled / Success / Warning / Critical / Knockout / Overlay）→ Intensity（Subtle / Default / Strong，以 Core Base Default 为锚，Strong 对比最高）。然后对全部 token 组合做三角形对比度矩阵，每格标 normal text、large text、background 三项的 pass/fail。暗色扩一条更暗的 primitive、微调少数色相、改部分语义 surface；Knockout 不反转，保持最高明度。命名上把 Brand / Primary 改成 Instructive，Warning 兼管「进行中、未完成、待定」。

**来源。** https://blog.khanacademy.org/how-we-rebuilt-khan-academys-color-system-from-the-ground-up/

**采纳。** 四支功能色照它的 Intensity 思路做成三档（fill/tint/ink），每档只绑一种用途；对比度按组合矩阵测，不只测白底；状态沿用 Success / Warning / Critical，品牌归 Instructive；「部分对」归 Warning，因为 Khan 的 Warning 明确包含 in-progress / incomplete / pending；暗色单独给值。

**不采纳。** 不展开 Domain × Layer × Context × Intensity 的全量 token（几百个，21 屏用不上）；不抄它整套 primitives；不引入 Knockout（我们没有深底反向组件）。

**可执行结论。** 先写一张 4 色相 × 3 档的表，`contrast.py` 直接读表里成对的 tint/ink 值；token 名用 `brand.<hue>.fill/tint/ink`。

### 2. Ant Design 6

**做法。** seed → map → alias 三层派生：seed 只放品牌色、圆角、字号、控件高度，algorithm 把 seed 展开成 map（`colorPrimary` 派生出 `colorPrimaryBg/Hover/Active/Border`），alias 供组件批量使用；组件另有 Component Token，`theme.components` 可单独覆盖。暗色是 `theme.darkAlgorithm` 换 token 值，组件不重做；`ConfigProvider` 可嵌套做局部主题。v6 还提供 `zeroRuntime`，把样式在构建期抽成静态 CSS。

**来源。** https://ant.design/docs/react/customize-theme

**采纳。** 品牌色只喂一个 seed：`colorPrimary: '#1A63E0'`，hover/active/bg/border 交给算法，不手写第二套梯度；功能色不进 seed（antd 的语义槽是 success/warning/error，不是品牌槽），走 CSS 变量与 `ui/` 组件；暗色主色用 `#2A6FD6`（白字 4.84、对 `#141414` 3.81），链接单独用 `#7DB1FF`（对 `#141414` 8.43）。

**不采纳。** 不用 `zeroRuntime`（我们要运行时切主题）；不给每个组件写组件 token（只在 Button / Table 少量覆盖）；不把四支功能色塞进 `colorSuccess` / `colorWarning` / `colorError`——那会把品牌色和判分色绑死，正是要避免的。

**可执行结论。** `tokens.ts` 里品牌只留一个 seed 蓝；状态色用独立字段，不进 antd 的语义槽。

### 3. Kahoot!（教育产品，官方品牌指南 2019-08）

**做法。** 官方 PDF 把颜色单列一章：彩色或照片底一律白字，禁止彩色字压彩色底；声明遵循 WCAG 2.1；默认品牌色是紫色 `#46178F`；同一设计最多用 2 支、最多 3 支色。色板页给 RGB hex 与 PMS，但该页是图片，具体值未取到。

**来源。** https://kahoot.com/files/2019/08/Kahoot-BrandGuide-August2019.pdf（第 10–11 页）

**采纳。** 「底与字必须由实测决定」这条原则。它的彩色底配白字不适用于我们的粉彩底——白字在四个 pastel 上只有 1.15–1.27，必须用深字（海军蓝 9.17–10.06）。同屏色相预算：一屏最多一支功能色加一组状态色，四支功能色不同屏。

**不采纳。** 不抄高饱和紫 + 白字组合；不把品牌色当正文色；不做「最多 3 色」的自动检查（无法稳定界定），只写进设计评审清单。

**可执行结论。** 一屏的色相预算写进评审清单；粉彩底上的字色只能取该色相的 ink 档或海军蓝。

### 4. Material Design 3

**做法。** 单色相的 tonal palette 只变 tone；scheme 把 tone 映射成角色，所以亮色 `primary` 是 primary40、暗色是 primary80，`onPrimary` 亮色是白、暗色是 primary20。容器类角色成对出现（primary / onPrimary、primaryContainer / onPrimaryContainer）。官方对自定义品牌色的要求是「保持同样的 luminance level」：亮色 primary 是 40，就继续用 40，否则组件对比会破。

**来源。** https://github.com/material-components/material-components-android/blob/master/docs/theming/Color.md；tonal palette 定义见 https://github.com/material-foundation/material-color-utilities

**采纳。** 亮暗主色不同值：亮 `#1A63E0`（白字 5.38）、暗 `#2A6FD6`（白字 4.84）；每个品牌色配一个 on-color，token 成对出现；「保 tone」用来约束暗色——不把亮色 hex 直接搬到暗底，玫红 `#A63960` 在 `#141414` 上只有 2.96。

**不采纳。** 不做动态取色与内容取色（品牌蓝要跨校稳定）；不做 HCT 的全阶 tone，只取 3 档；不引 M3 的 surface container 五层（antd 已有 bgLayout / bgContainer / bgElevated 三层）。

**可执行结论。** 亮暗各一份主色；每个 brand token 必须有配对的 on-color；暗色检查里加上「对 `#141414` ≥ 3:1」的边界项。

### 5. Radix Colors

**做法。** 每个色相 12 级，每级绑一个用途：1 app 背景、2 轻背景、3 组件背景、4 hover、5 active/selected、6 细分隔线、7 边框与焦点环、8 hover 边框、9 solid 背景、10 hover solid、11 低对比文字、12 高对比文字。step 9 是 chroma 最高、最纯的一级；多数 step 9 配白字，但 Sky、Mint、Lime、Yellow、Amber 明确配深色文字。step 11/12 才是文字级。

**来源。** https://www.radix-ui.com/colors/docs/palette-composition/understanding-the-scale

**采纳。** 命名即用途：我们只留三档，但 `fill` 只能做图形填充，`tint` 只能做容器底，`ink` 只能做文字与图标；「亮色 solid 配深字」直接支持黄、青绿胶囊用深字；step 9 的纯色不做文字，对应我们的 fill 禁文字。

**不采纳。** 不抄 12 级（我们只需 3 级 + 现有语义 token）；不引 alpha 色阶；不换 Radix Themes 组件。

**可执行结论。** token 名带用途后缀；页面不得把 fill 档用在 `color` 或 `background` 以外的属性上。

### 6. GitHub Primer

**做法。** 仓库按 `src/tokens/base`、`functional`、`component` 三层组织：base 是原始值，functional 输出 light/dark 的语义 CSS 变量，component 是组件级；主题是「主模式 + overrides」，变体只写与主模式的差异。仓库列出 9 个主题文件：light、light-tritanopia、light-high-contrast、light-colorblind、dark、dark-colorblind、dark-dimmed、dark-high-contrast、dark-tritanopia。

**来源。** https://github.com/primer/primitives ；目录结构 https://github.com/primer/primitives/tree/main/src/tokens

**采纳。** 只做 base + semantic 两层，component 层交给 antd 组件 token；主题只做亮/暗两套主模式，高对比与色盲变体不做，用「状态带图标 + 文字」从结构上替代；暗色只覆盖有变化的键，`tokens.css` 现在就是这么写的。

**不采纳。** 不做 9 套主题文件——每套都是 21 屏截图基线的成本；不引 component 层；不把 base 色阶全量导出成 CSS 变量（会诱使页面直接引用原始色）。

**可执行结论。** brand 原始值只允许出现在 `tokens.ts` 与 `tokens.css`；页面只能引语义变量。

### 7. IBM Carbon

**做法。** 数据可视化有独立色板：categorical 14 色按固定顺序使用，顺序是为「相邻色对比最大」挑选的；sequential 在亮色主题里最深表示最大值、暗色主题里最浅表示最大值；diverging 亮暗共用；另有 alert 色板（红/橙/黄/绿）专表状态。状态指示器要求 symbol、shape、color、type 四要素里至少三样，才满足 WCAG；颜色含义固定：红=危险/错误、橙=严重警告、黄=普通警告、绿=正常/成功、蓝=被动通知、灰=草稿、紫=异常/未定义。

**来源。** https://www.carbondesignsystem.com/building-blocks/data-visualization/color-palettes ；https://www.carbondesignsystem.com/building-blocks/core/patterns/status-indicators

**采纳。** 图表色板与状态色板分开命名、不互相引用；编码模块用 brand 的固定顺序，编码对错用 status；状态至少「颜色 + 图标 + 文字」三件套，「部分对」用半圆/半填充图标；系列顺序固定，不按数据临时挑色。

**不采纳。** 不抄 14 色 categorical（我们最多 5 系列）；不做 sequential/diverging；不把橙单独做成第四种状态——我们的「部分对」与系统警告共用琥珀值，靠文案与组件形态区分。

**可执行结论。** `chart.*` 与 `status.*` 不互相引用；图表最多 5 系列，顺序固定。

## 功能色与判分状态色：三对撞色怎么解

产品有三态判分（对 / 部分对 / 错），logo 有四支功能色。撞色是结构性的，不是挑错了色：

| 撞色对 | 品牌色（白底） | 状态色（白底） | 问题 | 处理 |
|---|---|---|---|---|
| 青绿 vs 绿 | `#20B998` 2.48 | `#17703F` 6.13 | 都是绿系，对白对比度差 3.65 | 判分屏模块图标退海军蓝；青绿只留粉彩底 |
| 黄 vs 琥珀 | `#FEBF41` 1.65 | `#8A5A00` 5.93 | 品牌黄太亮，做小 chip 会糊 | 新 logo 已去掉 Words 栏；黄只做铅笔/火花的装饰，不做功能入口 |
| 玫红 vs 红 | `#A63960` 6.22 | `#B42338` 6.49 | 明度接近，色相相邻 | 玫红只在 Insights 图表与品牌表面；判分用红 + 叉 + 文字 |

四条解法，按优先级：

1. **命名空间分离。** `brand.*`、`action.*`、`status.*` 三组 token，禁止跨组 alias。名字即语义，读代码能判断这个色能不能表达对错。
2. **场景分离。** 品牌色只出现在不判分的表面：导航、空态、插画、按模块着色的图表。状态色只出现在判分的表面：分数、单元格、结果条、复核结论。同一个元素只能属于一边。
3. **形状分离。** 状态是实心小胶囊 + 图标 + 文字：对用勾，部分对用半圆，错用叉。品牌是粉彩圆底 + 线图标 + 标签。去掉颜色仍然能区分，满足 WCAG 1.4.1；Carbon 的「四要素取三」是同一思路。
4. **判分屏收口。** 只要屏幕显示判分结果，模块入口的青、黄、玫红三支 ink 就换成海军蓝，蓝色保留；粉彩底可以保留，也可以退成灰。这样三对撞色不会在同一视区相邻。

图表单独一条：按模块编码用 `chart.brand.*`（ink 档，对白 ≥ 3:1），按对错编码用 `status.*`；同一张图不能混用两套。「部分对」和系统警告共用 `#8A5A00`，但 token 分开（`status.partial` / `status.warning`），组件形态不同，禁止在同一卡片里并列。

## 不采纳清单

- **不做 9 套主题。** Primer 的 light/dark × 高对比/色盲/暗化组合是它的产品约束，不是我们的；多一套就是 21 屏截图基线多一轮回归。
- **不做 codemod、不批量替换。** 1073 行手写 CSS 分散在 4 个文件（当前工作区合计），21 屏结构不一致；逐屏迁移才能把「哪屏坏了」缩到一次提交。
- **不引 Tailwind / shadcn。** 现有语义类名（`.btn.pri`、`table th`）是资产；再引一套工具类，`check-ui` 无法收敛。
- **不抄 12 级色阶全文。** 我们只需要 fill/tint/ink 三档；12 级会产出大量没人用的变量。
- **不做 M3 动态取色、不下沉 HCT 全色阶、不建 surface container 五层。**
- **不展开 Khan 的四层 token 体系。** Domain × Layer × Context × Intensity 对 21 屏是过度设计。
- **不让品牌亮色（fill 档）进文字、边框、图标**（logo 本身除外）。这是本文最重要的一条禁令。
- **不把粉彩底做成第三套主题。** 粉彩是组件背景，不是主题。

## 未核实（不要当结论引用）

- **Duolingo 品牌规范。** `design.duolingo.com/identity/color` 现在跳转到 `blog.duolingo.com/hub/design/`，页面上没有品牌色规范；搜索结果摘要不能当一手资料——未核实。Coursera 品牌页也只返回标题，Quizizz 未找到官方色板文档；教育产品改用 Kahoot! 官方 PDF。
- **Atlassian 的状态色规则。** `atlassian.design` 的 Lozenge 与颜色页只返回导航壳，没取到可引用正文。状态色证据改用 Carbon；Atlassian 未核实，不作依据。
- **Kahoot! 色板的具体 hex/PMS。** PDF 第 11 页是图片，文字层没有色值；具体色值未核实；只核实了第 10 页的用色规则与默认紫 `#46178F`。
- **Material 3 的 m3.material.io 正文。** 该站只返回标题，未取到正文；正文未核实；本文用 MDC-Android 官方 Color.md 与 material-color-utilities README 代替，两条都指向 M3 的同一套机制。

## 参考

- Khan Academy 重建色板（2026-03-23）https://blog.khanacademy.org/how-we-rebuilt-khan-academys-color-system-from-the-ground-up/
- Ant Design 定制主题（seed/map/alias、algorithm、组件 token、zeroRuntime）https://ant.design/docs/react/customize-theme
- Kahoot! 品牌指南 PDF（2019-08）https://kahoot.com/files/2019/08/Kahoot-BrandGuide-August2019.pdf
- Material Design Components Android 颜色主题（primary40 / primary80、保 luminance level）https://github.com/material-components/material-components-android/blob/master/docs/theming/Color.md
- Material Color Utilities（tonal palette / core palette 定义）https://github.com/material-foundation/material-color-utilities
- Radix Colors 色阶用途 https://www.radix-ui.com/colors/docs/palette-composition/understanding-the-scale
- Primer Primitives（base/functional/component、9 个主题、overrides）https://github.com/primer/primitives
- IBM Carbon 数据可视化色板 https://www.carbondesignsystem.com/building-blocks/data-visualization/color-palettes
- IBM Carbon 状态指示器（四要素取三、颜色含义）https://www.carbondesignsystem.com/building-blocks/core/patterns/status-indicators
- WCAG 2.2 SC 1.4.3 对比度（logo 豁免、品牌不豁免）https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html
- WCAG 2.2 SC 1.4.1 颜色的使用 https://www.w3.org/WAI/WCAG22/Understanding/use-of-color.html
- WCAG 2.2 SC 1.4.11 非文本对比度 https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html

## 结尾：对本项目的 8 条约束

1. `web/src/ui/tokens.ts` 新增独立 `brand` 常量（不塞进 antd 的 token 对象，避免 ThemeConfig 类型溢出），`tokens.css` 加同名变量；4 个色相 × `fill / tint / ink`，亮暗各一份，键集合完全一致；`contrast.py` 解析该常量并覆盖成对键。
2. 亮色 `colorPrimary` 改 `#1A63E0`，暗色改 `#2A6FD6`；暗色 `colorLink` 单独 `#7DB1FF`。三个值都进 `contrast.py`（白字、暗底两种底）。
3. `scripts/ui/contrast.py` 从 16 组扩到 40 组（最终值见 `specs/006-brand-visual/spec.md`）：现有 16 组不变，新增 5 组亮色 feature ink×tint、5 组暗色 feature ink×tint、3 组亮色 status 字×浅底、3 组暗色 status 字×浅底、3 组亮色 status 字×白、3 组暗色 status 字×面板、1 组暗色 accent-text×面板、1 组暗色 accent-text×accent-weak；任一不达标退出码为 1。
4. `tokens.ts` 新增独立 `status` 常量（同样不塞进 ThemeConfig），`tokens.css` 同步 `--status-pass` 等变量及 `-bg`，亮暗各一套；页面不得出现 `#17703F`、`#8A5A00`、`#B42338` 字面量。
5. `scripts/check-ui.py` 增加三条禁令：页面出现品牌或状态色字面量（含 5 个 fill 的 hex）；`status-*` 用在非判分装饰上；判分 Tag 自闭合（没有文字与图标）。
6. `web/src/ui/Tag` 的判分 tone 只允许 `pass / partial / fail` 三个值，组件内部强制渲染图标与文字；「部分对」图标固定为半圆，不接受只换颜色。
7. 报告图表只接受 `chart.series[0..4]` 的固定顺序，或 `status.*`；同一图不得混用两套；暗色用暗色 fill 表。
8. `/ui-kit` 增加「品牌 × 状态」对照页：5 个粉彩胶囊、3 个 status chip、亮暗各一组，附 `contrast.py` 的实测比值；没有这页不许合并样式改动。
