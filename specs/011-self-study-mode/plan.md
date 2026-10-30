# 实施计划 — 自学模式与统一今日页（011-self-study-mode）

- 上游：`specs/011-self-study-mode/spec.md`（7 US / 32 AC / 21 FR）、`docs/mock/self-study-2026-10-30/`、`docs/research/2026-10-30-self-study-overview.md`
- 状态：**草案（2026-10-30），待 review**；未拆 Tasks
- 配套：宪法第十四条（模块边界）、第十五条（UI 与可访问性）
- 迁移序号：写作时全库最大 `V075`，本计划取 `V076` / `V077`；**合并前按 main 现状重排**（第 4 条铁律：序号不预留）

---

## 1. 架构总览

单进程、单库、**进程内调用**——不是微服务编排。浏览器只看到 `GET /api/student/today` 一个端点。

```
web/src/student/TodayPage.tsx
        │  GET /api/student/today  (X-Timezone: America/Los_Angeles)
        ▼
teachloop-app  StudentTodayEndpoint        ← 只做：权限 / 时区解析 / 取数 / 拼装 / 降级
        │
        ├── Ports.ReviewSlicePort   ← 实现：teachloop-vocab  ReviewSliceAdapter
        ├── Ports.AgendaPort        ← 实现：teachloop-sitting AgendaAdapter
        └── Ports.UserZonePort      ← 实现：teachloop-identity UserZoneAdapter
```

- 依赖方向：`app → 所有业务模块`（`scripts/check-deps.py:28-29` 已允许）；业务模块之间只通过 `shared` 的端口互访。
- 本计划**新增 3 个端口**；`ClassStatus`（归档过滤）属于 012，这里只留接入点，不实现。
- app 层红线：**不得出现业务表 SQL / `JdbcTemplate` / `@Query`**，唯一白名单是 `DeepHealthEndpoint`（探活）。

## 2. 数据模型

### 2.1 `users.timezone`（属主：identity）

```sql
-- V076__identity_user_timezone.sql
alter table users add column timezone text;
```

- 可空：空值表示沿用回落链（`X-Timezone` → 产品默认）。
- 不加索引：只按主键取单行。
- 校验在服务端：只接受 `ZoneId.getAvailableZoneIds()` 里的值，最长 64 字符；非法值写入 `null` 并记一条 warning，不拒绝请求。

### 2.2 `classes` 收费字段（属主：classroom，US-95）

```sql
-- V077__classroom_class_pay.sql
alter table classes add column pay_mode    text    not null default 'FREE';
alter table classes add column price_cents integer not null default 0;
alter table classes add column currency    text    not null default 'CNY';

alter table classes add constraint ck_classes_pay_mode check (pay_mode in ('FREE', 'PAID'));
alter table classes add constraint ck_classes_price    check (price_cents >= 0);
```

- 存量班级自动成为 `FREE / 0 / CNY`，不需要回填。
- 第一期只允许 `FREE`：建班接口遇到 `pay_mode != FREE` 返回 400；入班遇到 `PAID` 返回 409。
- `currency` 用 `text` 而不是 `char(3)`：全库 68 个迁移里没有 `char(n)` 用法（只有 `varchar(n)`），长度由服务端校验（ISO-4217 三位大写）。

### 2.3 索引（属主：sitting）

今日页的作业查询从「学生的班」出发，现有索引不够：

```sql
-- V078__sitting_offering_classes_class_ix.sql
-- offering_classes 现在只有 unique (offering_id, class_id)（V005:31），前导列是 offering_id，
-- 按 class_id 过滤用不上 → 新查询会退化成全表扫。
create index offering_classes_class_ix on offering_classes (class_id);

-- submissions 已有 student_ix(student_id)（V009:12）与 active_uk(offering_id, student_id) where active；
-- 是否需要 (student_id, offering_id) 复合索引，按 EXPLAIN 结果决定，不预先加（见 §4.1 的验收）。
```

### 2.4 状态迁移

| 实体 | 前 | 后 | 触发 |
|---|---|---|---|
| `users.timezone` | `null` | IANA 名 | 资料页保存 / 首次带头的请求回写（只回写一次，避免每请求写库） |
| `classes.pay_mode` | 无 | `FREE` | 迁移默认值 |
| `classes.pay_mode` | `FREE` | `PAID` | **本期不开放**，仅字段预留 |

## 3. 端口与签名

全部写进 `teachloop-shared/src/main/java/com/teachloop/shared/port/Ports.java`（`check-boundaries` 的端口签名规则只允许 record 与 JDK 值类型；`ZoneId` 不在白名单，因此用 `String` 包装）。

