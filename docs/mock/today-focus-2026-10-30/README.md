# Mock — 今日复习改全屏会话（2026-10-30）

单文件原型：`index.html`（双击即可点，无依赖、无网络请求）。

起因：`specs/011-self-study-mode/spec.md` 的 AC-91.3 写「在复习页点开始后进入全屏会话」，但实现里
今日复习一直是 `/vocab/review` 页内出卡（`web/src/vocab/ReviewPage.tsx:556`），
只有词单 / 词库 / 词书发起的复习走全屏（`App.tsx:278`）。`docs/reports/011-t567-e2e.md:51-54`
把这条记成「需要产品确认」。本原型是拍板后的目标形态（方案 A：真会话）。

## 怎么看

顶栏四个屏：

| 屏 | 看什么 |
|---|---|
| ① 今日复习（准备） | 改造后这一页只剩「张数 + ⓘ + 开始复习」，卡片舞台搬走；Tab 与侧栏都还在 |
| ② 全屏会话 | 无侧栏、无 Tab、卡片 720px 居中；点卡片或空格翻面；两档评分在卡片内部最下方（只在正面） |
| ③ 会话小结 | 会话比页内卡片多出来的东西：本轮看过 / 认识 / 不认识 + 今日已复习（含本轮） |
| ④ 计数口径 | 为什么必须动一条 SQL：同一组动作在三种口径下的「今日已复习」 |

其他开关：`卡片`（正面 / 反面并列看）、`数据`（有卡 / 今天没卡）。

深链：`#prep` · `#session` · `#session?flip=1` · `#summary` · `#count` · 任一屏 `?data=empty`。

## 这版原型替你决定了什么

1. **评分按钮留在卡片内部最下方、只在正面**（AC-53.9 与现有 `VocabReviewCard` 一致）。
   011 spec §7 原写「按钮在卡片下方」——**2026-10-30 拍板保留卡片内形态**，spec §7 那句已回改。
   依据是量出来的：按「卡片下方」画，834×1194 下两个按钮会被顶出首屏（`actionsBelowFold=2`）。
2. **计数口径必须连带改**：会话日志带 `session_id`，而 `ReviewQueueStore.java:104` 的
   `todayReviewed` 只数 `session_id is null`。只改前端的话，「今日已复习」会从 16 变成 0（第 ④ 屏第三行）。
   **2026-10-30 拍板**：口径改成「`session_id is null` 或 `scope.source='today'` 会话日志」，自由复习仍不计入（AC-61.5 不变）。
3. **会话范围按到期队列取快照**：**2026-10-30 拍板「分两轮」**——超过 1000 词只取前 1000，
   背完回准备页再开一轮；每日新卡上限在建会话时算进快照，会话里不再新增。
4. **Tab 页不再渲染自己的 `h1`**（2026-10-30 拍板，AC-91.6）：准备页标题由壳层 Tab 承担，
   ⓘ 在 Tab 行右端。改造前线上三页都是「Tab：今日复习」+「h1：今日复习」两行同名
   （`vocab.nav.review` 与 `vocab.review.title` 两个 i18n 键值相同）。
5. **今日复习页不放设置入口**（2026-10-30 拍板，AC-91.7）：齿轮与设置抽屉整块删掉
   （`ReviewPage.tsx:407` 的 `VocabPrefsCard compact`），背单词设置唯一入口是 `/profile` 的
   「背单词」块（`ProfilePage.tsx:317`，本来就是完整版）。开始复习了就不再给调整入口。

## 验收（跑过的数字，不是感觉）

脚本用 `web/node_modules` 里的 Playwright（chromium），三档视口逐屏量两行判定：

```
桌面 1440×900：五屏截图，无横向溢出
ipad-air-11 834×1194：五屏 noHScroll=true，actionsBelowFold=0
ipad-pro-portrait 1024×1366：五屏 noHScroll=true，actionsBelowFold=0
```

`actionsBelowFold` = 可见的 `button,a,[role=button]` 中底边超出视口的数量（AGENTS 平板必检第二行）。
截图在 `shots/`（`1440-*.png`、`834-*.png`）。

复跑：

```bash
cd web && NODE_PATH=$PWD/node_modules node /tmp/tf-mock.cjs   # 脚本内容见本 README 的上文描述
```

（该脚本是一次性工具，不进货；重跑时按上面三条命令重写即可。）

## 还没验的

- 只在 chromium 里量过；Safari / 真 iPad 的 `100vh` 与软键盘行为没验。
- 折叠屏 / 横屏没做；断点仍只有 1100 与 1023 两档，未新增第三档。
- 全屏会话里「结束复习」没有二次确认弹层，真实现要补（现有 `useEscapeClose` / `useConfirm` 已有基建）。
