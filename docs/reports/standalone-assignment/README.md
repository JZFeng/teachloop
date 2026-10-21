# D-14 独立作业端到端验收（2026-10-20）

本目录是「作业与考试在数据层分开（D-14 严格版）」的浏览器实测证据。
环境：本机 dev 栈，Postgres 16（`teachloop-pg`，55432）+ 后端 8010 + 前端 5173；判分引擎为 stub。

## 实测链路

1. 教师登录 → 左侧「作业」→「新建作业」。
2. 单页表单填写：标题、题目要求、总分 20、评分标准、勾选「演示班」、截止 2026-11-30 22:00 → 发布。
   - 全程没有出现试卷、部分、题号、标准答案、时长、计时模式、按班覆盖。
   - 截图：`01-new-assignment.png`、`02-filled-assignment.png`。
3. 教师端跳转到批改详情：`已完成 0 / 待处理 1 / 未提交 29`，作业无时长。
   截图：`03-teacher-grading-detail.png`。
4. 学生 01 登录 → 首页出现「D-14 浏览器验收作业」→ 打开后看到题目要求与评分标准 → 开始作答 → 提交。
   截图：`04-student-assignment.png`、`05-student-submitted.png`。
5. 教师「立即派发」→ worker 判分：`DONE`，客观 0、主观 15/20，复核队列 `PENDING`。
6. 复核通过并发布（通过浏览器会话调用 `POST /api/submissions/{id}/review` 与 `/review/publish`；
   复核工作台的列表接口暂未按 `offeringId` 过滤，故未在 UI 里逐条定位该份）。
7. 学生「我的反馈」：总分 15/20，客观 0，主观 15，评语可见。
   截图：`06-student-feedback.png`。

## 数据层断言（dev 库）

```sql
select kind, count(*), count(paper_id) from offerings group by kind;
-- ASSIGNMENT | 13 | 0     （含本次新建；存量 13 条已在 V026 回填为 paper_id=null）
-- EXAM       |  1 | 1

select conname, pg_get_constraintdef(oid) from pg_constraint
 where conname = 'offerings_kind_content_ck';
-- CHECK ((((kind)::text = 'ASSIGNMENT'::text) = (paper_id IS NULL)))
```

本次新建作业的快照：

```text
kind=ASSIGNMENT  paper_id=NULL  paper_version=0  duration_minutes=NULL
items=1  questionType=ESSAY  points=20  stem=题目要求
```

## 验证命令与结果

- `make test` → 退出码 0；surefire 汇总 `tests=173, failures=0, errors=0, skipped=0`；
  `check-i18n` / `check-boundaries` / `check-deps` 均通过。
- `cd web && npx tsc --noEmit && pnpm run build` → 退出码 0。
- 迁移在**有存量数据的 dev 库**上真实执行：首次 `make seed` 暴露了 V026 顺序错误
  （先 `update ... paper_id = null` 再 `drop not null` 会撞 not-null 约束），已改为
  「先 drop not null → 回填 → 加 check」；重跑成功，13 条存量作业回填、1 场考试不受影响。

## 已知限制（未在本轮修复）

- 存量作业（V026 回填的 13 条）快照里可能仍带客观题项；学生作业页没有客观题作答控件，
  这是既有缺口，只影响历史数据；新作业按单条 ESSAY 生成，不受影响。
- AI prompt 仍不包含题目要求（只发 rubric + 指标 + 正文）；独立作业的评分标准需要自带「要写什么」，
  等有 API Key 能重跑 golden-set 基线时再单独立项。
- 复核工作台未把 URL 上的 `offeringId` 传给 `/api/grading/queue`，152 条待复核时无法从「进入复核」直接定位本场；
  本轮用浏览器会话的 API 完成复核/发布验证。
