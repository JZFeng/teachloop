# TeachLoop

公网 AI 教学平台：教师出卷 → 学生作答 → AI 批改 → 教师复核 → 发布反馈与报告。
面向一位教师、若干班级、几十名学生的真实课堂场景，部署在阿里云 + 自有域名。

`loop` 同时指两件事：批改反馈的闭环（出卷 → 作答 → 批改 → 复核 → 报告），以及将来「背单词」模块的间隔重复循环。所以加模块不需要改名。

> **最近更新：2026-10-23。** 当前分支 `main`。近期完成：写作题按题项存 rubric、逐篇判分（决策 007 / T360–T367）；
> 没有 rubric 的作文按「内容 80% + 语法与拼写 20%」判分，比例走配置（决策 008 / T368）。

## 现在到哪一步了

| 阶段 | 状态 | 产出物 |
| :--- | :--- | :--- |
| Research | 完成（7 份，至 2026-10-22） | [`docs/research/`](docs/research/) |
| Constitution | 完成（v2.1，15 条） | [`.specify/memory/constitution.md`](.specify/memory/constitution.md) |
| Spec | 已确认 | 002：**28 用户故事 / 265 验收标准 / 65 功能需求**；003（UI 标准）：4 / 16 / 10；006（品牌视觉）：3 / 15 / 10；004（背单词）只有目录与骨架 |
| Mock | 完成 | [`specs/002-v2-platform/mockups/interface.html`](specs/002-v2-platform/mockups/interface.html)（20+ 屏，可点击）+ 考试材料版式；品牌样张 [`docs/mock/brand-2026-10-22/`](docs/mock/brand-2026-10-22/) |
| Plan | 已确认 | [`specs/002-v2-platform/plan.md`](specs/002-v2-platform/plan.md)、[`specs/003-ui-standard/plan.md`](specs/003-ui-standard/plan.md) |
| Tasks | 已拆完 | 002：224 个；003：21 个；006：13 个 |
| Implement | **进行中** | 主链路已跑通，见下节；剩余与已知偏差见「已知缺口」 |
| 验证 | 绿（2026-10-23） | `make test` 退出码 0：**286 tests / 0 failures / 0 errors / 0 skipped**；前端 28/28；见「质量守卫」 |

## 已经能用的东西

**教学闭环**

- 班级：建班、邀请码、成员与提交数、访问控制。
- 试卷：新建向导（部分 / 题项 / 评分项）、Markdown 整卷导入（单选 / 多选 / 填空 / 写作，含答案表与参考范文）、发布锁、复制、归档。
- 布置：考试（一份试卷可发给多个班、按班覆盖截止与时长、服务端权威计时、到点自动交卷）与作业（自带题目要求 + 总分 + rubric，不引用试卷；过截止仍可交并标迟交）。
- 作答：富文本（基础格式）/ 文件（PDF / DOCX / TXT / MD）/ 视频（ffprobe 校验）；阅读与完形材料按组只渲染一次。
- 批改：客观题由代码判；主观题与写作题由 AI 按 rubric 判（题项级 rubric 优先，回退试卷级，再回退默认 80/20，仅作文）。作业每晚 02:30（Asia/Shanghai）夜间批量，考试到期派发；支持单份「让 AI 先批」、重试失败项、僵尸清扫。
- 复核：预览 AI 逐项评分、改分、批量通过、发布 / 撤回；改动写审计。
- 反馈与报表：学生看自己的评语 / 优点 / 建议，以及反馈里的班级聚合数（应交 / 实交 / 均分）；教师看错题、班级与学生聚合；导出 Markdown / XLSX / DOCX / ZIP / PDF。
- 账号与运维：本地密码登录（dev）、管理员密码登录（prod）、微信登录 provider（未配置凭据返回 503，不伪造登录）、邮箱 / 手机号补全、教师个人 AI Key、管理员全局 AI Key（AES-GCM 加密、只回掩码）、站内通知、限流、审计、JSONL 请求日志与 `daily_stats`、`/healthz?deep=1`。
- 界面：中英双语、亮 / 暗主题、品牌层与四个功能位、令牌 + 组件层 + 守卫；弹层统一 Esc 关闭。

**当前缺口**

