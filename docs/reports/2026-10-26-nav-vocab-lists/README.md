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
| 5 | 页面没动：`/vocab/lists`、`/vocab/lists/:id`，词库工具条的「词单」按钮保留，两个入口到同一页 | `web/src/App.tsx`、`web/src/vocab/ListsPage.tsx`（仅注释） |
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
- `/vocab/library`：仍是「我的词库」高亮；工具条「词单」按钮仍在，点击仍到 `/vocab/lists`（两个入口共存）。

| 截图 | 说明 |
| :--- | :--- |
| `library-nav.png` | 我的词库页：侧栏五个入口，「我的词库」高亮，「我的词单」紧跟其后 |
| `lists-nav-active.png` | 点「我的词单」后的 `/vocab/lists`，该项高亮 |

## 未验证

英文界面截图（`vocab.nav.lists` = `My lists` 已入 `en.json`，只做了键对齐校验）、暗色主题截图（本次未新增任何样式，沿用既有 `.nav-link`）、1024 / 768 窄屏、教师侧栏截图（用接口与单测断言替代）。