```java
public record ReviewSlice(int due, int reviewedToday, int newToday, int newLimit) {}

public record AssignmentRow(UUID id, String title, String className,
                            Instant dueAt, String status) {}

public record ExamRow(UUID id, String title, String className, Instant startsAt) {}

/** 学生的时区（IANA 名）。空 Optional = 未设置。 */
public record UserZone(String iana) {}

public interface ReviewSlicePort {
    ReviewSlice today(UUID userId, String iana);
}

public interface AgendaPort {
    List<AssignmentRow> pending(UUID studentId);
    List<ExamRow> upcoming(UUID studentId, Instant from, Instant to);
}

public interface UserZonePort {
    Optional<UserZone> of(UUID userId);
}
```

实现类（各模块内，`@Component`）：

| 端口 | 实现 | 位置 | 备注 |
|---|---|---|---|
| `ReviewSlicePort` | `ReviewSliceAdapter` | `teachloop-vocab/…/review/` | 复用现有队列口径（`ReviewQueueEndpoint` 的 `countReviewedToday` 抽成公共方法，端点与端口共用，禁止两份实现） |
| `AgendaPort` | `AgendaAdapter` | `teachloop-sitting/…/port/` | 复用 `studentUtils.loadStudentRows` 的服务端等价查询；`status` 映射与前端三态一致；`className` 走已有的 `Ports.ClassMembership.classNames(...)`（`OfferingEndpoint:551` 已在用），**不新增第 4 个端口** |
| `UserZonePort` | `UserZoneAdapter` | `teachloop-identity/…/profile/` | 单行查询 `users.timezone` |

## 4. 今日页聚合流程（app）

`StudentTodayEndpoint.today(CurrentUser me, @RequestHeader("X-Timezone") String tzHeader)`：

1. **权限**：非 `STUDENT` 直接返回三段空值 + `partial` 全 `ok`（AC-94.3）。
2. **时区**：`ZoneResolution.resolve(tzHeader, userZonePort.of(me.id()), productDefault)` → `ZoneId`（§5）。
3. **取数**（三段独立 `try/catch`，互不影响）：
   - `reviewSlicePort.today(me.id(), zone.getId())`
   - `agendaPort.pending(me.id())`
   - `agendaPort.upcoming(me.id(), now, now.plus(7, DAYS))`
4. **排序与截断**：
   - 作业：`dueAt` 升序，逾期的按「逾期更久」排在前；返回 `items`（最多 3 条）+ `total`。
   - 考试：`startsAt` 升序，最多 2 条。
5. **降级**：任一段抛异常 → 该段置空并把 `partial.<section>` 设为 `"error"`，**整体仍返回 200**（AC-94.2）。
6. **响应**：形状严格按 spec §7.1，额外增加 `partial`：

```json
{ "review": {...}, "assignments": {...}, "exams": {...},
  "partial": { "review": "ok", "assignments": "error", "exams": "ok" } }
```

- 不做并行：三次单库查询是毫秒级，引入 `CompletableFuture` 只会让异常处理更难（本计划明确否决）。
- 禁止 N+1：端点内不得循环调用 offering 相关接口（AC-90.7）。

### 4.1 作业查询形状（AC-90.2 / 90.7 的承接）

现有学生首页是「读全部已发布作业 → 逐个判断我在不在 → 逐个拼详情」，前端再对每个作业打两个请求（`OfferingEndpoint.java:214`、`web/src/student/studentUtils.ts:18`）。今日页不能照抄，改成**从学生的班出发的一次查询**：

```sql
-- 只碰 sitting 自己的表（offerings / offering_classes / submissions）；
-- classes 属于 classroom，按表属主规则**不得 join**，班名由端口批量补。
select o.id            as offering_id,
       o.title,
       oc.class_id,
       coalesce(oc.due_at_override, o.due_at) as due_at,
       case when s.id is null or s.submitted_at is null or s.rejected_at is not null
            then 'todo' else 'submitted' end as status
from offering_classes oc
join offerings o  on o.id = oc.offering_id
left join submissions s
       on s.offering_id = o.id and s.student_id = :studentId and s.active
where oc.class_id = any(:myClassIds)          -- 来自 Ports.ClassMembership.classIdsOf（1 次）
  and o.status = 'PUBLISHED'
  and (s.id is null or s.submitted_at is null or s.rejected_at is not null)   -- 只要待交
order by due_at asc
limit :limit
```

补充说明：

