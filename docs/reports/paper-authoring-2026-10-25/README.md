# 试卷录入重构（D-34）：四步向导 + Markdown 单一内容源 — 交付报告

- 日期：2026-10-25
- 需求：用户「重新梳理 `/papers`：去掉导入考试按钮、流程简化」+ 四条拍板（总分语义 / 命题人审题人 / 第 4 步排版 / 重新生成语义）
- 决策：`docs/decisions/021-paper-single-source-authoring.md`（D-34）
- 规格：`specs/002-v2-platform/spec.md` AC-4.9（重写）、AC-4.3 / AC-4.10 / AC-4.17④ / AC-4.23④（修订）、AC-4.24–4.29（新增）、FR-146–150
- 方案：`specs/002-v2-platform/plan.md` §20
- 任务：`specs/002-v2-platform/tasks.md` 阶段 31（T456–T466）
- 原型：`docs/mock/paper-flow-2026-10-25/index.html`（4 屏 + 7 状态，真实卷数据）

## 0. 结论

1. **四步向导已实现**：① 基本信息（标题 / 时长 / 命题人 / 审题人 / 目标总分）② 内容（一个 Markdown 编辑器 +
   一个文件选择 + 唯一的「生成考题」）③ 评分项（写作题一题一 tab）④ 预览与发布（评分项「无 / N/A」+ 写作题按题号分块）。
   第 3 步（标准答案）与三张内容卡（内容（部分）/ 上传整卷导入 / 批量改题型）已删除，`/papers/import` 页面与
   「导入考卷」按钮已删除。
2. **内容只有一个写入口**：Markdown 原文 → `POST /api/papers/{id}/import[/text]?replace=true` → 解析写库 →
   存 `papers.content_markdown` → 只读预览表。再次生成是**替换重建**：同事务内先写快照、再删评分项/题项/部分、
   按原文重建；确认框数出三类会丢的界面内编辑；已发布卷一律拒绝。
3. **验证到哪一档**：**接口 + 单测 + 真库迁移 + 浏览器端到端**（dev 5173 / 8010 实测走完四步并发布；截图见 §3.2）。
4. **与预案的偏差**（都不影响 AC，理由见 §5 与 §2.2）：已发布卷生成一律 409（`confirm` 只记审计）；
   发布失败改成 `400 + {code,message,detail}`（原先是抛异常，拿不到题号 detail）；文件上传存的是
   「实际进解析器的文本」（不是原始字节）；另外修了共用组件 `VditorMarkdown` 的两个既有缺陷
   （外部改值漏更新、首次挂载只读态失效）——原计划这两处是「不改」。

## 1. 用户口径 → 落点

