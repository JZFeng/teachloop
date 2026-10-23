# 任务清单 — 品牌视觉（006）

- 上游：`spec.md`（已实现，待验收）、`docs/research/2026-10-22-brand-visual.md`
- 完成定义：代码 + 守卫通过 + 截图证据 + 文档一致。缺一不算完成。
- 编号从 T342 起（002 已用到 T341）。
- 本轮是用户明确说「直接开整、改所有页面」后执行的；任务按实际落地顺序回填。

| ID | 任务 | 依赖 | 验收 | 状态 |
| :--- | :--- | :--- | :--- | :--- |
| T341 | 新版 logo 资产：源图存档、裁切脚本、mark/字标/整标/favicon、四底色去白边 | — | `scripts/brand/build-assets.py` 退出码 0；9 个资产 + manifest；`logo-surfaces.png` 无白边 | 完成 |
| T342 | 令牌层：语义色 + BRAND / FEATURE / STATUS / LOGO 常量与同名 CSS 变量 | T341 | `tokens.ts` / `tokens.css` 逐键一致；四个功能位与判分三态分命名空间 | 完成 |
| T343 | 对比度守卫 16 → 40 组；暗色背景改读令牌 | T342 | `contrast.py --verbose` 40/40；不再写死 `#141414` | 完成 |
| T344 | 全站文字蓝改 `--accent-text`；`--accent` 只留按钮底/焦点环/边框 | T342 | 暗色链接 7.96:1；`check-ui` 0 新增 | 完成 |
| T345 | `ui/Brand` 组件（mark / text / lockup，四档尺寸，暗色文本字标） | T342 | 组件从 `../ui` 可用；暗色登录可读 | 完成 |
| T346 | 壳层：侧栏品牌区 + 导航功能色胶囊（作业/试卷蓝、批改/复核青、报表/反馈玫红） | T345 | 首页/试卷/复核截图；当前项用 accent-text | 完成 |
| T347 | 登录页按样张重排：整标 + brandLine + 中文 tagline + 三个卖点 + 四个功能位 | T345 | `/login` 亮暗截图；Practice 带「规划中」脚注 | 完成 |
| T348 | 命名与入口：`app.name = TeachLoop`、tagline/brandLine、favicon、apple-touch-icon、标题 | T347 | `check-i18n` zh/en 对齐；浏览器标签图标生效 | 完成 |
| T349 | 全站页头 eyebrow：路由前缀 → 文案 + 色调；页标题 20 → 24px | T346 | 20+ 路由截图；无路由漏映射 | 完成 |
| T350 | `/ui-kit` 品牌色板：四个功能位、判分三态、原始色、实测对比度 | T343 | `/ui-kit` 亮暗截图；无新增字面量 | 完成 |
| T351 | 学生与深层页截图：学生首页/班级/反馈/作业/作答须知、批改详情、班级汇总、试卷向导、导入 | T346 | 30 张真页面截图；脚本 `scripts/brand/shoot-app.mjs` 可复跑 | 完成 |
| T352 | 文档与决策：spec / decision 006、宪法第十五条回改、003 标注、PROGRESS、本文件 | T341–T351 | 四份计数自检通过；003 顶部标注被 006 取代 | 完成 |
| T353 | 全量回归：`make test` + typecheck/build/check-ui/check-i18n/contrast | T341–T352 | 退出码 0；surefire 255/0/0/0 + 前端 14/14 | 完成 |

## 执行记录

| 日期 | 任务 | 结果 | 证据 |
| :--- | :--- | :--- | :--- |
| 2026-10-22 | T341 | 完成 | 新源图 `93f02fd1…`；9 个资产；`logo-surfaces.png` 四底色无白边 |
| 2026-10-22 | T342 | 完成 | `contrast.py` 断言两边一致；四功能位 = 4 × 2 主题 |
| 2026-10-22 | T343 | 完成 | `[contrast] 通过：40 组对比度全部达标` |
| 2026-10-22 | T344 | 完成 | `check-ui` 0 新增；暗色链接 7.96:1 |
| 2026-10-22 | T345 | 完成 | `ui/Brand.tsx`；登录页暗色用 mark + 文本字标 |
| 2026-10-22 | T346 | 完成 | `home-{light,dark}.png`、`papers-light.png` |
| 2026-10-22 | T347 | 完成 | `login-{light,dark}.png`；四个功能位一屏放下 |
| 2026-10-22 | T348 | 完成 | `check-i18n` 1197 键对齐；`web/index.html` favicon |
| 2026-10-22 | T349 | 完成 | 15 条路由规则；20+ 页头截图 |
| 2026-10-22 | T350 | 完成 | `/ui-kit` 品牌色板与 40 组里的关键比值 |
| 2026-10-22 | T351 | 完成 | `docs/reports/brand-app-2026-10-22/` 30 张 |
| 2026-10-22 | T352 | 完成 | 本文件、spec、decision 006、宪法第十五条品牌层、003 标注、PROGRESS |
| 2026-10-22 | T353 | 完成 | `make test` 退出码 0（surefire 255/0/0/0）+ `pnpm test` 14/14 + typecheck/build 0 + contrast 40/40 + check-ui 0 新增 + check-i18n 1197 键 |
