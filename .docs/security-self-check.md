# 安全自查（T172）

日期：2026-10-20　环境：本地 Testcontainers + prod profile 子集
运行：`./mvnw -q -pl teachloop-app -am -Dtest=IdentityAuthTest,ProdAuthTest,ProfileCompletionTest,SubmissionFormsTest,RateLimitTest -Dsurefire.failIfNoSpecifiedTests=false test` → exit 0

| 检查项 | 依据 | 结果 |
| :--- | :--- | :--- |
| 未登录访问受保护接口 | `IdentityAuthTest` 匿名 `GET /api/papers` | 401 |
| 已登录写操作缺 CSRF | `ProdAuthTest` 管理员会话 `POST /api/admin/teacher-invites` 无 token | 403 |
| CSRF token 正确 | `ProdAuthTest` 带 Cookie + `X-XSRF-TOKEN` | 200 |
| 越权：教师访问管理员接口 | `IdentityAuthTest` 教师 `POST /api/admin/teacher-invites` | 403 |
| 越权：学生建卷 | `IdentityAuthTest` 学生 `POST /api/papers` | 403 |
| 越权：读别人附件 | `SubmissionFormsTest` 其他学生 `GET /api/submissions/{id}/attachment` | 404 |
| 上传白名单 | `SubmissionFormsTest` `.doc` / `.jpg` | 400，提示另存 docx |
| 富文本消毒 | `SubmissionFormsTest` `<script>`/`onclick` | 被剥离；`security:rich_text_stripped` 审计 1 条 |
| 登录限流 | `RateLimitTest` 连续登录超限 | 429 + `RATE_LIMITED` + `Retry-After`；跨窗恢复 |
| 上传/派发/事件限流 | `RateLimitTest` 各自独立计数 | 超限 429；普通请求不受影响 |
| 停用账号旧会话 | `IdentityAuthTest` 停用后 `GET /api/auth/me` | 401 |
| 未补全资料 | `ProfileCompletionTest` 教师业务接口 | 403 `PROFILE_INCOMPLETE`；补全后 200 |
| 生产禁用开发登录 | `ProdAuthTest` 教师密码登录 | 503 `UNAVAILABLE` |
| 生产密码登录仅管理员 | `ProdAuthTest` 管理员登录 | 200 |

## 已知未覆盖

- CSRF 与资料补全只在 `prod` profile 强制；开发/测试关闭，避免本地与既有测试被卡。
- 微信登录未配置时 503 已实现，但未在真机微信凭据下验证。
- 越权“按 spec 返回 404”的个别端点仍由控制器 `notFound` 实现，未做全局统一。