1. **班名不在 SQL 里**：查出 `class_id` 后调一次 `Ports.ClassMembership.classNames(ids)` 批量补名（`OfferingEndpoint.java:551` 已在用这个模式）。这条不是洁癖：`check-boundaries` 的 `check_module_isolation` 会拦 `@Query` 里出现别的模块的表。
2. **`status` 只有两态**：`todo` / `submitted`。三态里的 `published` 需要 grading 的逐份发布状态（`ExamSessionEndpoint.scoresVisible` 走 `ReviewFeedback` 端口），今日卡只回答「要不要去交」，不需要它。**spec §7.1 的 status 说明按此修订**（列表页仍保留三态）。
3. **多班同一作业**：一个作业发到两个班时，同一学生会出现两行；按 `offering_id` 去重，`dueAt` 取更早者（与 `OfferingWindows.resolve` 的「取最早截止」一致）。
4. **请求数**：`classIdsOf`（1）+ 本查询（1）+ `classNames`（1）+ 复习切片（1）＝4 次，全部 O(1)，与作业数量无关。
5. **EXPLAIN 验收**：执行计划必须命中 `offering_classes_class_ix`，不得出现 `Seq Scan on offering_classes`；若 `submissions` 侧成本高，再补 `(student_id, offering_id)` 复合索引（那时才写第 4 个迁移）。

## 5. 时区

解析（`teachloop-shared/…/time/ZoneResolution.java`，纯函数，无 Spring 依赖）：

```java
static ZoneId resolve(String header, Optional<UserZone> stored, ZoneId productDefault) {
    // 1. header 合法 → 用它   2. stored 合法 → 用它   3. 否则 productDefault
}
```

- 产品默认：`teachloop.timezone.default: ${TEACHLOOP_TIMEZONE:Asia/Shanghai}`（`application.yml`，各环境 overlay 可覆盖）。
- 改动的**只有两处**计算日界：`ReviewQueueEndpoint.countReviewedToday`、`QuizEndpoint.todayWords`。
- **不改** `VocabularyGenerationService`（AI 日预算）：成本控制口径不随学生时区漂移（FR-228）。
- 回写 `users.timezone`：仅当资料页保存时写；请求头不落库（避免写放大）。

已知限制（接受，写进 spec 与验收报告）：

1. **旅行会重置两次**：一天内跨时区可能出现两个「新的一天」，每日新卡上限重置两次。只影响个人配额，不影响正确性。
2. **老师与学生的「今天」可能不同**：老师报表仍按产品时区；今日页只服务学生个人口径。
3. **DST**：`LocalDate.atStartOfDay(zone)` 已处理非 24 小时的一天；测试必须覆盖 `America/Los_Angeles` 的 2027-03-14 与 2027-11-07。

## 6. 资料补全改造（US-93）

`ProfileCompletionFilter` 从「普通请求 403」改为「放行 + 打标记」：

| 变化 | 位置 |
|---|---|
| 过滤器不再返回 403，只把 `profileIncomplete` 写入请求属性 | `ProfileCompletionFilter.java` |
| `/api/auth/me` 返回 `profileIncomplete: boolean` | `AuthEndpoint` 或 `ProfileEndpoint` 的 me 组装处 |
| 教师写操作显式校验：建班、发词（以及 012 的结课/恢复） | `ClassEndpoint.create`、`VocabularyAssignmentEndpoint.create` |
| 前端：顶部提示条 + `/welcome` 首次引导 | `web/src/app/App.tsx`、新 `WelcomePage.tsx` |

影响面（全部要回归）：登录、注册、激活、改密、找回密码、资料页、教师建班、发词、学生领取。清单进 §9。

## 7. 前端

| 项 | 方案 |
|---|---|
| 路由 | `/` 渲染 `StudentTodayPage`（教师仍走 `TeacherHomePage`，`HomeRouter` 只改 STUDENT 分支） |
| 侧栏 | 三个 key（`nav.vocab.review` / `library` / `lists`）**保留不变**（FR-204）；`nav.ts` 给它们加 `group: "vocab"`，`navSpecs` 把同组折叠成**一项**「背单词」，落点 `/vocab/review`；该项的高亮用三条路径任一匹配（自定义 `isActive`），不新增一级 key |
| 背单词模块 | `VocabShell` + 页内 Tab（**今日复习 / 我的词单 / 我的词库**），地址仍为 `/vocab/review`、`/vocab/library`、`/vocab/lists`；`nav.ts` 折叠后的落点是 `/vocab/review` |
| 组件 | `TodayPage` / `TodayReviewCard` / `TodayAssignmentCard` / `TodayExamCard` / `InviteClassCard` |
| 文案 | 新增 `today.*` 前缀，中英同步（`scripts/check-i18n.py` 守） |
| 状态 | 每卡独立 loading / empty / error（重试按钮），页面级 200 + `partial` 决定哪张卡显示错误 |
| 断点 | 只用 `max-width:1100px`（单列）与 `max-width:1023px`（侧栏抽屉），不新增第三档 |
| 平板 | 834×1194 与 1024×1366：`scrollWidth <= innerWidth + 2`，主操作 `rect.bottom <= innerHeight`（AC-90.6） |
| 宽度 | 列表页满宽（`max-width:1240px`）；今日页 880px；会话页全屏无侧栏 + 卡片 720px（对齐 `web/src/vocab/vocab.css:48` 的 `review-card-column`） |

