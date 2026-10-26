# TeachLoop 正式上线 runbook

日期：2026-10-25　状态：**待执行**（本文件只写计划与口径，不含已验证结论；每完成一步就地回写证据）

配套文档：`docs/deploy-aliyun.md`（部署技术步骤）、`docs/DELIVERY-STATUS.md`（功能交付状态，最后更新 2026-10-20，已滞后）。

## 0. 已定路线（2026-10-25 用户拍板，不再讨论）

| 决策项 | 选定 | 后果 |
| :--- | :--- | :--- |
| 服务器 | 大陆阿里云 ECS + ICP 备案 | 关键路径 = 备案 7–20 工作日 |
| 首发形态 | 邀请制，先放 1–2 个班 | 不开放注册；现有邀请码链路直接可用，**无需改代码** |
| 数据库 | 容器 Postgres（`docker-compose.prod.yml` 的 `db`）+ 每日备份 | 省 RDS 费用；备份与恢复脚本必须自己写好 |
| 收费 | 不收费，非经营性备案 | 不接支付、不走经营性 ICP |

**推论**：代码侧不需要新增业务功能。上线工作量集中在四件事——备案与合规、发布流水线、备份与密钥、真实 AI 成本实测。

## 1. 关键路径与日历

```
D0  今天            决策落地（本文件）
D1                  买域名（实名认证）×  买 ECS 4C8G 包年包月 ×  提交 ICP 备案
D1–D3   ← 并行     ① 合规页面文案与页面  ② 备份脚本 + 定时任务  ③ CI + 版本号/tag  ④ 回滚口径
D3–D20  ← 等备案号   期间：域名实名满 3 天后备案才受理；ECS 不能停机
D20                 备案下号 → 页脚挂备案号 → 部署 → 冒烟 → 公安联网备案（30 日内）
D21–D27             灰度 1 个班，测真实 AI 成本与积压
D28+                第二班 → 再谈扩大
```

大陆备案是**外部工期**，无法并行压缩；其余工作在等待期内全部可完成。境外服务器路线可在 5–7 天上线，但本路线已放弃。

## 2. 阶段 A · 域名与备案（D1 启动，工期 7–20 工作日）

### 2.1 域名
- 买 `.com`（.cn 需额外实名+资质），完成**域名实名认证**；实名认证通过后通常要 **3 个自然日**才能提交备案，先做这一步。
- 域名持有者、备案主体、ECS 主体三者必须一致。用企业主体（学校/公司）备案，个人主体备案教育类站点大概率被驳回。
- 解析：`A` 记录指向 ECS 弹性公网 IP；`www` 可同指。

### 2.2 ECS
- 规格 4C8G 起（`docs/deploy-aliyun.md` §1）；系统盘 ≥ 60G，数据放数据盘。
- **包年包月 ≥ 3 个月**才具备备案资格；按量付费不能备案。
- 安全组：只开 22（限办公 IP）、80、443。**8010 不对公网开放**。

### 2.3 备案材料清单（阿里云 ICP 备案控制台提交）

| 材料 | 说明 |
| :--- | :--- |
| 主体证件 | 营业执照彩色扫描件（企业）/ 身份证正反面（个人；教育类不建议） |
| 主体负责人证件 | 身份证正反面 + 手机号（需接收核验短信） |
| 网站负责人证件 | 非法人时需《网站负责人授权书》 |
| 域名证书 | 注册商后台导出，持有者与主体一致 |
| 服务器信息 | 阿里云 ECS 实例 ID（同账号下） |
| 网站名称 | 不含「中国/国家/教育/学校」等敏感词；建议用品牌名（TeachLoop 中文名） |
| 服务内容 | 如实选「其他」或「单位门户」，如实描述为教学辅助工具 |
| 应急联系人 | 手机 + 邮箱，管局会回访核验 |
| 核验方式 | 阿里云 App 人脸核验（幕布拍照已取消） |

**风险点（提前问阿里云备案客服确认，不要上线后才处理）**：
- 「教育」相关的**前置审批**：部分省管局对教育类站点要求教育行政部门审批文件。若被要求，二选一是准备办学资质，二是在备案时把站点定位为「校内教学辅助工具、不面向社会招生」，按实际服务内容申报。**不要虚假申报**，被抽查会注销备案。
- 备案期间网站**不能对外提供访问**（部分管局要求关站审核），所以域名解析先不生效，别急着发链接。

### 2.4 备案通过后（顺序不能乱）
1. 页脚挂 ICP 备案号，链接到 `beian.miit.gov.cn`。
2. **30 日内**做公安联网备案（全国互联网安全管理服务平台），拿公安备案号也挂页脚。
3. 才开始对外发邀请码。

