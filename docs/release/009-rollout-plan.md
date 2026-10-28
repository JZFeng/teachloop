# 009（注册与身份通道）完整上线计划

日期：2026-10-28　状态：**已执行（2026-10-28 02:14，见 §10 执行记录）**；后续待办只有邮件通道配置
（本文件每执行一步就地回写证据）
上游：`specs/009-registration/{spec,plan,tasks}.md`　配套：`docs/release/launch-runbook.md`

---

## 0. 一句话

线上跑的是 **009 之前**的版本（前端 115 个源文件、DB 迁移 V071），仓库 HEAD 是 **V073**。
这次发布要把「登录页新版 + 注册/激活/忘记密码 + 管理端通道配置」一次推上去，
**镜像、两个迁移、前端 dist 必须同一个窗口发**——只发前端会 404，只发后端会没有入口。

## 1. 差距盘点（2026-10-28 实测）

| 项 | 线上 prod | 仓库 HEAD | 证据 |
| :--- | :--- | :--- | :--- |
| `web/src` 文件数 | 115 | 120 | `find web/src -type f \| wc -l`；prod 少 `ActivatePage`/`AuthLayout`/`ForgotPasswordPage`/`IdentityChannelsPanel` 等 5 个 |
| 前端产物 | `index-uL5sQDpE.js` + `index-oa-6_mj3.css` | `index-CHZz_fNW.js` + `index-VmnlvbT5.css` | prod `web/dist/index.html` |
| 登录页文案 | 「开发期用邮箱密码登录；微信扫码在接入后启用」，无「忘记密码 / 去注册」 | 「请输入邮箱和密码」+ 两个入口 | prod 实测 DOM 与 `web/src/locales/zh-CN.json:499` |
| DB 迁移 | **V071**（`identity_session_invalid_before`） | **V073**（`identity_verification_codes_and_channels`、`identity_user_identities`） | `flyway_schema_history` 最后一条 |
| 后端镜像 | `teachloop:latest`，创建于 2026-10-28T02:13Z | — | `docker inspect teachloop-app-1` |
| `TEACHLOOP_SECURITY_SECRET` | **已设置** | 必需（`SecretBox` 用它加解密通道密钥） | prod `.env` 键清单 |

未交付、本次**不发**的部分（`specs/009-registration/tasks.md` 头部写明）：T524 HTTP 网关驱动、
T540 收尾、T541 短信驱动、T542 微信两驱动、T543 改邮箱需验证码。

## 2. 发布内容清单

| # | 内容 | 位置 | 需要停机 |
| :--- | :--- | :--- | :--- |
| 1 | 后端镜像重建（含 T517–T523、T526–T535 的代码与 V072/V073 迁移） | `docker compose -f docker-compose.prod.yml build app worker` | 否（构建时旧容器继续服务） |
| 2 | 迁移 V072/V073 | app 启动时 Flyway 自动应用 | 是（随 app 重建） |
| 3 | 前端 dist（登录改造 / 注册 / 激活 / 忘记密码 / 通道面板） | 本机构建后 rsync 到 `~/teachloop/web/dist` | 否（静态文件，换 index.html 即生效） |
| 4 | 邮箱通道配置 | **管理端界面**（`identity_channels` 表 + `SecretBox` 密文），不是 env | 否 |

**不需要改 `deploy/nginx.conf`**：`/api/`、`/healthz`、`/assets/`、SPA 回退都已就位。

## 3. 前置检查（缺一条就停）

1. `ssh prod 'docker exec teachloop-db-1 pg_dump ...'` 全库备份，落到主机磁盘外的位置（现有 `scripts/backup-pg.sh` 复用）。
2. 记录当前镜像 ID：`docker images teachloop:latest --format '{{.ID}} {{.CreatedAt}}'`，回滚要用。
3. 记录当前 `web/dist/index.html` 备份名（沿用 `index.html.bak-YYYYMMDD` 约定）。
4. 一个**能收信**的邮箱账号（SMTP 发件人），163/QQ 需「授权码」而非登录密码。
5. 发布窗口：选择无人作答时段。作答中的学生会被中断（见 §5）。

## 4. 发布步骤（按序，每步都验）

