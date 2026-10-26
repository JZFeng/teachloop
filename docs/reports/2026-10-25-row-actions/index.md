# 行内动作收敛 +「汇总」入口归位 + 首页表格改造（T486–T495）验收报告 — 2026-10-25

**结论**：8 张有操作列的表收敛到「1 个主操作 + ⋯」或整列删除；批改详情不再折行
（1280 与 1024 宽实测 `scrollWidth == clientWidth`，行高 59–61px）；「成绩汇总」入口 **3 → 2**
（首页行删除、批改详情页改为「提交明细 ↔ 成绩汇总」视图切换、作业台账保留唯一按钮）；
首页去掉班级筛选大卡片，改工具栏「筛选 · N / 列 / 清空」，列序作业第一。
`check-ui` 新增第 8 条守卫：把本次改造前的代码喂给它，旧试卷页 4 个动作、旧批改详情 6 个动作都会被拦下，
改造后 0 命中。浏览器验收在 **5177（临时 dev，后端 8011）** 完成，**未改动 5174 的 QA 快照**。

## 1. 改了什么

| 层 | 文件 | 变更 |
| :--- | :--- | :--- |
| 组件 | `web/src/ui/RowActions.tsx`（新增） | 行内动作唯一实现：至多 1 个主按钮 + 「⋯」菜单；禁用原因**拼进菜单文案**（AC-25.5）；容器 `stopPropagation`，点动作不会同时触发行跳转 |
| 组件 | `web/src/ui/icons.tsx` | 登记 `MoreOutlined`（第 3 条守卫要求图标只从 ui 层出） |
| 样式 | `web/src/ui/ui.css` | `td > .row` 改成 `td .row`（**折行的真因**：批改详情为放拒收内联表单多包了一层 div，直接子选择器命中不到）；新增 `.tl-actions / .tl-row-link / .tl-link-button / .tl-filter-group / .tl-col-row` |
| 首页 | `web/src/teacher/HomePage.tsx` | 删班级筛选卡片、删操作列；列序改为 作业 / 班级 / 截止 / 提交 / 状态；作业名做成链接；工具栏「筛选 · N」「列」「清空」；筛选＝班级（多选，默认全部）+ 状态（未交齐/待批改/待公布/已公布，多选，默认全部），写 URL `classes` / `state`；列显隐与顺序存 `localStorage['teachloop.home.columns.v1']` |
| 批改总览 | `web/src/teacher/GradingOverviewPage.tsx` | 删「详情」按钮；只留「汇总」单按钮（132 → 92px）；作业名做成链接 |
| 批改详情 | `web/src/teacher/GradingDetailPage.tsx` | 4 个动作 → `判分 / 复核` + `⋯{让 AI 先批, 拒收, 审计}`；操作列 250 → 120px；头部删「汇总」，改标题下的视图切换 `Segmented` |
| 汇总页 | `web/src/teacher/ClassSummaryPage.tsx` | 与详情页同一组视图切换（选中「成绩汇总」），可一键回提交明细；路由与深链不变 |
| 试卷库 | `web/src/teacher/PapersPage.tsx` | 4 个动作 → `布置考试` + `⋯{复制, 归档}`（320 → 120px）；标题=编辑（链接）；归档态禁用并写原因 |
| 作业 | `web/src/teacher/AssignmentsPage.tsx` | 删操作列（「去批改」= 行点击）；标题做成链接 |
| 复核队列 | `web/src/teacher/ReviewPage.tsx` | 删操作列（「打开」= 行点击）；提交号做成链接样式的按钮（就地选中提交） |
| 词单 | `web/src/vocab/ListsPage.tsx` | 3 个动作 → `开始复习` + `⋯{编辑, 删除}` |
| 班级 | `web/src/teacher/ClassesPage.tsx` | 原生 `btn sm` 两个按钮 → `成员` + `⋯{轮换邀请码}`；`LEGACY_BTN_CLASS` 与 `INLINE_STYLE` 存量下降 |
| 守卫 | `scripts/check-ui.py` | 新增第 8 条 `ROW_ACTION_OVERFLOW`：`key: 'actions'` 列块内 `<Button>/<button>/<Link>` >3 即失败，并打印命中片段 |
| 基线 | `scripts/ui-baseline.txt` | 收紧：删掉 6 行已删除文件（`ImportPaperPage`）的条目，下调 `AssignmentsPage` / `ClassesPage` 3 处（未整表重排，避免 190 余行零值噪声） |
| 文案 | `web/src/locales/zh-CN.json`、`en.json` | 新增 `common.moreActions`、`teacher.home.{filter,filterTitle,filterClasses,filterStates,apply,reset,clear,columns,columnsTitle,columnsHint,moveUp,moveDown,stateFilter.*}`、`teacher.detail.viewSubmissions/viewSummary`、`teacher.papers.archivedHint`；删无引用的 `teacher.common.detail`、`teacher.home.classFilter*` |
| 规范 | `specs/002-v2-platform/spec.md` | AC-25.1 改写（行点击=详情、至多 1 个一级按钮、>3 由守卫拦）；新增 AC-25.5（禁用原因必须可见）、AC-25.6（汇总入口）；AC-19.3 修订（班级筛选进工具栏）、新增 AC-19.7（首页表格与筛选）、AC-19.8（列配置与无行内动作） |
| 任务 | `specs/002-v2-platform/tasks.md` | 新增阶段 33（T486–T495），含取号记录与「本次拍板不做」 |

