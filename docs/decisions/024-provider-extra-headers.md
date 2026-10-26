# 决策 024 — provider 级额外请求头

日期：2026-10-25　状态：生效　相关：决策 022、T482、T483

## 问题

2026-10-25 实测：opencode go 作为**判分** provider 时每份都返回
`400 {"type":"MissingSessionID"}` —— 它的网关要求请求带 `x-opencode-session`（一个请求级 UUID），
而判分客户端（`AiGradingConfig` 里的 `LlmClientProvider`）只发 `Authorization`。

这个头此前只在**词库生成**路径实现（`content-prompt-v2.5.json` 的 `sessionHeader`），
判分链路没有。后果不是崩，而是隐蔽：主 provider 永远熔断、套餐白买、每场作业前 5 份稳定失败
（决策 022 的故障转移会把流量接到备用，掩盖问题）。

## 决策

**把请求头做成 provider 配置项，不写死特例。**

| 项 | 决定 |
| :--- | :--- |
| 存储 | `ai_providers.extra_headers jsonb`（V067），值为字符串到字符串的对象 |
| 占位符 | 值为 `$uuid` 时，每次解析配置生成一个新的 UUID（网关路由头只要求「每次不同」） |
| 生效范围 | 判分、错题解析、词库生成共用的 `Ports.AiSettings.extraHeaders` |
| 客户端 | `DeepSeekLlmClient`（http 客户端）发送；**带额外头的 provider 自动走 http 客户端**，因为 Spring AI 链路无法注入任意头 |
| 不允许覆盖 | `Authorization`、`Content-Type` 以及 JDK 受限头（Host/Content-Length）由客户端忽略，避免配置把调用搞挂 |
| 校验 | 保存时校验 JSON 对象，非法返回 400 `error.ai.extraHeadersInvalid`；现有 provider 升级后为空，不影响行为 |
| 配置写坏 | 解析失败只记 warn 并当作无额外头，不阻断判分 |

## 验证

- `DeepSeekLlmClientHeadersTest` 4/4：本地 HTTP 服务器断言头真的发出；`Authorization` 不能被覆盖；
  无额外头时行为不变；**带 `TEACHLOOP_OPENCODE_KEY` 时真实调用 opencode go 返回非空内容**（2026-10-25 实跑通过）。
- 迁移 V067 在 Postgres 16 上随全量迁移执行。

## 未做

- 前端还没有编辑这个字段的入口（随 T483 的 `/admin/ai` 一起补）。
- 不做「按 provider 自动推断头」——宁可配置，也不要在代码里塞 `opencode.ai` 这种特例。