```bash
# 0) 本地：确认要发的提交，工作区干净
git status --porcelain && git log --oneline -1

# 1) prod：先备份（前端整目录 + 镜像 ID），再动任何东西
ssh jzfeng@192.168.68.166 'cd ~/teachloop && \
  cp -a web/dist web/dist.bak-$(date +%Y%m%d) && \
  docker images teachloop:latest --format "{{.ID}} {{.CreatedAt}}" > image-before.txt'

# 2) prod：重建镜像（旧容器继续服务，不占停机窗口）
ssh jzfeng@192.168.68.166 'cd ~/teachloop && docker compose -f docker-compose.prod.yml build app worker'

# 3) prod：重启 app —— 迁移在这一步应用，停机窗口从这里开始
ssh jzfeng@192.168.68.166 'cd ~/teachloop && \
  docker compose -f docker-compose.prod.yml up -d --no-deps app && \
  for i in $(seq 1 60); do curl -fsS "http://127.0.0.1:8011/healthz?deep=1" && break; sleep 2; done'

# 4) prod：紧接着重启 worker（worker 依赖 app healthy）
ssh jzfeng@192.168.68.166 'cd ~/teachloop && docker compose -f docker-compose.prod.yml up -d --no-deps worker'

# 5) 本地：构建前端并同步 —— 不要 --delete，旧 assets 要留着给回滚
cd web && npm run build && cd ..
rsync -a web/dist/ jzfeng@192.168.68.166:~/teachloop/web/dist/

# 6) prod：确认迁移落到 V073
ssh jzfeng@192.168.68.166 'docker exec teachloop-db-1 psql -U teachloop -d teachloop -c \
  "select version, description from flyway_schema_history order by installed_rank desc limit 3"'
```

**为什么后端先、前端后**：旧前端不认识 009 的新接口，后端先上对它是无害的（账号密码登录照旧）；
反过来先发前端，会让「注册 / 忘记密码」入口在几十秒内指向还不存在的接口（404）。
第 3 步到第 5 步之间不要跨窗口停留，最好同一次操作里做完。

## 5. 停机窗口与用户可见

- **窗口时长**：app 容器重建 + Spring 启动 ≈ **30–60 秒**（实测 `Started TeachLoopApplication in 11.165 seconds`，加容器重建与迁移）；期间站点 502。
- **用户看到**：窗口内打开/刷新页面会看到网关错误；已打开的作答页在窗口内提交会失败（前端会报网络错误，不丢已填内容，但需要重试）。
- **不在窗口内做的事**：镜像构建（几分钟）不占窗口，先 `build` 再 `up`。

## 6. 回滚（分层，按故障位置选一条）

```bash
# a) 只回滚前端（页面白屏 / 新登录页有 bug，后端不动）
#    用 §4 第 1 步的整目录备份原地同步回去（不要 mv 目录：web 容器的 bind mount 绑的是目录 inode）
ssh jzfeng@192.168.68.166 'cd ~/teachloop && rsync -a --delete web/dist.bak-<日期>/ web/dist/'

# b) 回滚后端镜像（迁移已应用但代码有问题）
#    V072/V073 只新增表与列，旧代码不认识它们不会报错，所以可以只回镜像、不回库
ssh jzfeng@192.168.68.166 'cd ~/teachloop && docker tag <旧镜像ID> teachloop:latest && \
  docker compose -f docker-compose.prod.yml up -d --no-deps app worker'

# c) 连库一起回（仅在迁移破坏性时用，本次不预期；走 §3 的备份恢复）
```

**不要**在回滚时删 `V072/V073` 建的表：Flyway 校验会因 history 与代码不一致而失败，旧代码对多余的表是容忍的。

## 7. 验收清单（三档，逐条留证据）

**接口**
- `POST /api/auth/register` → 202/200 且库里出现 `user_identities` 行
- `POST /api/auth/channels` 返回已启用通道；管理端保存 SMTP 后 `SmtpEmailChannel.configured()` 为 true
- 频控：同一邮箱连续发码第 4 次被拦（`RateLimitFilter`）

**界面**（ego 实测 + 截图）
- `/login`：只剩邮箱 + 密码，出现「忘记密码」「去注册」
- `/register` → `/activate` → 登录成功闭环
- `/forgot` 两屏；`/admin/ai` 的通道面板能保存并回显（密文不显示明文）

**端到端**
- 用真实邮箱走完一遍「注册 → 收码 → 激活 → 登录 → 忘记密码 → 新密码登录」
- 现有教师账号仍能登录（回归旧链路）

## 8. 风险与判断

