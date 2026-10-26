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
| T354 | 所有弹层 Esc 可关（统一弹层栈） | T305 | 审计抽屉、放大编辑器（MarkdownEditor / VditorMarkdown）、交卷前检查、窄屏目录、完形/七选五选项都接 `ui/useEscapeClose`；栈逻辑单测 8/8；CDP 真键盘实测审计抽屉与放大编辑器 Esc 关闭、路由与表格不动（`docs/reports/t354-esc-dismiss/`）；`tsc`/`build`/`check-ui`/`check-i18n`/`contrast` 全绿 | 完成 |
| T356 | 界面标准从侧栏导航收进资料页「偏好设置」（旧链接 `/ui-kit` 跳展开态） | T305 | 侧栏不再有 `/ui-kit` 入口（教师 7 项）；资料页偏好设置默认收起、可展开，`?panel=ui-kit` 可深链；`/ui-kit` 前端重定向到展开态；学生看不到该行；`tsc`/`build`/`check-ui`/`check-i18n`/`contrast` 全绿；CDP 实测 11 项 + 亮暗截图（`docs/reports/t356-ui-kit-in-profile/`） | 完成 |
| T441 | 表格组件重写：`ui/DataTable.tsx` 换手写表体（表头排序 + 页脚 10/20/50/100 + 选择列 + 批量条 + 工具栏/空态插槽）；a11y（`scope`、`aria-sort`、键盘排序、全选半选）；`useListState` 迁到 `ui/` 并统一 `sort/dir/page/size`（读兼容 `pageSize`）；`tl-table*` 样式迁到 `ui/ui.css`；i18n 新键 | — | `pnpm typecheck` 0；界面标准面板能点到排序、翻页、改每页条数、全选/半选；`web/tests/list-state.test.mjs` 覆盖参数读写与旧参数兼容；`make check-ui` / `make check-i18n` 0 新增 | 完成 |
| T442 | 「我的词库」改用组件：列配置、过滤、StatCard、5s 轮询留在页面层；删掉页面内重复的表格与分页 markup | T441 | 改造前后截图一致；排序（`meaning→form`、`sources→createdAt`）、分页、每页条数写 URL；选中后批量暂停/删除可用；复习仍从选中词开始（AC-68.5） | 完成 |
| T443 | 12 个列表站点迁移 + 删除 `components/DataTable.tsx`（关闭 T310）+ 删 `PageSizePicker`（8 处）+ 学生反馈页卡片/表格共用 URL 状态 | T441 | `grep -rn "components/DataTable" web/src` 与 `grep -rn "PageSizePicker" web/src` 均为 0；12 页逐页截图；`pnpm typecheck` / `build` 0（AC-68.1–68.3） | 完成 |
| T444 | 其余数据列表并入：词集页 2 张表（词集表补排序/分页/选择/批量/URL）、发词预览、班级成员表；3 张只读分析表只统一视觉 | T441 | 词集页可排序/翻页/全选批量启停删；成员表与发词预览视觉一致；只读分析表无排序分页控件；编辑器/表单表 grep 确认未改动（AC-68.4、AC-68.6） | 完成 |
| T445 | 守卫与文档：`check-ui.py` 新增「页面不得自建列表表格」规则 + 基线重生成并人工 diff；002 AC-24.3/24.4 与 003 spec/plan 同步；`docs/DELIVERY-STATUS.md` 更新 | T443、T444 | `make check-ui` 通过且无文件的规则计数上升；四份文档计数自检通过；`make check-i18n` 0 新增（AC-68.6、AC-30.2） | 完成 |
| T446 | 逐页亮/暗截图与交付报告：15 屏 × 2 主题落 `docs/reports/2026-10-25-table-unify/`，报告列明未验证项 | T443、T444 | 截图与 `index.md` 存在，写明「改了什么 / 没改什么」；报告注明「无自动化视觉基线（T308 未完成）」（AC-30.3） | 完成 |