**没有动的**：后端、数据库、迁移、路由表、`DataTable` 的排序/分页契约。

## 2. 实测（浏览器，教师 `teacher@teachloop.local`）

环境：临时 dev `127.0.0.1:5177`（`TEACHLOOP_API=http://127.0.0.1:8011`），5174 快照保持原样未重建。

| 页面 | 检查项 | 结果 |
| :--- | :--- | :--- |
| `/` | 班级筛选卡片已消失；工具栏只剩 筛选 / 列 | ✅（截图 `home-light.png`） |
| `/` | 列序：作业 / 班级 / 截止 / 提交 / 状态 | ✅ 表头实测 |
| `/` | 「筛选」→ 班级 chips + 状态 chips；选「待公布」→ 应用 | ✅ URL 变 `?state=to_publish`，0 行，空态文案「当前筛选下没有作业」，工具栏显示「筛选 · 1 / 列 / 清空」（截图 `home-filter.png`） |
| `/` | 「清空」 | ✅ URL 回到 `/`，行数复原 |
| `/` | 「列」弹层：取消勾选班级 + 状态上移 | ✅ 表头立即变「作业/截止/状态/提交」，`localStorage` 写入 `{"order":[…],"hidden":["classes"]}`；刷新后保持（已复位并清空） |
| `/` | 行点击进详情；作业名是链接 | ✅ 链接 `href=/grading/<id>`，行可点 |
| `/grading` | 操作列只剩「汇总」 | ✅ 单按钮；表宽 972 = 客户端宽，无横向滚动 |
| `/grading/:id` | 操作列 `判分 / 复核` + `⋯`；行高单行 | ✅ 行高 59px（1024 宽 61px），`scrollWidth=clientWidth`（截图 `grading-detail-menu.png`） |
| `/grading/:id` | `⋯` 菜单内容与禁用原因 | ✅ `让 AI 先批 · 已派发、批改中、已完成、未交卷或已拒收的提交不能这样派发`（禁用态，原因可见）/ `拒收` / `审计` |
| `/grading/:id` | `⋯ → 拒收` 展开内联表单 | ✅ 出现「拒收理由」输入框 + 确定/取消（在菜单关闭后展开，未受菜单生命周期影响） |
| `/grading/:id` | `⋯ → 审计` 打开时间线 | ✅ 抽屉显示「学生01 · 审计 · 共 3 条记录」倒序事件链 |
| `/grading/:id` | 头部动作群不再有「汇总」 | ✅ 只剩 重试失败 / 生成解析 / 进入复核 / 公布成绩 / 全部交给 AI |
| `/grading/:id` ↔ `/offerings/:id/summary` | 视图切换双向 | ✅ 详情「提交明细」选中 → 点「成绩汇总」落到 `/offerings/:id/summary` 且选中项为「成绩汇总」；反向回 `/grading/:id` |
| `/papers` | `布置考试` + `⋯{复制, 归档}`；标题=编辑 | ✅ 菜单两项；标题 `href=/papers/<id>`；无横向滚动 |
| `/assignments` | 操作列消失 | ✅ 表头只剩 标题/班级/截止/提交/状态；标题链接到 `/grading/<id>` |
| `/classes` | `成员` + `⋯{轮换邀请码}` | ✅ 菜单可展开 |
| `/vocab/lists` | `开始复习` + `⋯{编辑, 删除}` | ✅ 临时建一份词单验证：行内 2 个入口、单行；`⋯ → 删除 → 确定` 后回到 0 份（临时数据已删） |
| 暗色 | 首页 / 批改详情 | ✅ 随令牌变化，菜单可读（`home-dark.png`、`grading-detail-dark.png`） |
| 1024 宽 | 批改详情 | ✅ 无横向滚动（716 = 716），操作列 136px（`grading-detail-1024.png`） |

