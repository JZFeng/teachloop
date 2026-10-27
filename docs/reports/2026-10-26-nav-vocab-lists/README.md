# 侧栏新增「我的词单」一级入口（T513 / AC-69.11 修订，2026-10-26）

## 用户要求

> 「我的词单在左边的导航栏里单独加一个，就在我的词库下面吧，风格和我的词库一样。点击就链接到 http://localhost:5174/vocab/lists
> 你可能同时需要更新 student 的权限了。」

## 做了什么

| # | 改动 | 文件 |
| :--- | :--- | :--- |
| 1 | 后端菜单矩阵新增 key `nav.vocab.lists`，只授予 STUDENT（这就是「student 权限」） | `teachloop-identity/src/main/java/com/teachloop/identity/nav/RoleNavMatrix.java` |
| 2 | 前端菜单目录加一项，紧跟 `nav.vocab.library`，`tone: practice`（与我的词库同色） | `web/src/app/nav.ts` |
| 3 | 新图标 `UnorderedListOutlined` 登记到图标唯一出口 | `web/src/ui/icons.tsx`、`web/src/app/Shell.tsx` |
| 4 | 文案 `vocab.nav.lists` = 我的词单 / My lists | `web/src/locales/zh-CN.json`、`web/src/locales/en.json` |
| 5 | 页面与路由没动：`/vocab/lists`、`/vocab/lists/:id` 早就在（`web/src/App.tsx`） | — |
| 5b | **同日补记**：词库工具条的「词单」按钮删除，侧栏是唯一入口（见文末「补记」） | `web/src/vocab/LibraryPage.tsx` |
| 6 | 规格回写：AC-69.11 由「不新增一级导航」改为「侧栏并列入口」；连带 AC-56.3、FR-104、007 的矩阵表/合计/AC-63.4/AC-63.10、两份 plan | `specs/004-vocab/`、`specs/007-role-nav/` |

## 验证

**接口**（QA 后端 `:8011`，重启后）：

| 账号 | `nav.vocab.lists` | 授权 key |
| :--- | :---: | :--- |
| `student01@teachloop.local` | **true** | home / classes / vocab.library / vocab.lists / vocab.review（5 项） |
| `teacher@teachloop.local` | false | 仍是 9 项（无词库/词单/复习） |
| `admin@teachloop.local` | false | 仍是 2 项（classes / ai） |

**前端**：`npx tsc --noEmit` 0；`pnpm test` 125/125（新增「词单紧跟词库」「教师看不到学生三项」两条断言）；`check-i18n` zh/en 各 1695 键对齐、0 新增中文字面量；`check-ui` 仅存量 `web/src/exam/importToEditor.ts` RAW_TABLE（T508，非本任务）；`check-deps` / `check-boundaries` 通过。

**浏览器**（`./scripts/env.sh deploy qa` 重建 jar+dist 后，`http://localhost:5174`，学生 学生01）：

- 侧栏 5 项：首页 / 班级 / 我的词库 / **我的词单** / 今日复习；「我的词单」就在「我的词库」下一行。
- 与新词库项**逐项计算样式相等**（8 项）：`className` 同为 `nav-link`、`padding 7px 10px`、高 40、宽 213、字号 14 / 字重 400、色 `rgb(14, 35, 64)`、圆角 8、`gap 10`、图标 16px。
- 点击 → `/vocab/lists`，该项 `on`（高亮），页面标题「我的词单」，渲染真实数据（1 份词单 / 10 词 / 「开始复习（10）」）。
- 进详情 `/vocab/lists/08cc8286-7914-4e3b-88c8-ba840abe92b1`：该项仍高亮（前缀匹配），「我的词库」不高亮。
- `/vocab/library`：仍是「我的词库」高亮；工具条「词单」按钮**已删除**（见文末补记，删除后复测）。

| 截图 | 说明 |
| :--- | :--- |
| `library-nav.png` | 我的词库页：侧栏五个入口，「我的词库」高亮，「我的词单」紧跟其后 |
| `lists-nav-active.png` | 点「我的词单」后的 `/vocab/lists`，该项高亮 |

## 未验证

英文界面截图（`vocab.nav.lists` = `My lists` 已入 `en.json`，只做了键对齐校验）、暗色主题截图（本次未新增任何样式，沿用既有 `.nav-link`）、1024 / 768 窄屏、教师侧栏截图（用接口与单测断言替代）。

## 补记（同日，用户拍板）：删掉词库工具条的「词单」按钮

**用户判断**：「我觉得没啥必要了」。

**为什么同意** —— 两个入口互相矛盾，不是「多一个方便」：

| 入口 | 它暗示的信息架构 | 现实 |
| :--- | :--- | :--- |
| 侧栏「我的词单」 | 词单是与「我的词库」**并列**的独立位置 | 用户 2026-10-26 就是这个要求 |
| 词库工具条「词单」 | 词单是「我的词库」的**子页面**（工具条一直在页面内导航） | 与侧栏并列关系冲突 |

同一页给两个入口，用户每次都要重新判断「这两个是同一件事吗」。留一个，剩一个必须能自证为什么在这——词库工具条其它按钮都满足这条：筛选 / 列 / 清空筛选 / 复习这些词 / 加词，其中「加词」指向 `/vocab/add`（**不在侧栏**，工具条是唯一入口）。词单进了侧栏就不再满足。

**改动**：`web/src/vocab/LibraryPage.tsx` 删那个 `Button`；`vocab.lists.toolbarEntry` 中英各删 1 键（只服务这个按钮）；AC-69.11 / `plan.md` 入口结论 / `tasks.md` T451 与 T514 行回写，并落成规则「**词库工具条只放指向侧栏没有的页的按钮**」。

**验证**：`npx tsc --noEmit` 0、`pnpm test` 125/125、`check-i18n` zh/en 各 1695 键对齐且 0 新增中文字面量、`check-ui` 无新增；重建快照后 5174 学生侧栏仍是 5 项、「我的词单」仍紧跟「我的词库」，词库页工具条按钮数从 6 → 5（筛选 / 列 / 清空筛选 / 复习这些词（N）/ 加词），页面里再无「词单」按钮，`/vocab/lists` 仍能从侧栏进入。截图 `library-toolbar-no-lists.png`。