## 8. 迁移与回滚

| 迁移 | 内容 | 回滚 |
|---|---|---|
| `V076__identity_user_timezone.sql` | `users.timezone` | `alter table users drop column timezone;`（列可空，无数据依赖） |
| `V077__classroom_class_pay.sql` | `classes` 三列 + 两个 check | 先删约束再删列；`drop column` 前确认无代码读取（回退代码后再执行） |
| `V078__sitting_offering_classes_class_ix.sql` | `offering_classes(class_id)` 索引 | `drop index offering_classes_class_ix;`（纯性能索引，删除不影响正确性） |

- 顺序：先回退应用版本，再执行 SQL 回滚（避免旧代码读到新列名不兼容）。
- 两份迁移都可在有数据的库上执行；不在迁移里做数据回填。
- 与 012 的关系：012 的 `class_members.status` 回滚必须先把 `LEFT` 改回 `ACTIVE`（见 012 §8），**不在本计划内**。

## 9. 测试策略

| 层 | 覆盖 | 命令 |
|---|---|---|
| 单测（Java） | `ZoneResolution`：优先级、非法值、DST 两天；`StudentTodayEndpoint`：排序、截断、降级；`AgendaAdapter` 的 status 映射 | `./mvnw -pl teachloop-app,teachloop-shared,teachloop-vocab,teachloop-sitting test` |
| 集成（Java） | `/api/student/today` 三段齐全、教师调用返回空、`partial=error` 时仍 200；`/api/vocab/review/queue` 带不同 `X-Timezone` 得到不同日界 | 同上（`@SpringBootTest`） |
| 前端纯函数 | 排序/截断/状态映射（`node --test`） | `make web-test` |
| 端到端 | 注册 → 加词 → 今日页显示复习卡 → 开始复习；入班 → 今日页出现作业卡；iPad 两档断言 | Playwright 三 project（`docs/process/e2e-playwright.md`） |
| 守卫 | `make check-deps check-boundaries check-i18n check-ui` | 见 Makefile `test` 目标 |

交付口径：只跑单测 = 「接口完成」；Playwright 跑通 = 「界面验收」。**不得用「结构校验」冒充界面验收。**

## 10. 外部依赖与失败路径

| 依赖 | 失败时 | 重试 |
|---|---|---|
| Postgres（三段查询） | 该段 `partial=error`，页面显示单卡错误 + 重试按钮；整体 200 | 用户点重试；不做自动重试 |
| `UserZonePort`（identity 单行查询） | 回落到产品默认时区，记 warning；不发生 500 | 不重试 |
| 浏览器时区头缺失/非法 | 回落链第二级；仍非法则产品默认 | 不适用 |
| AI 生成 / TTS | **本计划不新增调用**，行为不变 | 不适用 |

## 11. 模块边界与守卫注册

| 项 | 改动 |
|---|---|
| 新端口 | `ReviewSlicePort` / `AgendaPort` / `UserZonePort` 写进 `Ports.java`；实现类在各自模块 |
| 表归属 | `TABLE_OWNER` **不新增表**；`users` / `classes` 已在清单内 |
| 迁移前缀 | 文件必须叫 `V0xx__identity_*` / `V0xx__classroom_*`（`check_migrations` 校验前缀与目录一致） |
| **app SQL 守卫（新增）** | 扩展 `check_endpoint_persistence`：把 `app` 纳入扫描，允许清单只有 `DeepHealthEndpoint.java`；除它之外任何 app 类引用 `JdbcTemplate` / `JdbcClient` / `@Query` 即失败 |
| 端口签名守卫 | 新 record 定义在 `Ports.java` 内即被 `found_records` 自动允许；`ZoneId` 不得出现在签名（用 `String`） |
| 依赖清单 | `check-deps.py` **无需改动**（app 已允许依赖全部模块） |

