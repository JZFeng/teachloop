# 任务清单 — UI 标准（003）

- 上游：`spec.md`（已实现）、`plan.md`
- 完成定义：代码 + 检查脚本通过 + 本文件的验收逐条成立。缺一不算完成。
- 编号从 T301 起（002 用到 T253）。

| ID | 任务 | 依赖 | 验收 | 状态 |
| :--- | :--- | :--- | :--- | :--- |
| T301 | 调研 9–12 个设计系统并落盘 | — | `docs/research/2026-10-21-design-system.md` 存在，含令牌架构/组件边界/中文排版/暗色/表格/迁移路径六维对比与「对本项目的结论」 | 完成 |
| T302 | 宪法加「第十五条 UI 与可访问性」 | T301 | 8 条约束每条都能指到 `check-ui`/`contrast.py`/AC 之一 | 完成 |
| T303 | 令牌层：`ui/tokens.ts` + `styles/tokens.css`（亮/暗） | T302 | 两处值一致；`python3 scripts/ui/contrast.py` 11 组全过 | 完成 |
| T304 | 接 antd 6 + ThemeProvider（算法/令牌/语言包/cssVar） | T303 | `pnpm build` 通过；`html[data-theme]` 随开关变化 | 完成 |
| T305 | 组件层 `web/src/ui/`（11 个文件 + 唯一出口） | T304 | 页面能从 `../ui` 引到 Button/Field/Table/Tag/States | 完成 |
| T306 | `/ui-kit` 展示页 | T305 | 组件 × 状态矩阵可点开；无中文字面量（`check-i18n` 0 新增） | 完成 |
| T307 | 5 屏迁移（登录/首页/试卷列表/新建作业/复核） | T305 | `npx tsc --noEmit` 0；5 屏不再出现手写 `btn` 主按钮（各自基线为 0 新增） | 完成 |
| T308 | 截图基线：21 屏 × 亮/暗 | T307 | `docs/reports/ui-baseline/` 下有 42 张 PNG 与 `index.md` | **未完成**（未装 Playwright、夜间不起新服务） |
| T309 | 剩余 13 屏组件化 | T307 | 每屏的 `LEGACY_BTN_CLASS` 与 `INLINE_STYLE` 基线降到 0 | 未开始 |
| T310 | 删除 `components/States.tsx` 兼容层与 `components/DataTable.tsx` | T309 | 全仓无 `from '../components/States'` / `'../components/DataTable'` 引用 | 未开始 |
| T311 | `check-ui` 守卫 + Makefile 接入 + 反证 | T305 | `make check-ui` 通过；插入 `#ff0000` 时退出码 1（已实测） | 完成 |
| T312 | 对比度守卫 `scripts/ui/contrast.py` | T303 | 改任一处令牌不改另一处时该脚本失败 | 完成 |
| T313 | 文档回写：spec/plan/tasks/交付状态/AGENTS 路径 | T307 | 四份文档计数自检通过；`AGENTS.md` 的调研路径与仓库一致 | 完成 |
| T314 | 合入 main（rebase 到 vocab 提交之后） | T313 | `git log` 线性；`tsc`/`build`/`check-ui`/`check-i18n` 全绿后再 ff 合并 | 完成 |
| T315 | 修 3 处观感：复核「打开」按钮、登录页单面板布局、资料页主题开关 | T314 | 三处截图可对照，无新守卫违规 | 完成 |
| T316 | 真改批改总览：4 张可点统计卡 + Segmented 过滤 + 进度条列 | T314 | 截图 `grading-overview.png`；行内 4 个状态标签收敛为「进度条 + 状态标签」 | 完成 |
| T317 | 真改批改详情：统计卡替代 7 个标签、页头主次操作、Alert 回执、拒收表单用 Field、拒收理由并入状态 tooltip | T314 | 截图 `grading-detail.png`；表格一屏放下不再折行 | 完成 |
| T318 | 资料页改用组件层 | T315 | 截图 `profile.png`；必填红框与字段错误由 Field 统一 | 完成 |
| T319 | 侧栏导航图标（含 `/ui-kit` 图标清单、暗色当前项文字对比修正） | T305 | 截图 `nav-icons-{light,dark}.png`、`ui-kit-icons-{light,dark}.png`、`nav-focus-dark.png`；`check-ui` 125 处；`check-i18n` 1067 键；`contrast.py` 16 组；`tsc`/`build` 0；`make test` 209/0/0/0 | 完成 |

## 执行记录

| 日期 | 任务 | 结果 | 证据 |
| :--- | :--- | :--- | :--- |
| 2026-10-21 | T301 | 完成 | 子代理调研，28.6 KB，`docs/research/2026-10-21-design-system.md` |
| 2026-10-21 | T302 | 完成 | 宪法第 160 行起新增第十五条 |
| 2026-10-21 | T303 | 完成 | `contrast.py` 输出「11 组对比度全部达标，两套令牌与旧变量层一致」 |
| 2026-10-21 | T304 | 完成 | `pnpm build` 0；antd 6.6.7；chunk 拆分后 antd gzip 293.82 kB |
| 2026-10-21 | T305 | 完成 | `web/src/ui/` 14 个文件，`npx tsc --noEmit` 0 |
| 2026-10-21 | T306 | 完成 | `/ui-kit` 路由可达；`check-i18n` 前端新增中文字面量 0 |
| 2026-10-21 | T307 | 完成 | 5 屏改用组件层；`tsc` 0；`check-ui` 基线未上升 |
| 2026-10-21 | T311 | 完成 | 基线 145 处；插入 `#ff0000` 后退出码 1，撤回后 0 |
| 2026-10-21 | T312 | 完成 | 见 T303 |
| 2026-10-21 | T313 | 完成 | 计数自检见交付报告 |
| 2026-10-21 | T318 | 完成 | `profile.png`；Field 统一必填红框 |
| 2026-10-21 | T319 | 完成 | 侧栏 15 个图标经 `ui/icons.tsx` 唯一出口；`check-ui` 125、`check-i18n` 1067、`contrast.py` 16 组；截图见交付报告第 10 节 |

## 已知缺口

- **T308 未完成**：没有截图基线，就没有「改造前后」的像素对比。任何「视觉已达标」的说法都只能靠
  人工看，不能靠脚本。报告里已明确写「视觉未验证」。
- **T309/T310 未开始**：13 屏仍是手写类名，靠令牌层跟随新视觉；兼容转发层因此必须保留。
- **`make test` 全量已补跑（2026-10-21 第三轮）**：209 tests / 0 failures / 0 errors / 0 skipped，退出码 0。
  此前「未跑」的缺口已关闭；后续零后端改动仍需重跑一次，别把这次的绿当永久。
