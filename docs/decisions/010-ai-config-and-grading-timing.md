# 决策 010 — AI 模型自由配置、教师设置页与批改时机

日期：2026-10-23　状态：生效　相关：D-16、D-22、决策 003、US-27

## 问题

用户提出四条修改：

1. AI 模型不能写死，要允许教师自由配置；试卷涉及隐私，多数时候用本地 LLM（Ollama / LM Studio 等），
   本地模型通常没有 API Key，硬件要求也不高。
2. `/profile` 上的 AI 配置应只属于教师，学生不需要看到这些设置。
3. `profile` 要区分教师与学生，有些设置只有教师能改。
4. 作业的 AI 批改从「截止时间（due date）」改成「固定时间」是决策 003 做的；固定时间要可配置，
   默认仍是现在硬编码的固定时间，但教师可以切回 due date。

## 决策

### A. AI 模型配置

| 项 | 决定 |
| :--- | :--- |
| 可用条件 | `baseUrl` 与 `model` 都非空即可用；**API Key 可空**，空 Key 不发 `Authorization` 头 |
| Provider | 只作预设与文案，不限制实际端点；预设：DeepSeek / Ollama / LM Studio / vLLM / 自定义 OpenAI 兼容 |
| 模型名 | 自由文本；另提供「从 `/v1/models` 加载」按钮，拉不到时仍可手填 |
| 默认值 | 不再在代码里把 `deepseek-flash` 当唯一默认；新配置默认空白，由教师选择预设或填写 |
| 归属 | 教师个人配置优先，缺失回退管理员全局配置；两者都不可用时显式失败，不造假分 |
| 测试连接 | 从**后端服务器**发起；空 Key 也发请求；本地模型地址填 `localhost` 时提示它指的是后端所在机器 |
| 安全 | Key 仍只存密文、只回掩码；设置页提示「试卷正文会发送到这个地址」 |

### B. 教师设置页

| 项 | 决定 |
| :--- | :--- |
| 路由 | 不新增路由；`/profile` 按 `me.role` 渲染 |
| 学生 | 只看到账号资料（姓名/手机/邮箱）与界面偏好（语言/主题）；不请求 AI 接口 |
| 教师 | 账号资料 + 界面偏好 + **AI 与批改设置**（AI 端点、模型、Key、测试；批改触发方式与时刻） |
| 管理员 | 保留 `/admin/ai` 全局配置；`/profile` 的 AI 卡片对管理员可见，批改时机只对教师可见 |

### C. 批改触发偏好（教师级）

| 项 | 决定 |
| :--- | :--- |
| 适用范围 | `kind = ASSIGNMENT` 且 `status = PUBLISHED`；考试仍由 `findDueForGrading` 按 due+grace 派发，不变 |
| 挂载层级 | **教师级**，作用于该教师的所有作业；单份/整场「立即派发」仍是不受限制的例外 |
| 模式 | `FIXED_TIME`（默认）与 `DUE_DATE` |
| 固定时间 | 默认 **02:30 Asia/Shanghai**；教师可改时刻与时区；按教师本地日期每天最多跑一次；`catch_up` 控制错过后是否补跑 |
| 截止时间 | 复用 `due_at + grace_hours`，沿用 `GradingDispatchPolicy` 的峰谷限制；迟到提交继续补进同一 run |
| 调度 | 每分钟扫描一次教师偏好；固定时间模式在到达时刻后派发该教师的作业；due-date 模式按到期派发 |
| 幂等 | 派发仍用 `offerings.grading_dispatched_at` 条件更新；固定时间用 `last_fixed_run_at` 防止每分钟重复派发 |

## 数据

新增 `teachloop-sitting` 迁移：

```sql
create table teacher_grading_preferences (
  teacher_id uuid primary key,
  trigger_mode varchar(20) not null default 'FIXED_TIME',
  fixed_time time not null default '02:30',
  time_zone varchar(60) not null default 'Asia/Shanghai',
  catch_up boolean not null default true,
  last_fixed_run_at timestamptz,
  updated_at timestamptz not null,
  updated_by uuid
);
```

不改 `offerings`：教师级偏好已经足够，单条作业的例外用现有「立即派发」。

## 代价与对策

| 代价 | 对策 |
| :--- | :--- |
| 每分钟扫描所有教师偏好，数据库压力 | 只查有 QUEUED 作业的教师 + 有偏好的教师；固定时间用 `last_fixed_run_at` 短路 |
| 空 Key 不再被当成「未配置」，云端配置漏填 Key 会在调用时 401 | 设置页明确提示「云端服务通常需要 Key；本地模型通常不需要」；测试连接给出 401 提示 |
| 教师把时刻设在白天，云端 API 按原价计费 | 设置页写明默认 02:30 是夜间折扣时段；这是教师自己的选择 |
| 多实例同时扫描可能重复派发 | 派发本身按 offering 条件更新幂等；`last_fixed_run_at` 允许重复写，最坏多入队一次，不产生重复分数 |
| 本地模型从教师电脑不可达 | 设置页写明「测试连接从后端发起」；支持任意 base URL，隧道/内网由教师自行保证 |

## 影响面

- `teachloop-identity`：`AiSettingsService` 的 `configured` 语义、空 Key、模型发现、setup 不再强制 Key；
- `teachloop-grading`：`DeepSeekLlmClient` / `SpringAiLlmClient` 空 Key 可调用；
- `teachloop-sitting`：新增偏好表、实体、端点、每分钟调度器；`OfferingGradingDispatcher` 增按教师派发；
- `web`：`ProfilePage` 按角色分栏、AI 与批改设置、学生页精简；`AdminAiPage` / `SetupPage` 去掉强制 Key；
- `specs/002-v2-platform`：D-16 / A-10 / A-11 / AC-27.1 / AC-27.3 与新增 AC、FR、tasks。