| 用户原话 | 落点 | 证据 |
| :--- | :--- | :--- |
| 去掉导入考试这个 button | 列表页只剩「新建试卷」；`/papers/import` 路由与 `ImportPaperPage.tsx` 删除 | `PapersPage.tsx`、`App.tsx` |
| 流程要简化 | 5 步 → 4 步；第 3 步删除 | `PaperWizardPage.tsx`（stepper 4 项） |
| 第一步加命题人 / 审题人 / 总分 | `papers.author_name` / `reviewer_name` / `target_total_points`；`PUT /api/papers/{id}/meta` | `V065__paper_meta_and_content.sql`、`PaperEndpoint.java` |
| 三张卡直接不要，用新建作业页那个 markdown 编辑器（源码/所见即所得互切） | 复用 `VditorMarkdown`（rich/src 互切是组件自带的） | `PaperWizardPage.tsx` step 2 |
| 只保留一个按钮「生成考题」，上传文件也是它；点完要进度条 | 唯一按钮 + 文件选择器；不确定进度（四阶段 + 秒数，无百分比） | step 2 卡片、`paperFlow.ts::PROGRESS_STAGES` |
| 生成完显示题目 table view，只做预览 | 七列只读表 + 「N 题 · 合计 X 分 · 缺答案 K 题」+ 断档提醒 | `previewRows/previewSummary` |
| 「这个 preview 的 table view 有没有实际意义？」 | 有：它回答「解析漏题 / 答案配错 / 分值算错」，所以必须带缺答案与断档，且与写库共用同一次解析 | AC-4.25④ |
| 批量改题型 card 删掉 | 界面删除；`items/question-type/batch` 接口保留（脚本/旧数据） | `PaperEndpoint.java`（未改该端点） |
| 第 3 步标准答案删掉 | 删除；答案在原文里，改答案 = 改原文再生成 | 同 AC-4.24③ |
| 第 4 步要 N 个 markdown editor，2 个 tab 还是每题一个 step？越多复用越好 | **一题一 tab**（stepper 固定 4 步，只挂载当前 tab 的 Vditor；复用 `RubricPreview`） | AC-4.28、`paperFlow.ts::essayTabs` |
| 不需要参考范文 | 向导不再写 `reference_markdown`；列与解析保留、复核页仍可读 | AC-4.28③④ |
| 预览发布：评分项 `—` 改「无」/`N/A`；两道作文 `77 <内容>` 空行 `78 <内容>` | `rubricBlocks()`：无 rubric 显示「无 / N/A」，写作题按题号分块、块间空行 | `paperFlow.ts`、`teacher.css` |
| 两个按钮都保留 | 「同步并校验」只取详情 + 比合计 + 给消息（不再补建内容）；「发布试卷」保留 | step 4 |

## 2. 改了什么

### 2.1 后端（`teachloop-paper`，迁移 V065）

- `V065__paper_meta_and_content.sql`：`papers` 加四列（`target_total_points numeric(8,2)`、
  `author_name varchar(80)`、`reviewer_name varchar(80)`、`content_markdown text`，全可空）；
  新增 `paper_content_snapshots(id, paper_id, created_at, reason, payload jsonb)` + `(paper_id, created_at desc)` 索引。
  （V064 已被并行分支的 vocab-lists 占用，本次用 V065。）
- `Paper`：四个字段 + getter/setter。
- `PaperEndpoint`：
  - `PaperView` 增 `authorName/reviewerName/targetTotalPoints`；`PaperDetail` 增 `contentMarkdown`；列表不返回正文。
  - `POST /api/papers` 接受三个可选字段，`authorName` 空则写当前教师显示名。
  - **新增 `PUT /api/papers/{id}/meta`**：三个字段全量替换（null 清空）、不动 title/duration、不重算 total_points、
    已发布卷可改并写审计 `paper:meta_updated`。
  - 两个 import 端点增 `replace` / `confirm` 参数；`replace=true` 时同事务内
    写快照 → 删 criteria → items → sections → 重建 → `content_markdown` → `recalcTotal`；单部分文档先建「综合」部分。
  - `ImportResult` 增 `contentMarkdown` 与 `metaHints`；新增 `importing/PaperHeaderMeta`（从原文头部读命题人/审题人/总分，
    只做提示，不改数据）。
  - `POST /{id}/publish` 三条服务端复校验：总分 > 0、非 ESSAY 题项无空答案、目标非空时合计 == 目标（容差 0.001）；
    失败 `400 + {code,message,detail}`，缺答案 detail 是题号列表。
  - `copy`：复制原文与目标总分，命题人 = 当前教师显示名，审题人清空。
  - 生成与发布补 `requireAuthor`（原来 import/publish 没有作者校验，属于历史漏洞，本次一并补上）。
- 文案：新增 5 个消息 key（中英各一份），后端 api 层零新增中文字面量。

### 2.2 前端

- `web/src/teacher/PaperWizardPage.tsx`：重写（2374 → 约 780 行）。删除三套表单状态、批量改题型、
  参考范文、旧 `syncDraft` 补建逻辑；新增四步与确认框/进度/预览/评分项 tabs。
