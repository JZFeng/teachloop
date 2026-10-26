# 决策 025 — 作业状态按「角色责任」划分；分数以老师公布为准

日期：2026-10-25　状态：口径已拍板；分数可见性**已实现**，状态显示重排**待评审**
相关：AC-12.3（已改写）、D-5（已改写）、AC-17.11 / AC-19.4、
`docs/decisions/025` 依赖的 `specs/002-v2-platform/tasks.md:670`（被推翻的旧口径）
代码锚点：`teachloop-sitting/src/main/java/com/teachloop/sitting/api/ExamSessionEndpoint.java`、
`web/src/teacher/common.tsx`、`web/src/student/studentUtils.ts`、`web/src/teacher/GradingDetailPage.tsx`

## 问题

### 1. 教师首页的「状态」一列有 8 档，混了两种东西

`OfferingStateTag`（`web/src/teacher/common.tsx:230`-`243`）按优先级短路出 8 个互斥标签：
有失败 / 批改中 / 待处理 / 无提交 / 可发布 / 成绩已公布 / 待提交 / 进行中。

它回答的其实是 4 个**互不排斥**的问题：学生交齐了吗、AI 批完了吗、有异常吗、老师公布了吗。
一个词装不下四个答案，只能按优先级丢信息。具体后果：

- 「有失败」优先级最高，会盖掉「成绩已公布」：成绩已公布、但剩 1 份批改失败的作业，标签显示
  「有失败」，老师会以为成绩没发。
- 「无提交」「待提交」「进行中」是同一件事的三个说法，差别只在「学生没交」的形态
  （连卷子都没打开 / 打开了停在草稿）。而「进行中」的判定条件极窄：`failed=running=pending=0`
  且 `done+rejected<total`，剩下的只可能是草稿行（`common.tsx:230`-`243`）。
- 「待处理」「批改中」描述的是 AI 这个工具在干什么，不是老师的责任状态。

### 2. 用户 2026-10-25 给出按角色的划分

> AI 批改的状态，到底还有没有必要展示或者提醒给老师？作为老师，AI 啥时候批，老师门清。
> 我们根据 role & responsibility 来划分。
> 老师关注的点：作业发出去没，学生交齐没，我批改完了没（AI 只是辅助我批改的，说到底是工具，
> 是工具的话，我没必要在这统计状态，我应该直接到作业那，就能看到了。如果 AI 没批改完，
> 我自己手动按一下让 AI 批改；如果 AI 改完了，那就刚刚好）；
> 回到学生这个角色：作业发了没，我做了没，作业批了没？我只关心这三个。

### 3. 由此暴露的口径冲突：学生没等老师公布就看到了分

学生端「已批改」是看 `gradingState === 'DONE'`（`web/src/student/studentUtils.ts:35`-`42`），
不看 `publish_scores`；`my-submissions` 与 `my-feedback` 返回的分数也没有掩码
（`ExamSessionEndpoint.attemptView`）。而 `publish_scores` 默认 `false`（`Offering.java:63`），
作业创建页也从不传这个字段（`web/src/teacher/OfferingCreatePage.tsx:153` 只传 `publish`），
`POST /api/offerings/{id}/scores/publish` 在全前端无调用者。

也就是说：**分数一直对所有人可见，而「公布」这个动作从来没有入口。**

要注意这不是实现走样，而是 2026-10-19 记录在案的旧口径：`tasks.md:670` 原文
「自己的分始终可见」、AC-12.3「学生**永远**能看到自己的分数，不受 `publish_scores` 影响」、
D-5「不隐藏，学生永远可见自己的具体分」。本次是**推翻旧决策**，不是修一个失手写的 bug。

## 决策

### 一句话原则

**状态 = 现在谁欠下一个动作；工具在跑的过程不进状态。**

按这条原则切，「AI 批改」不该出现在任何统计或状态标签里——它是老师的工具，老师进作业详情页
就能看到进度，并且详情页已经有按钮可以自己推一把（`GradingDetailPage.tsx:444` 整场派发、
`:350` 单份「让 AI 先批」）。

### 老师端：3 个主状态

| 状态 | 谁欠动作 | 判定 | 原状态里被吸收的 |
| :-- | :-- | :-- | :-- |
| 待复核 | 老师 | 复核队列条数（`/api/grading/queue`） | 待复核（原样保留） |
| 待公布 | 老师 | `done > 0 && !publishScores` | 待发布 / 可发布 |
| 未交齐 | 学生 | `unsubmitted > 0` | 未提交 / 待提交 / 无提交 / 进行中 |
| （删除） | — | — | 批改中、待处理、有失败 → 移入作业详情页，见下 |

措辞：**去掉「我」**——账号下所有行为都是老师自己的，「待我公布」是赘字，用「待公布」。

### 学生端：3 个主状态

