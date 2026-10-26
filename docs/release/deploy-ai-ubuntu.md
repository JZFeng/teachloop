# 部署到内网 Ubuntu（ai-ubuntu / 192.168.68.101）

日期：2026-10-25　状态：**已部署并运行**（app / worker / db / web 四个容器）

## 一键部署

```bash
# 之后每次发版（本机执行，约 3–6 分钟）
scripts/deploy-ai-ubuntu.sh

# 首次（目标机没有 Docker，需要 sudo 装一次）
SUDO_PASS=你的密码 scripts/deploy-ai-ubuntu.sh

# 常用变体
scripts/deploy-ai-ubuntu.sh --skip-web      # 前端 dist 没改，跳过 pnpm build
scripts/deploy-ai-ubuntu.sh --no-build      # 镜像已建好，只重启服务
HOST=192.168.68.1 WEB_PORT=8081 scripts/deploy-ai-ubuntu.sh
```

脚本做的事：检查 SSH → 装 Docker（缺则装）→ 本机构建前端 → rsync 源码与 dist →
**目标机原生构建镜像**（amd64，比在 Apple Silicon 上交叉编译快）→ 生成 `.env`（随机密钥，已存在则保留）→
`docker compose up -d` → 等 `/healthz?deep=1` 通过 → 打印入口。

## 目标机现状

| 项 | 值 |
| :--- | :--- |
| 访问地址 | `http://192.168.68.101/`（80 端口，nginx） |
| 首次初始化 | `http://192.168.68.101/setup` 建管理员 |
| 健康检查 | `http://192.168.68.101/healthz?deep=1` → `{"status":"UP","db":"UP","pdfFont":"UP"}` |
| 部署目录 | `/home/jzfeng/teachloop`（compose / nginx.conf / dist / `.env` / `src`） |
| 数据卷 | `teachloop_pgdata`、`teachloop_uploads`、`teachloop_applogs`（重部署不删） |
| 机器 | x86_64 / 16 核 / 62G 内存 / 457G 磁盘（`Strata` 本地模型占 8080，未触碰） |

## 与官方 compose 的差异

`deploy/app/docker-compose.yml` 是给内网单机用的，和 `docker-compose.prod.yml` 的区别：

1. **加了 nginx**：后端不服务静态文件（`GET /` 返回 401 是 API），前端 dist 由 nginx 提供，`/api` 反代到 `app:8010`。
2. **HTTP 部署**：prod profile 默认 `server.servlet.session.cookie.secure=true`，纯 HTTP 登录会「登录成功但立刻掉线」；
   这里用 `SERVER_SERVLET_SESSION_COOKIE_SECURE=false` 关掉。**一旦要上公网必须改回 HTTPS。**
3. **内存上限**：db 2g / app 4g / worker 2g / web 128m（容器没设限时两个 JVM 会各按宿主机内存抢）。
4. **worker 关闭镜像自带 HEALTHCHECK**：镜像探针打的是 `/healthz`，worker 没有 Web 端口。

## 部署中修掉的三个问题

| # | 问题 | 性质 | 修法 |
| :--- | :--- | :--- | :--- |
| 1 | `pip install piper-tts` 报 `externally-managed-environment`（PEP 668） | **仓库缺陷**：任何干净构建都会失败 | Dockerfile 加 `--break-system-packages` |
| 2 | worker profile 启动即 `APPLICATION FAILED TO START`：`SecurityConfig` 在没有 Web 上下文时仍要求 `HttpSecurity` bean | **仓库缺陷**：文档里的 worker 起不来 | 条件下沉：只给 `filterChain` 与 `rateLimitFilterRegistration` 加 `@ConditionalOnWebApplication(SERVLET)`，`passwordEncoder` 保持无条件 |
| 3 | app 连库 `password authentication failed` | **部署脚本缺陷** | Postgres 与 app 必须共用同一密码；脚本改为生成一次 |

## 运维命令

```bash
ssh jzfeng@192.168.68.101
cd ~/teachloop
docker compose ps                        # 四个容器状态
docker compose logs -f app               # 后端日志
docker compose logs -f worker            # 批改队列日志
docker compose down                      # 停（保留数据卷）
docker compose down -v                   # 停并删除数据（慎用）
```

## Cloudflare 隧道（公网访问）

```bash
ssh jzfeng@192.168.68.101 'cd ~/teachloop && docker compose --env-file .env --profile tunnel up -d tunnel'
ssh jzfeng@192.168.68.101 'docker logs teachloop-tunnel-1 2>&1 | grep -oE "https://[a-z0-9-]+\.trycloudflare\.com" | head -1'
```

- 当前地址：**https://inch-foods-uncle-fisher.trycloudflare.com**（2026-10-25 验证：首页 200 / 登录 200 / `/api/auth/me` 200）
- 这是 **quick tunnel**：地址随机，容器重启就会换。要固定地址，需要先有域名（接入 Cloudflare）再做 named tunnel + DNS route。
- 暴露范围：登录页对公网可见。已先把首个管理员建好（`admin@teachloop.local`，密码见交付说明），否则 `/setup` 会被任何人抢注。
- 更强的保护需要 Cloudflare Access（要求 Cloudflare 账号 + 域名）。

## 部署中发现并修掉的生产缺陷

| # | 问题 | 影响 |
| :--- | :--- | :--- |
| 1 | `SecurityConfig` 没有 Web 条件注解，worker(non-web) 启动即失败 | worker 永远起不来（文档里的启动方式不成立） |
| 2 | Dockerfile `pip install piper-tts` 撞 PEP 668 | 任何干净构建失败 |
| 3 | **`/api/csrf` 在 prod 返回空 token**（Spring Security 7 下 request attribute 拿不到 token），浏览器所有 POST 被 CSRF 拒绝 → 401，**连首个管理员都建不了** | 生产不可用；dev profile 关了 CSRF 所以一直没暴露。修法：CsrfEndpoint 显式 `generateToken` + `saveToken`，并把 XOR 掩码换成 `CsrfTokenRequestAttributeHandler`（SPA 直传 Cookie 值） |
| 4 | 部署脚本中断会把 dist 撕成半套（index.html 指向不存在的 JS） | 首页白屏；已加产物校验 + 原地 `--delete-after` 同步 + 强制重建 web 容器 |
| 5 | `mv` 目录做「原子」替换会让 nginx bind mount 挂在旧 inode | 首页 403 |

## 已知缺口（本部署刻意没做）

- 没有 HTTPS / 域名：内网 HTTP 方案，`COOKIE_SECURE=false`。
- 没有异地备份：数据在单机盘上的 docker 卷里，`scripts/backup-pg.sh` 需要在目标机配 cron。
- 词表索引与内容包未装（`fixtures-local` 未同步）：词族区块降级隐藏，不影响启动；需要时在目标机跑 `make vocab-lexicon` 或同步目录。
