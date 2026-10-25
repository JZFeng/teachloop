# 管理员 AI 设置：默认模型切换 + provider→模型 文案（2026-10-24）

## 需求（用户原话）

1. 删掉「一行一个 provider；教师会在 /profile 的下拉里看到它们。」——已经无效（教师页没有 AI 入口了）。
2. 一个 provider 的时候，缺省就是它；多个的时候多加一个 button「Default Provider」，让管理员自由切换。
3. 页面上的 provider 中文叫「模型」，英文叫「Model」。

## 改动

### 前端 `web/src/app/AdminAiPage.tsx`

| 点 | 之前 | 现在 |
| :--- | :--- | :--- |
| 列表副标题 | 「一行一个 provider；教师会在 /profile 的下拉里看到它们。」 | 删除（`app.ai.providersHint` 键一并删除） |
| 默认标识 | 只有每个模型行的「当前使用」 | provider 卡标题旁加「默认」badge（`p.active`） |
| 切换默认 | 只能点某个模型的「设为当前」 | provider 多于一（`providers.length > 1`）且不是默认时，操作区出现「设为默认」按钮 |
| 文案 | provider / Provider | 模型 / Model（添加模型、编辑模型、已配置的模型、模型 ID、保存模型、删除模型…） |

只有一个 provider 时它天然是默认（后端创建第一个 provider 就设为 active），所以不显示「设为默认」按钮，只显示「默认」badge。

### 后端 `AiProviderEndpoint` / `AiProviderService`

- 新增 `PUT /api/admin/ai-providers/{id}/default`（管理员；教师/学生 403），返回刷新后的 provider 列表。
- `setDefault` 只切 `ai_settings.active_provider_id`，不要求管理员再挑模型：当前模型属于该 provider 就保留，否则用它的默认模型（`model` 列 / 模型列表第一个）。全局只有一个「当前模型」槽位，所以从 A 切到 B 再切回 A 时，A 会用回自己的默认模型（测试里锁定了这个语义）。
- 报错文案同步：`error.ai.adminOnly` / `providerId` / `providerExists` / `providerNotFound` / `noProvider` 里的 provider 改成模型（中英）。

### 文案

`web/src/locales/{zh-CN,en}.json`：删除 `app.ai.providersHint`，新增 `defaultBadge`（默认 / Default）、`setDefault`（设为默认 / Set as default）、`defaultSet`（已把 {{name}} 设为默认模型。/ {{name}} is now the default model.）；`app.ai` 里所有 provider 措辞改成模型/Model，并顺手修掉仍写着「教师下拉里看到」的过时提示（教师页已无 AI 入口）。

## 验证

| 项 | 结果 |
| :--- | :--- |
| `AiSetupTest` | 1 / 1：第二个 provider 不抢默认；`PUT .../default` 200 且返回 2 条；`/api/ai-models` 的 `currentProviderId/currentModel` 跟着变；切回原 provider 用它的默认模型；同一 provider 已选模型再点「设为默认」不被重置；教师/学生 403 |
| `cd web && pnpm typecheck` / `pnpm test` | 0；38 / 38 |
| `check-i18n` / `check-ui` | 0（zh/en 1532 键完全对齐；无新增中文硬编码、无新增 UI 违规） |
| QA 5174 浏览器验收 | 见下 |

### QA 5174 浏览器验收

环境：`make qa` 重新部署快照（版本 `bd6f70b+dirty`，后端 8011 / 前端 5174，QA 库 `teachloop_qa` 原有 2 个 provider）。
账号：为本次验收在 QA 库临时建了一个管理员（`verify-admin@teachloop.local`），验完已删除，`ai_settings.updated_by` 已改回原管理员。

| 步骤 | 证据 | 结果 |
| :--- | :--- | :--- |
| 打开 `/admin/ai` | `01-list-with-default.png` | 副标题已消失；标题「已配置的模型」；默认 provider 显示「默认」badge，另一个显示「设为默认」 |
| 点 deepseek 的「设为默认」 | `02-switched-default.png` | 提示「已把 deepseek 设为默认模型。」；badge 移到 deepseek；顶部当前模型变 `deepseek · deepseek-flash`；qwen 卡出现「设为默认」 |
| 再切回 qwen3.8-flash-next | 页面文本 | 提示「已把 qwen3.8-flash-next 设为默认模型。」；当前模型回到 `qwen3.8-flash-next · qwen3.8-flash-next-iq2_xs`（原状），只剩一个「设为默认」按钮 |
| 「添加模型」表单 | 页面文本 | 模型 ID / 显示名称 / 可用模型 / 保存模型 / 取消，均为模型口径 |

## 没验证 / 已知限制

- 英文界面没目视（只做了 zh/en 键对齐 + typecheck）；英文文案见 locale 文件。
- 「只有一个 provider」的界面形态没造数据验证（要删掉 QA 的 provider 才能看到）；逻辑就是 `providers.length > 1` 才出按钮，单 provider 时第一个 provider 自动 active，已有 `AiSetupTest` 的首个 provider `active=true` 断言兜底。
- 切默认 provider 会重置到该 provider 的默认模型（见上），如果以后要「记住每个 provider 上次选的模型」，需要给 `ai_providers` 加一列，属于另一个决定。