## 3. 截图

`home-light.png`、`home-filter.png`、`home-dark.png`、`grading-detail-menu.png`、
`grading-detail-dark.png`、`grading-detail-1024.png`（同目录）。

## 4. 未验证 / 已知缺口

- **复核队列（`/review`）队列为空**：删操作列后的行内样子没有在有数据的表上看到（代码层只删列 + 提交号改链接）。
- **试卷「归档」禁用原因**：QA 数据里没有 `ARCHIVED` 试卷，禁用态与原因文案未触发验证（AI 批改项的禁用原因已验证同一机制）。
- **菜单里「让 AI 先批」的可执行路径**未点（避免白跑 AI 配额），只验证了禁用态与原因文案。
- **窄屏只到 1024**：768 / 手机未验。
- **英文界面**未逐页看（键已双向对齐，`check-i18n` 通过）。
- **5174 的 QA 快照未更新**：需要 `./scripts/env.sh deploy qa` 或重建 dist 后重启才能看到本次改动。

## 5. 守卫与测试

| 检查 | 结果 |
| :--- | :--- |
| `python3 scripts/check-ui.py` | 通过，0 新增违规（存量 86 处） |
| 第 8 条守卫自检 | 把改造前的 `PapersPage`（4 动作）、`GradingDetailPage`（6 动作）喂给规则 → 均命中；改造后两者 0 命中 |
| `python3 scripts/check-i18n.py` | 通过：zh/en 各 1711 键且完全对齐；新增中文字面量 0 处 |
| `cd web && pnpm typecheck` | 通过 |
| `cd web && pnpm test` | 108 / 108 通过 |
| `cd web && pnpm build` | 通过 |
| `make test`（全量） | **红**：`teachloop-app` 3 个失败，见下 |

### `make test` 的 3 个失败与本次改动无关（证据如下）

本次 diff 里 **0 个 `.java` / `.sql` 文件**（`git status --porcelain | grep -c '\.java\|\.sql'` = 0），
失败的是既有后端测试，在 HEAD 上就会红：

| 测试 | 失败断言 | 性质 |
| :--- | :--- | :--- |
| `VocabWordListTest.queryByListAndSingleProgress:331` | `due_at` 毫秒值差 1（两次复跑都是 +1ms） | 系统性 1ms 偏差，测试与实现的精度口径不一致 |
| `AiSetupTest.setupAdminAndAiSettings:98` | `expected: "local" but was: null` | AI 设置未落库/未返回 |
| `AiReadinessTest.readinessGatesManualAndAutomaticDispatch:157` | `{"code":"INTERNAL","message":"服务内部错误"}` | 同一接口 500 |

对照：2026-10-25 T466 的执行记录写的是 `make test` **EXIT=0**（Maven 513 测试），
此后仓库上又进了 8 个提交（UI 色/守卫、状态口径、student 分数门控、CSRF、/setup 跳转等），
最可能是这些提交之后没人再跑过全量。**本次不修**：不属于本任务范围，单独立项排查更清楚。


## 6. 验收过程中发现并修掉的两个问题

1. **`⋯` 的 `title` 提示浮层盖住刚展开的菜单**（截图里能直接看到）：去掉 `title`，只留 `aria-label`（读屏仍可用）。
2. **单班行重复显示「1 个班」**：班级名已经写在上面一行，改成 `classCount > 1` 时才显示。

## 7. 与计划的偏差（都已落盘）

| 计划 | 实际 | 原因 |
| :--- | :--- | :--- |
| check-ui「第 7 条」 | 实际第 **8** 条 | 第 7 条已被 `MUTED_FAILURE`（决策 026 / AC-70.2）占用；spec/tasks 已改口 |
| 「基线重写」 | 改成「基线收紧」 | `--write-baseline` 会补进 190 余行零值（规则集合变了），噪声远大于收益；改为删 6 行失效条目 + 下调 3 处 |
| AC-25.6 原文「列表行不再开汇总入口」 | 改成「批改总览保留唯一一个」 | 原文与计划矛盾：作业台账是该页的终态动作，删掉后「可发布」的行没有出口 |
| 计划 T486–T494 | 加 T495（首页表格改造） | 用户追加的三条要求（删筛选卡片、照词库加筛选与列、作业列第一）单独成任务，验收更清楚 |