- `web/src/teacher/paperFlow.ts`（新增）：纯逻辑（目标 vs 合计、缺答案、断档、评分项列、草稿 v2 规整、
  删除摘要、进度阶段、meta 表单转换），有 14 条单测。
- `web/src/teacher/PapersPage.tsx`：去掉「导入考卷」按钮。
- `web/src/App.tsx`：删除 `papers/import` 路由与 import。
- 删除 `web/src/teacher/ImportPaperPage.tsx`；`teacher.import.*` 全部文案删除。
- `web/src/components/VditorMarkdown.tsx`：修两个既有缺陷（浏览器实测逼出来的，作业页同样受益）——
  ① 外部改值（导入 .md）改用「重建实例」：IR 模式下 `setValue` 会漏更新（state 已是新原文、编辑器还显示旧内容）；
  ② 只读态在 `after` 回调里补一次 `disabled()`：只挂 effect 时首次挂载的 `disabled` 会被 `ready` 的时序吞掉。
- `web/src/api/types.ts`：`PaperView` / `PaperDetail` / `PaperImportResult` 增字段。
- `web/src/api/client.ts`：新增 `api.postForm`（multipart，浏览器自己写 boundary）。
- `web/src/locales/{zh-CN,en}.json`：新增约 50 个键、更新四步标题与两条口径文案、删除废弃键与整个 import 块；
  两语言键集合 1663 = 1663 完全对齐。
- `web/src/teacher/teacher.css`：不确定进度条、tab 条、评分项分块（只用令牌颜色）。
- `web/tests/paper-flow.test.mjs`（新增）：14 条纯函数测试。

### 2.3 文档

- 新增：`docs/decisions/021-paper-single-source-authoring.md`、`docs/research/2026-10-25-paper-authoring-single-source.md`、
  `docs/mock/paper-flow-2026-10-25/`（原型 + 数据脚本 + 14 张截图）、`docs/reports/mockup-parity/03-paper-flow.md`、本报告。
- 修订：`specs/002-v2-platform/{spec,plan,tasks}.md`、`docs/PROGRESS.md`、`docs/DELIVERY-STATUS.md`、`scripts/check-boundaries.py`（登记新表）。

## 3. 验证（真实输出）

| 项 | 命令 | 结果 |
| :--- | :--- | :--- |
| 后端单测（paper + shared） | `./mvnw -pl teachloop-paper -am test` | paper **123 用例**、shared **12 用例**，0 失败 0 错误，BUILD SUCCESS |
| 前端纯函数 | `cd web && pnpm test` | **75/75** 通过（含本次 14 条） |
| 前端类型与构建 | `cd web && pnpm typecheck` | 通过（`tsc --noEmit` 无输出） |
| 文案守卫 | `python3 scripts/check-i18n.py` | 通过：zh/en 各 **1663** 键对齐；前后端新增中文字面量 0 |
| UI 守卫 | `python3 scripts/check-ui.py` + `scripts/ui/contrast.py` | 通过：无新增违规（存量 93）；44 组对比度达标 |
| 依赖/边界 | `python3 scripts/check-deps.py` / `check-boundaries.py` | 通过；**52 张表**归属清晰、14 条跨模块外键已登记 |
| 全量回归 | `make test` | **EXIT=0**：Maven 全模块 **513 个测试**（111 个测试类，0 失败 0 错误 1 跳过）+ 四个守卫 + 前端 80/80 全过。（首次运行曾因并行分支的陈旧 class 报 3 个 `NoClassDefFoundError: AdminEndpoint$UserView`；`clean` 掉 identity 后重跑全绿，属增量构建残留。） |
| 迁移真库 | Postgres 16 容器按版本序全量应用 | **58 个迁移**跑通；`\d papers` 见四列、`\d paper_content_snapshots` 见 `payload jsonb` + 索引（后端队友实测） |
| 浏览器端到端 | 见 §3.2 | 已实测（dev 5173 + 8010，真实卷 77 题 / 150 分），共 12 张截图。 |

