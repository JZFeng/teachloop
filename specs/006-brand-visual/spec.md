# 规格说明 — 品牌视觉（TeachLoop logo 落地）

- 特性编号：`006-brand-visual`
- 状态：**已实现，待用户验收视觉**
- 上游：`.specify/memory/constitution.md` 第十五条；`docs/research/2026-10-22-brand-visual.md`；用户 2026-10-22 的两版 logo 与「直接开整、改所有页面」的决定
- 决策来源：`docs/decisions/006-brand-visual.md`
- 样张：`docs/mock/brand-2026-10-22/index.html`（含 README 与 14 张截图）
- 真页面截图：`docs/reports/brand-app-2026-10-22/`（30 张）
- 日期：2026-10-22

---

## 1. 摘要

产品定名 **TeachLoop**。本轮把前端从「通用后台蓝」换成新 logo 的品牌层：

| 层 | 内容 | 落点 |
| :--- | :--- | :--- |
| 品牌资产 | mark / 字标 / 整标 / favicon，从 1254×1254 位图裁切、去白边 | `scripts/brand/build-assets.py`、`web/public/brand/` |
| 令牌 | 品牌原色、四个功能位（Create/Assess/Practice/Insights）、判分三态、语义色 | `web/src/ui/tokens.ts`、`web/src/styles/tokens.css` |
| 守卫 | 40 组对比度（原 16 组）+ 两处令牌一致性 + 暗色背景改读令牌 | `scripts/ui/contrast.py` |
| 组件 | `Brand`（mark / text / lockup）、导航功能色胶囊、页头 eyebrow | `web/src/ui/Brand.tsx`、`PageHeader.tsx`、`Shell.tsx` |
| 页面 | 登录页重排；全站 24 条路由继承新令牌；25 条路由已截图 | `web/src/app/`、`teacher/`、`student/`、`exam/` |
| 展示 | 资料页「偏好设置 → 界面标准」面板（原 `/ui-kit`）增加品牌色板与功能位对照 | `web/src/app/UiKitPanel.tsx` |

视觉立场（与 2026-10-21 的「教育行业稳重」不冲突）：**结构不动**——高信息密度、实边框、无渐变、无 emoji；**只换皮肤**——海军蓝字标、压深一档的品牌蓝主操作、四个功能位配色、粉彩底、更圆的圆角。

### 1.1 关键设计立场

| 议题 | 立场 | 理由 |
| :--- | :--- | :--- |
| 主操作颜色 | `#1A63E0`（白字 5.38:1），不是 logo 亮蓝 | 亮蓝 `#1F8DFD` 白字仅 3.34:1，违宪第十五条 4.5:1 |
| 亮色用途 | 只做填充、插画、图表；文字用深一档 ink | WCAG 1.4.3 豁免 logo 本身，不豁免界面正文 |
| 功能色 vs 判分色 | 两个命名空间，功能色进导航/eyebrow/胶囊，判分色进带文字标签 | 青↔绿、黄↔琥珀、玫红↔红三对撞色靠场景与文字分开 |
| 圆角 | 卡片 12 / 控件 8 / 小件 6 | logo 圆润；密度与控件高 32 不动 |
| 字体 | 不引 webfont，字标用位图 + 文本两种形态 | 中文 webfont 1–5 MB，与自托管冲突 |
| 暗色字标 | 亮色位图整标；暗色 mark + 文本字标 | 深海军蓝字标在暗底 2.14:1，硬反色位图会发毛 |
| 信息架构 | 不动，仍 7 个导航项 | Practice / Words 未上线，不画空承诺；新 logo 已去掉 Words |

### 1.2 与 003 的关系

003 的结构性规则**全部保留**：令牌唯一真源、组件唯一入口、状态齐全、对比度硬指标、中文排版、图标不用 emoji、亮暗两套、改动可验证。

本轮**取代** 003 的：主色值与暗色主色（`#1D5FBF` → `#1A63E0`；暗色仍 `#2A6FD6` 但暗底改海军蓝）、圆角（6/8 → 8/12/6）、页面标题（20 → 24px）、登录页版式。003 的 AC-27.1–27.6 仍成立；具体色值以 `contrast.py` 为准，不在 003 里重复维护。

---

## 2. 用户故事

### US-31 作为教师或学生，我一眼能看出这是 TeachLoop，而不是任意一个后台

登录页、侧栏、页头、按钮、标签都是同一套品牌语言；换一个页面不会像换了一个产品。
**验收**：AC-31.1–31.5。

### US-32 作为维护者，我改品牌只改两处令牌，走样会被脚本拦住

颜色、圆角、字号不散在页面里；品牌资产可复跑；对比度与硬编码各有守卫。
**验收**：AC-32.1–32.5。

