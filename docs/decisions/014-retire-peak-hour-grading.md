# 决策 014 — 撤除峰谷调度

日期：2026-10-24　状态：生效　相关：T093（作废）、决策 003、决策 010（D-30）、US-06 / US-20 / US-45

## 问题

T093 按 DeepSeek 的分时计价实现了「工作日 9:00–12:00、14:00–18:00 不自动派发，推迟到空闲起点」：
`GradingPeakHours` 计算时段，`GradingDispatchPolicy` 读 `teachloop.grading.peak-aware`（默认 true），
`OfferingGradingDispatcher.autoDispatch()` 在高峰直接返回 0。

用户 2026-10-24 指出三条：

1. **峰谷只是 DeepSeek 一家的计价口径。** 决策 010 之后 AI provider 可换（OpenAI 兼容 / 本地模型），
   把一家的分时计价写进调度器，换 provider 就不成立。
2. **「要不要避开高峰」是管理员的成本判断，不是业务规则。** 管理员想省钱，可以在教师级批改时机里
   选夜间固定时间（默认 02:30），或换更便宜的 provider；不需要代码替人作决定。
3. **决策 010 已经把作业从峰谷里摘出去**，峰谷只剩考试一条路径，不值得为此长期维护两个类。

## 决策

**整体删除峰谷调度。到期即派，不看一天中的时段。**

| 项 | 决定 |
| :--- | :--- |
| 删除代码 | `GradingPeakHours`、`GradingDispatchPolicy`；`OfferingGradingDispatcher.autoDispatch()` 的高峰前置判断；`application.yml` 的 `teachloop.grading.peak-aware` |
| 考试自动派发 | `GradingDispatchJob` 每分钟扫 `due_at + grace_hours <= now` 且未派发的场次，命中即派 |
| 迟到补派发 | 同一分钟照常把已派发场次的 `QUEUED` 推成 `PENDING`；旧实现里高峰会连这一句一起跳过，是 T093 的缺陷 |
| 作业 | 不变：教师级固定时间 / due-date（决策 010） |
| 手动派发 | 不变：任何时刻可用 |
| 成本控制 | 交给管理员：教师级批改时机选夜间固定时间，或换 provider。代码不再感知时段 |
| 测试 | 删 3 个峰谷用例，新增 1 个「工作日 09:00 / 14:00 也立即派发」 |

## 代价与对策

| 代价 | 对策 |
| :--- | :--- |
| 白天到期的考试按原价批 | 这是管理员的选择；考试有时限，老师当场等结果，按时出分本来就优先于省几分钱 |
| 失去「统一推迟」的全局开关 | 需要夜间批的作业用固定时间模式（决策 010）；考试若要延迟批，应改为按场次配置，而不是继续猜 provider 的计价时段 |

## 影响面

- `teachloop-sitting`：删 `GradingPeakHours`、`GradingDispatchPolicy`；`OfferingGradingDispatcher`
  构造器去掉 `GradingDispatchPolicy` 参数；`TeacherGradingScheduler` / `TriggerMode` /
  `OfferingEndpoint` / `OfferingRepository` 去掉峰谷措辞。
- `teachloop-app`：`application.yml` 删除 `peak-aware`；`GradingDispatchTest` 删 3 个峰谷用例、
  加 1 个「不看时段」用例（`autoDispatchIgnoresTimeOfDay`）。
- 文档：`plan.md` §7.4 改为已撤除、§18.1 / §18.3 去掉峰谷；`spec.md` AC-45.5 / FR-090 / D-30
  去掉「峰谷只保留给考试」并新增 D-32；`tasks.md` T093 标作废；决策 003 / 010 各加指向本决策的说明；
  `PROGRESS.md` 同步。

## 验证

- `GradingDispatchTest` 8/8（含 `autoDispatchIgnoresTimeOfDay`：09:00 与 14:00 两个旧高峰起点都立即派发）。
- `NightlyAssignmentGradingTest` 1/1、`FlowIntegrationTest` 3/3。
- 全仓 `grep -rn "peak\|峰谷" teachloop-*/src` 只剩解释性注释，没有代码与配置引用。
