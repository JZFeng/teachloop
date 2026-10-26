# AI provider 故障转移 · 实测报告（2026-10-25）

对应：决策 022、任务 T483。代码提交 `a5e068e` + 本轮新增的集成测试。

## 一、三个 provider 真实连通性

同一台机器、同一条命令、同一个小请求（`max_tokens=32`，内容 "Say OK only."）：

| provider | base URL | 模型 | 结果 | 延迟 | 备注 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat`（响应回 `deepseek-flash`） | ✅ OK | **0.89s** | 直接可用 |
| opencode go | `https://opencode.ai/inference/go/openai/v1` | `deepseek-v4.1-flash` | ✅ OK | **0.93s** | **必须**带 `User-Agent` 与 `x-opencode-session` |
| 本地（车库） | `http://192.168.68.101:8080/v1` | `qwen3.8-flash-next-iq2_xs` | ✅ OK | 0.98s | 无需 UA |

### 两个必须记住的坑（都实测复现）

1. **Cloudflare 1010**：不带自定义 `User-Agent`（例如 python urllib 默认值）调 opencode 直接
   `HTTP 403 error code: 1010`。带 `teachloop/1.0` 或 `Java-http-client/21.0` 都能过。
   —— 与 `teachloop-vocab/src/main/resources/vocab/content-prompt-v2.5.json` 的 notes 一致。
2. **`x-opencode-session` 是必需的**：缺失返回 `400 {"type":"MissingSessionID", ...}`，
   即「网关无法路由」，不是鉴权问题。**判分链路目前不发这个头**（见下文缺陷）。

## 二、切换与回切实测（`AiFailoverIntegrationTest`）

真实 Postgres（Testcontainers），provider 顺序 `opencode-go(10) → deepseek(20) → local(30)`，
失败来源是真实的 400：

```
[WARN] AI provider 故障转移：opencode-go -> deepseek（原因 circuit_open）
[INFO] AI provider 自动回切：deepseek -> opencode-go（连续成功 3 份）
Tests run: 1, Failures: 0, Errors: 0
```

断言覆盖：

- 第 1–4 份失败不打开熔断，第 5 份打开；
- 打开后 `ai_settings.active_provider_id` 指向 **deepseek**（不是 local）；
- `active_model` 被清空（换 provider 后沿用旧模型名会 400）；
- `failover_from_provider_key=opencode-go`、`failover_reason=circuit_open`；
- `audit_logs` 里 `ai:provider_failover` 恰好 1 条；
- opencode 熔断恢复 + deepseek 连续成功 3 份 → 回切 opencode，写 `ai:provider_failback` 1 条。

## 三、缺陷（上线阻塞级）

**opencode go 现在不能作为判分 provider 使用。** 判分客户端（`AiGradingConfig` 的
`LlmClientProvider`）只发 `Authorization`，不发 `x-opencode-session`，所以每份判分都会
`400 MissingSessionID` → 不可重试（决策 013-B）→ 5 份后熔断 → 切到 deepseek。

后果不是「崩」，而是更隐蔽的：**主 provider 永远处于熔断状态，套餐买了用不上，每场作业的前 5 份
稳定失败**。修法有两种，需拍板：

1. 给 provider 配置加一个「额外请求头」字段（JSONB），opencode 填 `{"x-opencode-session": "$uuid"}`，
   客户端按请求生成 UUID（与词库路径一致）；
2. 或者只在 `AiGradingConfig` 里硬编码：当 base URL 含 `opencode.ai` 时自动加该头。

## 四、安全

`docs/mock/ai-model-2026-10-23/index.html:197` 里**已经硬编码了本地模型的 API Key**（历史提交，
不是本轮引入）。建议：从 mock 里删掉并把该 key 轮换；本轮提供的 DeepSeek / opencode key 未写入任何文件。

## 五、复现命令

```bash
# 单测（不起容器）
./mvnw -q -B -o -pl teachloop-app -am -Dtest=AiProviderFailoverTest -Dsurefire.failIfNoSpecifiedTests=false test

# 集成（真实 Postgres，验证迁移与切换/回切）
./mvnw -B -o -pl teachloop-app -am -Dtest=AiFailoverIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```
