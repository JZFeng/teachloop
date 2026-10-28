# 决策 028 — 注册与身份通道（2026-10-27）

- 状态：**已确认（用户 2026-10-27 选 A 方案）**，spec 待 review
- 关联：`specs/009-registration/spec.md`、`docs/research/2026-10-27-registration-channels.md`

## 背景

用户 2026-10-27：「我们没有 register，之前都忘记这个 feature 了。」

盘点后确认：`AuthEndpoint` 只有 login / wechat / teacher-invite / logout / me 五个端点，
没有注册端点、没有验证码表；`teachloop.wechat.app-id/secret` 在 yml 里零配置，
生产上 `/api/auth/wechat` 恒 503；邮件与短信发送能力零依赖。

## 用户拍板

1. v1 **直接用邮箱**，但设计上保持灵活，将来加手机与微信。
2. **手机与微信的代码同批实现**（不是留空壳）。
3. **全部可配置**：发件邮箱、SMTP/网关、短信服务商、微信 appId/secret、限额与 TTL 都改配置不改代码。
4. **微信是长期最重要的通道**（中国人人手一个微信，没有微信号的可能性不存在）。
5. 方案选择：**A —— 通道端口 + 三实现 + 配置进库**。

## 决定

| 议题 | 决定 | 被否掉的选项与原因 |
| :--- | :--- | :--- |
| 结构 | `VerificationChannel` 端口（2026-10-28 改名 `MessageChannel`）+ email/sms/wechat 三实现，共用一套验证码流程 | B（只做 email 留空壳）违背第 2 条；C（合并成单一「验证码即身份」端点）把注册/登录/绑定/改邮箱挤进一个端点，角色判断散在分支里 |
| 配置 | `identity_channels` 单行多通道表 + `SecretBox` 加密密钥，管理员页可改、**即时生效** | 环境变量方案要重启容器；且用户明确说「以后可替换，甚至接 Email Grid」 |
| 注册与登录 | **两个行为、两组端点**（`/register/*`、`/login/*`），底层共用验证码服务 | 最初写成「一个 verify 端点自动判断」，用户 2026-10-27 指出「login 和 register 本就是两个行为」——登录时打错一个字母会静默建出空账号，学生的作业全进错号 |
| 注册碰已存在 / 登录碰不存在 | 分别回 **409 `ALREADY_REGISTERED`** 与 **404 `NOT_REGISTERED`**，都不发码 | 让用户白等一封不会来的信是最差体验；存在性可探测的风险由频控（60s / 1h 5 次 / 24h 20 次）与审计承担 |
| 注册采集字段 | 只多收**显示名（可空，缺省取邮箱 `@` 前缀）** | 用户 2026-10-27 多选只选了显示名；班级邀请码、协议勾选、预收手机号明确不做（见 spec §2 的合规风险注记） |
| 密码 | 自助注册不设密码（passwordless），管理员密码登录保持现状 | 加密码等于加「忘记密码」流程，v1 不需要 |
| 身份存储 | 新增 `user_identities`（账号 1 : N 身份） | 继续往 `users` 加列：`wechat_openid` 单列装不下第二个微信应用，且邮箱/手机/微信需要同一张表 |
| 微信主键 | `unionid` 优先、`openid` 兜底 | 只存 openid 会导致同一人换应用变成两个账号 |
| 通道未配置时 | 通道**不出现**在界面，接口 404/503 | 「出现了但一点就报错」是最差状态 |
| 防刷 | 单目标 60s 冷却 + 分档配额，不引第三方人机校验 | reCAPTCHA 在国内可用性差；短信开通时再评估 |

## 被推翻的既有条款

| 位置 | 原文 | 现在 |
| :--- | :--- | :--- |
| `specs/002-v2-platform/spec.md` AC-1.8 | v1 不发送短信/邮件验证码 | v1 启用邮箱验证码；短信/微信按开关；**MFA 仍是后续** |
| `specs/002-v2-platform/spec.md` AC-1.7 | 手机号与邮箱必填 | 至少一条已验证联系方式；手机在 sms 开启时必填 |
| `.specify/memory/constitution.md` 第六条 | 认证提供者枚举只有微信/密码/替身 | 补入邮箱验证码、短信验证码 |

