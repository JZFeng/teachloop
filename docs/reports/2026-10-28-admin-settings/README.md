# 2026-10-28 管理端「系统设置」改版验收

用户当轮提了七条（原文见 `docs/decisions/028-registration-channels.md` 的「2026-10-28 修订」）。
这里记录**怎么验的**，不重复需求。

## 改了什么

| # | 要求 | 落点 |
| :-- | :--- | :--- |
| 1 | 「AI 设置」→**系统设置** | `web/src/locales/{zh-CN,en}.json` 的 `app.settings.*`、`nav.aiSettings`；`nav.ts` 图标换齿轮；路由仍 `/admin/ai`，`/admin/settings` 重定向 |
| 2 | 两块风格统一 | 通道面板改成 `maxWidth: 900` + `h2` + `p.small muted`，与 AI 卡同一层级 |
| 3 | 短信 / 微信先隐藏 | `IdentityChannelsPanel.tsx` 只渲染 `channel === 'email'`；后端与 `identity_channels` 三行不动 |
| 4 | 邮件配置排列表单 | 发件人名称 / 发件人邮箱 / SMTP 服务器 / 端口 / 加密方式（无·SSL/TLS·STARTTLS）/ 授权码 + 保存设置·发送测试邮件（**2026-10-28 二次简化**：认证账号字段去掉，恒等于发件人邮箱） |
| 5 | 「身份通道」→**全域通讯通道** | `app.channels.title` |
| 6 | AI 两块并成一块 | `AdminAiPage.tsx`：单卡内分「当前全站模型 / 已配置的模型」 |
| 7 | 「已加载 N 个模型」改绿色 | `AdminAiPage.tsx` 的 `modelsLoadedOk` → `Alert type=success` |

新增能力（第 4 条要用得上就不能只是画个按钮）：

- `SmtpMailSender` 支持三档加密与 `From:` 显示名（RFC 2047）；
- 三个新字段进 `identity_channels.extra`（jsonb）：`{"name","encryption"}`（`user` 已废弃，认证账号恒等于发件人邮箱）；
- `POST /api/admin/identity-channels/email/test`：用**已保存**配置给发件人邮箱发一封测试信。

## 验到哪一档

**端到端（dev，浏览器实测）**，2026-10-28 10:56-10:58（America/Los_Angeles）：

| 证据 | 结论 |
| :--- | :--- |
| `01-settings-top.png` | 侧栏与页标题都是「系统设置」；AI 只剩一张卡 |
| `02-smtp-form.png` / `02b-smtp-form-bottom.png` | 邮件六个字段（无认证账号）+ 加密方式单选 + 两个按钮；开关读作「启用邮件服务 ☑ 开启」；短信 / 微信不渲染 |
| `03-test-email-sent.png` | 点「发送测试邮件」→ 绿色「测试邮件已发送到 19725156124@163.com · 3794ms」——**真发出去了**（SSL/TLS 465 + 真实授权码） |
| `04-models-loaded-green.png` | 对接本地假 `/v1/models`（127.0.0.1:8099）→「已加载 2 个模型」是**绿色** |

保存链路：把加密方式切到 STARTTLS → 保存 → `GET /api/admin/identity-channels` 的
`extra.encryption == "starttls"`；再切回 SSL/TLS 保存 → 恢复 `"ssl"`（dev 库保持可用状态）。

自动化：

- `IdentityChannelAdminTest` 6/6（新增 `extra` jsonb 回读、未配齐时测试发送 `ok=false`）；
- `LoginCodeFlowTest` 4/4、`RegistrationFlowTest` 2/2（SMTP 工厂签名改了，替身同步）；
- `web/tests/email-channel-form.test.mjs` 11/11；全量 `node --test` 152/152；
- `tsc --noEmit` 0、`vite build` 0、`check-ui` 0、`check-i18n` 0（zh/en 各 1791 键）。

**没验到的**：STARTTLS（587）与「无加密」两档没有真实服务商可对；代码路径写好了，
真机只跑了 SSL/TLS 465。生产环境未部署（按约定要单独授权）。