### 3.1 接口级证据（单测名）

- 替换重建：`replaceWritesSnapshotBeforeDeletingAndStoresSubmittedText`、`replaceCreatesDefaultSectionWhenDocumentHasNoParts`、
  `replaceRebuildsMultiPartStructureFromRealExam`（真实卷：4 部分 / 77 题 / 150 分 / 写作 76、77）、
  `replaceKeepsOnlyThreeSnapshotsPerPaper`、`rejectsGenerateOnPublishedPaperEvenWithConfirm`。
- meta：`metaReplacesAllThreeFieldsAndLeavesTitleDurationAndTotalAlone`、`metaClearsFieldsWhenTheyAreNull`、
  `metaOnPublishedPaperIsAllowedAndAudited`。
- 发布：`publishRejectsZeroTotal`、`publishRejectsMissingAnswerKeyAndListsNumbersInDetail`、
  `publishIgnoresEssaysWithoutAnswerKey`、`publishRejectsTotalThatDoesNotMatchTargetTotal`、
  `publishAllowsDifferenceWithinTolerance`、`publishSkipsTargetCheckWhenTargetIsEmpty`。
- 其它：`copyCarriesContentAndTargetButResetsAuthorAndReviewer`、`createFillsAuthorNameFromCurrentTeacherWhenBlank`、
  `detailExposesContentMarkdownForTheEditor`、`PaperHeaderMetaTest`（李璐 / 刘嘉伦 / 150）。

### 3.2 浏览器端到端

dev 栈 `./scripts/dev.sh --no-open --stub`（后端 8010 / 前端 5173），用 `teacher@teachloop.local` 登录后逐屏操作：

| 步 | 操作 | 实测结果 | 截图 |
| :--- | :--- | :--- | :--- |
| 试卷库 | 打开 `/papers` | 只剩「新建试卷」；`导入考卷` 文案在页面上不存在 | `shots/01-papers.png` |
| ① 基本信息 | 填标题 / 时长 120 / 审题人「刘嘉伦」/ 目标 150 | 命题人自动预填「示例老师」；保存走 `PUT /meta`（提示「基本信息已保存」） | `shots/02-step1*.png` |
| ② 内容（上传） | 选真实卷 `.md` | 提示「已把 e2e-paper.md 读进编辑器」，编辑器同步显示原文（21 063 字） | `shots/04-step2-loaded.png` |
| ② 内容（生成） | 点「生成考题」 | 弹出确认框：「部分 4 个、题项 77 道；已填评分项的写作题 0 道 / 错题解析状态非 none 0 道 / 旧评分项 0 条」 | `shots/05-step2-generating.png` |
| ② 内容（进度） | 确认后 120ms 采样 | 进度条 + 四阶段（读取文档 / 切分题号 / 绑定答案 / 写入题项）+ 已用秒数；720ms 后结束（≥700ms 最短展示） | 同上 |
| ② 内容（预览） | 生成完成 | 77 行；「77 题 / 合计 150 分 / 缺答案 0 题」；「题号连续（1–77）」；题干与答案来自真实卷 | `shots/06-step2-preview.png` |
| ③ 评分项 | 两个 tab 各填一份 rubric 并保存 | tab 为「第 76 题 · 15 分」「第 77 题 · 15 分」；合计预览 15/15；切 tab 不丢已保存内容 | `shots/07/08/09-*.png` |
| ④ 预览发布 | 打开第 4 步 | 统计 77 / 150 / 150 / 0；评分项列：听力/阅读/知识运用「无」，写作「76 …」+「77 …」分块；「总分一致：150 分」 | `shots/10-step4-preview.png` |
| ④ 目标不一致 | 回第 1 步把目标改成 140 再前进 | 红字「总分不一致：目标 140 分，实际合计 150 分」；「发布试卷」置灰；直调 `POST /publish` 返回 **400** `{message:…, detail:"total=150,target=140"}` | `shots/11-step4-mismatch.png` |
| ④ 发布 | 目标改回 150 后发布 | 成功提示 + 「已发布」标签；同步/发布均禁用；出现「去布置 / 返回试卷库」 | `shots/12-step4-published.png` |
| 已发布锁 | 重新打开编辑页 | 顶部「已发布试卷的内容已锁定」；生成按钮禁用；编辑器 `contenteditable=false`；直调 `import?replace=true&confirm=true` 返回 **409** | `shots/13-editor-locked.png` |

