# 决策 028 — 全站页头统一成首页：删 eyebrow，标题旁一律带 ⓘ

日期：2026-10-26　状态：**已实现**（T509）
相关：AC-31.3 / AC-31.4 / FR-068（本决策撤销其 eyebrow 要求）、AC-52.2 / AC-53.9 / AC-53.10（今日复习页恢复可见标题）、AC-72.4 / AC-72.5（eyebrow 键与例外随全站删除）
代码锚点：`web/src/ui/PageHeader.tsx:28,33-35`、`web/src/ui/ui.css:6`、`web/src/vocab/ReviewPage.tsx:344-348`、`web/src/vocab/WordDetailPage.tsx:208-218`、`web/src/locales/zh-CN.json:210,587,1734`

## 问题

2026-10-26 用户对照首页与其它页面：

> 1 首页现在的 header 长这样：`<header class="tl-page-head">…<div class="tl-page-title-row"><h1 class="tl-page-title">首页</h1></div>…</header>`。把其它页面都改成首页这样的。
> 我举个例子给你：actual：`http://localhost:5174/classes` 班级页，expected：把这个 div 删掉 `<div class="tl-page-eyebrow" data-tone="neutral">CLASSES</div>`
> 2 `http://localhost:5174/review`「复核工作台」改成「复核」；`http://localhost:5174/vocab/review` 今日复习页面，「复习」改成「今日复习」，然后风格要和其它页面统一；`http://localhost:5174/notifications` 通知页面也别漏掉。
> 3 每个页面的 header 那个 label，都要加上 Info icon，类似学生首页的那个（如图）。内容暂时先不定，我以后统一整理给你。

盘点下来是**同一个页头三种写法**：

| 路由 | 改造前 | 问题 |
| :-- | :-- | :-- |
| `/` 首页 | 标题 + ⓘ + 副标题，无 eyebrow | 用户认可的样板（决策 027） |
| `/classes`、`/notifications` | eyebrow（`CLASSES` / `INBOX`）+ 标题 + 副标题 | eyebrow 只是把标题换个语言再说一遍 |
| `/vocab/review` | `hideTitle`，只剩右上角一个快捷键 ⓘ | 没有标题、没有副标题，与其它页不像同一产品 |
| `/vocab/words/by-form/:form` | `hideTitle`，标题区只剩反馈按钮 | 页头空掉，标题让给卡片里的单词 |

`PageHeader` 里那张 `EYEBROW_RULES` 是 2026-10-22 品牌层留下的路由 → 英文/功能位 → 色调映射（T349 / AC-31.3）。它服务的场景是「一屏里要快速分辨功能位」，但每个页面本来就有中文标题和侧栏高亮；eyebrow 在中文界面里是重复信息，在全站统一后只会制造第二套叫法。用户已明确要求以首页为准，所以不再保留例外。

## 决策

### D1 · 全站删 eyebrow，不保留任何路由例外

- `PageHeader` 删掉 `EYEBROW_RULES`、`PageTone`、`eyebrow` / `tone` 入参，以及 `tl-page-eyebrow` 渲染；`web/src/ui/ui.css` 删掉对应样式与四档功能色规则。
- zh/en 删掉整段 `eyebrow.*`（20 个键），UI 标准面板的 `ui.kit.brandNote` 不再提 eyebrow。
- 这**撤销** AC-31.3 / AC-31.4 的 eyebrow 要求与 FR-068 的「路由前缀 → 文案 + 色调映射表」；决策 027 为首页开的例外不再需要——现在全站都没有。
- 页头颜色不再承载功能位：功能色只留在导航胶囊、图标胶囊与图表里（AC-31.4 同步改写）。

### D2 · 标题旁一律有 ⓘ；没文案先显示占位，不编内容

- `PageHeader` 在标题行固定渲染 `<h1>` + `InfoHint`，不再有 `hideTitle` 分支；所有使用 `PageHeader` 的路由都自动获得 ⓘ。
- `info` 入参仍然优先：学生首页（`student.homeInfo`）与新建作业页（`teacher.assignmentCreate.sub`）继续显示真实文案；今日复习页把原来的「统计 + 快捷键表」从右上角独立图标移进标题 ⓘ（AC-52.2 / AC-53.10 的口径改为「标题旁」）。
- **没有文案的页面先用 `common.pageInfoPending`（中：「本页说明整理中，稍后补充。」/ 英："Page help is being written and will be added soon."）占位**。用户明确说内容以后再统一给，所以这里不写猜测性说明；等文案到位后逐页传 `info` 即可，不改结构。
- ⓘ 仍是 `ui/InfoHint` 的 hover/focus 触发（点一下会先拿焦点再打开），不是新的交互组件。