### US-33 作为在暗光或强光下使用的用户，我能看清，也不被颜色误导

亮暗两套都过 WCAG AA；判分状态不靠颜色单独表达；焦点环可见。
**验收**：AC-33.1–33.5。

---

## 3. 验收标准

#### US-31 品牌一致

- [ ] AC-31.1 登录页 `/login` 在 1440×900 亮/暗两套下：左侧显示整标（亮色位图 / 暗色 mark + 文本字标）、`Better Practice. More Insights.`、中文 tagline、三条卖点、四个功能位（Create / Assess / Practice / Insights，Practice 带「规划中」脚注）；证据 `docs/reports/brand-app-2026-10-22/login-{light,dark}.png`。
- [ ] AC-31.2 侧栏品牌区使用 `Brand` 组件的 mark + 文本字标（不是位图字标）；作业/试卷蓝、批改/复核青、报表/我的反馈玫红的导航胶囊可见；证据 `home-{light,dark}.png`、`papers-light.png`。
- [ ] AC-31.3 每一条使用 `PageHeader` 的路由都有 eyebrow，颜色按模块：`/papers*` CREATE 蓝、`/assignments` 与 `/assignments/new` CREATE 蓝、`/grading*` 与 `/review` ASSESS 青、`/reports` 与 `/offerings/:id/summary` INSIGHTS 玫红、`/feedback*` 与 `/exams/:id` ASSESS 青、其余中性灰；截图覆盖至少 20 条路由。
- [ ] AC-31.4 四个功能色只出现在导航胶囊、eyebrow、登录页/UI kit 的功能位与图表；判分状态只出现在带文字的状态标签；同一个元素不同时使用两者。
- [ ] AC-31.5 品牌命名统一：`app.name = TeachLoop`（zh/en）、tagline 与 brandLine 入 i18n、`index.html` 标题为 `TeachLoop · AI 教学平台`、favicon/apple-touch-icon 存在；`check-i18n` 前端 zh/en 键数一致。

#### US-32 维护者

- [ ] AC-32.1 `python3 scripts/ui/contrast.py` 退出码 0，输出 **40 组**全通过；`tokens.ts` / `tokens.css` 的语义、功能位、判分三态、字标值逐键一致；原始品牌色只校验 `:root`。
- [ ] AC-32.2 `python3 scripts/check-ui.py` 0 新增违规；`web/src` 里除两个令牌文件外没有新增颜色字面量；页面不直接 `import antd`。
- [ ] AC-32.3 `python3 scripts/brand/build-assets.py` 退出码 0，源图 sha256 固定，产出 9 个资产 + `manifest.json`；`--check` 通过；四底色对照图 `docs/reports/brand-mock-2026-10-22/logo-surfaces.png` 无白边。
- [ ] AC-32.4 `pnpm typecheck` 0、`pnpm build` 0、`pnpm test` 5/5、`make test` 退出码 0（后端用例不改）。
- [ ] AC-32.5 文档计数自检通过：`specs/006` 的 US / AC / FR 计数与 `tasks.md` 的任务数互相吻合，无重复编号；003 顶部标注「调色板与圆角被 005 取代」。

#### US-33 可访问

- [ ] AC-33.1 40 组对比度在亮暗两套下全部达标：正文/小字 ≥4.5、边框/图标 ≥3.0；暗色背景使用 `#0A1120` / `#101A2C`，不再写死 `#141414`。
- [ ] AC-33.2 判分状态标签始终带文字（对 / 部分对 / 错），截图与组件代码都能看出；没有只靠颜色的状态。
- [ ] AC-33.3 资料页「界面标准」面板的焦点态可见（2px accent + 2px offset）；键盘 Tab 能走完登录 → 首页 → 试卷 → 新建作业 → 复核。
- [ ] AC-33.4 暗色字标可读：mark + 文本字标，`--logo-navy` 对暗底 14.95:1；不出现深海军蓝位图字标压暗底。
- [ ] AC-33.5 品牌亮色（`#1F8DFD` / `#20B998` / `#FEBF41`）没有承担正文、链接、主按钮底或唯一的状态指示。

---

## 4. 功能需求