## 遗留未确认

1. `teachloop.org` 备案状态 —— 决定微信两种 driver 能否过审。
2. 微信主体资质（个人/企业）。
3. 短信服务商选型。
4. 个人邮箱 SMTP 授权方式。

这些只影响「何时能打开开关」，不影响代码结构。

## 2026-10-28 修订 — 管理端改版（系统设置 / 全域通讯通道）

用户提出七条：

1. 「AI 设置」改叫**系统设置**——页面已经同时管 AI 模型与通讯通道。
2. 两块卡片风格统一（身份通道不再占满整行宽度，标题与说明用与 AI 区相同的字号层级）。
3. 手机短信与微信**先从 UI 隐藏**：后端 `identity_channels` 三行与驱动枚举保留，截图里的两条不渲染。
4. 邮件配置改成排列表单：启用开关、发件人名称、发件人邮箱、SMTP 服务器、端口、加密方式（无 / SSL/TLS / STARTTLS）、认证账号、密码/授权码，底部「保存设置 + 发送测试邮件」。
5. 「身份通道」改名**全域通讯通道**。
6. AI 的「当前全站模型」与「已配置的模型」合成一张卡。
7. 「已加载 N 个模型，勾选需要的」是**提示**不是告警，改绿色。

落地方式（不新增库表）：

- `identity_channels.base_url` 仍是 `host:port`，`from_address` 仍是发件人邮箱；
- 两个新字段进 `extra`（jsonb）：`{"name":"发件人名称","encryption":"ssl|starttls|none"}`；
  缺项按老行为回落（名称空 = 只写地址；user 空 = 用发件人邮箱；encryption 缺省 = ssl）；
- `SmtpMailSender` 支持三档加密（隐式 TLS / STARTTLS / 明文）与 `From:` 显示名（RFC 2047）；
- 新增 `POST /api/admin/identity-channels/{channel}/test`：用**已保存**的配置给该通道的发件人邮箱发一封测试信，
  不要求 `enabled=true`（先测通再开开关）。

**2026-10-28 当天二次简化**：用户要求「直接把认证账号拿掉吧，前面填啥邮箱就用啥，功能简单」。
`extra.user` 废弃、不再读写；`SmtpEmailChannel` 的 AUTH 账号恒等于 `from_address`
（163/QQ 本来就要求两者一致）。面板从七字段降到六字段。

## 2026-10-28 修订 — 出站消息统一接口 + 异步投递

用户三问：认证账号能不能拿掉（已办，见上）；「我之前好像让你 abstract 通知模块吧，mail、sms 都走一个接口」；
「发送邮件要异步，以后 sms 也要异步，注册信和测试邮件都要」。

**盘点（当天的真实状态）**：

- 统一接口**存在**：`MessageChannel`（当天由 `VerificationChannel` 改名）+ `ChannelRegistry`，
  邮件、短信、微信都按它插拔；但**只有邮件一个实现**（T541 短信、T542 微信仍未做）。
- `Ports.NotificationPort` 是**站内通知**（写 `notifications` 表），与邮件短信无关，别混为一谈。
- 发信共三处，**全部同步**，实测一封 3.8 秒：`RegistrationEndpoint.issueAndSend`（注册/重发激活信）、
  `AuthEndpoint.sendCode`（登录码 / 重置密码码）、`IdentityChannelAdminEndpoint.test`（测试邮件）。

**决定（用户选 A + A）**：

1. 持久化 outbox：`outbound_messages`（V074），业务只入队；`OutboundMessageWorker` 每 2 秒派发，
   `for update skip locked` 抢件，30s / 5min 退避，3 次失败置 `FAILED`。重启不丢消息。
2. 正文用 `SecretBox` 加密存 `body_cipher`，发出后立刻清空——激活链接等于一次性凭据。
3. 注册/验证码接口立刻返回「已发送」，不再「失败就作废令牌」（T525 作废）；用户收不到就点重发。
4. 管理端测试邮件返回消息 id，前端轮询 `GET /{channel}/test/{id}`，按 PENDING / SENT / FAILED 显示。
5. 未来的短信沿用同一条队列与同一个接口，不改调用方。
