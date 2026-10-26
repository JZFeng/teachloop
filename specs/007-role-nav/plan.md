# 实施计划 — 角色导航矩阵（007-role-nav）

- 上游：`specs/007-role-nav/spec.md`
- 状态：**定稿（2026-10-25）；6 条清单全部打勾，可拆 tasks**
- 相关：实验开关是独立服务（`specs/008-flags-service/`），与本计划无代码依赖，可并行

---

## 1. 改动范围

只有五个落点：后端矩阵、`/api/features` 合并、前端菜单目录、前端路由守卫、后端收口。**不新增表、不新增端点、不新增服务。**

| 落点 | 文件 |
| :--- | :--- |
| 角色矩阵（唯一真源） | `teachloop-identity/.../nav/RoleNavMatrix.java` |
| 菜单目录与渲染 | `web/src/app/nav.ts` |
| `/api/features` 合并 | `teachloop-app/.../FeaturesEndpoint.java` |
| 路由守卫与落地 | `web/src/App.tsx` |
| 后端收口 | `teachloop-identity/.../security/SecurityConfig.java` |
| 班级页入口 | `web/src/teacher/ClassesPage.tsx` |
| 测试 | `web/tests/vocab-nav.test.mjs`、`IdentityAuthTest` |

## 2. 矩阵归属：后端唯一真源

### 2.1 后端（矩阵在这里）

```java
// teachloop-identity/.../nav/RoleNavMatrix.java
static final Map<UserRole, Set<String>> ROLE_KEYS = Map.of(
  ADMIN,   Set.of("nav.classes", "nav.ai"),
  TEACHER, Set.of("nav.home", "nav.classes", "nav.assignments", "nav.papers",
                  "nav.vocab.sets", "nav.vocab.assign", "nav.grading", "nav.review", "nav.reports"),
  STUDENT, Set.of("nav.home", "nav.classes", "nav.vocab.library", "nav.vocab.review", "nav.feedback")
);
```

`GET /api/features` = `ROLE_KEYS(role)` ∪ vocab 模块开关 ∪ 实验订阅（008 服务），输出扁平布尔，含 false 的 key 以便前端明确判断。

### 2.2 前端（只有目录，没有权限）

```ts
const NAV_CATALOG = [            // 全局顺序，唯一一份
  nav.home, nav.classes, nav.assignments, nav.papers,
  nav.vocab.sets, nav.vocab.assign, nav.grading, nav.review, nav.reports,
  nav.vocab.library, nav.vocab.lists, nav.vocab.review, nav.ai,
]

export function navSpecs(features: Record<string, boolean>) {
  return NAV_CATALOG.filter((item) => features[item.key])
}
```

**三套顺序由同一份目录自然得出**：教师过滤后 = 首页/班级/作业/试卷/词集/发词/批改/复核/报表（9）；学生 = 首页/班级/我的词库/我的词单/今日复习（5，2026-10-26 AC-69.11 修订加词单）；管理员 = 班级/AI 设置（2）。前端没有任何一处写「某角色能看某菜单」。

### 2.3 加载态

`features` 返回前不渲染业务菜单（AC-63.11）；加载失败时只保留资料页与退出。

## 3. 路由守卫

- `HomeRouter`：`ADMIN → <Navigate to="/classes" replace />`；STUDENT 与 TEACHER 维持现状。
- 新增 `RequireRole roles={[...]}` 包裹：`/papers`、`/assignments`、`/assignments/new`、`/grading`、`/grading/:id`、`/review`、`/reports`、`/offerings/:id/summary`。
- 未通过 → `<Navigate to="/classes" replace />`，不渲染 `ErrorBoundary` 的崩溃页。

## 4. 后端方法级收口

**这是全计划最容易出事的地方**：`/api/offerings/**` 学生与教师共用，不能按前缀一刀切。