## 3. 阶段 B · 合规页面（D1–D3，与备案并行）

现状：`web/src` 与 `teachloop-app/src/main` 中**没有任何隐私政策/用户协议页面**（`grep -riE "隐私政策|用户协议|privacy|terms"` 零命中）。公网教育平台这是硬门槛，不是可选项。

必须产出三份，且都要有**可访问的页面路由**（不能只在 repo 里放 md）：

1. **隐私政策** — 至少写清：
   - 收集哪些个人信息：姓名、手机号、角色、班级归属、作业/试卷内容、上传附件、音视频作答、操作日志。
   - **必须明说「学生提交的作答内容会发送给第三方 AI 服务（DeepSeek）用于判分与解析生成」**，包括发送范围、是否留存、不用于训练（以供应商条款为准）。
   - 保存期限：`audit_logs` 永不删除、请求日志 90 天（对应 `.env.example` 的 `TEACHLOOP_OBSERVABILITY_RETENTION_DAYS=90`）——文案与实现必须一致，写错就是虚假陈述。
   - 学生/家长的联系方式与删除权行使路径。
2. **用户协议** — 账号归属（教师建班、学生凭邀请码加入）、内容权属、免责与申诉路径、服务终止后的数据导出。
3. **未成年人个人信息处理规则** — 面向中小学生时：
   - 处理不满十四周岁未成年人信息需**监护人同意**。邀请制首发最省事的做法：由教师统一发放邀请码时同步收集家长知情同意书（纸质或电子留档），而不是让学生自己勾「我已阅读」。
   - 最小必要收集、不推送广告、不做与教学无关的画像。

另外准备一份**个人信息保护影响评估（PIA）**留档：处理未成年人信息属于敏感个人信息，学校客户采购或监管抽查时会要。可以是一份 2–3 页文档。

> 这三份文案属于法律文件，需由用户/法务最终确认后才上线。我负责起草结构与必须覆盖的条款，不代替法务定稿。

## 4. 阶段 C · 发布流水线（D1–D3）

### 4.1 现状缺口
- 无 CI：仓库里没有 `.github/` 目录。
- 无版本 tag：`git tag` 为空；`pom.xml` 仍是 `0.1.0-SNAPSHOT`。
- `docker-compose.prod.yml` 用 `image: teachloop:latest` —— 生产用 `latest` 会让回滚失去确定性，**必须改成 pin 到不可变 tag**。
- 工作区有未提交改动（`git status` 显示 9 个文件），发布必须从干净 tag 构建。

### 4.2 要做的
1. 版本号：`pom.xml` 与 `web/package.json` 对齐到 `1.0.0`，打 `v1.0.0` tag。
2. CI 任务（最小可用，先跑通再扩展）：
   - 编译：`./mvnw -B -DskipTests package`
   - 守卫：`check-deps` / `check-boundaries` / `check-i18n` / `check-ui`（这四脚本 2026-10-25 本地实跑全 PASS）
   - 前端：`make web-build`（tsc + vite build）
   - 集成测试 `make test`（Testcontainers）放到单独 job，需 Docker，首轮可能要调；**词表依赖**：`WORDLIST_INDEX_DIR` 与 `fixtures-local/vocab-lexicon` 不进 git（`.env.example` 明示），CI 里必须显式跳过或生成，否则 `vocab-lexicon-test` 必失败。
3. 镜像：`IMAGE_TAG=$(git rev-parse --short HEAD)`，推送到阿里云 ACR 或直接在 ECS 本地构建；compose 用环境变量注入 tag。
4. 发布动作脚本化：拉镜像 → `pg_dump` 备份 → `up -d` → 轮询 `/healthz?deep=1` → 失败即自动回滚（见 §8）。

## 5. 阶段 D · 数据与密钥（D1–D3，0.5 天）

- 生成 `TEACHLOOP_SECURITY_SECRET`（≥32 字节随机串，`openssl rand -base64 48`），**离线保管**：丢失后所有已存的 AI Key 都要重录。
- `.env` 权限 600，权限位写进部署检查单；`.env` 不在镜像、不进 git、不进日志。
- `SPRING_PROFILES_ACTIVE=prod`、`TEACHLOOP_SEED_ENABLED=false`。prod 会开启 Secure Cookie，**必须经 HTTPS 访问**，直接 `http://IP:8010` 登录会失败（不是 bug）。
- prod 下密码登录只对管理员开放，学生/教师走邀请与既有认证链——与邀请制首发一致。
- 附件存储：首发可 `STORAGE_BACKEND=local`（卷 `uploads`），但**必须做备份**；切 `s3` 前用测试桶先跑 `make test-oss` 真上传读回，桶保持私有。
- 备份：`scripts/backup-pg.sh`（2026-10-25 新增）+ cron/systemd timer 每日一次，输出目录再同步到 OSS；`uploads` 卷同步打包。

