# 任务清单 — 注册与身份通道（009-registration）

- 上游：`specs/009-registration/spec.md`（36 条 AC / 18 条 FR）、`specs/009-registration/plan.md`（6 条清单已过）
- 编号规则：`T` 号全局唯一，从 **T517** 起（当前全局最大 T516）
- 状态：**进行中**（2026-10-27 开工）
- 进度：**后端主链路已交付 T517–T523、T526–T533**（表与状态机 / 通道端口 / 验证码服务 / SMTP /
  注册激活 / 密码登录 / 验证码登录 / 频控 / 忘记密码），测试 **27/27 绿**；
  前端 T536–T539 已交付（注册 / 激活 / 登录改造 / 忘记密码，界面已实测、流程待后端联调）；
  剩余 T524（HTTP 网关驱动）、T534–T535（管理端）、T540 收尾（i18n 已过）、T541–T544
- 约定：每个任务自带可判定验收；依赖见列；无孤儿任务（覆盖矩阵见 §10）

---

## 1. 依赖图（先做左边才做右边）

```
T517–T519 表
    └→ T520 状态机
T521 通道端口 → T522 验证码服务 → T523/T524 邮件驱动 → T525 发送失败语义
    └→ T526 注册 → T527 激活 → T528 重发 → T529 通道端点
    └→ T530 密码登录 → T531 验证码登录 → T532 频控
    └→ T533 忘记密码
T534 管理端点 → T535 管理页
T536–T540 前端（依赖 T526–T533 的契约）
T541 短信 / T542 微信（可并行，默认关）
T543 改邮箱验证码（依赖 T522）
T544 收尾验收
```

---

## 2. 批 A：数据与状态机

| T | 任务 | 依赖 | 验收（可判定） |
| :--- | :--- | :--- | :--- |
| T517 | 迁移 `V073__identity_verification_codes.sql`：`verification_codes` 表 + `(target,purpose,created_at desc)` 与 `(expires_at)` 索引 | — | `MigrationTest` 通过；表存在且索引名匹配；空库可从头迁到最新 |
| T518 | 迁移 `V073` 同文件追加 `identity_channels` 表 + 初始三行（`email/sms/wechat`，全 `enabled=false`） | — | 迁移后 `select count(*) from identity_channels` = 3；重复执行幂等 |
| T519 | 迁移 `V074__identity_user_identities.sql`：建表 + 把 `users.wechat_openid` 非空行回填为 `(channel='wechat', external_app='legacy')` | — | 回填行数 = 回填前 `wechat_openid` 非空行数；`(channel,external_id)` 唯一索引生效（重复插入报冲突） |
| T520 | `UserStatus` 加 `PENDING`；确认 `SessionAuthFilter`、`AuthEndpoint.signIn`、`ProfileCompletionFilter` 对「非 ACTIVE」行为一致（一律无效/拒绝） | T517 | 新增测试：PENDING 账号的会话访问 `/api/auth/me` 返回 401；`signIn` 对 PENDING 抛 403 |

## 3. 批 B：通道端口与邮件

| T | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T521 | `VerificationChannel` 端口 + `ChannelRegistry`：按 `enabled && configured()` 计算可用集合；每次请求读库（3 行，不缓存） | T518 | 真值表测试 4 种组合（enabled×configured）；关掉 `email` 后 `registry.available()` 不含它；改库后**下一次调用**即变化 |
| T522 | `CodeService`：生成 6 位码/32 字节令牌、SHA-256 哈希落库、`consume` 条件更新（`consumed_at is null`）、错误计数 +5 次作废、过期清理 | T517 | 并发两个 consume 只有一个成功；错误 5 次后再校验返回 `ATTEMPTS_EXCEEDED`；过期码返回 `EXPIRED` |
| T523 | `SmtpEmailChannel`（`spring-boot-starter-mail`），发件人/主机/端口/授权码全部来自 `identity_channels` | T521 | 用本地 `HttpServer`/GreenMail 替身发送成功 1 封；`configured()` 在缺 from 或缺 secret 时为 false |
| T524 | `HttpApiEmailChannel`：可配 endpoint + Bearer key，POST JSON；超时 5s | T521 | 替身返回 200 → 发送成功；返回 500 → 抛 `MAIL_SEND_FAILED` |
| T525 | 发送失败语义：502 + 该 challenge/令牌立即作废 + 写审计（不含正文与收件人明文）+ 不扣小时配额 | T523/T524 | 失败后再用同一码校验返回已作废；审计表出现一条 `channel` + `error_code`，不含邮箱字符串 |

## 4. 批 C：注册与激活

| T | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T526 | `POST /api/auth/register`：校验密码 ≥8、邮箱格式；建 `PENDING` STUDENT；发激活信；返回 202。PENDING 重复 → 重发；ACTIVE → 409 | T520/T522/T523 | 三次调用三种结果：202（新建）/202（重发，用户数不增）/409；库里 `status=PENDING`、`password_hash` 为 BCrypt |
| T527 | 激活：`GET /api/auth/activate?token=` 只渲染确认信息（返回邮箱掩码），**无副作用**；`POST /api/auth/activate` 才置 ACTIVE | T526 | GET 两次后账号仍 PENDING；POST 后 ACTIVE；同一 token 第二次 POST 返回 `TOKEN_USED` |
| T528 | `POST /api/auth/activate/resend`：PENDING 才发；冷却 60s / 1h 5 次 / IP 1h 20 次 | T526 | 两次连发第二次 429 且带 `Retry-After`；ACTIVE 账号调用返回 409 |
| T529 | `GET /api/auth/channels` 公开端点：只返回 `enabled && configured` 的通道与 `label/targetHint` | T521 | 全关时返回空数组；开 email 且配好 SMTP 后返回一项；未登录可访问（不带 cookie 也 200） |

