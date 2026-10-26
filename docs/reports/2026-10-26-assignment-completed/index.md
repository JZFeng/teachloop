# 学生作业页「已交卷」态重排（T500 / 002 AC-22.10–22.13）

用户 2026-10-26 的 review 原话（四条，按顺序）：

1. 「这个区域信息特别乱。你觉得怎么安排比较好」+ 把
   「成绩由老师批改并公布后可见；AI 判完不等于公布。」放进「待老师公布」这个 label 的 Info tip；
2. 学生已完成作业时，进页面「题目要求」「评分标准」默认 folded，点了才展开；
3. 作业说明里「本次为作业：没有倒计时，过了截止时间仍可提交，会标记为迟交。」去掉；
4. 题目要求 / 评分标准 folded 时带向下箭头；
5. 「查看结果」只在有结果的时候才可以点击。

## 结论

改后顺序按「谁欠动作」排：**已提交（结果 + 动作）→ 题目要求▽ → 评分标准▽ → 我提交的内容 → 作业说明**。
交卷后两卡默认收起，未交卷仍默认展开；「查看结果」按决策 025 的可见性口径门控。

| | 改前 | 改后 |
| :-- | :-- | :-- |
| 已提交卡 | 一行灰字说明 + 小灰字「待老师公布」+ 提交时间 + 2 个 chip + 2 个按钮挤在一起 | 标题 + 状态 chip / 分数槽（分数或「待老师公布」+ ⓘ）/ 提交时间 / 动作行 |
| 题目要求、评分标准 | 常驻展开，把分数挤到屏幕外 | 交卷后收起成两行标题（箭头向下），点开才看 |
| 我提交的内容 | 交完就整篇铺开（几千字） | 同样默认收起，点标题展开（2026-10-26 追问后补做） |
| 作业说明 | 一坨整句 + 长宽限期解释 | 种类 / 截止 / 宽限期（ⓘ）/ 剩余尝试 / 迟交 |
| 查看结果 | 恒可点，点进去只有「待老师公布」 | 未公布时禁用 + title「成绩公布后才能查看结果」 |

## 截图

| 文件 | 内容 |
| :-- | :-- |
| `before-5174-submitted.png` | 改前（5174 快照，已交卷态）：分数、状态、时间、按钮挤在一行 |
| `after-submitted-collapsed.png` | 改后：已提交卡三段式 + 两张折叠卡 |
| `after-score-pending-tip.png` | 「待老师公布」的 ⓘ 悬停，气泡里是那句完整规则 |
| `after-prompts-expanded.png` | 点开题目要求：箭头翻转为向上 |
| `after-published-score.png` | 公布态（fetch 拦截伪造 `totalScore=88`）：绿「已公布」+ 分数 + 客观/主观 + 按钮可点 |
| `after-notes-compact.png` | 作业说明：整句已删，宽限期拆「宽限期 0 小时」+ ⓘ |
| `after-all-three-folded.png` | 三张卡都收起后，已交卷态整页一屏（`scrollHeight` 813 = 视口高） |
| `after-content-expanded.png` | 点开「我提交的内容」：箭头翻转，正文按原样式渲染（页面 2358px） |
| `unsubmitted-still-expanded.png` | 未交卷（fetch 拦截伪造未交）：两卡仍展开，作业说明无那句 |
| `live-5174-after-deploy.png` | 5174 快照重新部署后的实测（暗色）：折叠卡、分数槽、禁用的「查看结果」都在 |

## 怎么验的

- 环境：`http://localhost:5199`（`vite` dev，`TEACHLOOP_API=http://127.0.0.1:8011` 代理 qa 后端）。
  之所以不用 5174：**5174 是 `.envs/qa` 快照**（`vite preview` 服务构建产物），且工作区里还有另一条线
  未提交的首页/导航改动，现在 `pnpm build` 会把那份半成品一起发布。
- 交互实测（CDP）：`.fold-head` 的 `aria-expanded` 由 `false` → `true`；`.fold-caret` 拿到 `is-open`；
  `.score-pending .tl-info-btn` 悬停后 popover 文本 = 「成绩由老师批改并公布后可见；AI 判完不等于公布。」；
  「查看结果」`disabled=true` 且父级 `title="成绩公布后才能查看结果"`。
- 公布态与未交卷态用页内 `window.fetch` 拦截伪造响应验证（不改数据库）：公布态
  `totalScore=88 / subjectiveScore=88 / gradingState=DONE`；未交卷态 `attempts=[]` + `entry.submitted=false`。
  **没有在真实「已公布」数据上验**——qa 库里这场作业仍是未公布。

## 静态检查

`typecheck` 0、`vite build` 0（319ms）、`check-i18n`（zh/en 1704 键对齐）、
`check-ui`（存量 86 → 83，本页 `LEGACY_BTN_CLASS` 7 → 4）、`web/tests` 108/108。
`check-ui` 另有存量下降（`PaperWizardPage` 的 `INLINE_STYLE`/`LEGACY_BTN_CLASS`/`RAW_TABLE`/`EMOJI_ICON`）
来自另一条线，不是本次改的。

## 未做 / 待用户拍板

1. **5174 快照已上线**：另一条线（T499 学生首页）在本次 commit 之后跑了 `./scripts/env.sh deploy qa`，
   所以 5174 上的 `/assignments/:id` 已经是这版（实测 `.fold-head` 2 个、页面内搜不到「没有倒计时」）。
2. 学生首页卡片、「成绩与评语」列表仍可点进 `/feedback/:id`（未公布时页面有「未发布」说明）。
   是否一起门控，等用户决定（已写进决策 025 的修订段）。
3. ~~`我提交的内容` 保持展开~~ → 2026-10-26 已按用户追问「要折叠吧」改成默认收起（同一 `SectionCard`）。
