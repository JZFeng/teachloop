# 实施计划 — 实验开关独立服务（008-flags-service）

- 上游：`specs/008-flags-service/spec.md`
- 状态：**草稿（2026-10-24）**
- 用户 2026-10-24 定的节奏：**先做这个服务的骨架**，TeachLoop 侧的角色矩阵在其后

---

## 1. 骨架范围（第一批交付，可独立验收）

做完这一批，服务能独立跑起来并被 curl 调通，TeachLoop 完全不用改。

| 项 | 内容 |
| :--- | :--- |
| Maven 模块 | `teachloop-flags-service`（新增，独立 `main`） |
| Spring Boot | 3.x，与现有模块同版本 |
| 数据表 | `flags_invite`、`flags_enrollment`（服务自己的 schema） |
| 端点 | 5 个：发邀请、兑换、查订阅、退出、查邀请 |
| 认证 | 服务间 Bearer 密钥过滤器 |
| 运维 | `/healthz`、结构化日志、独立 `Dockerfile` |
| 测试 | 单测 + MockMvc 集成测试，覆盖 spec 的 4 个失败分支 |

**不在骨架内**：TeachLoop 的 `FeatureClient`、`/api/features` 合并、前端确认页与资料页块。这些是第二批，见 §8。

## 2. 包结构

```
teachloop-flags-service/
  src/main/java/com/teachloop/flags/
    FlagsServiceApplication.java
    api/        InviteEndpoint, EnrollmentEndpoint, HealthEndpoint
    domain/     Invite, Enrollment, InviteRepository, EnrollmentRepository
    security/   ServiceTokenFilter
    config/     ClockConfig（可注入时钟，测试过期用）
```

**零依赖原则**：不 import 任何 `teachloop-*` 业务模块的类。它必须能被单独打包、单独部署、单独带走。

## 3. 数据模型

```sql
create table flags_invite (
  id             uuid primary key,
  token_hash     char(64)     not null,
  subject_id     varchar(120) not null,
  flag_key       varchar(80)  not null,
  channel        varchar(64),
  created_by     varchar(120) not null,
  expires_at     timestamptz  not null,
  used_at        timestamptz,
  created_at     timestamptz  not null,
  constraint uq_invite_token unique (token_hash)
);
create index idx_invite_subject on flags_invite (subject_id);
create index idx_invite_flag_exp on flags_invite (flag_key, expires_at);

create table flags_enrollment (
  id                uuid primary key,
  subject_id        varchar(120) not null,
  flag_key          varchar(80)  not null,
  state             varchar(16)  not null,   -- OPTED_IN | OPTED_OUT
  source_invite_id  uuid,
  opted_in_at       timestamptz,
  opted_out_at      timestamptz,
  created_at        timestamptz  not null,
  updated_at        timestamptz  not null,
  constraint uq_enrollment unique (subject_id, flag_key)
);
```

**为什么枚举用 varchar**：这个服务将来可能被别的项目复用，加状态不该要求改表类型；约束写在应用层。

**状态迁移**：`（无行）→ OPTED_IN → OPTED_OUT → OPTED_IN`，永不删行。

## 4. API 契约

| 方法 | 路径 | 入参 | 成功 | 失败 |
| :--- | :--- | :--- | :--- | :--- |
| POST | `/v1/invites` | `{subjectId, flagKey, channel?, ttlDays?}` | 201 `{id, token, expiresAt}` | 400 参数缺失 |
| POST | `/v1/enroll` | `{subjectId, token}` | 200 `{flagKey, state:"OPTED_IN"}` | 403 非本人 / 410 过期 / 409 已消费且当前为 OPTED_OUT |
| GET | `/v1/enrollments` | `?subjectId=` | 200 `{"flag_a":true}` | 400 |
| POST | `/v1/opt-out` | `{subjectId, flagKey}` | 204 | 400 |
| GET | `/v1/invites/{id}` | — | 200 `{..., usedAt, channel}` | 404 |
| GET | `/healthz` | — | 200 `{status:"UP"}` | 503 数据库不可用 |

`token` 明文只在 201 响应里出现一次；入库前 `sha256`。

## 5. 并发与幂等

- 兑换用**条件更新**：`update flags_invite set used_at = now() where token_hash = ? and used_at is null`，影响行数为 0 说明已被消费 → 走 AC-64.3 的幂等/冲突分支。不用「先查后写」，避免竞态。
- `flags_enrollment` 的 `unique (subject_id, flag_key)` 是最后一道兜底：并发兑换同一 flag 时第二个请求转为更新。

## 6. 测试策略

| 测试 | 覆盖 |
| :--- | :--- |
| `InviteTokenTest` | 熵足够、库里只有哈希、明文不再出现 |
| `EnrollFlowTest` | 本人成功、非本人 403、过期 410、重复兑换 200 幂等、OPTED_OUT 后再兑换 409、opt-out 后重新 opt-in |
| `EnrollmentQueryTest` | 只返回显式开启项；无记录返回空对象 |
| `AuthFilterTest` | 无密钥 401、错误密钥 401、正确密钥放行 |
| `HealthTest` | 数据库正常 200、断连 503 |

时间相关全部走注入的 `Clock`，不 `sleep`。

## 7. Plan review 6 条清单

**完整性**

1. **spec 每条 AC 都有承接** —— ✅ AC-64.1–64.7 → §3 表 + §4 端点；AC-65.1–65.3 → §3 + 审计；AC-66.1–66.3 → §2「零依赖」+ 包结构；AC-67.1 → `/healthz`；**AC-67.2 / 67.3 属 TeachLoop 侧（第二批），骨架阶段不承接**。
2. **每个实体的表、字段、索引、状态迁移写全** —— ✅ §3。
3. **外部依赖的失败路径** —— ✅ 服务自身只依赖数据库：DB 挂 → `/healthz` 503、写端点 500；调用方按 AC-67.2 回落。无 AI / 对象存储 / 队列依赖。

**结构**

4. **模块依赖无环** —— ✅ 新模块不依赖任何 `teachloop-*`；`teachloop-app` 也不依赖它（通过 HTTP 而非 jar）。
5. **每张表只有一个属主** —— ✅ 两张表都属本服务，其他项目只能通过 API 访问。
6. **拆服务路径** —— ✅ 它本身即独立服务；将来被别的项目复用，只需换数据库连接与密钥。

**结论：5 条打勾，第 1 条因 AC-67.2/67.3 明确切片到第二批而不在本批承接 → 骨架范围内可定稿。**

## 8. 分批与依赖

| 批次 | 内容 | 前置 |
| :--- | :--- | :--- |
| 1 | 本服务骨架（§1） | 无 |
| 2 | TeachLoop `FeatureClient` + `/api/features` 合并 + 降级 | 批次 1 可调用 |
| 3 | 前端确认页 + 资料页实验块 | 批次 2 |
| — | 角色矩阵（`007-role-nav`） | 与本服务**无依赖**，可并行 |

## 9. 风险

| 风险 | 缓解 |
| :--- | :--- |
| 服务被误暴露到公网 | 只监听内网地址；密钥必填；部署清单里写明不映射公网端口 |
| `subject_id` 被业务方塞进邮箱等 PII | 契约写死为不透明 ID；集成测试断言只存传入值 |
| 复制粘贴 TeachLoop 的用户表 | §2 零依赖原则；code review 断言新模块无 `teachloop-*` import |
