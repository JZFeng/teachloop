# UI 标准落地报告（2026-10-21 夜间）

- 分支：`feat/ui-standard`（`main` 上只多了一个问卷记录的 commit）
- 规格：`specs/003-ui-standard/spec.md` / `plan.md` / `tasks.md`
- 调研：`docs/research/2026-10-21-design-system.md`
- 宪法：第十五条（UI 与可访问性）
- 决策来源：`.dsh/surveys/20261021-0000-ui-6-fb9093b8.json`

---

## 1. 一句话结论

**紫渐变按钮没了，全站换成一套蓝主色 + 实边框的令牌，21 屏一起变（不是只改 5 屏）；
亮/暗两套在真浏览器里验过；写死颜色、直接引 antd、手写 btn 类名现在会让 `make test` 红。**
视觉「好不好看」需要你点头——这一点我没法替你确认。

## 2. 实际跑过的命令（真实结果，不是「应该会」）

| 命令 | 结果 |
| :--- | :--- |
| `cd web && npx tsc --noEmit` | 退出码 0 |
| `cd web && pnpm run build` | 成功；antd chunk gzip **293.82 kB**、业务 chunk gzip 98.72 kB、react 14.79 kB |
| `make check-ui` | 通过，存量 145 处（基线只降不升） |
| `python3 scripts/ui/contrast.py` | **11 组对比度全部达标**，两套令牌与旧变量层一致 |
| `make check-i18n` | 通过：zh/en **各 1060 键**完全对齐；前端新增中文字面量 0 |
| `make test` | **退出码 0**，54 个测试类 **206 tests / 0 failures / 0 errors / 0 skipped** |
| 反证：往 `PapersPage.tsx` 插 `#ff0000` | `check-ui` 退出码 **1**（2 处新增），撤回后回到 0 |

## 3. 截图（真浏览器，1280×813）

`docs/reports/ui-standard-2026-10-21/` 下 9 张：

| 文件 | 说明 |
| :--- | :--- |
| `login-light.png` | 登录页：实色主按钮、antd 输入框、必填星 |
| `teacher-home-light.png` / `teacher-home-dark.png` | 教师首页：4 张统计卡 + 表格分页，两套主题 |
| `papers-light.png` / `papers-dark.png` | 试卷列表：antd 表格、行内操作、状态标签 |
| `ui-kit-light.png` / `ui-kit-dark.png` | 组件展示页：按钮 5 态、字段 4 态、统计卡、表格、状态块 |
| `review-light.png` | 复核工作台 |
| `student-home-light.png` | 学生首页（**未迁移的屏**，靠令牌层自动跟随新视觉，用来证明不会再一半新一半旧） |

截图是**临时的 dev server（5176）**跑出来的，截完已关闭，没有动你的 8010/5173。

## 4. 我替你做的决定（醒来可以推翻）

| # | 决定 | 为什么 | 代价 |
| :--- | :--- | :--- | :--- |
| 1 | 用的是 **antd 6.6.7**，不是计划里写的 5 | pnpm 装到最新稳定；v6 原生支持 React 19 | v6 与网上多数 v5 教程有差异（已按装到的类型写代码） |
| 2 | 删掉 `@ant-design/v5-patch-for-react-19` | v6 迁移文档明确写 React 19 已原生支持 | 无 |
| 3 | `tokens.css` 里写**具体值**，不是 `var(--ant-*)` | antd 的 CSS 变量挂在每个组件的 hash class 上，`:root` 里引用会失效，16 屏会整片掉色 | 同一份值写两处；已用 `contrast.py` 断言两边相等 |
| 4 | 主题开关放**侧栏底部**，资料页没加 | 侧栏常驻、位置固定；资料页要新起一块 UI | 与计划 A2 的「资料页也放」有偏差 |
| 5 | 默认亮色，不跟随系统 | 教室投影要可预测 | 想跟系统的用户要手动切 |
| 6 | 保留 `components/States.tsx` 作为转发层 | 15 个未迁移页面不必今夜改 import | 一个临时文件要等 T310 才能删 |
| 7 | 调研文档由子代理产出 | 与写代码并行 | 文中 7 类标了「未核实」（如各家中文字号专项规范） |

## 5. 没做到 / 没验证（不许当已完成）

1. **21 屏截图基线（T308）未建立**：没装 Playwright、没跑 42 张基线。今晚只有 9 张人工截图，
   没有「改造前后像素差」的硬证据。
2. **剩余 16 屏没有组件化（T309）**：它们靠令牌层跟随新颜色/圆角/字号，但按钮仍是手写 `.btn`。
   好处是不会一半新一半旧；代价是交互细节（焦点环、禁用态）在旧页面里仍不如组件层齐。
3. **跨浏览器未验**：只在本机 Chromium 里看过，Safari/Firefox 未测。
4. **`/ui-kit` 的「悬停」是说明文字**，「焦点」用 `box-shadow` 模拟——浏览器不允许脚本伪造 `:focus-visible`。
5. **键盘走查（AC-29.1）没跑完整流程**：组件层按钮有 `focus-visible`，但没有逐屏 Tab 走一遍并留证。
6. **试卷向导里还有 1 处 emoji 图标**（已进基线，迁移该屏时替换）。
7. **antd 单 chunk 922 kB（gzip 294 kB）** 触发构建体积提示；已拆 vendor chunk，未做进一步按需引入。

