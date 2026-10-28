# 界面叫法（术语表）

> 2026-10-25 与用户对齐。目的：改布局、写 spec/tasks、做验收时，**同一个界面只有一个叫法**，避免「自由复习」既指页面又指卡片正面的歧义。
> 本表只定义**叫法**，不定义功能；功能口径以 `specs/<模块>/spec.md` 为准。

## 五个叫法

| 叫法 | 中文界面 | 是什么（一句话） | 代码锚点 | 别混淆 |
| :-- | :-- | :-- | :-- | :-- |
| `review-front` | 卡片正面 | 卡片的未翻面状态：单词 + 英/美音标 + 提示行；T440 起**单词居中、音标另起一行居中** | `web/src/vocab/VocabReviewCard.tsx` 的 `flipped=false`；`.review-card`（`web/src/vocab/vocab.css:81`）；i18n `vocab.review.front` = 正面 / Front（`web/src/locales/zh-CN.json:1499`） | **不是页面**；今日复习和自由复习都各有一个正面 |
| `review-back` | 卡片反面 | 卡片的翻面状态：释义、例句（各带朗读）、构词分析；版式仍是 **T437 一行左对齐**（单词与音标同一行，不跟正面居中） | `flipped=true`；`.review-card.is-flipped`（`vocab.css:94`）、`.review-back`（`vocab.css:127`）；i18n `vocab.review.back` = 背面 / Back（`zh-CN.json:1500`） | 反面是**只读视图**，没有评分按钮 |
| `word-details` | 查词页 | 查一个词的独立页面：默认翻到反面，底部「返回」，不在库时多一个「＋」；**不在库的词进页面就自动接 AI 生成**（T515，卡背先写「AI 正在生成…」，好了自动出内容，不用先点「＋」） | `web/src/vocab/WordDetailPage.tsx`；路由 `/vocab/words/by-form/:form`（`web/src/App.tsx:123`）；接口 `GET /api/vocab/words/by-form/:form` + `POST /api/vocab/words/lookup`；i18n `vocab.detail.*` | 版式与 review-front **分开**：单词与音标同一行左对齐（T437/T440）；「＋」= 加入词库，**不是**看到内容的前提 |
| `today-review` | 今日复习页 | 按 SRS 到期队列出卡的页面 | `web/src/vocab/ReviewPage.tsx`；路由 `/vocab/review`（`App.tsx:118`）；i18n `vocab.review.*` | 页面（包含 review-front 与 review-back 两种卡片状态） |
| `free-review` | 自由复习页（会话） | 从词库点词开始的临时复习会话页 | `web/src/vocab/FreeReviewPage.tsx`；路由 `/vocab/review/session/:id`（`App.tsx:124`）；i18n `vocab.freeReview.*`（`zh-CN.json:1634`） | **不是卡片正面**；它同样有正、反两面 |

## 一句话记法

- **卡片状态**用 `review-front` / `review-back`（成对，和 CSS `.review-back`、i18n `vocab.review.back` 对齐）。
- **页面**用 `today-review` / `free-review`。
- **查词**用 `word-details`。

## 提需求的标准句式（照这个说，不会歧义）

- 好：「`review-front`：单词居中、音标另起一行居中；`review-back` 和 `word-details` 不动。」（= 2026-10-25 T440 那次）
- 好：「`word-details` 保持 T437 左对齐，别跟着 `review-front` 改。」
- 差：「复习页那个卡片」——分不清是 today-review、free-review，还是卡片正面。

## 已知的历史混用（看到别当真）

- 2026-10-24 的 T435/T436 记录里「音标与收藏挪到单词同一行」说的是**当时的卡片正面**（`review-front`），不是页面布局。
- `vocab.freeReview.keys`（"空格翻面 · 1 不认识 · 2 认识 · S 暂停这个词"）里的「翻面」= `review-front` → `review-back` 的切换。

## 作业 / 考试状态词（2026-10-25，决策 025 已实现）

原则：**状态 = 现在谁欠下一个动作；工具在跑的过程不进状态。** 完整推导见
`docs/decisions/025-offering-status-by-role.md`。

### 老师端行内状态（3 个待办 + 1 个终态，纯函数 `web/src/teacher/offeringState.ts`）

| 叫法 | 谁欠动作 | 判定 |
| :-- | :-- | :-- |
| 未交齐 {{n}} | 学生 | `unsubmitted > 0` |
| 待批改 12/30 | 老师（可自己推一把） | 交齐但 `done + rejected < total` |
| 待公布 | 老师 | 交齐且都批完，`!publish_scores` |
| 已公布 | 没人欠动作 | `publish_scores` |
| （角标）{{n}} 份失败 | 老师 | `failed > 0`，与任意主状态并存 |

「待复核」是**全局复核队列**（首页统计卡），不是行内状态。

### 学生端状态（3 个 + 1 个角标，纯函数 `web/src/student/rowState.ts`）

| 叫法 | 一句话 |
| :-- | :-- |
| 未完成 | 没开始、停在草稿，或被拒收要重交 |
| 已交卷 | 交了，等老师公布 |
| 已公布 | 分数可见 |
| （角标）已拒收 · 需重交 | 不占主状态位 |

### 措辞规则

- **不要「我」**：「待我公布」→「待公布」。账号下所有行为都是本人的，「我」是赘字。
- **成绩用「公布」，不用「发布」**：作业 / 试卷用「发布」，成绩用「公布」。所以是「待公布」。
- **AI 进度不叫状态**：详情页写「已批改 / 批改中 / 待派发 / 失败」四张工具卡，首页不再出现。
- **筛选项与状态词同一套**：总览页 `pending` + `running` 已合并为「等 AI 批」（`state=grading`）。

### 2026-10-25 之前已废弃的词（代码里不应再有）

`待处理`、`进行中`、`无提交`、`待提交`、`可发布`、`有失败`（作主状态时）、`成绩已公布`
（作主状态时）、学生端 `未开始` / `作答中` / `已批改` / `被拒收`（作主状态时）。