## 6. 阶段 E · 上机与冒烟（D20，1 天）

按 `docs/deploy-aliyun.md` §3→§4 执行，冒烟判据：

| 检查 | 期望 |
| :--- | :--- |
| `curl -fsS http://127.0.0.1:8010/healthz?deep=1` | `{"status":"UP","db":"UP","pdfFont":"UP"}` |
| `docker compose -f docker-compose.prod.yml ps` | `db`/`app`/`worker` 全 healthy |
| `https://<域名>/setup` | 可建首个管理员；建完自动关闭 |
| 配 AI 后重启 app 与 worker | `/admin/ai` 显示掩码，测试连接成功 |
| 建 1 班 + 1 份 3 题试卷 + 布置 | 学生能收到 |
| 学生提交（含 1 个 100MB 内附件） | 附件经鉴权接口可下载，未泄漏直链 |
| 批改 → 复核 → 发布 → 学生看反馈 | 全链路通 |
| 导出 XLSX / PDF | 中文显示正常（PDF 为非嵌入字体，复制中文会乱，属已知限制） |
| 备份脚本 | 产出 dump，`gunzip -t` 通过，大小 > 0 |

## 7. 阶段 F · 可观测与告警（D20，0.5 天）

- `/healthz?deep=1` 接阿里云云监控告警：连续 3 次失败 → 短信/钉钉。
- 磁盘使用率 > 80%、内存 > 85%、容器重启次数告警。
- 证书：certbot 自动续期 + `systemd` timer，另设「到期前 14 天」邮件兜底（自动续期失效是经典事故）。
- 日志：`/app/logs/requests-*.jsonl` 落卷并设 90 天轮转（应用内置清理，确认生效）。
- 一个能收信的域名邮箱（备案回访、证书通知、家长申诉）。

## 8. 回滚口径（写死，出问题时不讨论）

- **Flyway 迁移不可逆** → 回滚 = 旧镜像 + 恢复上线前的 `pg_dump`。
- 若迁移已产生新数据（例如上线后学生已提交），**不能简单回滚数据库**；只能前滚修复（hotfix 新 tag）。这条在上线当日必须有值班的人做判断。
- 每次发布前：`scripts/backup-pg.sh` 产出一份 dump，并把镜像 tag、dump 路径、发布时间写进发布记录（`docs/release/releases.md`）。
- 旧镜像保留最近 3 个 tag，不自动清理。

## 9. 阶段 G · 真实成本实测与灰度（D21–D27）

压测用的是 `stub` 判分引擎（`.docs/perf-report-v2.md`），**真实 DeepSeek 的吞吐与成本没有数据**。上线首周必须测出来：

- 一份典型英语卷（20 题客观 + 1 篇作文）走完 AI 判分的 token 用量、耗时、失败率。
- 一个 50 人班 × 1 次作业的总成本，据此定「每次作业成本上限」与告警阈值。
- 人工复核占比：AI 直接可用的比例决定运营人力。

灰度期间只放 1 个班；稳定后（一周无 P1、AI 失败率 < 阈值）再加第二个班。

## 10. 上线前置检查单

- [ ] 域名已实名，A 记录指向 ECS
- [ ] ICP 备案号已下，页脚已挂并链接工信部
- [ ] 公安联网备案已提交（30 日内）
- [ ] 隐私政策 / 用户协议 / 未成年人规则三个页面可访问，且与实现一致
- [ ] 家长知情同意流程有留档方式
- [ ] `v1.0.0` tag 已打，镜像 tag 为 git sha，`latest` 已从 prod compose 移除
- [ ] 工作区干净（无未提交改动）
- [ ] `.env` 权限 600，`TEACHLOOP_SECURITY_SECRET` 已离线备份
- [ ] `TEACHLOOP_SEED_ENABLED=false`、`SPRING_PROFILES_ACTIVE=prod`
- [ ] HTTPS 证书有效，自动续期演练过一次
- [ ] 备份脚本已跑通一次，且**恢复演练过**（空库导入 → 应用能起）
- [ ] `/healthz?deep=1` 已接告警
- [ ] 冒烟全表通过（§6）
- [ ] 回滚演练过一次（旧镜像 + dump 恢复）
- [ ] 已知限制已告知用户：PDF 中文不可复制/检索、AI 吞吐未压测、微信登录未真机验证