## 6. 你怎么看效果

```bash
git switch feat/ui-standard
cd web && pnpm dev          # http://localhost:5173
# 登录 teacher@teachloop.local / teachloop123
# 左侧导航最后一项「组件标准」→ /ui-kit
# 侧栏底部「主题 亮色/暗色」切换
```

回滚：`git switch main`（数据库零改动，`dist/`、`work/` 都不入库）。

## 7. 文件清单

**新增**
- `web/src/ui/`：`tokens.ts`、`ThemeProvider.tsx`、`ThemeToggle.tsx`、`Button.tsx`、`Field.tsx`、`Input.tsx`、`Alert.tsx`、`PageHeader.tsx`、`StatCard.tsx`、`Tag.tsx`、`DataTable.tsx`、`States.tsx`、`ui.css`、`index.ts`
- `web/src/styles/tokens.css`
- `web/src/app/UiKitPage.tsx`
- `scripts/check-ui.py`、`scripts/ui-baseline.txt`、`scripts/ui/contrast.py`
- `specs/003-ui-standard/{spec,plan,tasks}.md`
- `docs/research/2026-10-21-design-system.md`
- `docs/reports/ui-standard-2026-10-21/`（本报告 + 9 张截图）

**改动**
- `web/package.json`（+antd、+@ant-design/icons）、`web/index.html`、`web/src/main.tsx`
- `web/src/App.tsx`（`/ui-kit` 路由）、`web/src/app/Shell.tsx`（导航 + 主题开关 + 图标）、`web/src/app/LoginPage.tsx`
- `web/src/teacher/{PapersPage,HomePage,AssignmentCreatePage,ReviewPage,common}.tsx`
- `web/src/components/States.tsx`（转发层）
- `web/src/styles/mockup.css`（删主题块与死代码、去渐变、去大写表头）
- `web/vite.config.ts`（vendor chunk 拆分）
- `web/src/locales/{zh-CN,en}.json`（+43 键）
- `Makefile`（`check-ui` 进 `test`）
- `.specify/memory/constitution.md`（第十五条）
- `AGENTS.md`（调研路径 `.docs/research/` → `docs/research/`）
- `scripts/ui-baseline.txt`（145 处存量）

## 8. 留给你的三件事

1. **看截图说「对不对味」**——方向不对现在改最便宜，16 屏还没动。
2. **决定要不要 T308（截图基线）**：需要装 Playwright（约 150 MB 浏览器内核）与 21 屏 × 2 主题的基线维护。
3. **决定 T309 的节奏**：剩余 16 屏是「下次改到就顺手迁」还是「专门排一批」。

---

## 9. 第二轮（你反馈后）

你说三件事：合入 main、修我点出的 3 处观感、真改批改总览与详情。已做完：

| 项 | 改法 | 证据 |
| :--- | :--- | :--- |
| 合入 main | rebase 到 vocab 提交之后，再 `--ff-only` 合并；宪法两节都在（第十四条进度注 + 第十五条） | `git log` 线性，工作区干净 |
| 复核「打开」按钮 | 由文字按钮改为默认小按钮，按钮不再像纯文本 | `review-light.png` |
| 登录页布局 | hero + 表单收进一块面板：左侧用强调色最浅一档做品牌区，右侧表单；移动端单列 | `login-v2.png` |
| 资料页主题开关 | 资料页改用组件层，偏好设置里补 `ThemeToggle` | `profile.png` |
| 批改总览 | 4 张统计卡可点即过滤（再点取消）；一排 chip 换成 Segmented；每行 4 个状态标签改成「进度条 + 已交/应交 + 百分比 + 状态标签」 | `grading-overview.png` |
| 批改详情 | 7 个状态标签改成 4 张统计卡；5 个操作按主次分到页头（派发为唯一主按钮）；回执/错误改 Alert；拒收表单用 Field；拒收理由并入状态 tooltip，表格不再折行 | `grading-detail.png` |

守卫也跟着收紧了：`check-ui` 存量从 **145 → 126**（我自己新写的两页直接 `import 'antd'` 被它拦下，改成从 `ui/` 引）。

仍未做：截图基线（T308）、剩余 13 屏组件化（T309）、删兼容层（T310）。

---

## 10. 第三轮（2026-10-21）：侧栏图标 + 暗色当前项对比

你的反馈是侧栏「纯文字怪丑的」。这一轮只改这一件事，外加规划时发现的一个既有对比度问题。
截图全部来自已在跑的 5173（真浏览器 1280×813），没有新起服务。

### 10.1 改了什么