- 学生「班级概览」整屏与微信扫码登录的前端入口尚未落地；报表页的勾选导出还没接线（后端导出接口已有）。逐屏清单见 [`docs/reports/mockup-parity/README.md`](docs/reports/mockup-parity/README.md)（2026-10-20 审计，其后部分已修）。
- 背单词（004）只有空模块：无表、无端口、无路由，七步流水线未开始。
- OSS 只有骨架（`STORAGE_BACKEND=oss` 运行期 503）；PDF 中文用非嵌入字体；错题解析按需触发（AC-18.5 未自动生成）；压测用 stub 引擎。
- UI 标准 T308（21 屏截图基线）/ T309（剩余 13 屏组件化）/ T310（删兼容层）未做；品牌视觉的考试作答 live、管理员 AI 设置、反馈详情、`/setup` 未做浏览器验证（口径见 [`specs/006-brand-visual/spec.md`](specs/006-brand-visual/spec.md) 6.2）。

## 文档怎么读

1. 先读 [`AGENTS.md`](AGENTS.md)——七步顺序与红线，只有一屏。
2. 再读 [`specs/002-v2-platform/plan.md`](specs/002-v2-platform/plan.md) 的 §0「怎么评审这份文件」。
3. 界面长什么样，直接用浏览器打开 [`specs/002-v2-platform/mockups/interface.html`](specs/002-v2-platform/mockups/interface.html)（单文件，无需构建）。
4. 「为什么这样做」在 [`docs/decisions/`](docs/decisions/)（001–008）；当前进度与剩余看 [`docs/PROGRESS.md`](docs/PROGRESS.md)，已知偏差看 [`docs/DELIVERY-STATUS.md`](docs/DELIVERY-STATUS.md)。
5. 品牌源图与重新生成方式见 [`docs/brand/README.md`](docs/brand/README.md)。

## 技术栈

- 后端：Java 21 + Spring Boot 4.1.1（Spring Framework 7）+ Jackson 3（`tools.jackson`）+ Spring Data JPA / Hibernate 7 + Flyway + PostgreSQL 16
- 模型：DeepSeek `deepseek-flash`，经 Spring AI 2.0.1 `ChatClient` 调用 OpenAI 兼容端点；`teachloop.ai.client=http` 保留 JDK HttpClient 回退。Key 解析顺序是「教师个人 → 全局 → `DEEPSEEK_API_KEY`」，无 Key / 调用失败不出假分
- 前端：`web/`，React 19.3 + TypeScript 7 + Vite 8.3 + antd 6 + i18next 26；Markdown 编辑器用 Vditor 4.0.1
- 队列：不引入消息队列。提交行就是工作项，SQL 条件更新领取；`@Scheduled` + 数据库条件更新。考试到期派发、作业夜间批量、僵尸清扫、到点自动交卷都在这里
- 部署：单镜像（内置 Noto CJK 与 ffprobe）→ compose 起 app + worker + postgres，无 Redis

十个 Maven 子模块（`teachloop-shared` … `teachloop-app`）与依赖方向见 plan §3。**模块边界是编译期强制的**：`make check-deps` 守依赖方向，`make check-boundaries` 守表归属、跨模块外键与「端口只收发 record」。

## 目录

```
teachloop/
├── pom.xml                 Maven 聚合工程（Java 21 / Spring Boot 4.1.1）
├── teachloop-shared/       端口与共享原语（record、错误、时钟、审计）
├── teachloop-identity/     登录、会话、账号、通知、AI Key、管理员 CLI
├── teachloop-classroom/    班级与成员
├── teachloop-paper/        试卷、部分、题项、评分项、导入解析
├── teachloop-sitting/      布置、参与班级、作答、计时、提交
├── teachloop-grading/      派发、批改、复核、AI 调用留痕
├── teachloop-report/       报表与导出（只读聚合）
├── teachloop-observability/ 审计与统计
├── teachloop-vocab/        背单词骨架（无表、无端口、无路由）
├── teachloop-app/          装配与迁移调度
├── web/                    前端（Vite + React + TS + i18next）
├── specs/                  002 平台 / 003 UI 标准 / 004 背单词（待写）/ 006 品牌视觉
├── docs/                   调研、决策、品牌源图、评分模板、历史归档、报告
├── scripts/                边界守卫、环境脚本、品牌资产、CDP 验证
├── .specify/               项目宪法
├── Dockerfile / docker-compose.prod.yml / .env.example
└── Makefile
```

## 快速开始

```bash
make dev                  # 一键：Postgres + 后端 :8010 + 前端 :5173，Ctrl-C 自动清理
make dev ARGS=--seed      # 额外造演示数据：1 班 + 30 学生 + 1 份 4 题试卷
# 或分步：
make start-db             # 本地 Postgres（55432）
make build                # 后端编译打包
make test                 # 全部测试（Testcontainers，需要 Docker）
make run                  # 后端 :8010
make web-install          # 安装前端依赖
make web-dev              # 前端 :5173，/api 代理到 :8010
make web-build            # 前端生产构建
```