## 11. 成本明细（估算，2026-10 量级，非报价）

| 项 | 估算 | 备注 |
| :--- | :--- | :--- |
| 域名 `.com` | ¥60–90/年 | |
| ECS 4C8G 包年包月 | ¥300–400/月 | 备案要求 ≥3 个月起步 |
| 数据盘 / 快照 | ¥20–40/月 | |
| OSS（备份 + 附件，<100GB） | ¥20–50/月 | 首发可用本地卷，后期再切 |
| TLS 证书 | ¥0 | certbot |
| DeepSeek API | 按量，待实测 | 唯一随用量线性增长项 |
| **小计（不含 AI）** | **约 ¥400–500/月** | |

## 12. 已知风险与对策

| 风险 | 对策 |
| :--- | :--- |
| 备案被要求教育前置审批 | D1 就问阿里云备案客服，别等 20 天后被驳回 |
| `latest` 镜像导致回滚不确定 | compose 改 pin tag（§4.2） |
| Flyway 迁移不可逆 | 上线前强制 dump + 回滚演练（§8） |
| 真实 AI 成本未知 | 首周实测（§9），设预算告警 |
| `docs/DELIVERY-STATUS.md` 停在 2026-10-20，含一个 P1（班级成员面板） | 上线前按最新代码重核已知问题，不信旧文档 |
| 合规页面缺失 | §3，法律文稿需用户/法务定稿 |
| 词表与内容包不进 git | 部署时 `make vocab-lexicon` 生成并挂卷，否则词族区块降级隐藏（不影响启动） |

## 13. 轻量路径：先自己测、再请朋友帮测（不走备案）

上面 §1–§12 是「对真实学生长期用、大陆服务器、可回滚」的价钱。只做验证不需要它。按目的分三档，别混：

| 档 | 目的 | 成本 | 工期 | 要不要备案 |
| :--- | :--- | :--- | :--- | :--- |
| **A** | 自己点一遍，确认功能与流程 | ¥0 | 30 分钟 | 不要 |
| **B** | 朋友帮你测（3–10 人） | ¥40–100/月 + 域名 ¥70/年 | 半天 | **不要**（服务器在境外） |
| **C** | 正式对真实学生用 | ¥400–500/月 + AI 用量 | 3–4 周 | 必须 |

### A 档 · 本机（今天就能，零成本）

```bash
make dev                 # 起 Postgres(55432) + 后端(8010) + 前端(5173)，Ctrl-C 自动清理
make dev ARGS=--stub     # 没有 DEEPSEEK_API_KEY 时用替身引擎，流程照样能走完
make seed                # 需要演示数据：1 班 + 30 学生 + 1 份 4 题试卷
```

- 2026-10-25 实测：本机这套已在跑，`curl 127.0.0.1:8010/healthz?deep=1` → `{"status":"UP","db":"UP","pdfFont":"UP"}`，5173 返回 200。
- 入口 `http://localhost:5173`，本地测试账号 `teacher@teachloop.local` / `teachloop123`（仅 dev profile 有效）。
- 一路走完：建卷 → 布置 → 学生交卷 → 批改 → 复核 → 发布 → 导出 PDF/XLSX。
- 这一档**不要**用 `docker-compose.prod.yml`：prod 强制 Secure Cookie，`http://localhost:8010` 登录会失败（不是 bug）。

### A+ 档 · 免费公网试用（Cloudflare 临时隧道，0 元，5 分钟）

不想买服务器、只想让外网打开看一眼，用这个。不需要域名、不需要备案、自带 HTTPS。

```bash
# 1) 起隧道（把本机 dev server 挂到公网）
docker run -d --name teachloop-tunnel cloudflare/cloudflared:latest \
  tunnel --url http://host.docker.internal:5173
docker logs teachloop-tunnel 2>&1 | grep -oE "https://[a-z0-9-]+\.trycloudflare\.com"

# 2) 用完立刻关（链接随即失效）
docker rm -f teachloop-tunnel
```

前置：`web/vite.config.ts` 的 `server.allowedHosts` 必须放行 `.trycloudflare.com`，否则 Vite 回
`403 Blocked request. This host ... is not allowed.`（2026-10-25 实测确认，已配置）。

2026-10-25 实测：隧道首页 200、`POST /api/auth/login` 200、登录后 `GET /api/papers` 200，往返 0.13s。

