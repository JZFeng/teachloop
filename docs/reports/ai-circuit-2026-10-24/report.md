# AI 失败保护（D-31 / US-62）验收报告

日期：2026-10-24　范围：T414–T419（重试、熔断、通知、自动重派、状态接口与横幅）

## 结论

代码完成，自动化测试与浏览器验收都做了。**全量 `make test` 退出码 0（6m47s）**；
熔断横幅在真实页面上出现与消失都验过。

## 自动化证据

| 测试 | 结果 |
| :--- | :--- |
| `RetryingLlmClientTest` | 11 / 11 |
| `GradingWorkerCircuitTest` | 5 / 5 |
| `AiGradingConfigTest`（改为校验重试装饰器） | 5 / 5 |
| `AiCircuitTest`（连续 5 份打开、半开只放 1 份、胜负恢复、试探超时） | 5 / 5 |
| `make test` | 退出码 0，6m47s |

`make test` 内含：`check-deps`、`check-boundaries`（48 表 / 8 端口）、`check-i18n`（1552 键、新增中文 0）、
`check-ui`（无新增违规）、`web-test`、`vocab-lexicon-test`、`mvn test`。
前端 `tsc --noEmit` 与 `pnpm build` 通过。

## 浏览器验收

环境：QA 快照前端 `http://127.0.0.1:5174`（同一份构建），临时后端 `8013` 指向 dev 库（`TEACHLOOP_GRADING_ENGINE=stub`）。
教师 `teacher@teachloop.local` 登录后打开「批改总览」。

| 状态 | 证据 | 结果 |
| :--- | :--- | :--- |
| CLOSED | `grading-closed.png` | 页面正常，无横幅 |
| OPEN（5 份 / SERVER） | `grading-open.png` | 横幅：「AI 批改已暂停（熔断）：qwen3.8-flash-next / 连续 5 份失败，最后错误：SERVER。预计 2026/10/24 12:19 自动试探一份，成功即恢复。」 |
| 恢复 CLOSED | `grading-recovered.png` | 刷新后横幅消失 |

说明：OPEN 是直接在库里写入熔断状态触发的（不是真实跑 5 份失败）；状态机与 worker
上报路径由 `AiCircuitTest` / `GradingWorkerCircuitTest` 覆盖。

## 已知限制

- 熔断通知文案固定按简体中文解析（消息包里同时有英文）；没有按收件人语言分发。
- 没做真实 AI 故障下的端到端（需要 provider 连续 5 次失败）；半开的多进程唯一性靠条件更新保证，
  单进程集成测试覆盖，未做双进程并发实测。
- 批改详情页也引用了同一个横幅组件，但只在总览页做了截图验证。

## 环境备注

本次与另一条并行改动共享工作区（AI 多模型 / 通知 payload / 学生反馈）。全量测试通过时两边改动都在树里。
QA 库此前被乱序迁移（`060` 先于 `050/051/052` 应用），已用 `SPRING_FLYWAY_OUT_OF_ORDER=true` 修复，
8011/5174 已恢复；QA 熔断状态已重置为 CLOSED。