开发账号：`teacher@teachloop.local` / `student@teachloop.local`，密码 `teachloop123`（`DevDataSeeder` 创建；`TEACHLOOP_SEED_ENABLED=false` 时不建）。

### 配 AI Key

三种来源，优先级是「教师个人 → 全局 → 环境变量」：

- 首次部署：打开 `/setup` 创建管理员并填全局 Key。
- 管理员：`/admin/ai` 改全局 Key、测试连接。
- 教师：`/profile` 填个人 Key，只影响自己布置的作业与考试。
- 本地也可以直接 `DEEPSEEK_API_KEY=... make dev`。

Key 以 AES-GCM 加密存库，读取只返回掩码；保存后下一次调用生效。没有 Key 时调用直接失败并把该份标 FAILED，不会写假分。

### 第二个环境：边改代码边做 E2E

`make dev` 那套是**开发环境**——源码一改页面就热更新，重新打包会覆盖正在跑的 jar。要一边开发一边有一个稳定的地方点，用测试台：

```bash
make qa              # 构建快照 → 起在 :8011（后端）/ :5174（前端）
make qa-status       # 两个环境的端口、健康、版本
make qa-logs         # 跟踪测试台后端日志
make qa-down         # 停掉
```

测试台不共享任何可变状态：

| | 开发（`make dev`） | 测试台（`make qa`） |
|---|---|---|
| 后端 | `teachloop-app/target/*.jar`，每次重建都是新的 | `.envs/qa/app.jar` **副本**，只有跑 `make qa` 才更新 |
| 前端 | `vite dev` 监听源码，改代码即热更新 | `vite preview` 服务 `.envs/qa/web/` 的**静态快照** |
| 数据 | 容器 `teachloop-pg`（55432）/ 库 `teachloop` | 容器 `teachloop-pg-qa`（55433）/ 库 `teachloop_qa` |
| 附件 | `./uploads` | `.envs/qa/uploads` |

我在开发环境里改文件、跑测试、重新打包，测试台上跑的还是上一次 `make qa` 的快照。想看到新东西就再跑一次 `make qa`；`make qa-status` 会打出各自的 commit，提 bug 时带上那个版本号。

## 质量守卫

`make test` 会跑完下面除「品牌资产校验」以外的全部条目；品牌校验单独跑。任何一条红都不算完成：

| 命令 | 守什么 | 当前数 |
| :--- | :--- | :--- |
| `make check-deps` | 十个模块的依赖方向，禁止反向边与环 | 通过 |
| `make check-boundaries` | 每张表只有一个属主；跨模块外键必须登记；端口只收发 record | 25 表 / 13 外键 / 7 端口 |
| `make check-i18n` | 前端 zh/en 键完全对齐；新增中文字面量即失败 | 各 1219 键 |
| `make check-ui` | 样式只从令牌来；颜色对比度与两套令牌一致 | 存量 122 处，无新增；对比度 40 组 |
| `make web-test` | Vditor IR 的 Markdown 往返金标与弹层栈单测 | 28/28 |
| `make test` | 以上全部 + `mvn test`（Testcontainers 连 Postgres 16） | 286/0/0/0 |
| `python3 scripts/brand/build-assets.py --check` | 两张品牌源图 sha256 与 9 个产物齐全（需要 Pillow） | 通过 |

真实 AI 与评分质量另有两条按需命令：`make test-ai`（需要 `DEEPSEEK_API_KEY`，跑 `@Tag("ai")` 真机调用）、`python3 scripts/golden-set.py`（人工均分对照）。

## 部署

生产用 `Dockerfile` + [`docker-compose.prod.yml`](docker-compose.prod.yml)（app + worker + postgres），步骤见 [`docs/deploy-aliyun.md`](docs/deploy-aliyun.md)。

```bash
cp .env.example .env      # 至少填 POSTGRES_PASSWORD、DEEPSEEK_API_KEY、TEACHLOOP_SECURITY_SECRET
make docker-build
make compose-up           # 对外 :8010；首次打开 /setup 建管理员
```

生产必须 `SPRING_PROFILES_ACTIVE=prod`、`TEACHLOOP_SEED_ENABLED=false`，并经 HTTPS 访问（prod 会启用 Secure Cookie）。健康探针用 `/healthz?deep=1`（数据库或 PDF 中文字体异常返回 503）。

## 隐私

真实试卷与真实学生作答**不进入本仓库**（宪法第七条）。仓库内的样例提交与评语是脱敏的教学样例，仅用于测试评分流程。
