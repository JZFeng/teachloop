# 决策 027 — 学生首页：页头只说一次，动作落在有证据的行上

日期：2026-10-26　状态：**已实现**（US-72 / AC-72.1–72.8 / T503 + T504）
相关：AC-31.2/31.3（已修订）、AC-24.1（加例外）、AC-63.4（改名后删项）、AC-12.x、`docs/decisions/025-offering-status-by-role.md`
代码锚点：`web/src/student/HomePage.tsx`、`web/src/ui/PageHeader.tsx`、`web/src/student/rowState.ts`、
`web/src/app/nav.ts`、`teachloop-identity/.../RoleNavMatrix.java`、`web/src/exam/AssignmentPage.tsx:384,434`

## 问题

2026-10-26 用户看学生首页（`http://localhost:5174/`，账号 student01）后提了两点：

> 1 学生页面的 dashboard，这个「我的反馈」，怎么这么别扭呢，你有什么想法？
> 2 DASHBOARD 我的学习，这个是不是重复了？还有这个「考试、作业与反馈；成绩由老师公布后可见」，莫名其妙。

盘点下来是**同一件事说了四遍，而唯一有用的动作放错了地方**：

| 层 | 内容 | 位置 |
| :-- | :-- | :-- |
| eyebrow | `DASHBOARD` | `PageHeader` 的 `^/$` 规则 |
| 标题 | 我的学习（侧栏那一项却叫「首页」） | `student.homeTitle` |
| 副标题 | 考试、作业与反馈；成绩由老师公布后可见 | `student.homeSub` |
| 汇总 | 共 4 场 · 未完成 0 · 待公布 3 · 已公布 1 | `student.homeSummary` |

副标题那半句规则，每张卡上已经写着（「待老师公布」/「已公布」）；汇总的四个数，就是下面 tag 的展开，
而且数字不可点，不能当筛选用。eyebrow 是唯一一个纯英文、不带功能位的值（其余都是 `ASSESS · 反馈` 这种），
信息量等于「你在一级页」。

而真正的动作——「看评语」——被做成了页头右上角的按钮：它跟侧栏 `nav.feedback` 同字、指向同一份列表，
`HomePage.tsx:59-63` 里只有一句 `navigate('/feedback')`；反过来，行内那枚说得最清楚的「有反馈」是个
不可点的 `tag`（`HomePage.tsx:125`），整卡点击永远去作业页（`:79`）。信号在行上，动作在页头。

## 决策

### D1 · 页头只说一次

- 删 eyebrow：`PageHeader` 的 `^/$` 规则移除，**教师首页同时生效**（同一条路由、同一个缺陷）。
  AC-31.3 相应加例外，理由写清：首页没有功能位，eyebrow 只能重复标题。
- 删可见副标题。它原本想说的规则（成绩由老师批改并公布后才可见）收进标题旁 ⓘ（AC-71 的口径：
  页面上看不出来的规则才进气泡），并补上「AI 批完不算公布」这一条真正容易误解的。
- 删页头操作按钮。`/feedback` 在侧栏已经有一项，页头再放一个同名按钮等于制造第二个入口。

### D2 · 计数变成筛选，不是装饰

`共 4 场 · 未完成 0 · 待公布 3 · 已公布 1` 改成四枚 chip（全部 / 未完成 / 已交卷 / 已公布），
点击即过滤，`aria-pressed` 表达选中。数字从此是「按这个数能筛出来的东西」，而不是一行读不出用途的统计。
用词统一走状态词（未完成 / 已交卷 / 已公布），不再出现汇总说「待公布」、行内说「已交卷」的两套说法。

### D3 · 动作落在有证据的行上

已公布（`rowHasFeedback`）的行内给「看评语」按钮，直连 `/feedback/:offeringId`，`stopPropagation`
不触发整卡跳转。删掉静态的「有反馈」tag——它和「已公布」「我的分数 14 / 14」三枚并排，
而判定条件 `submittedAt && totalScore != null` 与「已公布」等价，属于必然重复。

### D4 · 名字换成学生的话

「反馈」是供给侧的词（老师给反馈），学生脑子里是「我的分数 / 老师说了什么」。页面标题、
考试结果页按钮统一改成**「成绩与评语」**；`eyebrow.feedback` 跟着改短（`ASSESS · 成绩`）。

## 第二轮（2026-10-26 00:45，用户睡前拍板「全部按你的想法改」；T504）

第一轮只接回了入口、没动结构，三件事留成了待决项；用户在睡前面谈里逐条选了方向：

### D5 · 排序改成「最急的在最上」

`sortRowsByDue` 从**倒序**改**正序**（`web/src/student/rowState.ts`，纯函数，`web/tests/student-home.test.mjs`
锁三条断言：升序、不改入参、解析不出的截止时间沉底）。这跟 AC-24.1「列表默认时间倒序」冲突，
所以在 AC-24.1 里写明例外：**首页是「待办」不是「档案」**——倒序会让最晚截止的排最前、最急的沉到最下面。
教师端列表仍按 AC-24.1 倒序。

### D6 · 页名与侧栏同名

`student.homeTitle` 从「我的学习」改成**「首页」**。同一个位置三个名字（侧栏「首页」、eyebrow「DASHBOARD」、
标题「我的学习」）在第一轮删掉 eyebrow 后还剩两个，本次统一成一个。

### D7 · 删掉 `/feedback` 列表页，只留详情

用户选了方案 C。`FeedbackListPage.tsx` 删除、`App.tsx` 去掉 `feedback` 路由、`nav.ts` 删 `nav.feedback`
目录项、后端 `RoleNavMatrix` 学生集合去掉 `NAV_FEEDBACK`（5 → 4 项，vocab 关 2 项）。
`/feedback/:offeringId` 保持不变，入口在首页卡片与作业页行内；详情页的「返回列表」删除，只留「返回首页」。

连带处理：
- **历史通知**：`notificationRoute` 里 `feedback_published` + `targetType=submission` 的旧行原来跳 `/feedback`
  （已无此页），改为跳 `/`——拿不到布置 id，回首页让用户自己点「看评语」，比落到死路由好。
- **UI 标准面板**：icon 清单里的 `nav.feedback` 标签改用 `student.feedbackTitle`（图标本身还在）。
- **i18n**：删 10 个只属于列表页的键（`feedbackSub`/`viewCard`/`viewTable`/`colTitle`/`colType`/`colSubmittedAt`/`colScore`/`feedbackEmpty`/`loadingFeedback`/`backToList`）。

## 本次没做（记在这里，别下一轮当成漏了）

1. `teacher.home` 首页也去掉了 eyebrow，但页头仍有副标题，未按 AC-72 的口径重排。
2. `eyebrow.feedback` 的色调在代码里是 `assess`，而 `specs/006` AC-31.2 原来写的是 Insights 靛蓝——
   这是本次之前就存在的口径冲突；AC-31.2 已注明 Insights 靛蓝此后只归「报表」，代码色调未改。
3. 首页仍是单列表 + 筛选（方案 A），没有按「要交 / 等老师 / 已出成绩」分组（方案 B）。
