# 决策 023 — AI 每日调用预算护栏

日期：2026-10-25　状态：**生效，待实现**　相关：决策 013、D-31、T484、`docs/release/launch-runbook.md` §9

## 问题

用户 2026-10-25 认同「没有花费护栏是很好的担心」。代码盘点：

- **词库生成有护栏**：`teachloop.vocab.generation.daily-budget`（`VOCAB_GENERATION_DAILY_BUDGET=2000`，按 Asia/Shanghai 自然日计已建 job 数）。
- **判分没有护栏**：`GradingWorker` 领到就调 AI，没有每日上限、没有单场上限、没有花费统计。

在 1000 人 / 包月套餐的组合下，这意味着：
1. 套餐额度耗尽 → 401/402/403 → 按决策 013-B 不重试 → 批量 FAILED → 熔断（现有行为）；
2. 一次 bug 造成的重复派发（或教师误触「重试失败项」很多次）可以在几分钟内把一个月额度打光，而**没有任何人会在中途知道**；
3. 没有「今天花了多少」这个数，无法回答「这个月够不够」。

## 决策

### D1 · 计数口径

新增 `ai_usage_daily`：按 `(usage_date, provider_key, model, purpose)` 聚合 `calls / input_tokens / output_tokens`（`usage_date` 用 Asia/Shanghai 自然日，与词库预算一致；`purpose` 取 `grading | explanation | vocab`）。

token 数取自 provider 响应里的 `usage`；provider 不返回 usage 时只累加 `calls`，并标记 `usage_missing=true`（**不估算 token，宁可缺数据也不写假数**）。

### D2 · 限额与超限行为

| 项 | 配置 | 默认 | 超限行为 |
| :--- | :--- | :--- | :--- |
| 每日总调用上限 | `TEACHLOOP_AI_DAILY_CALL_BUDGET` | `0`（不限，v1 上线首周保持 0，观测一周后再设） | 停止**自动**派发（新提交保持 PENDING，队列不丢），通知全部管理员 |
| 预警阈值 | `TEACHLOOP_AI_DAILY_WARN_PERCENT` | `80` | 达到阈值时通知管理员一次（同日不重复） |
| 单场作业调用上限 | `teachloop.grading.max-calls-per-offering` | `0`（不限） | 该场剩余份转人工（`graded_provider=manual`），通知教师 |

超限**不**降级到本地模型（决策 022 的 failover 只在「provider 不可用」时触发，不等同于「今天额度用完了」——后者是管理员的成本决定，不该由代码替他换质量更低的模型）。

### D3 · 管理员可见与放行

- `/admin/ai` 显示：今日调用数 / 上限 / 剩余 / 各 purpose 分布 / 是否处于停派状态。
- 「今日放行」按钮：把当日上限临时提高（写审计 `ai.budget.override`，带操作人与新上限），**不修改配置**。

### D4 · 与词库预算的关系

词库预算（`VOCAB_GENERATION_DAILY_BUDGET`）继续独立生效，同时计入 `ai_usage_daily` 的 `vocab` purpose，使「今天总共调了多少次 AI」只有一个数。两个闸门都开时取先触发的那个。

## 不做的

- **按金额计费**：不同 provider 单价不同且会变，按「调用次数」计量比按「估算金额」诚实；金额只在报表里按配置的单价乘出来展示。
- **自动购买/自动充值**：不做。
- **超限后静默转本地模型**：不做（见 D2）。

## 影响与代价

- 1000 人规模的用量估算：每份判分 prompt ≈ 2.5K、输出 ≈ 0.4K token；每周 1 次作业时月用量约 11M input / 1.6M output，按公开牌价是几十元量级——**护栏的作用是防疯跑，不是省钱**。
- 实现成本：1 个迁移 + 计数写入点（`RetryingLlmClient` 或调用层统一拦截）+ 一个显示面板 + 停派开关，约 1.5–2 天（含测试）。