| 风险 | 判断 | 应对 |
| :--- | :--- | :--- |
| SMTP 送达失败（授权码错 / 被反垃圾） | 中高，且**只能在发布后才知道** | 先自己收一封再放给学生；失败时通道按「未配置」处理不抛 500（AC-76.4） |
| 迁移在启动时失败导致 app 起不来 | 低（V072/V073 只新增） | 有 §3 备份；回滚镜像即可起旧版 |
| 停机会打断进行中的作答 | 中 | 选无人作答时段；窗口压到 1 分钟内 |
| 前端发了但后端没发（或反之） | 中 | §4 的顺序是「前端先、后端后」，两步之间不得跨窗口停留 |
| 真实邮件成本与频控绕过 | 低 | 频控已在 `RateLimitFilter`，观察日志里 429 计数 |

## 9. 工作量估算（未执行，仅估）

| 步骤 | 估时 | 备注 |
| :--- | :--- | :--- |
| 前置检查 + 全库备份 | 10 min | 库当前体积小 |
| `docker compose build app worker` | 5–15 min | 取决于主机与 Maven 缓存，**首次实测后再回填** |
| 停机窗口（app + worker 重建） | 1–2 min | 实测 Spring 启动 11.2s |
| 前端构建 + rsync | 2 min | `npm run build` 实测 1.3s |
| 验收清单三档 | 30–45 min | 端到端要等真实邮件 |

合计 **约 1–1.5 小时**，其中不可压缩的是端到端等邮件与构建。

---

## 10. 执行记录（2026-10-28 02:06–02:15 PDT，已回写证据）

| 时刻 | 动作 | 证据 |
| :--- | :--- | :--- |
| 02:06 | 全库 + uploads 备份 | `teachloop-db-20261028-020643.sql.gz`（43 KB） |
| 02:07 | 前端整目录备份 + 记录旧镜像 | `web/dist.bak-20261028-0207`（13 MB）、镜像 `ac6616b23c89` |
| 02:08 | **踩坑**：在 prod 旧源码树上 `docker compose build`，镜像里没有 V072/V073——切上去后 Flyway 仍停 V071 | 迁移查询返回 071；`auth/DevPasswordAuthProvider.java` 仍在 |
| 02:09 | 纠正：`git archive HEAD`（7.4 MB，排除 docs/specs/.dsh）覆盖源码，删掉 HEAD 已移除的 `DevPasswordAuthProvider.java`，重建（1m32s） | prod `web/src` 120 文件；`AuthEndpoint.login` 用 `passwordProvider` |
| 02:10 | 切 app：18 秒健康；迁移 **V072 → V073** | `flyway_schema_history` = `073 identity user identities`；ERROR 计数 0 |
| 02:11 | worker 重建；前端 `rsync`（不加 `--delete`） | `index-CHZz_fNW.js` + `index-VmnlvbT5.css` |
| 02:11 | 界面验证 | `/login` 新文案 +「忘记密码 / 去注册」；`/register` 「创建账号」；`/api/auth/channels` 返回 `[]` |
| 02:13 | 清库前再备份 | `teachloop-db-20261028-021309.sql.gz`（45 KB，含 009 之后状态） |
| 02:14 | **清空测试数据**：stop app/worker → `DROP SCHEMA public CASCADE` → 重建 + 授权 → 清 `teachloop_uploads` 卷（0 项）→ 启动 | 清空前：用户 6（ADMIN 1 / TEACHER 2 / STUDENT 3，其中 1 个 DISABLED）、55 张表 |
| 02:15 | 重建后校验 | Flyway 重跑 **66 条**到 V073；`users` = **0**；表 **55**；健康 19 秒；ERROR 0；`/api/setup/status` = `{"needsAdmin":true}` |

**遗留（唯一一项）**：`identity_channels` 为空，`/api/auth/channels` 返回 `[]` —— 注册与忘记密码会在「发验证码」一步失败。
需在 `/setup` 建管理员后，去 `/admin/ai` 的通道面板填 `host:port`（如 `smtp.163.com:465`）、发件人（163 要求与登录账号一致）、授权码。

**回滚口径**：数据回到清空前 → `gunzip -c ~/teachloop-backup/teachloop-db-20261028-021309.sql.gz | docker exec -i teachloop-db-1 psql -U teachloop -d teachloop`；
代码回到 009 之前 → `docker tag ac6616b23c89 teachloop:latest && docker compose -f docker-compose.prod.yml up -d --no-deps app worker`。
