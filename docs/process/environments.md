# 环境配置分层（dev / qa / prod）

- 定稿：2026-10-28（用户拍板「补 dev/qa 薄 overlay + 安全开关改成属性」）
- 起因：生产注册恒 404「该注册方式不可用」，qa 却是好的；排查时发现 qa 与生产到底差在哪，
  只能靠翻代码——`SecurityConfig` 里一行 `acceptsProfiles("prod")`，配置文件里看不出来。

## 1. 每个环境读哪些文件

| 环境 | 启动方式 | `SPRING_PROFILES_ACTIVE` | 生效配置 |
| :--- | :--- | :--- | :--- |
| dev | `scripts/dev.sh` | `dev` | `application.yml` + `application-dev.yml` + 环境变量 |
| qa | `scripts/env.sh up qa` | `qa` | `application.yml` + `application-qa.yml` + 环境变量 |
| prod app | `docker-compose.prod.yml` | `prod` | `application.yml` + `application-prod.yml` + `.env` |
| prod worker | 同上 | `worker` | `application.yml` + `application-worker.yml` + `.env` |
| 测试 | `@SpringBootTest` | 无（或 `prod`） | `application.yml` + `src/test/resources/*` |

## 2. 四条规则

1. **base 只放「与环境无关」的默认值**；凡是某环境不同的，一律进 overlay。
2. **overlay 只写差异**，禁止把 base 的块复制过来（`datasource`、`vocab:`、`grading:` 这些
   出现两次后，改一处忘一处就是下一个事故）。
3. **「本环境默认开 / 关」只能落在属性 + overlay**，不许写进代码的 profile 判断。
   反面教材：`SecurityConfig` 曾用 `acceptsProfiles("prod")` 决定 CSRF，结果 qa 永远打不开、
   `application-prod.yml` 里的 `teachloop.security.csrf: true` 成了没人读的死配置。
4. **构建与环境无关**：`make build` 只出一个 jar、`pnpm build` 只出一份 dist，环境差异全部在
   运行时决定（属性 / 环境变量 / 库里的配置行）。谁想「构建时按环境打不同产物」，先过 review。

## 3. 当前各环境的已知差异

| 项 | dev | qa | prod |
| :--- | :--- | :--- | :--- |
| CSRF（`teachloop.security.csrf`） | 关 | **开** | 开 |
| 强制补全资料（`teachloop.security.require-profile-completion`） | 关 | **开** | 开 |
| session cookie `secure` | 否 | 否（走 http://LAN-IP:5174，Secure cookie 会被浏览器丢） | 是 |
| session cookie `domain` | 不设（host-only） | 不设（根域对不上 `.teachloop.org`，设了会丢 cookie） | 默认不设；要子域共享登录时由 `.env` 注入 `SERVER_SERVLET_SESSION_COOKIE_DOMAIN=.teachloop.org` |
| 种子账号（`teachloop.seed.enabled`） | 开 | 开（只建库时 seed） | 关 |
| 判分引擎 | 环境变量（默认 ai，`--stub` 用替身） | 环境变量（默认 ai） | ai（替身引擎 `@Profile("!prod")` 不可用） |

## 3.1 子域与共享登录（2026-10-29 决定）

以后 TeachLoop 拆成多个 app（`word.teachloop.org` 等）时，**共享登录**，做法分三步：

1. **开 cookie 域**：在部署机的 `.env` 里设
   `SERVER_SERVLET_SESSION_COOKIE_DOMAIN=.teachloop.org`。
   **不写进 `application-prod.yml`**——写死会让任何非 `teachloop.org` 的环境都拿不到 cookie。
   实测：把 `domain` 硬编码进 prod overlay 后，`ProdAuthTest` 三个用例全变 401
   （测试跑在 `127.0.0.1`，`Domain=.teachloop.org` 的 cookie 被客户端丢弃），
   还原成环境变量注入后 3/3 通过。
2. **会话同源**：会话存 Postgres（`spring.session.jdbc`），所以**各 app 必须连同一个 session 表**；
   若拆成不同库，先要把 session 表独立出去（单独库或 Redis），否则 cookie 域放开了也认不到会话。
3. **路由**：单隧道 + 显式 public hostname + nginx 按 Host 分流（`deploy/nginx.conf`）；
   Cloudflare Universal SSL 覆盖 `teachloop.org` 与 `*.teachloop.org`（**只覆盖一级**，
   `a.b.teachloop.org` 不在免费证书内）。

代价（必须写清楚）：cookie 域放宽后，**任一子域被 XSS 等于整个 `teachloop.org` 的会话被拿走**，
子域必须与主域同级对待（同样的 CSP、同样的依赖审计）。

## 4. 自查

```bash
grep -rn "acceptsProfiles" teachloop-*/src/main/java   # 应为空：没有代码用 profile 决定功能
grep -n "" teachloop-app/src/main/resources/application-dev.yml  # overlay 应该只有十几行
```