| ID | 需求 | 对应故事 |
| :--- | :--- | :--- |
| FR-062 | 品牌资产：从固定源图裁切 mark / 字标 / 整标 / favicon；四角洪水去背 + 边缘去白；脚本可复跑并自检裁切框 | US-32 |
| FR-063 | 令牌层：语义色、`BRAND`、`FEATURE_LIGHT/DARK`、`STATUS_LIGHT/DARK`、`LOGO_LIGHT/DARK`；两套主题同键集合 | US-31/32 |
| FR-064 | 对比度守卫：16 → 40 组；暗色背景从令牌读；功能位 ink×tint、判分 ink×浅底/白/面板、链接色逐对检查 | US-32/33 |
| FR-065 | `Brand` 组件：mark / text / lockup 三形态、四档尺寸、亮暗自适应、屏幕阅读器可读 | US-31/33 |
| FR-066 | 壳层：侧栏品牌区、导航功能色胶囊、当前项文字用 `--accent-text` | US-31 |
| FR-067 | 登录页：整标 + brandLine + 中文 tagline + 三个卖点 + 四个功能位；错误/加载态沿用组件层 | US-31 |
| FR-068 | 页头 eyebrow：路由前缀 → 文案 + 色调的唯一映射表；页标题 24px | US-31 |
| FR-069 | `/ui-kit`：品牌色板、四个功能位、判分三态、原始色、实测对比度 | US-31/32 |
| FR-070 | 命名与入口：`app.name = TeachLoop`、tagline/brandLine、favicon、apple-touch-icon、页面标题 | US-31 |
| FR-071 | 文档：spec / decision / 宪法第十五条 / 003 标注 / PROGRESS / tasks 互相一致 | US-32 |

---

## 5. 令牌表（唯一真源）

### 5.1 语义令牌

| 令牌 | 亮色 | 暗色 | 关键实测 |
| :--- | :--- | :--- | :--- |
| `--bg` | `#F6F8FC` | `#0A1120` | 正文对底 14.82 / 16.18 |
| `--panel` | `#FFFFFF` | `#101A2C` | 边框对面板 3.45 / 3.60 |
| `--panel-2` | `#EFF4FB` | `#16223A` | — |
| `--line` / `--line-2` | `#DCE4F2` / `#EBF0F8` | `#24334D` / `#1B2740` | — |
| `--line-strong` | `#7C8BA3` | `#5E7391` | 3.45 / 3.60 |
| `--text` | `#0E2340` | `#E8EEF9` | 15.75 / 16.18 |
| `--muted` / `--muted-2` | `#41536E` / `#55677F` | `#B3C0D4` / `#93A4BD` | 7.81 / 6.87 |
| `--accent` | `#1A63E0` | `#2A6FD6` | 白字 5.38 / 4.84 |
| `--accent-2` | `#1450BC` | `#2463C4` | 7.23 / 5.74 |
| `--accent-weak` | `#E8EFFD` | `#14233B` | 当前项文字 4.66 / 13.52 |
| `--accent-text` | `#1A63E0` | `#7DB1FF` | 5.38 / 7.96 |
| `--green` / `-weak` | `#17703F` / `#E6F5EC` | `#6EE7A5` / `#0C2A1D` | 5.44 / 9.99 |
| `--amber` / `-weak` | `#8A5A00` / `#FFF3D6` | `#FFC85C` / `#2A2110` | 5.37 / 10.33 |
| `--red` / `-weak` | `#B42338` / `#FDE6E8` | `#FF7D8A` / `#331218` | 5.45 / 6.89 |

### 5.2 品牌原色（主题无关，只做填充）

| 名称 | 值 | 白底对比度 | 允许用途 |
| :--- | :--- | ---: | :--- |
| Navy | `#0E3676` | 11.62:1 | 字标、正文级深色 |
| Azure | `#1F8DFD` | 3.34:1 | **仅图形/填充**，不做 14px 文字 |
| Teal | `#20B998` | 2.48:1 | **仅装饰填充**，不做图标或文字 |
| Yellow | `#FEBF41` | 1.65:1 | 铅笔/火花装饰；承载内容时必须配海军蓝 |
| Rose | `#A63960` | 6.22:1 | 文字、图标、图表 |

### 5.3 四个功能位

| 功能 | 亮色 ink / tint | 实测 | 暗色 ink / tint | 实测 |
| :--- | :--- | ---: | :--- | ---: |
| Create | `#1957E7` / `#E8EFFD` | 5.11 | `#6FB4FF` / `#14233B` | 7.24 |
| Assess | `#0E7066` / `#DBF4EE` | 5.15 | `#3FD6B4` / `#0F2C28` | 8.14 |
| Practice | `#5B3AC4` / `#EAE0FD` | 5.82 | `#A78BFA` / `#1E1A3A` | 6.10 |
| Insights | `#A63960` / `#FDE2E7` | 5.10 | `#E58BB0` / `#33141E` | 6.87 |

Practice 的紫色是淡紫底的同族延伸，不是原图取样值；logo 里该图标本身是海军蓝。Practice 模块未上线，登录页与导航只作占位/预留。

### 5.4 判分三态

