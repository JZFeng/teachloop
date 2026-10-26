# 决策 013 — AI 调用重试、熔断与失败通知

日期：2026-10-24　状态：生效　相关：D-30、US-62、FR-116–FR-121

## 问题

用户问：「如果 AI 调用失败，现在怎么处理？会 retry 吗？如果 retry 失败，但配了多个 AI，怎么办？」

代码盘点（2026-10-24）的现状：

- 只有「输出不合规」会重试 1 次（`AiGradingEngine.MAX_ATTEMPTS=2`，带纠正提示）；
- 网络失败、超时、401、429、5xx **一次都不重试**，直接抛 `LlmCallException`；
- 失败即 `submissions.grading_state='FAILED'`，没有任何自动恢复，只能老师手动点「重试失败项」；
- 唯一的自动回收是 RUNNING 卡死（5 分钟阈值退回 PENDING，最多 3 次），FAILED 不在此列；
- 多个 provider 只是「可手动切换的备选」，没有 fallback 链，`AiSettingsService.resolve()` 只返回当前 active 那条；
- 失败没有通知，站内通知只在复核发布时发。

## 用户拍板

| 议题 | 决定 | 理由 |
| :--- | :--- | :--- |
| A. 自动 fallback 链 | **不做**。v1 保持 D-30「全站一个当前模型」<br>**⚠️ 2026-10-25 已被决策 022 取代**：改为「熔断打开时按 priority 整站切下一个 provider」。原理由（口径不一致、静默换模型）由 022 的 D3（每份记录判分者）与 D4（切换可见）承接 | 同一批混用两种模型会让分数口径不一致；失败要让人知道，而不是静默换个模型继续烧钱 |
| B. 调用层退避重试 | **做**：429 / 5xx / 连接失败重试 2 次（2s、8s + 抖动）；400/401/403/404 与读超时不重试 | 瞬断和限流值得吸收；鉴权/参数错误重发也不会成功 |
| C. 失败后 30 秒自动重派 | **不做固定 30 秒**；改成「熔断恢复后把熔断期间的失败项自动重派一次」 | 固定定时会在 provider 持续故障时空转；跟着熔断恢复走更准 |
| D. 熔断 + 通知 | **做**：同一 provider 连续 5 份失败即打开；5 分钟冷却后半开 1 份；通知受影响教师与全部管理员 | provider 整体挂掉时止损并让人知道，避免几十份挨个白等 |
| E. 一键「换模型并重试」 | **不做** | 与现有「切模型 + 重试失败项」两步操作重复 |

B 的重试口径里，读超时（单次 180 秒）**不内联重试**：一次 180 秒已经占满 worker 一轮，
再重试会把整批拖死；这种「对端挂起」交给 D 的熔断处理。

## 决策

### A. 重试（调用层）

- 可重试：HTTP 429、500、502、503、504；连接失败（拒绝连接 / DNS 失败 / 连接超时）。
- 不可重试：400、401、403、404；读超时；中断；空响应（空响应重发通常同样为空）。
- 次数与退避：最多 2 次重试（共 3 次尝试），退避 2 秒、8 秒，加 ±20% 抖动；
  429 带 `Retry-After` 且 ≤ 30 秒时优先使用它。
- 与「输出不合规」的纠正重试**分开计数**（后者仍为 1 次）。
- 实现为 `RetryingLlmClient`（`LlmClient` 装饰器），grading 与 explanation 共用；
  `LlmCallException` 增 `kind` 枚举作为分类依据，不再靠 message 字符串匹配。
- 每次重试写 WARN 日志（provider、model、第几次、kind、耗时）；失败尝试不写 `ai_calls`
  （该表是成功调用的留痕：tokens / latency / content）。

### B. 熔断

- 键：当前 provider 的 `provider_key`（全站一个，跟随 `ai_settings.active_provider_id`）。
- 计数单位是**份**不是**次调用**：一份提交最终 FAILED 记 1 次；任意一份成功即清零。
- 触发：同一 provider 连续 5 份失败 → `OPEN`。
- `OPEN` 期间 worker 停止领取新的 PENDING；已在跑的份跑完；老师此时点「重试失败项」会看到
  「AI 已熔断，预计 X 分钟恢复」，而不是无声排队。