E2E 期间发现并修掉两个**共用组件**的缺陷（作业页同样受益，已写进 plan §20.5 偏差）：
1. `VditorMarkdown` 外部改值用 `setValue` 在 IR 模式下会漏更新：state 已是新原文，编辑器还显示旧内容（只有刷新才恢复）。
   改成「外部改值即重建实例」；复测：先载 22 424 字原文，再导入 55 字小卷，编辑器正确变成 55 字内容。
2. `VditorMarkdown` 的只读态只挂在 effect 上，首次挂载时 `disabled` 会被 `ready` 的时序吞掉（已发布卷编辑器仍可编辑）。
   改成在 `after` 回调里补一次 `disabled()`；复测：已发布卷 `contenteditable=false`。

## 4. 兼容与数据

| 面 | 行为 |
| :--- | :--- |
| 旧卷（无 `content_markdown`） | 编辑器为空 + 预览表照常显示服务端题项；发布按旧规则（只校验总分 > 0，目标为空） |
| 旧卷遗留 `section_criteria` | 列/接口保留，界面不再创建；重建会删掉本卷的行（确认框里给出条数与金额） |
| 参考范文 `reference_markdown` | 列、解析、复核页展示都保留，向导不再写 |
| 粘贴答案表 `POST /answers` | 接口保留，界面入口随第 3 步与 `/papers/import` 移除 |
| `/papers/import` | 路由删除，旧书签落 404 |
| `localStorage` | 换键 `teachloop.paperDraft.v2`；旧形状（五步、sections）直接忽略，不会把页面渲染崩 |
| 已发布卷 | 生成与第 3 步禁用；服务端 409；meta 仍可改并写审计 |

## 5. 偏差与理由

1. **已发布卷生成：一律 409**（`confirm=true` 也拒绝）。理由：重建会整体替换题项/题号，
   而快照与判分都按题号引用；plan §20.2 的状态机与 AC-4.24③ 写的就是「禁止」。
   `confirm` 仍被接收并写进审计 `paper:content_replaced`，但不解锁。
2. **发布失败改为 `400 + ApiError{code,message,detail}`**。理由：原先抛 `ApiException` 走全局处理器，
   拿不到「缺哪些题号」；AC-4.26③ 要求 API 直调也被拦并能定位。成功仍是 `200 + PaperView`。
3. **文件上传写入 `content_markdown` 的是「实际进解析器的文本」**（.md 的标记已被剥、docx/xlsx 是抽取段落拼接）。
   理由：保证「回填编辑器 → 再生成」得到同一批题项；`.md/.txt` 在前端就直接读进编辑器，不走上传路径。
4. **`scripts/ui-baseline.txt` 没有更新**：本次改动让 `PaperWizardPage` 比基线更干净
   （内联样式少 9 处、手写 btn 少 16 处、raw table 少 3 处），但工作区里另一条并行分支正在改 `web/`，
   现在重写基线会把它的中间态一起冻进去。留给下次单独 `--write-baseline`。

## 6. 复现步骤（本地）

