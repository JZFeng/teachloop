# 实施计划 — 注册与身份通道（009-registration）

- 上游：`specs/009-registration/spec.md`（2026-10-27 定稿，5 个 US / 36 条 AC / 18 条 FR）
- 上游调研：`docs/research/2026-10-27-registration-channels.md`
- 决策：`docs/decisions/028-registration-channels.md`、`029-test-accounts.md`、`030-password-change.md`
- 状态：**草稿（2026-10-27），待 review（6 条清单见 §11）**
- 迁移起点：现有最高迁移 `V071`，本计划占 `V072`–`V074`

---

## 1. 交付分批

| 批 | 内容 | 能否独立验收 |
| :--- | :--- | :--- |
| **批 1**（后端核心） | 3 张表 + `users` 改动 + 通道端口 + 邮件驱动 + 注册/激活/登录/忘记密码端点 + 频控 | ✅ 可用 curl 走完「注册→激活→登录→改密」 |
| **批 2**（管理端） | `identity_channels` 配置端点 + 管理页（开关/驱动/发件人/密钥/配额） | ✅ 改配置后下一次请求生效 |
| **批 3**（前端） | 注册 / 激活确认 / 登录（密码+验证码）/ 忘记密码 四屏 + i18n | ✅ 浏览器端到端 |
| **批 4**（可选） | 短信驱动、微信两驱动 | 依赖资质，代码先在但默认关 |

批 1 与批 3 之间可以先上线「只能管理员建号」的现状继续跑，不阻塞。

## 2. 数据模型

**属主一律是 `teachloop-identity`**（与 `users` 同模块，无跨模块 JOIN）。

### 2.1 `users` 改动（V072）

| 变更 | 说明 |
| :--- | :--- |
| `status` 增加枚举值 **`PENDING`** | 只加值不改列；`UserStatus` 枚举加一项 |
| `password_login_enabled` | **保留列，退出判断逻辑**（AC-73.11 之后所有 `password_hash != null` 的 ACTIVE 账号都可密码登录）；留作降级开关，v2 再删 |
| 不新增姓名列 | 用户拍板：姓名就一个 `display_name` |

### 2.2 `verification_codes`（V073）

| 列 | 类型 | 说明 |
| :--- | :--- | :--- |
| `id` | uuid PK | |
| `purpose` | varchar(24) | `REGISTER_ACTIVATE` / `LOGIN` / `PASSWORD_RESET` / `CONTACT_CHANGE` |
| `channel` | varchar(16) | `email` / `sms` |
| `target` | varchar(255) | 邮箱或手机号（明文，用于唯一性与频控查询；不含密钥） |
| `code_hash` | varchar(64) | 激活令牌与 6 位码都在这里，只存哈希 |
| `expires_at` | timestamptz | 激活 24h；验证码 10min |
| `consumed_at` | timestamptz null | 一次消费 |
| `attempts` | int default 0 | 错误次数上限 |
| `created_at` | timestamptz | |

索引：`(target, purpose, created_at desc)`、`(expires_at)`（清理用）。

### 2.3 `identity_channels`（V073）

| 列 | 类型 | 说明 |
| :--- | :--- | :--- |
| `channel` | varchar(16) PK | `email` / `sms` / `wechat` |
| `enabled` | boolean default false | 用户可见开关 |
| `driver` | varchar(32) | `smtp` / `http-api` / `aliyun-sms` / `wechat_web` / `wechat_mp` |
| `from_address` | varchar(255) null | 发件人 / 短信签名 |
| `base_url` | varchar(300) null | SMTP host 或网关端点 |
| `secret_cipher` | text null | `SecretBox` 密文（SMTP 授权码 / API Key / 微信 secret） |
| `extra` | jsonb | 模板 ID、端口、region 等驱动私有字段 |
| `daily_quota` | int null | 单通道每日发送上限 |
| `updated_at` / `updated_by` | | 审计 |

初始数据：`email` 一行（`enabled=false`，等管理员配好再开）、`sms`、`wechat` 各一行（`enabled=false`）。

### 2.4 `user_identities`（V074）

