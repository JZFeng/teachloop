# 今日页与背单词模块结构 · 原型（2026-10-30）

对应规格：[`specs/011-self-study-mode/spec.md`](../../../specs/011-self-study-mode/spec.md)（草案，2026-10-30；本原型是它的需求输入）
上游讨论：2026-10-30「自学工具与班级体系分离」产品设计稿

单文件、无依赖、无构建：**双击 `index.html` 就能点**。

## 怎么 review

顶部三组开关，任意组合：

| 开关 | 值 | 看什么 |
| :--- | :--- | :--- |
| 学生状态 | 已加入班级 / 未加入班级 | 同一个「今日」页，模式差异只体现在卡片，不体现在落地路由 |
| 背单词内部 | 页内 Tab / **侧栏树（仅对照，v1 不做）** | 两种二级导航的实际观感；侧栏树会改扁平导航模型，见 spec §6 非目标 |
| 数据 | 有数据 / 空态（新用户） | 新用户第一天看到什么 |
| 时区 | 产品时区 / 洛杉矶 | 学生时区 ≠ 产品时区时，今日页那行「新的一天按 … 0 点开始」 |
| 屏 | 今日 / 背单词 / **复习会话** | 会话页是全屏形态：左侧栏与 Tab 都消失 |

另有「屏」开关切换 今日 / 背单词。

深链直接开某一屏：

- `index.html#today`
- `index.html#today?mode=noclass`
- `index.html#today?tz=la`
- `index.html#today?data=empty`
- `index.html#vocab?nav=tree`
- `index.html#vocab?tab=library`
- `index.html#vocab?tab=lists&data=empty`

## 屏与深链

| 屏 | 深链 | 这一屏看什么 |
| :--- | :--- | :--- |
| 今日 · 已入班 | `#today` | 单列：复习卡（24 待复习 / 已复习 8 / 新词 10）→ 待交作业（带班名）→ 考试；**没有班级卡** |
| 今日 · 未入班 | `#today?mode=noclass` | 没有作业/考试卡；词单卡补位；末尾是「有邀请码？」卡 |
| 今日 · 空态 | `#today?data=empty` | 复习卡变成「先加第一批词」行动卡；考试与学习记录消失 |
| 今日 · 洛杉矶时区 | `#today?tz=la` | 副标题下一行说明「新的一天按洛杉矶时间 0 点开始」（同区时不出现） |
| 背单词 · 页内 Tab | `#vocab` | 侧栏只有一级「背单词」，进去落今日复习，三个页面用 Tab |
| 背单词 · 侧栏树 | `#vocab?nav=tree` | 对照形态：二级挂在侧栏，手机抽屉要两级展开 |
| 我的词单 | `#vocab?tab=lists` | 词单卡片（词数、上次复习）；Tab 第二位 |
| 我的词库 | `#vocab?tab=library` | 词形/音标/释义/下次复习/状态五列；Tab 第三位 |
| 复习会话 | `#session` | **全屏无侧栏、无 Tab**；卡片先给正面，点击/空格翻面；底部两档评分（对应 AC-91.3） |

## 这一版定的三件事（待你确认）

1. **落地页统一为「今日」**，不按「有无班级」分流。理由：分流后 QA 组合是 2×3×2＝12 种；统一后回到 3 种角色，且绕开「学生属于多个班时默认进哪个班」的无解问题。
2. **背单词模块内部用页内 Tab**（**今日复习 / 我的词单 / 我的词库**），侧栏只放一级。理由：现有侧栏是扁平渲染（`web/src/app/Shell.tsx:120`）+ 扁平特性 key（`RoleNavMatrix.java:24-32`），做树要改模型、抽屉、i18n 与 `web/tests/vocab-nav.test.mjs`，约 1–1.5 人日；页内 Tab 只改模块内部，约 0.5 人日。
3. **「今日复习」是 Tab 里的准备页，不是复习会话本身**。真正的复习是全屏会话（`/vocab/review/session/:id`），Tab 只显示待复习张数与「开始复习」。
4. **今日页不放班级卡**。班级是师生之间的组织桥梁，学生首屏只呈现任务；班级只作为作业/考试条目上的班名标签，入口留在侧栏「班级」。

