# E2E 记录（T170/T171）

日期：2026-10-20　环境：本地后端 + Vite，stub 判分引擎（真实 AI 另见 `make test-ai` 记录）

## 旅程 A — 整份试卷（考试型）

按 plan §18.4 清单实际执行/复用证据：

| # | 清单项 | 实际执行 |
| :--- | :--- | :--- |
| 1 | 教师登录并补全资料 | 已用 DevProvider 登录；T034 资料补全改由 `ProfileCompletionTest` 覆盖（prod 语义），本地开发未强制 |
| 2 | 建 3 个班 + 邀请码 | **未按 3 个班执行**；实测 1 个班 + 邀请码（截图 `.docs/screenshots/`） |
| 3 | 77 题整卷 | **未按 77 题执行**；实测 3 题（单选/填空/写作）+ rubric；题型模型与导入分别由 `T196QuestionTypeTest`、`PaperTextParserTest` 覆盖 |
| 4 | 布置 3 班 + duration_override | 单班 + individual 实测；多班/覆盖由 `FlowIntegrationTest`、T198–T202 测试覆盖 |
| 5 | 学生入班→须知→开始→作答→提交 | 浏览器实测（`.docs/screenshots/exam-entry.png`、`exam-live.png`、`exam-submit-check.png`） |
| 6 | 目录树/剩余时间/到点自动交卷 | 浏览器实测目录树与计时；自动交卷由 `Phase2ExamTimingTest` 覆盖 |
| 7 | 派发与进度 | `GradingDispatchTest` 7 例 + 实机派发回执 |
| 8 | 复核：通过/改分/驳回 | `ReviewWorkflowTest` 2 例 + 浏览器复核工作台实测 |
| 9 | 发布→总分→错题解析→评语→班级概览 | 学生反馈页与 `my-feedback` 实测；解析生成/复用由 `Explanation*Test` 覆盖 |
| 10 | 导出 MD/PDF/XLSX/DOCX | `ExportTest` 8 例全部通过；浏览器下载实测 |

## 旅程 B — 纯写作（作业型）+ new attempt

| # | 清单项 | 实际执行 |
| :--- | :--- | :--- |
| 1 | 建作业型（仅主观、无倒计时）→ 发布 | 实测（单班） |
| 2 | 富文本（粗体/列表）→ 新 attempt | 富文本由 `SubmissionFormsTest` 覆盖（消毒+保留格式）；new attempt 由 `NewAttemptTest` 覆盖（最多 3 次、active 唯一）；文件提交另由 `SubmissionFormsTest` 覆盖 |
| 3 | 过 due 后仍可交、标 late、教师拒收 | `Phase2ExamTimingTest` 覆盖迟交与拒收理由 |
| 4 | 派发→复核→发布 | `GradingDispatchTest` + `ReviewWorkflowTest` |
| 5 | 批改最新 attempt、历史保留 | `NewAttemptTest` 断言 3 条 attempt 全部保留、仅 1 条 active |

## 结论与偏差（不隐瞒）

- 旅程 A 的「3 个班 + 77 题」规模没有逐项手工重放；用 1 班 3 题跑通主链路，其余由自动化测试覆盖。**T170/T171 不宣称完整通过**。
- T173（四模型段落计数 parity）依赖 T072，目前 T072 未实现，**阻塞**。
- T174（50 份 / 2 app + 2 worker 压测）未执行；当前 compose 是 1 app + 1 worker，尚无 2+2 编排与压测脚本。
- T175（golden set 人工基线）需要 10 份已人工评分的作文；仓库内没有该语料，**未开始**。