| 端点 | 现在 | 改后 |
| :--- | :--- | :--- |
| `GET /api/offerings` | 登录 | 不变（学生也看自己的作业） |
| `GET /api/offerings/{id}` | 登录 | 不变 |
| `POST /api/offerings` | 登录 | **TEACHER** |
| `POST /api/offerings/{id}/publish` | 登录 | **TEACHER** |
| `POST /api/offerings/{id}/scores/publish` | 登录 | **TEACHER** |
| `GET /api/offerings/{id}/submissions` | 登录 | **TEACHER** |
| `GET /api/offerings/{id}/progress` | 登录 | **TEACHER** |
| `POST /api/offerings/{id}/grading/dispatch` | 登录 | **TEACHER** |
| `POST /api/offerings/{id}/grading/retry-failed` | 登录 | **TEACHER** |
| `POST /api/offerings/{id}/submissions/{sid}/grading/dispatch` | 登录 | **TEACHER** |
| `GET /api/offerings/{id}/class-summary` | 登录 | **TEACHER** |
| `GET /api/offerings/{id}/export/**` | 登录 | **TEACHER** |
| `POST /api/offerings/{id}/submissions/{sid}/reject` | 登录（方法体内只拦 STUDENT） | **TEACHER**（2026-10-25 语义已确认） |
| `GET /api/offerings/{id}/entry` `/paper` `/session` `/my-submissions` `/my-feedback` | 登录 | 不变（学生答题） |
| `POST /api/offerings/{id}/start` `/attempts` | 登录 | 不变 |
| `PUT /api/offerings/{id}/answers` | 登录 | 不变 |
| `POST /api/offerings/{id}/submissions` `/file` `/video` | 登录 | 不变（学生交卷） |
| `GET /api/reports/**` | 登录 | **TEACHER** |
| `GET /api/grading/queue`、`POST /api/grading/bulk-approve*` | 登录 | **TEACHER** |
| `/api/submissions/{id}/review*` | 登录 | **TEACHER** |
| `GET /api/submissions/{id}/attachment` | 登录 | 不变（学生下载本人附件） |
| `/api/papers/**` | TEACHER + ADMIN | **TEACHER** |
| `/api/admin/**` | ADMIN | 不变 |
| `/api/classes/**`、`/api/vocab/**`、`/api/profile/**`、`/api/features` | 登录 | 不变 |

**实现方式**：路径前缀清晰的（`/api/reports/**`、`/api/papers/**`）继续写在 `SecurityConfig` 的 `requestMatchers`；方法级语义分不清的（`POST /api/offerings` vs `GET /api/offerings`）用 `@PreAuthorize` 或一个按 `(method, path)` 匹配的小型 `AuthorizationManager`。

## 5. 班级页入口

`ClassesPage.tsx` 已有一个 `isStudent` 分支。ADMIN 落地到这里后，页面里不能出现「布置作业」「批改」等会 403 的按钮。做法：把入口可见性统一由 `can(role, 'nav.assignments')` 之类的矩阵查询决定，不在页面里另写角色判断。

## 6. 迁移与回滚

- 无表结构变更，无数据迁移。
- 回滚方式：还原 `nav.ts` 与 `SecurityConfig` 两个文件即可；不涉及存量数据。

## 7. 测试策略

| 层次 | 测试 | 覆盖 |
| :--- | :--- | :--- |
| 前端单测 | `vocab-nav.test.mjs` | ADMIN 恰好 2 项且顺序固定；TEACHER 7/9；STUDENT 3/5 |
| 后端集成 | `IdentityAuthTest` 扩展 | 表中「改后 TEACHER」的端点：ADMIN 403、TEACHER 正常；「不变」行的端点：STUDENT 全部正常 |
| 回归基线 | 现有全量测试 | 收口改完先跑一遍，确认没有误拦学生链路 |
| 手工 | 三个角色各登录一次 | admin 落在班级、手输 /grading 被重定向、班级页无 403 按钮 |

## 8. Plan review 6 条清单

**完整性**

1. **spec 每条 AC 都有承接** —— ✅ AC-63.1–63.4 → §2 后端矩阵 + `/api/features`；AC-63.5–63.6 → §3；AC-63.7 → §4 授权表（`reject` 已定为 TEACHER）；AC-63.8 → §4「不变」行；AC-63.9 → §5；AC-63.10–63.11 → §2.2 / §2.3。
2. **每个实体的表、字段、索引、状态迁移** —— ✅ 本计划无新增表（N/A）。
3. **外部依赖的失败路径** —— ✅ 无新增外部依赖（N/A）。

**结构**

4. **模块依赖方向无环** —— ✅ 只改 `identity` 的安全配置与 `web`，不新增跨模块依赖。
5. **每张表只有一个属主** —— ✅ 无新增表（N/A）。
6. **拆服务路径** —— ✅ 不影响各模块可拆性；`identity` 的收口规则都在自己的 `SecurityConfig` 与端点注解里。

**结论：6 条全部打勾 → 本计划定稿，可以拆 tasks。**

## 9. 风险

| 风险 | 缓解 |
| :--- | :--- |
| 误拦学生链路（`/{id}/submissions` 教师看列表 vs 学生交卷） | 先跑现有全量测试拿基线，再改；集成测试覆盖学生全流程 |
| ADMIN 失去报表后 `AdminAiPage` 若依赖报表接口会 403 | 实现前 grep 该页接口调用 |
| admin 落地班级页后页面内残留会 403 的按钮 | §5 统一走矩阵查询；手工点一遍 |
