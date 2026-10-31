# 今日复习改全屏会话（T595 / T596 / T597）· 2026-10-31 夜间交付

> 交接给你早上看。**dev 已经在跑新代码**（http://127.0.0.1:5173），可以直接点。
> **qa 与生产没动。**

## 0. 一句话结论

**今日复习不再自己出卡**：点「开始复习」建一个 `source=today` 的全屏会话，评分计入「今日已复习」；
三个 Tab 页不再有与页签同名的标题；今日复习页的设置齿轮删掉，背单词设置只在 `/profile`。
另外 `/profile` 的「背单词」说明也收进了标题右侧的 ⓘ。
**注**：当天上午曾按你的要求把「删除当前单词」加进会话（T598），随后按你的判断**撤销**——
删除不适合放在卡片上，入口只在词库页行内。详见 §2 与 §5.1。

## 1. 早上三分钟验收（照着点）

| # | 点这里 | 应该看到 |
|---|---|---|
| 1 | http://127.0.0.1:5173/vocab/review | 页签行（今日复习 / 我的词单 / 我的词库）+ 右上角 ⓘ；下面只有「24 张待复习 + 开始复习」，**没有标题「今日复习」**，**没有齿轮** |
| 2 | 点「开始复习」 | 跳 `/vocab/review/session/<id>?source=today`：**无侧栏、无页签**，标题「今日复习」，副标题「第 1 张 / 共 24 张」，页头 ⓘ 里是快捷键表 |
| 3 | 评一张「认识」→ 结束复习 | 小结出现「今天已复习 N 张（含本轮 M 张）」——这是这次改的计数口径在界面上的样子 |
| 4 | 点「回今日复习」 | 大数字变小、ⓘ 里的「今日已复习」把刚才那张算进去了 |
| 5 | http://127.0.0.1:5173/vocab/lists 与 `/vocab/library` | 没有重复标题「我的词单 / 我的词库」；「新建词单」在页签行右端 |
| 6 | http://127.0.0.1:5173/profile | 「背单词 ⓘ」——说明「每天背多少、先复习还是先学新词、卡面显示什么」进了气泡，块里直接是每日任务量 |

## 2. 改了什么

### T595 今日复习改真会话（AC-91.3 后半句）

- 后端 `POST /api/vocab/review-sessions` 新增 `source` 字段（`ReviewSessionEndpoint.java:82`）：
  `source=today` 时 scope 里写 `"source":"today"`，scope key 加 `today|` 前缀（`:136`），
  与词单 / 词库发起的会话**不共用续位游标**；同一批词如果这一轮已走到尾，再点开始从 cursor 0 起（`:140`），
  避免直接落到「0 张」的小结。
- 计数口径（`ReviewQueueStore.java:100`）：`todayReviewed` 从「`session_id is null` 的日志」改成
  「`session_id is null` **或会话 `scope.source='today'`** 的日志」。自由复习（词单 / 词库 / 词书）仍不计入（AC-61.5 不变）。
- 前端 `ReviewPage.tsx` 重写为**准备页**：张数 + ⓘ + 「开始复习」。点开始按到期游标把队列取齐
  （`collectDueIds`，一页 100 张往后翻，**上限 1000**，超出显示「本轮 1000 张，背完回这里再开下一轮」），
  再建会话跳全屏。新卡额度用完（到期数 > 0 但队列为空）时按钮禁用并说明原因。
- 前端 `FreeReviewPage.tsx` 认 `?source=today`：标题换「今日复习」、副标题换「第 N 张 / 共 M 张」、
  小结多一行「今天已复习 X 张（含本轮 Y 张）」、「回今日复习」变主按钮。
- 快捷键表从今日复习页的 ⓘ 移到**会话页页头的 ⓘ**（卡片在哪，表就在哪）：`ReviewShortcutTable.tsx`。

### T596 三个 Tab 页去掉同名标题（AC-91.6）

- `ViewTabs` 加 `extra` 槽（`web/src/ui/ViewTabs.tsx:27`）+ `.tl-tab-extra`（`ui.css:212`）。
- 新增 `web/src/vocab/VocabTabExtra.tsx`：context + portal，页面把 ⓘ / 新建词单投进页签行右端。
- `ReviewPage` / `ListsPage` / `LibraryPage` 删掉 `PageHeader`；词单详情页保留自己的标题（它显示的是词单名，本来就不撞）。

### T597 今日复习页去掉设置入口（AC-91.7）

- 删掉 `ReviewPage` 的齿轮按钮、设置 `Drawer` 与 `VocabPrefsCard compact`；`vocab.prefs.open` 键一并清掉。
- `/profile` 的「背单词」块（`ProfilePage.tsx:317`）是完整版，成为**唯一入口**。

### T598 会话里的「删除当前单词」（已撤销）

