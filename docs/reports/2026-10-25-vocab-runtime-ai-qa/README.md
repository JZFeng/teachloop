# 运行时 AI 生成在 QA 跑通（2026-10-25）

对应 T439 / AC-60.12。记录 QA 环境切换 provider 后两条路径的实测结果，以及两个只有真跑才会暴露的配置坑。

## 1. 结论

- **内容包命中**：`prepare` 7.6ms 返回，`ready=1 / pending=0`，`content_id=2587`（包区间 1..N），**0 个生成任务**。
- **包外未命中 → AI**：`prepare` 立即 `pending=1`，**9 秒后端到端 READY**（含 worker ≤2s 轮询 + 模型 ~6s），`content_id=1000000000+N`（delta 区间），delta 行 `prompt_version=vocab-content-v2.3`、`source=AI`。
- 生成内容为 v2 结构：6 条例句、`popularity` 1..6、zh 翻译齐全、词族/构词/音标正常；`GET /api/vocab/words/by-form/<word>` 回读为前端 v1 视图（`senses/examples/analysis/family/phonetic`）。

## 2. QA 配置（本次改动）

| 位置 | 值 |
| :--- | :--- |
| `ai_providers` | `provider_key=opencode-go`、`base_url=https://opencode.ai/inference/go/openai/v1`、`model=deepseek-v4.1-flash`、`api_key_cipher` 用 `teachloop.security.secret` 派生密钥 AES-GCM 加密 |
| `ai_settings` | **`active_model=deepseek-v4.1-flash`**（关键，见坑 1） |
| 进程环境 | `VOCAB_CONTENT_STORE_PATH=$ENV_DIR/content-delta.db`、`VOCAB_CONTENT_PACK_DIR=$ROOT/fixtures-local/vocab-content`（见坑 2，已写进 `scripts/env.sh`） |

## 3. 两个坑（只有真跑才暴露）

**坑 1：只改 `ai_providers` 不改 `ai_settings.active_model` → 400 `Model is unavailable`。**
`AiSettingsLookup.current()` 返回的模型名来自 `ai_settings.active_model`（选中的模型快照），provider 行里的 `model` 只是该 provider 的默认值。
本次先手改了 provider 行，结果网关收到的是旧的 `qwen3.8-flash-next-iq2_xs`，连报两次 400，worker 记 `attempts=1/3`、`2/3`。
**正确做法**：走管理端 `PUT /api/admin/ai-providers/{id}` + `PUT /{id}/default`（会同时更新两处），不要手改单表。

**坑 2：部署脚本没把 delta 路径传进去 → QA 把内容写进仓库 `./data/`。**
`cmd_up` 只传了 DB / 存储 / 端口，`teachloop.vocab.content.store-path` 落回默认 `./data/vocab-content-delta.db`（相对启动目录）。
表现为 `content_id` 有效但 `.envs/qa/content-delta.db` 里查不到行，而且不同环境会共用同一个 delta 文件。
已修：`scripts/env.sh` 启动时显式传 `VOCAB_CONTENT_STORE_PATH` 与 `VOCAB_CONTENT_PACK_DIR`。

## 4. 验证命令（可复现）

```bash
# 登录测试学生
curl -sS -c /tmp/c.txt -X POST http://127.0.0.1:8011/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"student01@teachloop.local","password":"teachloop123"}'

# 包内词（struggle）：期望 ready=1 pending=0
curl -sS -b /tmp/c.txt -X POST http://127.0.0.1:8011/api/vocab/review/prepare \
  -H 'Content-Type: application/json' -d '{"scope":"WORD","wordId":"<id>"}'

# 包外词（quixotic）：先 pending=1，轮询至 ready=1（实测 9s）
curl -sS -b /tmp/c.txt -X POST http://127.0.0.1:8011/api/vocab/review/prepare \
  -H 'Content-Type: application/json' -d '{"scope":"WORD","wordId":"<id>"}'

# 回读内容
curl -sS -b /tmp/c.txt http://127.0.0.1:8011/api/vocab/words/by-form/quixotic
```

## 5. 遗留

- 后台「AI 设置」里的 `temperature` 只对**批改**生效（`AiGradingConfig` 读 `db.temperature()`）；词内容生成固定 0.2（契约），建议在设置页加一行说明，或后续把该字段与词内容解耦说明。
- `GET /api/vocab/words/{id}`（不存在的路由）返回 500 而不是 405：`GlobalExceptionHandler` 的兜底把 `HttpRequestMethodNotSupportedException` 当未知异常。属低优先级 DX 问题。
- QA 里有两条测试词（`serendipity`、`quixotic`）留在 student01 词库，作为本次验收证据。