## 还没定

- 「我的单词」这个名字：术语表里是「我的词单」（`docs/process/glossary.md`、`web/src/app/nav.ts:57-62`）。本原型按「我的词单」画；如果「我的单词」指所有单词的列表，它是第四个页面。
- 学习记录卡（连续天数 / 本周词数）标了「第二期」，第一期不做。
- 未登录落地页（获客页）不在本原型内，另画。

## 三档宽度（2026-10-30 定）

| 页 | 宽度 | 理由 |
| :--- | :--- | :--- |
| 我的词库 / 我的词单 | 满宽（壳层 `max-width:1240px`） | 表格列多，压窄会让「释义」折行 |
| 今日页 / 今日复习准备 | 880px | 大数字 + 按钮的行动页，太宽会散 |
| 复习会话 | 全屏无侧栏，卡片 720px 居中 | 一次只做一件事；720 对齐现有 `review-card-column` |

## 平板自检（2026-10-30 规则：834×1194 与 1024×1366）

实测 `document.documentElement.scrollWidth` 与首屏动作数，12 个组合全部通过：

| 视口 | 屏 | scrollWidth / innerWidth | 横向溢出 | 首屏外的按钮数 |
| :--- | :--- | :--- | :--- | :--- |
| 834×1194 | 今日 | 834 / 834 | 无 | 0 |
| 834×1194 | 今日 · 未入班 | 834 / 834 | 无 | 0 |
| 834×1194 | 我的词库 | 834 / 834 | 无 | 0 |
| 834×1194 | 今日 · 洛杉矶时区 | 834 / 834 | 无 | 0 |
| 1024×1366 | 今日 | 1024 / 1024 | 无 | 0 |
| 1024×1366 | 今日 · 未入班 | 1024 / 1024 | 无 | 0 |
| 1024×1366 | 我的词库 | 1024 / 1024 | 无 | 0 |
| 1024×1366 | 今日 · 洛杉矶时区 | 1024 / 1024 | 无 | 0 |
| 1440×1000 | 今日 | 1440 / 1440 | 无 | 0 |
| 1440×1000 | 今日 · 未入班 | 1440 / 1440 | 无 | 0 |
| 1440×1000 | 我的词库 | 1440 / 1440 | 无 | 0 |
| 1440×1000 | 今日 · 洛杉矶时区 | 1440 / 1440 | 无 | 0 |

复跑：

```bash
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
"$CHROME" --headless=new --no-sandbox --disable-gpu --hide-scrollbars \
  --user-data-dir="$PWD/.chrome-profile" --virtual-time-budget=3000 \
  --window-size=834,1194 --dump-dom "file://$PWD/index.html?measure=1#today" \
  | grep -o 'data-measure="[^"]*"'
```

断点只用了 AGENTS 允许的两档：`max-width:1100px`（今日页两列变单列）、`max-width:1023px`（侧栏变抽屉，顶栏出现「菜单」）。

## 截图

`shots/` 下 10 张：

`today` `today-la` `today-noclass` `today-empty` `vocab-tree` `vocab-library`
`today-834` `vocab-library-834` `today-1024` `vocab-library-1024`

重新生成前先做语法自检，避免截出空白页：

```bash
node -e "const fs=require('fs');const h=fs.readFileSync('index.html','utf8');
const m=h.match(/<script>([\s\S]*)<\/script>/);new Function(m[1]);console.log('JS OK')"

CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
"$CHROME" --headless=new --no-sandbox --disable-gpu --hide-scrollbars \
  --user-data-dir="$PWD/.chrome-profile" --virtual-time-budget=2500 \
  --window-size=1440,1000 --screenshot="$PWD/shots/today.png" \
  "file://$PWD/index.html#today"
```

## 数据口径

页面里的班级、作业、考试、词条都是**示例数据**，不是真实账号；日期按 2026-10-30（周五）写。
复习卡上的 24 / 8 / 10 取自 `GET /api/vocab/review/queue` 的真实字段（`total / todayReviewed / newToday`），不是编的。