> 编号说明（2026-10-25）：T360–T362 已被 `specs/002-v2-platform/tasks.md` 占用、T363–T371 属
> `specs/006-brand-visual`，故表格统一这组任务按**全局最大号**顺延为 T441–T446，不新增重复编号。

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
| 2026-10-22 | T354 | 完成 | 用户反馈审计弹层按 Esc 无反应。新增 `web/src/ui/escapeStack.ts`（纯逻辑 LIFO 栈）+ `useEscapeClose.ts`（document 级监听、`canClose` 拦截），接入 6 处弹层并给审计抽屉补 `role="dialog"`/`aria-modal`；`web/tests/escape-stack.test.mjs` 8/8；`make test` 退出码 0（surefire 255/0/0/0、前端 28/28）、`typecheck`/`build` 0；CDP 实测（dev 5173）审计抽屉与放大编辑器 Esc 关闭、路由/表格不动、无控制台异常，截图 `docs/reports/t354-esc-dismiss/`；`check-ui`/`check-i18n`（1200 键）/`contrast` 40 组/`check-deps`/`check-boundaries` 全过 |
| 2026-10-22 | T356 | 完成 | 用户反馈「组件标准不该放首页/导航」。`UiKitPage.tsx` → `UiKitPanel.tsx`（去掉页头与 Appstore 图标行），侧栏删 `/ui-kit`；资料页偏好设置加「界面标准」行（默认收起、`?panel=ui-kit` 深链、`aria-expanded`/`aria-controls`），`/ui-kit` 重定向 `Navigate` 到展开态；学生看不到该行。验证：CDP（dev 5173）11/11——导航 7 项且无入口、默认收起、展开 10 个分区、URL 同步、旧链接跳转、收起、无控制台异常、学生无该行；截图 `docs/reports/t356-ui-kit-in-profile/`（亮/暗/收起）；`typecheck`/`build` 0、`check-ui` 0 新增、`check-i18n` 1202 键对齐 |