- 恢复：`OPEN` 满 5 分钟后进入 `HALF_OPEN`，**只允许 1 份**试探（多进程用条件更新保证唯一）；
  成功 → `CLOSED` 并清零；失败 → 重新 `OPEN` 并重新计时；试探进程崩溃超过 5 分钟 → 退回 `OPEN`。
- 状态存库（`ai_circuit_state`），app 与 worker 两个进程共享；读不到状态时**放行**并记 ERROR
  （可用性优先，不能因为保护机制把批改全停）。

### C. 恢复后自动重派

- 熔断关闭时，把「这一串连续失败的第一份」到关闭时刻之间失败的提交退回 `PENDING`；
- 每份累计最多自动重派 1 次（新列 `submissions.grading_circuit_requeues`），避免
  「失败 → 恢复 → 重派 → 再失败」的循环；
- 老师的「重试失败项」是另一条路径，不计入这个上限。

### D. 通知

- 打开时：给「这一串失败里涉及的作业创建者」和全部管理员各发 1 条站内通知
  （provider、最后错误类别、连续失败份数、预计探测时间）；同一轮只发一次。
- 关闭时：给管理员发 1 条「已恢复，自动重派 N 份」。
- 通知写失败只记 WARN，不影响批改。

## 数据

| 表 / 列 | 属主 | 内容 |
| :--- | :--- | :--- |
| 新表 `ai_circuit_state` | identity | `provider_key` 主键、`state`（CLOSED/OPEN/HALF_OPEN）、`consecutive_failures`、`streak_started_at`、`opened_at`、`half_open_started_at`、`last_error_kind`、`last_failure_at`、`updated_at` |
| `submissions.grading_failed_at` | sitting | 失败时刻，自动重派的窗口依据 |
| `submissions.grading_circuit_requeues` | sitting | 自动重派次数，默认 0，上限 1 |

迁移：`V050__identity_ai_circuit_state.sql`、`V051__sitting_grading_circuit.sql`
（V049 已被 vocab 占用，版本号全局唯一）。

## 端口

- 新增 `Ports.AiCircuit`：`acquire()`（是否允许领取、允许几份）、`reportSuccess()`、
  `reportFailure()`、`snapshot()`；identity 实现，grading 只依赖端口。
- `Ports.NotificationPort` 增 `notifyAdmins(NotificationMessage)`（identity 解析 ADMIN 收件人）。
- `Ports.SittingLedger` 增 `teacherIdsWithFailedGradingSince(Instant)`（通知与重派范围用）。
- `scripts/check-boundaries.py` 的 `REQUIRED_PORTS` 从 7 个增加到 8 个。

## 配置

| 键 | 默认 | 说明 |
| :--- | :--- | :--- |
| `teachloop.ai.retry.max-attempts` | 2 | 调用层最多重试次数 |
| `teachloop.ai.retry.backoff-ms` | `2000,8000` | 每次重试前的等待 |
| `teachloop.ai.retry.jitter` | 0.2 | 抖动比例 |
| `teachloop.ai.retry.retry-after-max-seconds` | 30 | 429 的 Retry-After 上限 |
| `teachloop.ai.circuit.failure-threshold` | 5 | 连续失败多少份打开熔断 |
| `teachloop.ai.circuit.open-ms` | 300000 | 冷却时间（5 分钟） |
| `teachloop.ai.circuit.probe-timeout-ms` | 300000 | 半开试探的存活上限 |

## 失败模式

| 情况 | 行为 |
| :--- | :--- |
| 熔断状态表读不到 | 放行批改并记 ERROR（可用性优先） |
| 通知写失败 | WARN，不影响批改 |
| 自动重派失败 | ERROR，失败项保持 FAILED，老师仍可手动重试 |
| 半开试探进程崩溃 | 超过 5 分钟后 `acquire()` 把它退回 OPEN |
| 切换 provider | 熔断按 `provider_key` 隔离；新 provider 从 CLOSED 开始 |
| 429 / 5xx / 连接失败 | 退避重试 2 次，仍失败则该份 FAILED |
| 401 / 400 / 读超时 | 不重试，直接 FAILED |

## 不做 / 后续

- 不做自动 fallback（保持 D-30）；v2 若要做，先改 D-30 的口径。
- 不做固定 30 秒重派；不做「一键换模型并重试」。
- 失败尝试不写 `ai_calls`；将来若要统计失败率，再给该表加 `outcome` 列。
- 熔断粒度为「整个 provider」，不做按作业 / 按教师的隔离。
