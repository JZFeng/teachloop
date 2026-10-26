# 作业列表改成「我的词库」那套 table view（搜索 / 筛选 / 列配置，2026-10-26）

## 用户要求

> 「2 就是也参照我的词库 那个 table view，把它变成可以 filter 和 column 的那种。另外，能做 search 吗？」

三件事，全部做在 `/assignments`：

| 能力 | 实现 | 状态 |
| --- | --- | --- |
| 搜索 | 常驻搜索框，按**标题**即时过滤（大小写不敏感），写 URL `q` | 完成，浏览器实测 |
| 筛选 | 「筛选」弹层：班级（带人数）、状态（已发布 / 草稿 / 已关闭），写 URL `classes` / `status` | 完成，浏览器实测 |
| 列配置 | 「列」弹层：勾选显隐 + 上移下移，存 `localStorage['teachloop.assignments.columns.v1']` | 完成，浏览器实测（含刷新后保留） |

排序与分页仍在 URL（`sort` / `dir` / `page` / `size`），换筛选不丢排序与分页（AC-19.4）。

## 关键实现决定

1. **不另造组件**。另一个会话同时在同一个工作区里把「筛选 / 列 / 清空」抽成了 `web/src/ui/TableToolbar.tsx`
   （`TableToolbar` / `ColumnPicker` / `useColumnPrefs`，注释写明「三处共用：词库、首页最近作业、批改详情提交明细」）。
   我先按旧写法建了一个 `ui/tablePrefs.ts`，发现这份共享件后**删掉自己的**，改用共享件——否则仓库里会有第三份手写弹层。
   我这一版只加了共享件的两处东西：`ui/ui.css` 里 `.tl-table-toolbar .tl-table-search`（搜索框占位宽度），
   以及 `teacher.assignments.*` 的 4 个文案键（`searchPlaceholder` / `filterClasses` / `filterStates` / `noMatch`）。
2. **搜索用「本地态 + URL」双轨**。输入立即过滤（不等一次 URL 往返），`useEffect` 在 URL 变化（后退/前进）时同步回来；
   清空（`allowClear` 或全选删除）当场生效。第一版只用 URL 做唯一真源，实测清空时 URL 已写入但输入框不回落，改成本地态后正常。
3. **删掉页头那行提示**「默认按截止时间倒序；表头可排序，排序与分页写入 URL。」——
   工具栏已经把这些控制摆出来了，按 AC-71.2「自解释文案一律删除」，同时删掉 `teacher.assignments.orderHint` 键。
   行为没变；要恢复只需在页头加回一行。
4. 上一轮的「提交 = 已交/应交（按学生去重）」保持不变，列配置里它是 `submitted` 一列。

## 验证

| 档位 | 做什么 | 结果 |
| --- | --- | --- |
| 静态 | `pnpm -C web typecheck`（全量） | 退出码 0；`pnpm -C web test` 117/117 |
| 静态 | `python3 scripts/check-ui.py` | 通过，无新增违规 |
| 静态 | zh/en 键对齐（脚本比对 + 人工核对 `teacher.assignments.*`） | check-i18n 通过，zh/en 各 1702 键且完全对齐 |
| 接口 | `GET /api/offerings` 的 `submissionCount` / `expectedCount` | 见上一份报告；本轮未改动 |
| 界面 | 5174 打开 `/assignments` | 工具栏出现：搜索框 + 「筛选」+「列」；表格仍是 5 列，提交列 `1/30` |
| 界面 | 搜索框输入 `unit` | 行数 4 → 1（Unit4写作），URL 变 `/assignments?q=unit&page=1` |
| 界面 | 全选删除清空搜索 | 行数 1 → 4，URL 变 `/assignments?page=1` |
| 界面 | 「筛选」弹层内容 | 班级：三年2班 (3)、演示班 (30)；状态：已发布、草稿、已关闭 |
| 界面 | 选「三年2班」→ 应用 | 行数 4 → 1，URL `/assignments?classes=3590e50c…&page=1`，按钮显示「筛选 · 1」 |
| 界面 | 「清空」 | URL 回 `/assignments?page=1`，行数 4 |
| 界面 | 「列」→ 取消「班级」 | 表头 5 → 4（标题/截止/提交/状态），`localStorage` 写入 `{"hidden":["classes"]}` |
| 界面 | 刷新 | 表头仍是 4 列，隐藏列持久 |
| 界面 | 直接打开 `/assignments?classes=…&status=PUBLISHED`（深链） | 1 行（Unit4写作），按钮显示「筛选 · 2」——URL 是唯一真源，分享链接可用 |

截图：`docs/reports/2026-10-26-assignments-table-view/toolbar-filtered-1280.png`（筛选后 1 行 + `1/30`）。

## 未验证

- 英文界面下的这一屏（文案是整数键对齐，但没截图）。
- 暗色主题与 1024 / 768 宽度（工具栏会换行，搜索框 `flex: 1 1 260px`，理论上不挤压表格）。
- 状态筛选（草稿 / 已关闭）在真实数据上的表现：QA 库里 4 条作业全是「已发布」，只用 URL 参数验证了多值读取，没有真实草稿可点。
- 搜索 + 筛选叠加的边界（两者都命中/都不命中）：逻辑是「与」，代码直读；没有单独造数据实测。

## 并发施工说明（重要）

我这一轮开始时，**另一个会话正在同一个工作区里同时改前端**（它先后提交了 `4395932`、`ce8df4a`、`ec01555`、`e9f09f8`、
`31d732f`、`7a59b43`、`cc48142`，内容涵盖学生作业页重排、批改详情页重做、角色导航矩阵、共享表格工具栏）。
因此：

- 共享文件（`ui/index.ts`、`ui/ui.css`、`locales/*.json`、`teacher/HomePage.tsx`、`teacher/GradingDetailPage.tsx`）
  里的改动是两边混在一起的，最后被**对方的提交**带进了历史；我这一轮单独提交的只有 `teacher/AssignmentsPage.tsx`
  与这份报告。功能与提交的对不上不是丢失，`git log -p web/src/ui/TableToolbar.tsx` 能看到来源。
- 浏览器验收用的是同一个 agent 浏览器，测到一半会被对方切页/导航打断，上面的结论是分多次、每次只做一两步拿到的，
  每一步都给出了 URL 或 DOM 证据。