09:29 你要求加回，实现并验过（后端 `DELETE` advance + 卡头垃圾桶 + `Delete` 键 + 二次确认，
`VocabReviewSessionTest` 4/4、dev 实测「第 1 张 → 第 2 张、词从词库消失」）。
随后你判断**删除不适合放在卡片上**，同一小时整体回滚：后端分支、前端 `onDelete` / 键位、
快捷键表行、那条集成用例全部移除，删除入口只在词库页行内（AC-59.11）。
卡片上现在只有「收藏（F）」和「暂停这个词（S）」两个图标。

### 顺带：`/profile` 的说明进气泡

「每天背多少、先复习还是先学新词、卡面显示什么」原来是标题下的一行灰字，现在在「背单词」右侧的 ⓘ 里
（`ProfilePage.tsx:316-320`，样式 `.vocab-prefs-head`）。

## 3. 验证到哪一档

| 档 | 项目 | 结果 |
|---|---|---|
| 编译 | `web` `tsc --noEmit`（含 `tsconfig.e2e.json`） | 0 错 |
| 单测 | `pnpm test`（node，`web/tests/*.test.mjs`） | **223/223** |
| 集成 | `VocabReviewSessionTest`（含 `todaySessionCountsAsReviewed`） | **3/3** |
| 集成 | `VocabReviewQueueTest` / `VocabTodayTimezoneTest` | **1/1** / **5/5** |
| 闸门 | `check-i18n` / `check-ui` / `check-boundaries` | exit 0 / 0 / 0 |
| 全量 | `make test`（全部闸门 + 后端全量） | **exit 0**，706 tests / 0 failures / 0 errors / 4 skipped（见 §3.1） |
| 端到端 | Playwright 3 project（1440 / 834×1194 / 1024×1366）跑**生产产物** | **83 passed** |
| 界面 | 5 个页面 × 3 档视口 `scrollWidth <= innerWidth+2` | 全部 true（截图见 §4） |
| 未验 | 真 iPad / Safari 触摸与 `100vh`、软键盘 | 仍然没验 |

### 3.1 `make test` 明细

```
make test
→ check-deps / check-boundaries / check-i18n / check-ui / web-test / vocab-lexicon-test 全绿
→ mvn -q test：exit 0
   surefire 汇总（152 个测试类）：706 tests, 0 failures, 0 errors, 4 skipped
   （撤销 T598 后重跑：删掉的那条 DELETE 用例不再计入）
```

其中与本次改动直接相关的三组：`VocabReviewSessionTest` 3/3（含新增的 `todaySessionCountsAsReviewed`）、
`VocabReviewQueueTest` 1/1、`VocabTodayTimezoneTest` 5/5。

### 3.2 端到端为什么跑产物而不是 dev server

同一个改动在 dev（:5173）跑 E2E 会红 3 条：`AC-90.7 今日页只请求一次 /api/student/today` 收到 2 次。
根因不是这次改动——`web/src/main.tsx` 里 React `StrictMode` 一直开着（2026-10-19 起），dev 下 effect 双调用；
同一份代码 `make web-build` 后用 `vite preview`（:4173）跑就是 83 passed。
这条已写进 `docs/process/e2e-playwright.md` §9 第 9 条，**别当 bug 改测试**。

## 4. 截图

都在 `docs/reports/shots/2026-10-31-today-focus/`：

| 文件 | 看什么 |
|---|---|
| `vocab-review-1440.png` | 准备页：页签 + ⓘ + 24 张 + 开始复习，没有 h1、没有齿轮 |
| `after-rating-1440.png` | 评完一张回到准备页：ⓘ 与副行里「今日已复习 2」 |
| `session-1440.png` / `session-834.png` | 全屏会话：无侧栏无页签，标题「今日复习 · 第 1 张 / 共 24 张」 |
| — | T598 的两张「删除」截图已随撤销删除，不留误导性证据 |
| `vocab-lists-1440.png` | 我的词单：无重复标题，新建词单在页签行右端 |
| `vocab-library-1440.png` | 我的词库：无重复标题 |
| `profile-1440.png` | /profile：「背单词 ⓘ」+ 每日任务量 |

## 5. 偏离与代价（照实写）

1. **「删除当前单词」不在复习卡上**：今日复习页没有卡片了，会话里只有「暂停这个词 / S」；
   删除入口只剩词库页行内（AC-59.11 的另一半）。T598 当天加回又按你的判断撤销——**卡片上不放删除**。
2. **计数口径变了**（这是拍板过的）：今日复习会话的评分现在计入「今日已复习」；词单 / 词库 / 词书发起的
   自由复习仍不计入。如果哪天想「所有复习都算」，改的是 `ReviewQueueStore.java:100` 那一个 SQL 和 004 的 AC-52.2。
3. **会话容量 1000**：今日到期超过 1000 时分两轮（背完 1000 回准备页再开）。分母不会骗人：
   准备页的大数字是「剩余待复习」，第二轮开完大数字会落到余数。