```bash
# 1) 起本地栈（Postgres + 后端 8010 + 前端 5173；--stub 只是判分引擎，不影响本页）
./scripts/dev.sh --no-open --stub

# 2) 浏览器打开，用开发账号登录
#    teacher@teachloop.local / teachloop123
# 3) /papers → 新建试卷
#    - 第一步：标题随便写，目标总分填 150
#    - 第二步：选择 teachloop-paper/src/test/resources/exam/english-grade10-2026-with-answers.md
#      → 点「生成考题」→ 应看到 77 题 / 合计 150 分 / 缺答案 0
#    - 第三步：两道写作题 tab，各填一份 rubric（合计 15）并保存
#    - 第四步：目标 150 = 实际 150 → 发布（发布后内容锁定）
```

## 7. 文件清单

新增（本报告相关）：
`docs/reports/paper-authoring-2026-10-25/README.md`、`docs/reports/mockup-parity/03-paper-flow.md`、
`docs/reports/paper-authoring-2026-10-25/shots/*`、
`docs/decisions/021-paper-single-source-authoring.md`、`docs/research/2026-10-25-paper-authoring-single-source.md`、
`docs/mock/paper-flow-2026-10-25/*`、
`teachloop-paper/src/main/resources/db/migration/V065__paper_meta_and_content.sql`、
`teachloop-paper/src/main/java/com/teachloop/paper/domain/PaperContentSnapshot{,Repository}.java`、
`teachloop-paper/src/main/java/com/teachloop/paper/importing/PaperHeaderMeta.java`、
`teachloop-paper/src/test/java/com/teachloop/paper/{api/PaperGenerateEndpointTest,api/PaperMetaAndPublishEndpointTest,importing/PaperHeaderMetaTest}.java`、
`web/src/teacher/paperFlow.ts`、`web/tests/paper-flow.test.mjs`。

修改：`PaperEndpoint.java`、`Paper.java`、`messages{,_zh_CN}.properties`、`PapersPage.tsx`、`App.tsx`、
`PaperWizardPage.tsx`、`api/{types,client}.ts`、`locales/{zh-CN,en}.json`、`teacher.css`、
`specs/002-v2-platform/{spec,plan,tasks}.md`、`docs/{PROGRESS,DELIVERY-STATUS}.md`、`scripts/check-boundaries.py`。

删除：`web/src/teacher/ImportPaperPage.tsx`。

## 8. 还没做 / 已知风险

- **标题与时长创建后不可改**：`PUT /meta` 按 AC-4.26④ 只改三列，页面沿用「创建后只读」并提示原因。
  要支持改名需另立需求（后端加 title/duration 到 meta）。
- **重建会覆盖第 3 步手填的 rubric**：确认框已明示；要长期保存请写进原文（解析器会把写作任务里「带分值的要点行」
  带回题项 rubric）；重建前有快照，误删可走运维 SQL 恢复（v1 无撤销按钮）。
- **目标总分与合计不一致会拦发布**：这是需求（AC-4.26②），不是 bug；老师要么改分值、要么改目标。
- **快照只有 3 条、无界面恢复入口**：按 AC-4.24⑥ 明确不做「一键撤销」，避免假装可撤销。
- **批量改题型 / 粘贴答案表的接口还在**：本次只删界面；是否删接口另议。
- **向导里已不再使用的旧文案键**（批量改题型、手工建部分/题项、导入页那一组之外仍有少量）暂留在 locale：
  本轮有并行的 vocab-lists 分支同时在改同一批文件，等合并后统一清理，避免互相覆盖。
- **本地验证环境还开着**：`./scripts/dev.sh --no-open --stub` 起的后端 8010 / 前端 5173 仍在跑，
  可以直接打开看；不用了在起它的终端 Ctrl-C（会自动停后端与前端，DB 容器保留；`docker stop teachloop-pg` 停库）。
  开发库里留了一张 E2E 试卷「E2E 高一英语月考」（77 题 / 150 分 / 已发布），需要时可在 `/papers` 归档或删库重来。
- **`section_criteria` 仍是总分的一部分**：旧卷遗留金额会显示在预览发布页，重建会清掉。