| 列 | 类型 | 说明 |
| :--- | :--- | :--- |
| `id` | uuid PK | |
| `user_id` | uuid FK → users | |
| `channel` | varchar(16) | `email` / `phone` / `wechat` |
| `external_id` | varchar(128) | 邮箱 / 手机号 / unionid 或 openid |
| `external_app` | varchar(32) null | 微信应用标识（`web` / `mp` / `legacy`） |
| `verified_at` | timestamptz | 验证时间决定能否用于登录 |
| `created_at` / `last_used_at` | | |

唯一索引：`(channel, external_id)`。回填迁移把 `users.wechat_openid` 非空行搬进来（`channel='wechat'`, `external_app='legacy'`），旧列保留一个版本周期只读。

## 3. 模块与端口

```
teachloop-identity
  auth/
    MessageChannel.java           ← 端口（2026-10-28 由 VerificationChannel 改名）：channel() / configured() / send(target, subject, body)
    CodeService.java              ← 生成、哈希、消费、错误计数
    ActivationService.java        ← 令牌签发/校验（与 CodeService 共用表）
    PasswordLoginProvider.java    ← 取代 PasswordAuthProvider 的白名单判断
    channel/
      SmtpEmailChannel.java
      HttpApiEmailChannel.java
      AliyunSmsChannel.java
      WeChatWebChannel.java
      WeChatMpChannel.java
    ChannelRegistry.java          ← 读 identity_channels，按 enabled+configured 决定可用集合
```

**依赖方向**：`MessageChannel`（原 `VerificationChannel`）与驱动全部留在 identity 内，**不进 `teachloop-shared`**——它们只服务认证，shared 不应认识「验证码」这个概念。`shared` 只加一个 `ErrorCode.ACCOUNT_PENDING`。

## 4. API 契约

| 方法 | 路径 | 说明 |
| :--- | :--- | :--- |
| GET | `/api/auth/channels` | 公开；只返回 enabled 且 configured 的通道 |
| POST | `/api/auth/register` | `{channel,email,password,displayName?}` → 202 |
| GET | `/api/auth/activate?token=` | **只渲染确认页**（返回 200 + 邮箱掩码），无副作用 |
| POST | `/api/auth/activate` | `{token}` → ACTIVE，返回 200 |
| POST | `/api/auth/activate/resend` | `{email}` → 202 / 429 |
| POST | `/api/auth/login` | `{email,password}` → MeView（PENDING 403） |
| POST | `/api/auth/login/challenge` | `{channel,email}` → 202 / 404 / 403 |
| POST | `/api/auth/login/verify` | `{challengeId,code}` → MeView |
| POST | `/api/auth/password-reset/challenge` | `{channel,email}` → 202 |
| POST | `/api/auth/password-reset/verify` | `{challengeId,code,newPassword}` → 200 |
| GET/PUT | `/api/admin/identity-channels` | 仅 ADMIN；密钥只回掩码 |

## 5. 配置与密钥

- 密钥用现有 `SecretBox`（AES-GCM，密钥来自 `TEACHLOOP_SECURITY_SECRET`）；
- 解密失败 → 该通道 `secretUnreadable=true` 且视为未启用（不抛 500）；
- 每次请求读一次 `identity_channels`（3 行，无缓存），保证「保存后下一次请求生效」；
- 管理页只显示 `hasSecret` + 掩码。

## 6. 外部依赖的失败与重试（清单第 3 条）

| 依赖 | 失败形态 | 行为 |
| :--- | :--- | :--- |
| SMTP | 连接超时 / 认证失败 / 5xx | 返回 502 `MAIL_SEND_FAILED`；令牌或验证码立即作废；写审计；不扣小时配额；用户可 30s 后重试 |
| HTTP 邮件网关 | 429 / 5xx | 同上；记录网关错误码到审计（不含正文） |
| 短信服务商 | 余额不足 / 流控 | 502 + `SMS_SEND_FAILED`；通道标记 `lastErrorAt`，连续 3 次后自动 `enabled=false` 并告警日志 |
| 微信换 openid | 网络错误 / errcode≠0 | 502/401；不建号；state 保留至过期 |
| 数据库写失败 | 连接中断 | 整个事务回滚，注册返回 503，不发信（顺序：先落 PENDING 账号再发信，发信失败时账号保留，可重发） |

**顺序约束**：注册必须「先落库成功 → 再发信」，否则用户收到信但库里没账号。