### D3 · 三处标题按用户指定改

- `/review`：`review.title` 从「复核工作台 / Review workbench」改成「复核 / Review」；侧栏本来就叫「复核」。
- `/vocab/review`：`vocab.review.title` 本来就是「今日复习 / Today's review」，但页面 `hideTitle` 把它藏了，实际只显示 eyebrow 里的 `PRACTICE · 复习`。现在删 `hideTitle`，标题按用户要求显示「今日复习」；原快捷键 ⓘ 合并进标题 ⓘ，页面不再有第二个说明图标。
- `/vocab/words/by-form/:form`（查词页）：原来 `hideTitle`，页头没有 label。为满足「每个页面」，新增 `vocab.detail.title = 查词 / Word details` 并显示；单词本身仍在下方卡片里，不重复当 h1。
- `/notifications` 与 `/classes` 不需要改代码：标题文案本来就是「通知」「班级」，删 eyebrow 后自动与首页一致。

### D4 · 今日复习页的「风格统一」只改页头，不动卡片

- 卡片宽度、翻面、评分按钮、快捷键、SRS 逻辑都不动；本次只把 `PageHeader` 从「无标题 + 右侧 ⓘ」改成「今日复习 + ⓘ」。
- 原 AC-53.9 写的「页面不再显示 h1」是 2026-10-24 为了给卡片让空间做的；用户现在要求与其它页统一，AC-53.9 同步改写，卡片区仍保持原宽度与高度。

## 影响与代价

- 全站页头少一层英文/功能位标签，标题整体上移约 18px；无路由增加或删除。
- 每页多一个 ⓘ 按钮（18×18，无底色），键盘 Tab 会多停一站；按钮有 `aria-label="说明"` 与可见焦点环。
- 占位文案会出现在所有未补内容的页面气泡里；这是**有意暴露的待办**，不是成品文案。英文占位同键存在，`check-i18n` 键集合仍对齐。
- 删除 20 个 `eyebrow.*` 键后，任何仍按路由读 eyebrow 的旧代码/截图脚本都会失效；仓库内 `grep -rn "eyebrow" web/src` 已无业务引用，只余本决策与 PageHeader 注释。

## 验证

- 守卫：`pnpm typecheck` 0、`pnpm build` 0、`pnpm test` 117/117、`check-i18n` 1689 键对齐（本任务验证时）、`check-ui` 0 新增（存量 78；本任务自己的改动 0 新增。当前共享工作树另有并发任务 T508 的 `web/src/exam/importToEditor.ts` 触发 `RAW_TABLE`，不属于本任务）。
- DOM 扫描（QA 5174，最终构建 `index-DaqtAXVQ.js`；同一页头改动也曾在 `index-BEE0fsx1.js` 上验过）：教师 19 条路由 + 学生 6 条路由，`document.querySelectorAll('.tl-page-eyebrow').length === 0`、`.tl-page-title-row .tl-info-btn === 1` 全部成立，样例：`/review` 标题「复核」、`/vocab/review`「今日复习」、`/vocab/words/by-form/hello`「查词」、`/notifications`「通知」。
- 浏览器截图：`docs/reports/2026-10-26-unified-page-header/`（home / classes / classes-info / review / vocab-review / vocab-review-info / notifications / word-details，均 1280×813）。
- ⓘ 内容：`/classes` 悬停显示占位「本页说明整理中，稍后补充。」；`/vocab/review` 悬停显示原统计行 + 8 行快捷键表。

## 本次没做（记在这里，别下一轮当成漏了）

1. **逐页 ⓘ 文案**：等用户统一整理；现在只有学生首页、新建作业、今日复习三处是真实内容，其余是 `common.pageInfoPending` 占位。
2. **英文界面逐页截图**：仅抽查 `/classes`、`/review`、`/vocab/review`、`/notifications` 四条（标题 Classes / Review / Today's review / Notifications，均无 eyebrow），未逐页截图。
3. **768px 以下窄屏**：只验证 1280×813；标题 + ⓘ + 操作区在窄屏的换行未单独截图。
4. **`/vocab/assign` 的「说明」按钮**：它是页面动作，不是标题 label 的 ⓘ，本次未合并；若以后要收成同一个 ⓘ，需另开一条。
