# 教师首页「最近作业」对齐「我的词库」table view：常驻搜索 + 清空筛选（2026-10-26）

## 用户要求

> 「teacher 首页的 table view，能不能加工 search box 和 清空筛选 类似 我的词库 http://localhost:5174/vocab/library。保持一致性。」

改前首页工具栏只有「筛选」「列」，`清空` 按钮还带条件（`count > 0` 才出现）；词库那页是
「常驻搜索框 + 筛选 · N + 列 + 清空筛选」。这一轮把首页补成同一套摆法，并让「清空筛选」一次清干净。

## 改了什么

| 文件 | 改动 |
| --- | --- |
| `web/src/teacher/HomePage.tsx` | 工具栏最前加常驻搜索框（`ui/Search`，`className="tl-table-search"`）；搜索按**作业标题**即时过滤并写 URL `q`（本地态 + URL 双轨）；「清空筛选」走新 `onReset`，关键字与 `classes`/`state` 一起清；空态文案按原因分档（搜不到 → `teacher.home.noMatch`，筛空 → `teacher.home.filteredEmpty`） |
| `web/src/ui/TableToolbar.tsx` | 新增可选 `onReset?: () => void`：传了就常驻显示「清空筛选」并调用它（页面负责清到底），不传时沿用旧行为（只清筛选组）。按钮本身从 `count > 0 &&` 改成常驻 |
| `web/src/teacher/AssignmentsPage.tsx` | 补 `onReset`：作业列表的「清空筛选」现在也把 `q` 一起清掉，与首页同一语义 |
| `web/src/locales/zh-CN.json`、`en.json` | 新增 `teacher.home.searchPlaceholder`（搜索作业标题 / Search assignment titles）、`teacher.home.noMatch`（没有匹配的作业。/ No assignments match your search.）；`common.clearFilters` 由「清空 / Clear」改为「清空筛选 / Clear filters」 |

## 关键实现决定

1. **搜索框位置与宽度复用现成 CSS**，不新增样式：`.tl-table-toolbar .tl-table-search { flex: 1 1 260px; min-width: 200px }`
   （`ui/ui.css:267`）是上一轮作业列表对齐词库时加的，词库自己的 `.vocab-search` 是 `flex: 1 1 260px; min-width: 220px`。
   两页视觉一致，窄屏都会换行到第二排。
2. **「清空筛选」常驻，并且一次清到底（含关键字）**。词库那页的搜索框语义不同：它是「按 Enter 查任意词」的跳转框
   （`LibraryPage.tsx:424` `onSearch` → `/vocab/words/by-form/:form`），不是过滤器，所以它的 `clearFilters`
   （`LibraryPage.tsx:292`）只删 `state/source/favorite/paused/due/tag/list`、不删 `q`，是自洽的。
   首页/作业列表的搜索框是**过滤器**，同一行里只清一半，用户看到的是「表还是空的、按钮说清空了」，所以这两个页面的清空必须连 `q` 一起清。
3. **改共享件而不是抄第三份**：`TableToolbar` 的注释本来就写明「三处共用：词库、首页最近作业、批改详情提交明细」。
   新增一个可选 prop 比在页面里再拼一个按钮便宜，也不会让「计数 > 0 才显示」这条旧语义在别处复活。
4. **按钮文案统一成「清空筛选」**：改前共享键 `common.clearFilters` 是「清空」，词库是独立的 `vocab.library.clear` =「清空筛选」。
   既然要求一致，就把共享键改成「清空筛选」，三处（首页 / 作业列表 / 批改详情提交明细）一起变。
   代价：AC-19.7 原文写的「「筛选 · N」「列」「清空」三件套」需要跟着改，已同步修订（见下）。

## 验证

浏览器都是 **5174**（`vite preview` 服务 `.envs/qa/web` 静态快照，已用 `pnpm -C web build` + `cp -R web/dist/. .envs/qa/web/` 重灌；后端 jar 未动）。

| 档位 | 做什么 | 结果 |
| --- | --- | --- |
| 静态 | `pnpm -C web typecheck` | 退出码 0 |
| 静态 | `pnpm -C web test` | 117/117 通过 |
| 静态 | `python3 scripts/check-i18n.py` | 通过：zh/en 各 1704 键完全对齐，新增中文字面量 0 |
| 静态 | `python3 scripts/check-ui.py` | 通过：无新增违规（存量 78 处） |
| 界面 | 5174 打开 `/` | 工具栏：`[搜索作业标题 ...... 🔍] [筛选] [列] [清空筛选]`；截图 `after-toolbar-1280.png` |
| 界面 | 搜索框输入 `unit` | 4 行 → 1 行（Unit4写作），URL `?q=unit`；点搜索框的 ⓧ 清空 → 4 行 |
| 界面 | 「筛选」选「未交齐」→ 应用，再搜 `111` | URL `?state=uncollected&q=111`，1 行，按钮显示「筛选 · 1」；截图 `after-filter-and-search-1280.png` |
| 界面 | 点「清空筛选」 | 4 行、搜索框为空、按钮回「筛选」、URL 回到 `?`（仅剩我自己加的 `t=1` 调试参数） |
| 界面 | 搜 `zzz`（无命中） | 表格替空态「没有匹配的作业。」；工具栏仍在，可直接点「清空筛选」 |
| 界面 | 深链 `/?q=unit` | 输入框回填 `unit`、1 行——URL 是唯一真源，链接可分享 |
| 界面 | `/assignments?q=unit` | 输入框回填、1 行；点「清空筛选」→ 4 行、URL 变 `/assignments` |
| 界面 | `/grading/e96789fd…`（提交明细） | 工具栏 `筛选 / 列 / 清空筛选`（常驻）；选「已完成」→ `?state=done`，点清空 → URL 干净 |
| 界面 | 暗色主题 | 搜索框、三个按钮、表头对比度正常；截图 `after-dark-1280.png` |

## 未验证

- **1024 / 768 宽**：`ego_cdp` 的 `Emulation.setDeviceMetricsOverride` 在两次 ego 调用之间不保留（实测 `window.innerWidth` 仍 1280），
  所以只有 1280 截图。工具栏靠 `flex-wrap` + `flex: 1 1 260px`，理论上会换行，但这轮没有像素证据。
- **英文界面截图**：键是 z/en 对齐的，没切语言看一屏。
- 搜索 + 筛选都不命中的叠加态：逻辑是「与」，只测了单侧各一次。
- 词库页 `/vocab/library` 本身没改（它不用 `TableToolbar`），也没重测。

## 附带影响（用户可见）

- 首页、作业列表、批改详情「提交明细」三处的按钮都从「清空」变成「清空筛选」，且**空态也显示**（不再按 `count > 0` 隐藏）。
- 作业列表的「清空筛选」现在会把搜索关键字一起清掉（改前只清筛选组，关键字留在框里）。

截图：`before-1280.png`（改前）/ `after-toolbar-1280.png` / `after-filter-and-search-1280.png` / `after-dark-1280.png`。