| 状态 | 亮色 ink / 浅底 | 白底 | 暗色 ink / 浅底 | 面板 |
| :--- | :--- | ---: | :--- | ---: |
| 对 | `#17703F` / `#E6F5EC` | 6.13 | `#6EE7A5` / `#0C2A1D` | 11.31 |
| 部分对 | `#8A5A00` / `#FFF3D6` | 5.93 | `#FFC85C` / `#2A2110` | 11.33 |
| 错 | `#B42338` / `#FDE6E8` | 6.49 | `#FF7D8A` / `#331218` | 7.09 |

### 5.5 造型

- 圆角：卡片 12 / 控件 8 / 小件 6；胶囊 999。
- 控件高 32（大号 40）；正文 14px / 行高 1.65；字号六档 12/13/14/16/20/24；页面标题 24 / 750。
- 阴影：海军蓝偏色，不用纯黑；主按钮无渐变、无彩色投影。

---

## 6. 守卫与证据

| 检查 | 命令 | 结果 |
| :--- | :--- | :--- |
| 对比度 40 组 + 两处令牌一致 | `python3 scripts/ui/contrast.py --verbose` | 40/40 通过 |
| UI 硬编码/组件入口 | `python3 scripts/check-ui.py` | 0 新增（存量 123） |
| i18n 键对齐 | `python3 scripts/check-i18n.py` | zh/en 各 1197 键，新增中文字面量 0 |
| 类型与构建 | `cd web && pnpm typecheck && pnpm build` | 0 / 0 |
| 前端金标 | `cd web && pnpm test` | 5/5 |
| 全量回归 | `make test` | 见交付记录（后端未改） |
| 品牌资产 | `python3 scripts/brand/build-assets.py --check` | 9 个资产 + manifest |
| 截图 | `node scripts/brand/shoot-app.mjs` | 30 张真页面截图 |

### 6.1 已截图的路由

登录（亮/暗/英文/焦点）、初始化；教师：首页（亮/暗/英文/焦点）、班级、作业、作业新建、试卷、试卷新建（亮/暗）、
导入、批改总览、批改详情、复核、报表、班级汇总、资料、通知、组件标准、管理员 AI 设置；
学生：首页（亮/暗）、班级、反馈、反馈详情、作业、作答须知、考试作答 live。完整清单见
`docs/reports/brand-app-2026-10-22/README.md`。

### 6.2 明确未验证（不许写成已优化）

- 考试 live 页与反馈详情页已补截（`student-exam-live-light.png`、`student-feedback-detail-light.png`）。
  live 页的取图方式：用户建的考试窗口在创建时就已过期（startAt 02:56Z / dueAt 03:00Z），
  临时把 `due_at` 延后 3 小时、经 `POST /api/offerings/{id}/attempts` 开了一次作答，截完删掉该 attempt
  并把 `due_at` 还原；这不改变任何产品代码。
- **管理员 AI 设置页**与**初始化 `/setup`** 已截图（`admin-ai-light.png`、`setup-light.png`）：
  教师打开管理员页会看到 API 403 的 Alert，但版式与令牌继承正常；这是权限行为，不是视觉问题。
- **静态检查已做**：`AdminAiPage` / `FeedbackDetailPage` / `SetupPage` 无颜色字面量，全部走令牌与组件；
  `ExamLivePage` / `ExamPaper` 的颜色走 `exam.css` 的令牌变量（仅两处 `rgba(15,20,28,.34)` 遮罩是主题无关的深色叠层）。
  live 页的交互态等 QA 数据重建后补截：`scripts/brand/shoot-app.mjs` 已支持自动点「开始答题」并截
  `student-exam-live-light`（考试不可开始时跳过并告警）。
- **跨浏览器**：只在 Chrome（headless 1440×900 @2x）验证。
- **21 屏截图基线**：003 的 T308 仍未做；本轮是评审截图，不是像素基线。

---

## 7. 非功能约束

- **零后端、零表、零路由、零新依赖**；`make test` 只证明没碰坏。
- **不引 webfont**；品牌字标用位图 + 文本两种形态。
- **资产体积**：`logo-lockup-960.png` 236 KB、`logo-mark-512.png` 88 KB；登录页首次加载可接受。
- **i18n**：所有新文案走 zh/en；UI kit 与登录页没有新增中文字面量。
- **主题**：仍只做亮/暗两套；暗色是换令牌值，不另做界面。
- **性能**：构建 chunk 仍按 antd / react 拆分；包体积只增加两张 PNG。

---

## 8. 范围外

- 信息架构调整、五个功能位改成五个导航项（Practice / Words 未上线）。
- 21 屏逐屏重写；本轮靠令牌继承 + 页头 eyebrow + 壳层统一。
- `Tag` 组件的语义重写（仍用 tone: blue/green/amber/red/gray）。
- 管理员 AI 设置、考试 live、反馈详情的视觉重截（见 6.2）。
- 生产部署与真实微信/OSS 验证。