| 入口 | 图标 | 入口 | 图标 |
| :--- | :--- | :--- | :--- |
| 首页 | `HomeOutlined` | 复核 | `AuditOutlined` |
| 班级 | `TeamOutlined` | 报表 | `BarChartOutlined` |
| 作业 | `BookOutlined` | 组件标准 | `AppstoreOutlined` |
| 试卷 | `FileTextOutlined` | AI 设置（管理员） | `RobotOutlined` |
| 批改 | `HighlightOutlined` | 我的反馈（学生） | `CommentOutlined` |
| 通知（顶栏） | `BellOutlined` | 资料 / 退出登录（页脚） | `UserOutlined` / `LogoutOutlined` |
| 主题亮/暗 | `SunOutlined` / `MoonOutlined` | | |

- 新增 `web/src/ui/icons.tsx` 作为图标唯一出口；全仓只有它直接 `import '@ant-design/icons'`
  （`grep` 命中 1 个文件：导入语句 + 注释里的说明）。页面与 `ThemeToggle` 都从 `../ui` / `./icons` 取。
- 侧栏 `NavItem` 增加必填 `icon`，教师 8 项、学生 3 项、管理员额外 1 项；图标 16px、
  与文字间距 10px，未选中 `--muted-2`、悬停 `--text`、当前项 `--accent`。
- 页脚资料行与「退出登录」按钮补图标；顶栏「通知」沿用 Bell，但改从组件层引。
- `/ui-kit` 新增「图标」区，15 个图标一页对照；这是宪法第十五条「样式改动附展示页」的落点。
- 图标全部 `aria-hidden`（文字已经可读），键盘焦点补 `.nav-link:focus-visible` 环。

### 10.2 顺手修掉的暗色对比度问题

暗色下当前项文字原来是 `--accent` on `--accent-weak`，实测 **3.25:1**，正文要求 4.5:1，是既有违规。
改法：` :root[data-theme='dark'] .nav-link.on { color: var(--text); }`，文字 **11.14:1**；
强调色留在背景与图标上（图标 3.25:1 ≥ 3:1）。主按钮白字仍是 4.84:1，没有被牵连。
`contrast.py` 从 11 组扩到 **16 组**，新增 5 组（亮/暗未选中图标、亮/暗当前项文字、暗当前项图标），
以及 `--accent-weak ↔ colorPrimaryBg` 的同步断言。

### 10.3 实际跑过的命令

| 命令 | 结果 |
| :--- | :--- |
| `cd web && pnpm run typecheck` | 退出码 0 |
| `cd web && pnpm run build` | 退出码 0；antd gzip **297.49 kB**（前 293.82，+3.67）、业务 chunk gzip 99.65 kB、CSS 7.19 kB |
| `make check-ui` | 退出码 0；存量 **126 → 125**（Shell 的 `ANTD_DIRECT` 归零） |
| `make check-i18n` | 退出码 0；zh/en 各 **1067 键**；新增中文字面量 0 |
| `python3 scripts/ui/contrast.py` | 退出码 0；**16 组**全过 |
| `make test` | 退出码 0；**209 tests / 0 failures / 0 errors / 0 skipped** |
| `grep -rn "@ant-design/icons" web/src` | 只命中 `web/src/ui/icons.tsx`（导入 + 注释字样，1 个文件） |

### 10.4 截图（`docs/reports/ui-standard-2026-10-21/`）

| 文件 | 说明 |
| :--- | :--- |
| `nav-before-light.png` / `nav-before-dark.png` | 改前：纯文字侧栏（亮/暗） |
| `nav-icons-light.png` / `nav-icons-dark.png` | 改后：教师 8 项带图标，当前项图标蓝色 |
| `ui-kit-icons-light.png` / `ui-kit-icons-dark.png` | `/ui-kit` 新增的图标清单区 |
| `nav-focus-dark.png` | 键盘 Tab 到「组件标准」，焦点环可见 |

真浏览器另外还核过：英文界面下最长标签 `Assignments` 宽 83px、行高 23px，238px 侧栏不换行不截断；
数了每个 `.nav-link` 正好 1 个 `.nav-ico`，`aria-hidden="true"` 生效，读屏树里只出现文字标签。

### 10.5 未验证 / 已知限制

1. **只在本机 Chromium 验过**，Safari/Firefox 未测（与第一轮同样的限制）。
2. **字形选择是主观的**：作业用书本、试卷用文档、批改用记号笔、复核用审核清单。想换字形只改
   `ui/icons.tsx` 的导出与 `Shell.tsx` 的映射，不动结构。
3. **`contrast.py` 断言的是令牌组合**，它证明「暗色当前项文字用正文色」这组值达标，不能证明
   CSS 规则本身没被改坏；后者靠代码 review 与 `nav-focus-dark.png` / `nav-icons-dark.png`。
4. 英文宽度是 DOM 量出来的，没有单独提交英文截图。

### 10.6 回滚

一条 `git revert <commit>`；零数据库、零迁移、零配置。只想撤对比度部分时：删掉那条暗色覆盖规则
与 5 组新检查、把 AC-27.6 的对应句去掉即可。

仍未做：截图基线（T308）、剩余 13 屏组件化（T309）、删兼容层（T310）。