4. **E2E 必须跑生产产物**（见 §3.2）。dev 上红 3 条是 React 调试行为，不是回归。
5. **提交是被迫分两次的**：夜里另有一个并行会话在改 v3 卡面，它的提交 `d2261e7` 把
   **我当时未完成的改动一起带进了仓库**（`locales`、`vocab.css`、`ProfilePage.tsx`、`ReviewQueueStore.java`、
   `FreeReviewPage.tsx` 里都有我的行）。我随后把**剩下属于自己的文件**单独提交（见 §7 的 hash），
   所以 **HEAD 上现在是完整的**：`d2261e7`（并行会话的 v3 卡面）+ 我这次那条 T596/T598/计数口径的提交。
   以后同一棵 worktree 并行时，建议一个会话固定改一个目录。

## 6. 没做 / 建议下一步

1. **会话里不做「删除当前单词」**（你 2026-10-31 上午的判断，T598 已撤销）：卡片上只保留评分与暂停。
   如果以后想「复习时删词」，方向是词库页行内或卡外菜单，而不是卡片头上的图标。
2. **准备页的「预计 N 分钟」不做**：2026-10-23 你明确说过不要这类估算（AC-52.2 写着）。
3. **连续学习天数**（AC-90.1）仍未做，属第二期。

## 7. 提交与回滚

**提交**：`feat(vocab): T596 去掉 Tab 页同名标题 + T598 会话内删除 + T595 计数口径`（43 个文件）
→ 随后 `revert(vocab): 撤销 T598 会话内删除`（本文档与规格同步改回）。用
`git log --oneline --grep T598` 能看到这两条；
更早的 `T595` 部分行被并行会话的 `d2261e7` 带走（见 §5.5），两者合起来就是今晚的全部改动。

**回滚**：`git revert <T596/T598 那个 hash>` 即可回到「今日复习页内出卡、会话无删除」的旧形态；
数据库没有迁移（会话表本来就有 `scope` 列），**不需要**回滚数据。dev 回滚后重新 `make build` + 重启 `./scripts/dev.sh`。

本次涉及的文件：

```
# 属于 T595/T596/T597 的文件（我的）
teachloop-vocab/src/main/java/com/teachloop/vocab/review/ReviewQueueStore.java
teachloop-vocab/src/main/java/com/teachloop/vocab/review/ReviewSessionEndpoint.java
teachloop-app/src/test/java/com/teachloop/VocabReviewSessionTest.java
web/src/ui/ViewTabs.tsx
web/src/ui/ui.css
web/src/vocab/VocabShell.tsx
web/src/vocab/VocabTabExtra.tsx          （新）
web/src/vocab/ReviewShortcutTable.tsx    （新）
web/src/vocab/ReviewPage.tsx
web/src/vocab/FreeReviewPage.tsx
web/src/vocab/ListsPage.tsx
web/src/vocab/LibraryPage.tsx
web/src/app/ProfilePage.tsx
web/e2e/specs/vocab-flows.spec.ts
web/src/locales/zh-CN.json · en.json      （与另一会话混在同一文件）
web/src/vocab/vocab.css                   （与另一会话混在同一文件）
specs/004-vocab/spec.md
specs/011-self-study-mode/spec.md
specs/011-self-study-mode/tasks.md
docs/process/e2e-playwright.md
docs/mock/today-focus-2026-10-30/         （新）
docs/reports/2026-10-31-today-focus-session.md（新，本文）
docs/reports/shots/2026-10-31-today-focus/（新）
```

**dev 回滚**：`git checkout -- <上面这些>` 之后重新 `make build` + 重启 `./scripts/dev.sh` 即可回到旧行为；
数据库没有迁移，**不需要**回滚数据（会话表本来就有 `scope` 这一列）。

## 8. 最终闸门（真实退出码）

| 命令 | 退出码 | 说明 |
|---|---|---|
| `cd web && npx tsc --noEmit` | 0 | 前端类型（含 e2e 配置单独跑过一次也是 0） |
| `cd web && pnpm test` | 0 | node 测试 223/223 |
| `make check-i18n` | 0 | zh/en 各 1997 键对齐，无新增中文字面量 |
| `make check-ui` | 0 | 无新增违规（存量 74 处） |
| `make check-boundaries` | 0 | 62 张表归属清晰，8 个端口只收发 record |
| `make test` | 0 | 706 tests / 0 failures / 0 errors / 4 skipped |
| `E2E_BASE_URL=http://127.0.0.1:4173 pnpm e2e` | 0 | 83 passed（3 project，生产产物） |
| `make web-build` | 0 | 产物 429ms 构建成功 |

dev 现在的形态：后端 jar 由 `make build` 于 2026-10-31 01:04 产出并跑在 `:8010`；
前端 `vite` 在 `:5173` 热更新（本次没有重启它）。qa（`:5174`）与生产**未动**。