## 12. 拆服务迁移路径（现在不做，路线写下）

| 步骤 | 内容 | 成本量级 |
|---|---|---|
| 1 | 三个端口签名改 HTTP 契约（DTO 已是 record，形状不变） | 1 人周 |
| 2 | 鉴权透传（会话 → 服务间 token）+ 超时/重试/幂等 | 1 人周 |
| 3 | 数据拆分：`vocab_*` / `classes` / `users` 分库，跨库引用改端口调用 | 2–3 人周 |
| 4 | 一致性：今日页三段可能读到不同时刻，需要版本号或读己之写 | 1 人周 |
| 5 | 运维：部署、监控、链路追踪、回滚 | 1 人周 |

触发条件（任一满足才拆）：单模块负载是其它模块的 10 倍以上（AI 生成 / TTS 已由 `worker` 进程承担）；团队 ≥3 人抢同一个库；需要独立发布节奏。

## 13. Plan review 清单（6 条）

| # | 条目 | 结论 | 证据 |
|---|---|---|---|
| 1 | 每条 AC 都能指出由哪张表/流程承接 | ✅ | §14 的 AC→承接表，33 条全覆盖 |
| 2 | 每个实体表、关键字段、索引、状态迁移写全 | ✅ | §2：两列迁移 + 一条新索引（V078）+ 状态迁移表；查询形状与 EXPLAIN 验收见 §4.1 |
| 3 | 每条外部依赖写了失败与重试路径 | ✅ | §10；本计划不新增外部调用 |
| 4 | 模块依赖无环、跨模块只走端口 | ✅ | §1 依赖图 + §3 三个端口；`check-deps`/`check_module_isolation` 兜底 |
| 5 | 每张表只有一个属主、无跨模块 JOIN | ✅ | `TABLE_OWNER` 不变；`ReviewSliceAdapter` 只读 `vocab_*`，`AgendaAdapter` 只读 `sitting` 的表 |
| 6 | 拆服务路径写得出来 | ✅ | §12 五步 + 触发条件 |

## 14. AC → 承接

| AC | 承接 |
|---|---|
| AC-90.1 / 90.8 | `StudentTodayPage` + `StudentHome` 路由改向（§7） |
| AC-90.2 | `AgendaAdapter.pending` 的单查询 + 排序 + `TodayAssignmentCard` 页内展开（§4.1 / §7） |
| AC-90.3 / 90.4 | 卡片渲染规则（前端）+ `partial`（§4.5） |
| AC-90.5 | 无班级分支：`ClassMembership` 为空 → 不渲染作业/考试卡，渲染 `InviteClassCard` |
| AC-90.6 | 前端断点 + Playwright iPad 两档断言（§7 / §9） |
| AC-90.7 | 端点只调 3 个端口、1 次 HTTP；作业侧 4 次 O(1) 调用（§4.1 第 4 条） |
| AC-91.1–91.5 | `navSpecs` 按 group 折叠三项为一项（不新增 key）+ `VocabShell` 页内 Tab（顺序 今日复习 / 我的词单 / 我的词库）+ `features.vocab` 开关（§7） |
| AC-92.1–92.4 | 复用 `POST /api/classes/join`；`InviteClassCard` 内联错误（§7） |
| AC-93.1–93.4 | `ProfileCompletionFilter` 改造 + `me.profileIncomplete` + 教师写操作显式校验（§6） |
| AC-94.1–94.3 | 响应形状 + `partial` + 非学生空值（§4.6） |
| AC-95.1–95.4 | `V077` + 建班 400 / 入班 409（§2.2） |
| AC-99.1–99.5 | `ZoneResolution` + `UserZonePort` + 两处日界改造 + 前端提示（§5） |

## 15. 已定与待定

**已定（2026-10-30，可改但不再等确认）**

1. 教师侧不变：`nav.vocab.sets` / `nav.vocab.assign` 仍是侧栏一级项，不放进学生的 `VocabShell`。理由是教师是「备课时用工具」，不是「每天来学习」，折叠反而多一次点击。
2. `/welcome` 与 `/profile` 保留两个页面：前者是首次引导（可跳过），后者是长期资料页。合并会让「跳过」没有落点。
3. 产品默认时区配置项名用 `teachloop.timezone.default`，环境变量 `TEACHLOOP_TIMEZONE`。
4. 今日页的 `status` 只返回 `todo` / `submitted` 两态；`published` 由列表页负责（理由见 §4.1 第 2 条）。spec §7.1 随之修订。

**待定**

- 无。等你 review 本计划。
