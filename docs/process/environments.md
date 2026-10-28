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
| 种子账号（`teachloop.seed.enabled`） | 开 | 开（只建库时 seed） | 关 |
| 判分引擎 | 环境变量（默认 ai，`--stub` 用替身） | 环境变量（默认 ai） | ai（替身引擎 `@Profile("!prod")` 不可用） |

## 4. 自查

```bash
grep -rn "acceptsProfiles" teachloop-*/src/main/java   # 应为空：没有代码用 profile 决定功能
grep -n "" teachloop-app/src/main/resources/application-dev.yml  # overlay 应该只有十几行
```