## 7. 迁移与回滚

| 步骤 | 命令/文件 | 回滚 |
| :--- | :--- | :--- |
| V072 | `users.status` 加 PENDING 值（枚举在 Java，SQL 无变更）+ 注释 | 无操作（Java 枚举去掉值即可） |
| V073 | 建 `verification_codes`、`identity_channels` + 初始 3 行 | `drop table`（新表，无存量数据） |
| V074 | 建 `user_identities` + 从 `users.wechat_openid` 回填 | `drop table`；旧列仍在，数据不丢 |

回填脚本必须先算行数、再执行，并在 plan 的验收里对比 `count(users.wechat_openid 非空) == count(user_identities where channel='wechat' and external_app='legacy')`。

## 8. 测试策略

| 层 | 覆盖 |
| :--- | :--- |
| 单元 | `CodeService`（哈希、过期、错误计数、并发消费）、`ChannelRegistry`（enabled/configured 组合真值表） |
| 集成（Testcontainers + 真实 HTTP） | 注册→激活→登录 全链路；PENDING 登录 403；重复注册 PENDING 重发 / ACTIVE 409；令牌过期与复用；忘记密码踢会话 |
| 冒烟 | 用 `HttpServer` 替身模拟 SMTP 网关与短信服务商，覆盖 5xx 与超时 |
| 回归 | `ProdAuthTest` 断言改写（见 §9）、`IdentityAuthTest`、`PasswordChangeTest`、`ProfileCompletionTest` |

## 9. 与现有代码的冲突清单（必须先改，否则测试互打）

| 位置 | 现状 | 本计划要求 |
| :--- | :--- | :--- |
| `PasswordAuthProvider.login` | `role==ADMIN \|\| passwordLoginEnabled` | 改成 `status==ACTIVE && passwordHash != null` |
| `ProdAuthTest.devLoginDisabledInProd` | 断言教师密码登录 503 | 改为「错密码 401 / PENDING 403」 |
| `AuthEndpoint.login` | prod 走 admin 专用 provider，dev 走替身 | 统一成一个 provider；dev 替身只保留在非 prod |
| `SessionAuthFilter` | 只查 `status==ACTIVE` | PENDING 也拒绝（当前逻辑已是「非 ACTIVE 即无效」，加枚举值即可） |
| `ProfileCompletionFilter` | 要求 phone+email | 不变（用户拍板 v1 不修订） |

## 10. 拆服务路径（清单第 6 条）

本特性**不新增进程**：全部落在 `teachloop-app`（web）与既有 `worker` 内。
若将来要把「发信」拆出去：`identity_channels` 与 `verification_codes` 属主仍是 identity，
拆出时只需把 `ChannelRegistry` 变成一个 HTTP 客户端，调用方（`CodeService`）不变——因为端口已隔离。

## 11. Plan review 6 条清单（逐条自检）

| # | 条目 | 结论 | 依据 |
| :--- | :--- | :--- | :--- |
| 1 | 每条 AC 都能指出由哪个表/流程承接 | ✅ | §4 端点表 ↔ spec §4；激活看 `verification_codes`，配置看 `identity_channels`，身份看 `user_identities` |
| 2 | 每个实体的表、关键字段、索引、状态迁移写全 | ✅ | §2；`users.status` 只有加值没有改列 |
| 3 | 每条外部依赖都有失败与重试路径 | ✅ | §6 五行依赖逐一覆盖 |
| 4 | 模块依赖方向无环、跨模块只走端口 | ✅ | 全部落在 identity 内，`shared` 只加一个枚举值 |
| 5 | 每张表只有一个属主、无跨模块 JOIN | ✅ | 三张表都属 identity；没有跨模块查询 |
| 6 | 拆服务路径写得出来 | ✅ | §10，端口已隔离，拆出只需换实现 |

**未打勾项**：无。

---

## 12. 估算

| 批 | 人日 |
| :--- | :--- |
| 批 1 后端核心（含测试） | 2.0 |
| 批 2 管理端 | 0.5 |
| 批 3 前端四屏 + i18n | 1.0 |
| 批 4 短信 + 微信驱动 | 1.0（资质到位前不验收） |
| **合计** | **4.5 人日** |
