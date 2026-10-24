# 决策 010 — 全局 AI provider、教师选模型与批改时机

日期：2026-10-23（同日按可点 mock 修订）　状态：生效　相关：D-16、D-22、决策 003、US-45

## 问题

用户提出四条修改：

1. AI 模型不能写死，要允许配置本地 LLM；试卷涉及隐私，多数时候用本地模型，本地模型通常没有 API Key。
2. AI 配置只属于教师，学生不需要看到。
3. `profile` 要区分教师与学生，有些设置只有教师能改。
4. 作业的 AI 批改从「截止时间」改成「固定时间」是决策 003 做的；固定时间要可配置，教师可以切回 due date。

第一版实现把 **provider ID / 地址 / Key / 拉模型** 全放进了教师 `/profile`，信息层级错了。
用户要求先出可点 mock；确认后的 v1 口径是：**管理员维护 provider 列表，教师在下拉里选一个；
选中的模型对全站生效**。v2 才考虑把模型挂到考试或作业级别。

可点 mock：`docs/mock/ai-model-2026-10-23/index.html`。

## 决策

### A. 全局 provider 与当前模型

| 项 | 决定 |
| :--- | :--- |
| 表 | 新增 `ai_providers`：`id`、`provider_key`（Provider ID）、`display_name`、`base_url`、`protocol`、`api_key_cipher`、`model`（兼容列，第一个模型）、`models`（JSON 数组，可多个）、`created_at`、`updated_at`、`updated_by` |
| 当前模型 | `ai_settings.active_provider_id` + `active_model` 指向正在使用的 provider 和模型；v1 只有这一份全局选择 |
| 谁能配 | 只有 ADMIN：`/admin/ai` 新增/编辑/删除 provider，`POST /models` 拉 `GET {baseUrl}/models`，`POST /test` 测试连接 |
| 谁能选 | 教师与管理员：`GET /api/ai-models` 返回下拉选项，`PUT /api/ai-models/current` 切换当前 provider；**教师切换后全站生效** |
| Key | 可空；本地 provider 不需要 Key；只存密文、接口只回掩码；留空表示不修改已保存的 Key |
| 协议 | v1 只实现 OpenAI 兼容（DeepSeek / Ollama / LM Studio / vLLM）；`anthropic` 字段保留但界面置灰、后端拒绝 |
| 模型 | 一个 provider 可以保存多个模型；「获取可用模型」后以勾选列表展示，可勾多个/手填；失败时仍可手填 |
| 旧数据 | `user_ai_settings` 保留但不再读取；旧 `ai_settings` 四字段保留为没有 active provider 时的兼容回退 |

### B. 角色化资料页

| 项 | 决定 |
| :--- | :--- |
| 路由 | 不新增路由；`/profile` 按 `me.role` 渲染 |
| 学生 | 只看到账号资料（姓名/手机/邮箱）与界面偏好（语言/主题）；不请求 AI 接口 |
| 教师 | 账号资料 + 界面偏好 + **AI 模型下拉** + 作业批改时机；不填地址/Key/协议 |
| 管理员 | `/admin/ai` 维护 provider 列表；`/profile` 的 AI 卡片可切换当前模型 |

### C. 批改触发偏好（教师级）

| 项 | 决定 |
| :--- | :--- |
| 适用范围 | `kind = ASSIGNMENT` 且 `status = PUBLISHED`；考试仍按 due+grace，不变 |
| 挂载层级 | **教师级**，作用于该教师的所有作业；单份/整场「立即派发」仍是不受限制的例外 |
| 模式 | `FIXED_TIME`（默认）与 `DUE_DATE` |
| 固定时间 | 默认 **02:30 Asia/Shanghai**；教师可改时刻与时区；按教师本地日期每天最多跑一次；`catch_up` 控制错过后是否补跑 |
| 截止时间 | 复用 `due_at + grace_hours`；60 秒扫描派发；不看任何时段（峰谷调度已于 2026-10-24 整体撤除，见决策 014） |
| 调度 | 每分钟扫描教师偏好；固定时间到达后派发该教师的作业；due-date 按到期派发 |
| 幂等 | 派发仍用 `offerings.grading_dispatched_at` 条件更新；固定时间用 `last_fixed_run_at` 防止每分钟重复派发 |

## 数据

新增 `teachloop-identity` 迁移 `V039__identity_ai_providers.sql`：

```sql
create table ai_providers (
  id uuid primary key,
  provider_key varchar(80) not null unique,
  display_name varchar(100) not null,
  base_url varchar(300) not null,
  protocol varchar(20) not null default 'openai',
  api_key_cipher text,
  model varchar(100) not null,
  models jsonb not null,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  updated_by uuid
);
alter table ai_settings add column active_provider_id uuid references ai_providers (id) on delete set null;
-- V060：多模型
alter table ai_providers add column models jsonb;
update ai_providers set models = jsonb_build_array(model) where models is null;
alter table ai_providers alter column models set not null;
alter table ai_settings add column active_model varchar(100);
```

`V037__sitting_teacher_grading_preferences.sql` 保持不变；`V060__identity_ai_provider_models.sql` 增加多模型列与 `active_model`。`user_ai_settings` 与 `V038` 保留兼容，不再读取。

## 代价与对策

| 代价 | 对策 |
| :--- | :--- |
| 任何教师切换会改变所有人的模型 | 这是用户确认的 v1 口径；下拉旁明确写「对全站生效」；v2 再做按作业/考试覆盖 |
| 本地模型从教师电脑不可达 | provider 地址从**后端服务器**发起连接；设置页写明 localhost 指后端所在机器 |
| 一个 provider 可保存多个模型，但全站只启用一个 | 教师下拉按 provider+model 展开；v1 不按作业/考试覆盖 |
| 旧 `user_ai_settings` 数据变成死数据 | 保留表与迁移，不删数据；评审时注明不再读取 |
| 白天固定时间批改按原价计费 | 这是教师自己的选择；默认 02:30 仍是夜间折扣时段 |

## 影响面

- `teachloop-identity`：`ai_providers` 表/实体/仓库；`AiProviderService` 与管理员端点；`AiModelEndpoint`
  教师选择；`AiSettingsService` 只解析 active provider；`user_ai_settings` 停用；
- `teachloop-grading`：`LlmClient` 仍从 `Ports.AiSettingsLookup.resolve()` 取全局配置；空 Key 可调用；
- `teachloop-sitting`：教师级偏好表、调度器、按教师派发（同前）；
- `web`：`AdminAiPage` provider 列表 + 表单；`ProfilePage` 只保留模型下拉；`Shell` 无模型提示；
- `specs/002-v2-platform`：D-30、US-45、AC-45、FR-087/088、plan §5/§6.6、tasks T380–T385。
