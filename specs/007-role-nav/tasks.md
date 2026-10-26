# 任务清单 — 角色导航矩阵（007-role-nav）

- 上游：`specs/007-role-nav/spec.md`（AC-63.1–63.11）、`specs/007-role-nav/plan.md`（6 条清单全部打勾，2026-10-25）
- 完成定义（DoD）：代码 + 测试 + `make test` 全绿 + 本文件执行记录勾选。缺一不算完成。
- 依赖规则：`依赖` 列写了前置任务的，必须全部完成才能开始。
- 日期：2026-10-25

---

| ID | 任务 | 依赖 | 验收 |
| :--- | :--- | :--- | :--- |
| T475 | **后端角色矩阵**：新增 `RoleNavMatrix`（`teachloop-identity/.../nav/`），`ADMIN`→{`nav.classes`,`nav.ai`}、`TEACHER`→9 个 key、`STUDENT`→5 个 key；纯常量，无数据库、无配置 | — | 单测断言三角色 key 数量与集合（AC-63.2 / 63.3 / 63.4）；同一 key 不出现在两个角色的「可见」集合里（vocab 类 key 除外，它们由模块开关控制）；`make test` 编译通过 |
| T476 | **`/api/features` 按用户算**：`FeaturesEndpoint` 从构造期 `Map.of` 改为按当前会话用户返回 `RoleNavMatrix(role)` ∪ vocab 模块开关 ∪（实验占位，本期无），扁平布尔且**包含为 false 的 key** | T475 | 集成测试：同一实例上 ADMIN 拿到 `nav.classes=true`/`nav.reports=false`，TEACHER 相反，STUDENT 拿到 `nav.feedback=true`/`nav.papers=false`；未登录 401；`vocab` 键仍按环境变量返回（AC-63.1） |
| T477 | **前端菜单目录**：`nav.ts` 删除角色分支，改为 `NAV_CATALOG`（全局顺序）+ `navSpecs(features)` 过滤；`vocab-nav.test.mjs` 改为按 features 断言 | T476 | 函数签名不再接受 `role`；三套顺序由同一目录过滤得出：ADMIN `[classes, ai]`、TEACHER 9 项、STUDENT 5 项；`web/tests/vocab-nav.test.mjs` 全绿；`pnpm run build`（tsc）通过（AC-63.10） |
| T478 | **路由守卫与落地**：`HomeRouter` 对 ADMIN 返回 `<Navigate to="/classes">`；新增 `RequireFeature`（或等价）包裹 `/papers`、`/assignments`、`/assignments/new`、`/grading*`、`/review`、`/reports`、`/offerings/:id/summary`；`features` 未返回前不渲染业务菜单 | T477 | 手工 + 测试：ADMIN 访问 `/`、`/papers`、`/grading`、`/review`、`/reports`、`/assignments` 全部落到 `/classes`，不出现白屏或崩溃页；登录后不闪出全量菜单（AC-63.5 / 63.6 / 63.11） |
| T479 | **后端方法级收口**：按 plan §4 授权表改 `SecurityConfig` 与端点注解；`/api/papers/**` 从 `hasAnyRole(TEACHER,ADMIN)` 收成 `TEACHER`；`POST /api/offerings`、grading、review、class-summary、export、`reject` 收成 TEACHER | — | `IdentityAuthTest` 扩展：授权表里「改后 TEACHER」的每条端点 ADMIN 均 403、TEACHER 均非 403；「不变」行的学生链路（entry/paper/session/my-submissions/answers/提交）全部保持 200（AC-63.7 / 63.8） |
| T480 | **班级页入口收敛**：`ClassesPage.tsx` 的入口可见性改由矩阵查询（`features['nav.assignments']` 等）决定，删除页面内角色分支 | T479 | ADMIN 登录落在班级页后，页面内不出现「布置作业」「批改」类入口，点击不产生 403；TEACHER 与 STUDENT 页面行为不变（AC-63.9） |
| T481 | **三档验收与状态文档**：跑 `make test`、`make web-build`；手工三角色各走一遍；更新 `docs/DELIVERY-STATUS.md` 与 `docs/PROGRESS.md` | T475–T480 | `make test` 退出码 0；`make web-build` 退出码 0；三张角色截图/记录（admin 侧栏 2 项、teacher 9 项、student 5 项）；`nav.ts` 内 grep 不到 `role ===` 之类的分支；DELIVERY-STATUS 新增一行 |

---

## 执行记录

- [x] **T475** 后端角色矩阵 — `RoleNavMatrix` + `RoleNavMatrixTest` 6/6 通过（`./mvnw -pl teachloop-identity -am test`，EXIT=0）
- [x] **T476** `/api/features` 按用户算 — `FeaturesEndpoint` 改为读 `currentUser`；`FeaturesEndpointTest` 5/5 通过（EXIT=0）
- [x] **T477** 前端菜单目录 — `NAV_CATALOG` + `navSpecs(features)`；`pnpm test` 94/94、`tsc --noEmit` EXIT=0
- [x] **T478** 路由守卫与落地 — `HomeRouter` 按 `nav.home` 落地、`RequireFeature` 包 11 条路由；`tsc --noEmit` EXIT=0。**浏览器未验收**
- [x] **T479** 后端方法级收口 — `SecurityConfig` 10 条规则；`RoleAccessTest` 4/4 通过（9 端点 × admin 403 / teacher 非 403）；全量 Maven `./mvnw -pl teachloop-app -am test` EXIT=0
- [x] **T480** 班级页入口收敛 — 核实班级页只有「创建班级 / 成员列表」，`/api/classes/**` 未收口，不会 403；`RoleAccessTest.adminCanUseClassPageActions` 钉住
- [x] **T481** 三档验收与状态文档 — `check-deps` / `check-boundaries` / `check-i18n` / `check-ui` / 44 组对比度全绿；`pnpm run build` EXIT=0；**三角色浏览器验收通过**：admin 侧栏 2 项且 `/grading`、`/reports` 均落到 `/classes`，教师 9 项、学生 5 项（截图 `docs/reports/role-nav-2026-10-25/`）

---

### 编号取号记录（2026-10-25）

- 落笔前全局最大号：`US 69`、`AC 69`、`FR 150`、`T 474`；本文件使用 `T475–T481`，不新增 `US/AC/FR`。
- 已跑 `uniq -d`：本文件无重号。