| 状态 | 判定 | 合并了 |
| :-- | :-- | :-- |
| 未完成 | 无答卷，或有未交草稿 | `not_started` + `in_progress` |
| 已交卷 | 已交、成绩未公布 | `submitted`（并吸收原来过早出现的 `graded`） |
| 已公布 | 已交，且分数可见 | `graded` |
| （角标）被拒收 | `rejectedAt` | `rejected`：异常不占主状态位 |

### 分数的可见性（本次已实现）

分数可见 = 整场公布成绩（`publish_scores=true`），**或**这一份的复核反馈已发布
（`reviewFeedback.noteOf(submission).publishedAt != null`）。两者任一，与 `my-feedback` 原有的
`published` 口径一致。

不可见时：`objectiveScore` / `subjectiveScore` / `totalScore` / `overview.myScore` 全部返回 `null`，
`gradingState` 照旧返回（前端据此判断「在批」还是「没派发」，但不显示分）。

## 实施

### 本次已做（接口 + 界面）

| 改动 | 位置 |
| :-- | :-- |
| `scoresVisible(o, s)` 辅助方法；`attemptView(s, maskScores)` 重载 | `ExamSessionEndpoint.java` |
| `my-submissions` 逐份按可见性掩码 | `ExamSessionEndpoint.myAttempts` |
| `my-feedback` 的 `latestAttempt` 与 `overview.myScore` 掩码 | `ExamSessionEndpoint.myFeedback` |
| 提交回执 `objectiveScore` 掩码（提交完不能立刻看分，刷新后也不矛盾） | `ExamSessionEndpoint.submitResponse` |
| 学生端「已批改」改为「分数可见才算已批改」 | `web/src/student/studentUtils.ts:35` |
| 学生首页 / 反馈列表 / 反馈详情 / 作业结果 / 考试结果：分数为空时显示「待老师公布」 | `web/src/student/*.tsx`、`web/src/exam/{AssignmentPage,ExamResult}.tsx` |
| **补「公布成绩」按钮**（`POST /scores/publish` 的调用者原本不存在） | `web/src/teacher/GradingDetailPage.tsx` |
| 改掉承诺「永远可见 / 立即出分」的旧文案 4 处 | `web/src/locales/{zh-CN,en}.json` |
| 改写 AC-12.3、D-5；标注 tasks.md:670 被推翻 | `specs/002-v2-platform/{spec,tasks}.md` |

**为什么必须同时补公布入口**：分数改成按公布状态门控后，如果没有公布按钮，`publish_scores`
永远停在 `false`，学生就永远看不到任何分数。所以「补按钮」不是顺手加的功能，是这个改动能成立的
前提。

### 待评审、本次未做（等本文件过 review）

- 老师端 8 档 → 3 档的判定链重排（`web/src/teacher/common.tsx:230`-`243`）
- 老师首页 4 张统计卡删掉「批中」（`web/src/teacher/HomePage.tsx:190`-`200`）
- 学生端 5 个词 → 3 个词的合并（`web/src/student/studentUtils.ts:33`）
- AI 进度（已批 x/y、失败 n）下沉到作业详情页头部

## 验收

- AC-12.3 改写为按公布状态；D-5 同步改写；`tasks.md:670` 加注「2026-10-25 决策 025 已推翻」。
- 接口级测试：`Phase2ExamTimingTest`（未公布时提交回执与 `my-submissions` 都给 `null`、
  公布后给分、错题与快照答案）、`T196QuestionTypeTest`、`ObjectiveRecomputeTest`、
  `ManualGradingTest`（逐份复核发布后学生能看到主观分 17.5）——`10 tests / 0 failures`。
- 前端：`tsc --noEmit` 通过；`pnpm test` 94 通过；`scripts/check-i18n.py` 1699 键对齐。
- **未做浏览器验收**：本轮只到接口与类型层，界面视觉与点击流未在浏览器里点过。

## 影响与代价

- 学生交完试/作业后**不再立即看到客观分**，要等老师公布。这是决策的直接代价；如果希望作业场景
  保留「即时客观分」，需要单独立一个「作业即时反馈」例外，不要在状态判定里做特例。
- 老师多了一个动作（公布成绩）。这是把「AI 判完」和「交付学生」分开的必付成本。
- 老数据：`publish_scores` 全为 `false`，上线后所有历史作业的分数都变成不可见，需要老师逐场公布。

## 未覆盖（已知残留）

1. 作答中的自动保存 `PUT /answers` 仍返回 `objectiveScore`（`AnswerSaveResponse`）；前端没有渲染
   这个字段，所以界面上不泄漏，但接口还在给。是否掩码待定。
2. 老师端 8 档 → 3 档未改（见「待评审」），所以本文件生效前，首页仍会显示「批改中 / 待处理 /
   有失败」这些工具状态。
3. `publish_scores` 只影响本人分数可见性；导出（ZIP/PDF）与班级平均分早已按此门控，不受本次影响。