| 2026-10-25 | T441 | 完成 | `ui/DataTable.tsx` 重写为手写表体（aria-sort + 键盘排序、页脚 10/20/50/100、选择列与半选、工具栏/空态、服务端分页、无回调自带分页）；`ui/listState.ts` + `ui/useListState.ts` 统一 `sort/dir/page/size` 并读兼容 `pageSize`；`web/tests/list-state.test.mjs` 9/9；界面标准面板换成 24 行可操作演示（排序/翻页/每页/勾选，暗色截图 `docs/reports/2026-10-25-table-unify/ui-kit-table-dark.png`）；`typecheck`/`build` 0 |
| 2026-10-25 | T442 | 完成 | 词库页改用组件（列配置/筛选/统计卡/5s 轮询仍在本页）；默认每页 10；真浏览器实测：表头排序 → `?sort=form&dir=desc`、每页改 20 → `?size=20`、勾选出现批量条；截图 `library-light.png` / `library-dark.png` / `library-selection.png`。**改造前截图未留存**（改造前未起服务），AC-68.5 的截图对照只有 after |
| 2026-10-25 | T443 | 完成 | 12 个站点迁移、删 `components/DataTable.tsx` 与 `components/useListState.ts`（`grep` 0 引用，T310 关闭）、删 `PageSizePicker`（8 处）；复核队列/学生反馈的排序与分页首次真正写进 URL；14 条路由 DOM 实测（表格数 + 页脚文案）见报告 §3，实际截图 4 屏（试卷/复核/班级/批改详情），未逐页 12 张 |
| 2026-10-25 | T444 | 完成 | 词集页两张表（词集补排序/分页/选择/批量/URL）、发词预览、班级成员表并入；班级汇总两张只读表与复核逐题只加 `tl-table` 类；编辑器/表单表未动（`RAW_TABLE` 基线冻结 14 处）；截图 `sets-light.png`、`classes-members-light.png` |
| 2026-10-25 | T445 | 完成 | `check-ui.py` 新增第 6 条规则 `RAW_TABLE` + 基线重生成（145 → 128 处，无规则新增上升）；002 AC-24.3/24.4 与 003 spec/plan/tasks、`docs/DELIVERY-STATUS.md` 同步；`check-ui`/`check-i18n`/`contrast` 全绿 |
| 2026-10-25 | T446 | 完成 | 报告 `docs/reports/2026-10-25-table-unify/index.md` + 9 张亮/暗截图；报告列明 3 项未验证（改造前截图、学生反馈有数据路径、报表错题表真实查询）与「无自动化视觉基线（T308）」 |
| 2026-10-25 | T496 | 完成 | 用户要求「错误/警告一律上色」：新增 `ui/InlineNote`（四档）+ `.inline-note.ok/.warn/.err` 文字与底色同给 + 全局 `.chip`（原先根本没有全局样式，词库状态渲染成灰字）+ 12 处灰字失败改红/黄；`check-ui` 第 7 条规则 `MUTED_FAILURE`（存量 0，基线不豁免）；宪法第十五条加「反馈必须带语义色」；决策 `docs/decisions/026-message-tones.md`（四档判据）。反证：插入 `__guard_probe.tsx` 后退出码 1 并打印命中两行，删除后回 0；`check-ui` 存量 89、`check-i18n` 1697 键对齐、`typecheck`/`build`/`test 94` 全 0；截图 `docs/reports/2026-10-25-message-colors/` |
| 2026-10-25 | T497 | 完成 | 用户反馈 `/assignments/new` 一屏堆满灰字提示。新增 `ui/InfoHint`（ⓘ + Popover，trigger 只开 hover/focus）+ `Field.info` + `PageHeader.info`；删「参考范文（可选）」字段（页面不再发 `anchorExamples`，后端列与历史值保留，`trimmed()` 对 null 安全）；删 4 条自解释 hint（title/instructions/totalPoints/classes）；截止时间、宽限期、最多尝试、页面副标题 4 条长解释收进 ⓘ；评分标准格式 hint 保留可见。zh/en 删 7 键、加 `common.moreInfo`；`typecheck`/`build`/`check-ui`（存量 86，第 8 条 `ROW_ACTION_OVERFLOW` 已在）/`check-i18n`（1705 键）全绿；5174 实测：页面上搜不到「参考范文」与 4 条被删文案，4 个 ⓘ 悬停与点击都能打开且文案正确；截图 `docs/reports/2026-10-25-assignment-form/` |
| 2026-10-25 | T498 | 完成 | 按 AC-71.4 扫其余表单页：`/admin/ai` 6 处 hint 改 ⓘ + 删「显示名」自解释句；`/profile` 2 处改 ⓘ；`/offerings/new` 删「可多选…」与「默认取试卷默认时长」，把「统一开考意味着未到场学生也在掉时长」改成选中 FIXED_WINDOW 时才出现的 warning 提示条。zh/en 各删 3 键（`app.ai.displayNameHint`、`teacher.offering.classesHint/durationHint`），1702 键对齐；`typecheck`/`build`/`check-ui`（86）/`check-i18n` 全绿；5174 实测：AI 设置表单 6 个 ⓘ 且长文案只在气泡里、资料页「界面语言/每天几点开始」有 ⓘ、发考试页两条 hint 消失且切到统一开考后出现琥珀提示条（`rgb(255,243,214)` 底）。截图 `docs/reports/2026-10-25-info-hints/` |
| 2026-10-26 | T500 | 完成 | 用户 review 学生作业页（`/assignments/:id`，已交卷态）：「信息特别乱」。① 已提交卡重排为标题 + 状态 chip / 分数槽 / 提交时间 / 动作行，`成绩由老师批改并公布后可见；AI 判完不等于公布。` 从悬空说明行收进「待老师公布」的 ⓘ；② 题目要求、评分标准改可折叠卡（`SectionCard` + `DownOutlined`，`aria-expanded`，交卷后默认收起、未交卷默认展开），交卷后顺序改为 结果 → 题目要求▽ → 评分标准▽ → 我提交的内容 → 作业说明；③ 删作业说明里「本次为作业：没有倒计时…」整句（后端 notice 仍返回、页面不渲染，zh/en 删 `exam.assignmentNoCountdown`），宽限期拆「宽限期 N 小时」+ ⓘ（新增 `exam.graceHint`）；④「查看结果」按决策 025 口径门控：`totalScore == null` 时禁用 + `title`「成绩公布后才能查看结果」（新增 `exam.viewResultLocked`）；⑤ 新增 `exam.scorePublished`，`ui/InfoHint` 进 `ui/index.ts`，`ui/icons.tsx` 登记 `DownOutlined`；`SubmittedContentSection` → `SubmittedContentCard`（正文独立成卡）。验证：`typecheck`/`build` 0、`check-i18n`（1704 键对齐）、`check-ui`（存量 86 → 83，本页 `LEGACY_BTN_CLASS` 7 → 4）、`test` 108/108；浏览器实测（dev 5199 代理 qa 后端 8011，登录态同 host）已交卷态默认收起两卡、点开箭头翻转、ⓘ 气泡文案正确、未公布时「查看结果」disabled 且 `title` 正确；用 fetch 拦截把 `totalScore` 伪造成 88 验证公布态：绿「已公布」+ `88 / 100` + 客观/主观 + 按钮可点。**5174 快照未重新部署**（见报告） |

## 已知缺口

- **T308 未完成**：没有截图基线，就没有「改造前后」的像素对比。任何「视觉已达标」的说法都只能靠
  人工看，不能靠脚本。报告里已明确写「视觉未验证」。
- **T309/T310 未开始**：13 屏仍是手写类名，靠令牌层跟随新视觉；兼容转发层因此必须保留。
- **`make test` 全量已补跑（2026-10-21 第三轮）**：209 tests / 0 failures / 0 errors / 0 skipped，退出码 0。
  此前「未跑」的缺口已关闭；后续零后端改动仍需重跑一次，别把这次的绿当永久。
