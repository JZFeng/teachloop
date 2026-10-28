# 决策 030 — 自助改密码 + 管理员重置（2026-10-27）

- 状态：**已实现**（待部署）
- 关联：`specs/002-v2-platform/spec.md` AC-1.11–1.13、`teachloop-identity/.../profile/ProfileEndpoint.java`

## 背景

用户 2026-10-27：「好像我们没用改密码功能……要不在 profile 那加一个？」

盘点结论：**确实不存在**——`ProfileEndpoint` 只有 `GET` / `PUT`（`ProfileEndpoint.java:31`、`:36`），请求体只有
`phone/email/displayName/locale`；密码只在建号时写入（`--create-admin` / `--create-user` / `/setup`）。
线上当时已有 5 个真实账号（1 管理员 + 4 试用），**忘密码没有任何自救路径**。

## 决定（用户拍板）

| 议题 | 决定 | 被否掉的选项与原因 |
| :--- | :--- | :--- |
| 范围 | **B：profile 页自助改 + CLI 管理员重置** | 只做 A（自助）解决不了「朋友忘密码」；等 009（passwordless）则这 4 个账号期间无解 |
| 代价取舍 | 多 0.1 天换「忘密码不用改库」 | 概率问题不是可能性问题：外部用户一定会忘 |
| 会话 | **改完踢掉其他会话**，执行修改的设备保持登录 | 不踢的话旧密码泄露时无法自救 |
| 实现 | `users.session_invalid_before` + 会话 `teachloop.loginAt` 比较 | 直接删 Spring Session 行要按属性反查，会话里只存了 uid，查不动 |
| 密码策略 | 只校验「≥8 位」与「不能与原密码相同」 | 复杂度规则等有真实用户投诉再加，避免先立一个自己都记不住的规则 |

## 与 009 的关系

009 定的是 **passwordless**（邮箱验证码登录）。本决策**不是**在给 009 挖坑：

- 没有密码的账号（`password_hash IS NULL`）**看不到**改密码入口（`hasPassword` 字段控制）；
- 009 落地后，密码只保留给管理员与受邀账号，这个入口自然只对他们可见；
- 「忘记密码」在 009 之后由「验证码直接登录」取代，不需要单独的找回流程。

## 实现落点

| 项 | 位置 |
| :--- | :--- |
| 迁移 | `V071__identity_session_invalid_before.sql`（幂等 `add column if not exists`） |
| 端点 | `POST /api/profile/password`（`ProfileEndpoint.java`） |
| 会话判定 | `SessionAuthFilter.staleAfterPasswordChange`、登录时写 `SESSION_LOGIN_AT`（`AuthEndpoint.signIn`） |
| 管理员重置 | `AdminService.setPassword` + `AdminCli --set-password=<email>` |
| 前端 | `ProfilePage.tsx` 新增「修改密码」卡（仅 `hasPassword` 时渲染） |
| 测试 | `PasswordChangeTest` 3/3：改密码校验、多端踢下线、管理员重置 |