## 5. 批 D：登录与频控

| T | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T530 | 统一密码登录：`ACTIVE && password_hash != null` 即可，PENDING 403、停用 403、错密码 401；删除白名单判断；**同步改写 `ProdAuthTest` 断言** | T520 | `ProdAuthTest` 3/3 且新断言覆盖 401/403 两种；白名单列的账号与新注册账号登录行为一致 |
| T531 | 验证码登录 `challenge/verify`：仅 ACTIVE 账号发码；不存在 404、PENDING 403；验证成功签发会话 | T522/T523 | 三态测试通过；登录会话可访问 `/api/auth/me` |
| T532 | 频控接入：注册、重发、登录发码、忘记密码**分桶计数**，复用 `rate_limits` | T526–T531 | 每桶独立计数（打满登录桶不影响注册桶）；429 带 `Retry-After` |

## 6. 批 E：忘记密码

| T | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T533 | `password-reset/challenge` + `/verify`：改哈希、置 `session_invalid_before=now()`、**不自动登录** | T522/T532 | 重置后旧会话 401、新密码可登录；响应不含任何账号资料；旧密码 401 |

## 7. 批 F：管理端

| T | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T534 | `GET/PUT /api/admin/identity-channels`：掩码 + `hasSecret` + `secretUnreadable`；保存即生效；写审计 | T521 | 非 ADMIN 403；保存后下一次 `/api/auth/channels` 立即反映；任何响应都不含明文密钥 |
| T535 | 管理页（`/admin/channels`）：三通道表单（开关/驱动/发件人/端点/密钥/配额），保存提示 | T534 | 前端可改 email 开关并立刻在登录页生效；密钥输入框只显示掩码 |

## 8. 批 G：前端

| T | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T536 | 注册页：姓名（单框，可空）+ 邮箱 + 密码 → 提交后到「激活邮件已发送」态（含 60s 重发倒计时） | T526/T528 | 浏览器实测：注册→看到已发送态；重发按钮 60s 后可点 |
| T537 | 激活确认页：GET 展示「确认激活」→ POST 激活 → 成功页；过期/已用各有文案与重发入口 | T527 | 三种 token 状态各截图一次；确认前账号仍 PENDING |
| T538 | 登录页改造：**只保留邮箱 + 密码**，加「忘记密码」「去注册」两个入口（验证码登录 UI 不做，后端保留） | T529/T530 | 密码错显示「邮箱或密码不正确」；两个链接都能跳对页面；页面上不出现验证码页签 |
| T539 | 忘记密码两屏（发码 + 新密码） | T533 | 走完可登录；成功后其他会话失效 |
| T540 | i18n：新增键进 zh/en，`check-i18n` 通过；文案遵守 FR-173（不出现错误码/AC 编号/实现细节） | T536–T539 | `check-i18n` 退出码 0；grep 断言：界面文案里没有 `AC-`、`PENDING`、`CODE_`、`TOKEN_` |

## 9. 批 H：可选通道与收尾

| T | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T541 | 短信驱动（服务商可插拔）+ 手机号规范化；默认 `enabled=false` | T521 | 服务商替身成功/失败两路；通道关时 `/api/auth/channels` 不含 sms |
| T542 | 微信 `wechat_web` / `wechat_mp` 两驱动 + `user_identities` 以 unionid 优先 | T519/T521 | 同一 unionid 在两个 app 下命中同一账号；已登录扫码是绑定不是新建；appId 未配时 503 |
| T543 | `PUT /api/profile` 改邮箱必须带验证码（AC-73.13） | T522 | 不带验证码改邮箱 400；带码成功；旧码重放 400 |
| T544 | 收尾：spec 的 AC 逐条勾选并注明证据；`docs/PROGRESS.md` 更新；端到端清单跑一遍 | 全部 | AC 36 条全部有勾选或明确「未做+原因」；PROGRESS 有本次条目 |

---

## 10. AC → T 覆盖矩阵（反查用）

| AC | 承接任务 |
| :--- | :--- |
| AC-73.1 通道发现 | T529 / T538 |
| AC-73.2 注册提交 | T526 |
| AC-73.3 重复注册 | T526 |
| AC-73.4 激活令牌 | T527 |
| AC-73.5 令牌失效 | T527 / T537 |
| AC-73.6 PENDING 不能登录 | T520 / T530 |
| AC-73.7 激活后登录 | T527 / T530 |
| AC-73.8 姓名单字段 | T526 / T536 |
| AC-73.9 提权字段忽略 | T526 |
| AC-73.10 频控 | T528 / T532 |
| AC-73.11 密码登录 | T530 |
| AC-73.12 验证码登录 | T531 |
| AC-73.13 改邮箱要验证码 | T543 |
| AC-74.1–74.5 手机通道 | T541 |
| AC-75.1–75.8 微信 | T542 |
| AC-76.1–76.5 通道配置 | T534 / T535 |
| AC-77.1–77.5 忘记密码 | T533 / T539 |
| FR-173 文案最小化 | T540 |

**孤儿任务检查**：T517–T544 每个都能在矩阵里找到落点，无孤儿。

---

## 11. 风险与前置

| 风险 | 处理 |
| :--- | :--- |
| `ProdAuthTest` 断言与 AC-73.11 冲突 | T530 内同步改写，不留给收尾 |
| 邮件网关预取链接 | T527 用「GET 无副作用 / POST 才激活」，并有专门测试 |
| dev 库曾被实验分支污染（V069） | 本地联调用干净库（Testcontainers）；不动 dev 库历史 |
| 短信/微信资质未到 | T541/T542 代码写完但默认关，验收标「仅指令层，未接真实服务商」 |
