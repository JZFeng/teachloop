# 决策 031 — 持久层分工收口：不引 mapper、不拆微服务、不上 MQ（2026-10-29）

- 状态：**已实现**（持久层收口 + 守卫）；微服务/MQ 为**不做**的决定，按 §3.5 触发条件再议
- 关联：`specs/002-v2-platform/plan.md` §2 技术栈、§3.3.1 持久层两条规则、§3.5 拆服务路径；`scripts/check-boundaries.py`
- 起因：用户 2026-10-29「我们现在这么多数据表了，有没有必要引入 mapper 之类的……是不是该走 micro service，上消息队列了……我总担心迁移成本太高」

## 背景（盘点事实）

| 事实 | 数字 | 复核方式 |
| :--- | :--- | :--- |
| 表 | 58 张（去重）/ 68 个源迁移 | `find . -path "*/db/migration/*.sql" -not -path "*/target/*"` |
| JPA 一侧 | 26 个 `@Entity` + 24 个 `JpaRepository`，分布在 7 个模块 | `grep -rl "@Entity" --include=*.java` |
| 手写 SQL 一侧 | 26 个生产文件 | `grep -rl "JdbcTemplate" --include=*.java */src/main` |
| 最新模块 | `vocab`（25 张表 = 43%）100% 手写 SQL，**0 个 `@Entity`** | 同上，按模块统计 |
| SQL 漏到端点 | vocab 5 个 `*Endpoint.java` 自持 `JdbcTemplate`，共 26 条 SQL | `QuizEndpoint` 12、`ReviewQueueEndpoint` 3、`ReviewSubmitEndpoint` 5、`ReviewPreparationEndpoint` 5、`ReviewPrefsEndpoint` 1 |
| MyBatis | pom 里出现 0 次 | `grep -rn mybatis --include=pom.xml` |
| 已拆进程 | `app` + `worker` 两个进程、同镜像不同 profile | `docker-compose.prod.yml` |
| 已有 DB 队列 | 3 处，`for update skip locked` 抢件 | `OutboundMessageRepository.java:60`、`GenerationJobStore.java:177`、grading dispatch |

## 决定（用户拍板）

| 议题 | 决定 | 被否掉的选项与原因 |
| :--- | :--- | :--- |
| 引入 MyBatis / 全量 JPA | **都不做**；维持 JPA（实体）+ 手写 SQL（队列/批量/统计）双轨，把规则写死 | 端口已要求「只收发 record、不许 `@Entity`」（§3.2 / T204），JPA 的实体图与懒加载优势被砍掉一半；`for update skip locked`、`on conflict`、`cast(? as jsonb)` 用 JPA 表达不了 |
| 拆微服务 | **不拆**；按 §3.5 既定顺序，只在触发条件出现时拆 | 现在 1 个教师、几十个学生（plan §3 原文），拆服务的网络/部署/分布式事务成本立刻要付，独立扩容的收益用不上 |
| 上 Kafka / RabbitMQ | **不上**；DB 队列已具备「至少一次投递 + 幂等领取 + 僵尸清扫」 | 多一个有状态组件要备份监盘；prod 是 8G 小主机，一个 broker 吃 1–2G，而 `app`/`worker` 的 `mem_limit` 都还没设 |
| 迁移成本 | 判断为**低**：1 JAR + 1 Postgres + compose + 出站隧道 + S3 协议存储 + DB 会话，是云上最便宜搬的形状 | 拆微服务反而**抬高**迁移成本（9 个部署单元、服务发现、跨服务追踪） |
| 本轮实际动手 | **只做两件**：① 5 个端点的 SQL 搬进 Store；② 加守卫 + 修文档漂移 | 其它都留到触发条件出现 |

## 已落地的两条规则

1. **SQL 只在 `*Repository` / `*Store`**；`*Endpoint.java` / `*Controller.java` 不得持有 `JdbcTemplate`。
   `scripts/check-boundaries.py` 的 `check_endpoint_persistence` 强制（`app` 豁免：`DeepHealthEndpoint` 深探需要 `select 1`）。
2. **一张表只允许一种访问方式**，由属主模块决定用哪种。已知存量重叠 2 处（`submissions`、`offerings`，在 `ClassSubmissionStatsImpl.java:24` / `PaperUsageImpl.java:20`），不在本轮回收范围。

## 验收

- 新增 `QuizStore` / `ReviewQueueStore` / `ReviewProgressStore` / `ReviewPreparationStore`，`ReviewPrefsStore` 加 `save()`；26 条 SQL 原样搬移，HTTP 契约与 `@Transactional` 位置未变。
- 守卫：故意放一个持 `JdbcTemplate` 的 `GuardProbeEndpoint` → `exit=1`；删掉 → `exit=0`。
- 测试：`teachloop-vocab` 单元 **125 passed / 1 skipped**（live AI）；`teachloop-app` 的 `Vocab*Test` 全量 **54/54**；`check-boundaries` / `check-deps` 通过。
- 未验证：浏览器实测（本次不改任何 HTTP 行为，无界面变化）。

## 未决

- **UI 层零自动化回归**是 feature parity 的唯一真空（§18.4 是手工清单）。用户 2026-10-29 决定**单开一个 session** 用 Playwright + page object 补，本轮不做。
- 62 个黑盒 HTTP 测试参数化成 `TEACHLOOP_TEST_BASE_URL` 可指向任意部署（迁移当天当验收套件用）——顺延到那个 session 一起做。