**坑（都会真实咬人）**：

1. **dev profile 的演示账号是公开的**：任何拿到链接的人都能用 `teacher@teachloop.local / teachloop123` 以教师身份登录。只发给特定的人，用完 `docker rm -f teachloop-tunnel`。
2. **域名每次重启都变**，且国内访问 `*.trycloudflare.com` 常常很慢甚至打不开。给国内朋友之前先自己用手机 4G 点一下。
3. **电脑休眠/合盖即断**，不适合「挂一晚上让别人自助点」。
4. **重新构建 jar 会切断正在运行的后端**：`mvn package` 覆盖 jar 后，运行中的进程对未加载过的类抛 `NoClassDefFoundError`（2026-10-25 实测踩到：`ch/qos/logback/classic/spi/ThrowableProxy`，表现是带 `X-Forwarded-*` 的请求全部 400，本机 localhost 却正常）。这就是 `docs/DELIVERY-STATUS.md` 第 14 条的老坑：**先停后端再构建，构建后立刻重启**。
5. 免费但有代价：出网走 Cloudflare，学生作答内容会经过第三方 CDN（HTTPS 终止在 CF 边缘）。只用于演示，不要放真实学生数据。

### B 档 · 朋友帮测（半天，免备案）

前置：一台**境外**云服务器（香港/新加坡/日本，2C4G 起）+ 一个域名（解析到境外 IP **不需要**备案）。用大陆 ECS 就必须先备案，没有中间地带。

```bash
# 1) 服务器装 Docker + compose v2
git clone git@github.com:JZFeng/teachloop.git /opt/teachloop && cd /opt/teachloop
cp .env.example .env && chmod 600 .env
# .env 必填：POSTGRES_PASSWORD / DB_PASSWORD / TEACHLOOP_SECURITY_SECRET / SPRING_PROFILES_ACTIVE=prod
# 朋友测可以关掉朗读 sidecar 省内存：RUN_TTS_SIDECAR=false

# 2) 起服务（首次 build 约 5–10 分钟）
docker compose -f docker-compose.prod.yml up -d --build
curl -fsS http://127.0.0.1:8010/healthz?deep=1   # 期望 {"status":"UP","db":"UP","pdfFont":"UP"}

# 3) Caddy 自动 HTTPS（装 caddy 后，/etc/caddy/Caddyfile）
cat >/etc/caddy/Caddyfile <<'EOF'
teachloop.example.com {
    encode gzip
    request_body {
        max_size 100MB
    }
    reverse_proxy 127.0.0.1:8010
}
EOF
systemctl reload caddy
```

**B 档必须知道的坑（都会真实咬人）**：

1. **必须 HTTPS**：prod profile 的 Cookie 带 `Secure`，浏览器走 `http://IP:8010` 登录会「登录成功但立刻掉线」。所以域名 + 真证书不是可选项。
2. **8010 别对公网开**：`docker-compose.prod.yml` 现在是 `"8010:8010"`（绑定 `0.0.0.0`，2026-10-25 `docker compose config` 实测确认）。改成 `"127.0.0.1:8010:8010"`，只让 Caddy 进来。
3. **小内存机器要设上限**：容器没设 `mem_limit`，`docker/entrypoint.sh` 是 `-XX:MaxRAMPercentage=75`，两个 JVM（app + worker）会各自按宿主机内存的 75% 抢 → 2C4G 上会互相 OOM。给 `app: mem_limit: 3g`、`worker: mem_limit: 2g`（或直接用 4C8G）。
4. **朋友只能被邀请进来**：prod 下密码登录只对管理员开放。顺序是 `/setup` 建管理员 → 管理员发教师邀请码 → 教师建班发班级邀请码。
5. **别放真实学生数据**：朋友测试用假名、假班级；真实姓名/真实试卷属于个人信息，测试环境不该收。
6. **链接不要公开传播**：一旦在群里/公开渠道扩散，性质就从「内部试用」变成「对公众提供服务」，备案与合规问题立刻回来。
7. 备份：`scripts/backup-pg.sh` 在 B 档就能用（`BACKUP_DIR=/srv/backup scripts/backup-pg.sh`），C 档原样复用。

### 升级到 C 档的触发条件（满足任一条）

- 有真实学生（哪怕是 1 个班）开始用它交作业，且要长期留着数据；
- 服务器落在大陆 ECS；
- 要对外发链接、给不认识的人用、或学校要采购；
- 需要「出问题能回到前一天」的承诺。

这四条任一成立，回到 §1 走备案与流水线——不是变复杂了，是开始有人依赖它了。

