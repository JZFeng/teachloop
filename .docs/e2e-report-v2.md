# E2E 记录（T170/T171）

日期：2026-10-20　环境：本地后端 + Vite，stub 判分引擎（真实 AI 另见 `make test-ai` 记录）

## 旅程 A — 整份试卷（考试型）

按 plan §18.4 清单实际执行/复用证据：

| # | 清单项 | 实际执行 |
| :--- | :--- | :--- |
| 1 | 教师登录并补全资料 | 已用 DevProvider 登录；T034 资料补全改由 `ProfileCompletionTest` 覆盖（prod 语义），本地开发未强制 |
| 2 | 建 3 个班 + 邀请码 | **已按 3 个班执行**：E2E-A/B/C，各 1 名学生加入 |
| 3 | 77 题整卷 | **已按 77 题执行**：76 道单选 + 第 77 题写作，总分 86；浏览器目录树显示 1–77（`.docs/screenshots/e2e-77-exam.png`） |
| 4 | 布置 3 班 + duration_override | **已执行**：3 班挂同一场；A 班 duration_override=45 分钟、due_override 更早；浏览器计时显示 44:55，证明按班覆盖生效 |
| 5 | 学生入班→须知→开始→作答→提交 | 浏览器实测：3 名学生入班；student01 打开须知并开始，目录树 77 题；提交 3 题（含写作 77）成功 |
| 6 | 目录树/剩余时间/到点自动交卷 | 浏览器实测 1–77 目录树、44:55 计时；自动交卷由 `Phase2ExamTimingTest` 覆盖 |
| 7 | 派发与进度 | 实机派发：progress 从排队到 `{total:1,done:1}`；`GradingDispatchTest` 7 例覆盖 |
| 8 | 复核：通过/改分/驳回 | 实机：AI 主观草稿 7.5，复核通过并发布；`ReviewWorkflowTest` 2 例覆盖改分/驳回/批量 |
| 9 | 发布→总分→错题解析→评语→班级概览 | 浏览器实测反馈页：总分 9.5/86（客观 2 + 写作 7.5）、评语「E2E 通过」、班级概览（`.docs/screenshots/e2e-77-feedback.png`）；解析生成/复用由 `Explanation*Test` 覆盖 |
| 10 | 导出 MD/PDF/XLSX/DOCX | 实机 200：summary.md 1134B、summary.xlsx 5428B、summary.docx 2996B、feedback.zip 523B、feedback.pdf 1824B；`ExportTest` 8 例覆盖内容 |

## 旅程 B — 纯写作（作业型）+ new attempt

| # | 清单项 | 实际执行 |
| :--- | :--- | :--- |
| 1 | 建作业型（仅主观、无倒计时）→ 发布 | 已实测：E2E 纯写作作业（1 道 20 分写作），发布到 E2E-B 作业班 |
| 2 | 富文本提交 → 新 attempt | 浏览器实测：切到「Rich text」，输入粗体 + 列表（`<strong>` + `<ul><li>`）并提交；页面出现「Start another attempt」，点击后第二次提交成功。API 显示 `attemptsUsed=2`，第 1 次 `active=false`、第 2 次 `active=true`，历史保留。截图 `e2e-journey-b-attempts.png` |
| 3 | 过 due 后仍可交、标 late、教师拒收 | 已实测：另建 due=2026-10-01 的作业，提交返回 `lateBySeconds=1662389`；教师拒收理由「迟交且内容不完整」，学生 `my-submissions` 可见 `rejectedAt` 与理由 |
| 4 | 派发→复核→发布 | 派发后 progress `{total:2,done:2}`；但第 2 次提交未生成复核记录（`my-feedback.review=null`，最新 attempt 分数 0），与「富文本提交应进入批改/复核」预期不符，**T171 仍为部分完成** |
| 5 | 批改最新 attempt、历史保留 | 学生 `my-feedback.latestAttempt.attemptNo=2`，第 1 次 attempt 仍在 `attempts` 列表中（active=false） |

## 结论与偏差（不隐瞒）

- 旅程 A 已按「3 个班 + 77 题」在本地完整重放（新增班级 E2E-A/B/C、77 题整卷、按班时长覆盖、提交、派发、复核发布、反馈、四类导出）。**T170 可宣称通过**。
- 旅程 B 已用浏览器重放富文本提交与新 attempt，并实测迟交/拒收；但第 2 次提交后未生成复核记录（最新 attempt 0 分），这是待查缺陷，**T171 不宣称完整通过**。
- T173（四模型段落计数 parity）依赖 T072，目前 T072 未实现，**阻塞**。
- T174（50 份 / 2 app + 2 worker 压测）未执行；当前 compose 是 1 app + 1 worker，尚无 2+2 编排与压测脚本。
- T175（golden set 人工基线）需要 10 份已人工评分的作文；仓库内没有该语料，**未开始**。
