# 012-class-lifecycle 交付报告（T581–T591）

- 日期：2026-10-30
- 分支：`feat/012-class-lifecycle`（从 `main` 2e9ce2b 起）
- 上游：`specs/012-class-lifecycle/{spec,plan,tasks}.md`、`docs/mock/class-lifecycle-2026-10-30/`

## 0. 三行结论

1. **改了什么**：`class_members` 退班/移出只写 `LEFT`（不删行）、重入幂等复用同一行、班级结课/恢复两个端点、`Ports.ClassLifecycle` 实现并在 sitting 的提交与新建作业两侧拦截（多班规则）、成员列表默认 `ACTIVE`、发词收件人只取 `ACTIVE`、学生/老师班级两屏与三套二次确认、回滚脚本与演练。
2. **验到哪一档**：接口 + 界面入口 + 端到端三段都走过——`ClassLifecycleTest` 5/5（真 HTTP + 真 Postgres）、web `pnpm test` 162/162（本分支树）/191/191（共享树，含别人新增用例）、平板 834×1194 与 1024×1366 在真实浏览器实测 `scrollWidth == innerWidth` 且首屏外按钮 0（学生「进行中/已结束」与老师「成员表含已离开」各一轮）。**AC-98.4 未验证**（011 的 `/api/student/today` 未合并，见 §6）。
3. **回滚命令**：`ops/rollback/012-class-lifecycle.sh --apply`（先 `update class_members set status='ACTIVE', left_at=null where status='LEFT';`，再回退代码到 `2e9ce2b`）；qa 真库演练 `--apply` 复位 1 行 / real 0.129s、幂等第二次 0 行、行数 37→37 不变。

## 1. 文件清单

### 后端

| 文件 | 改动 |
|---|---|
| `teachloop-classroom/.../domain/MemberStatus.java` | 新增枚举 `ACTIVE` / `LEFT` |
| `teachloop-classroom/.../domain/ClassMember.java` | 加 `status`/`left_at`/`rejoined_at`、`leave()`/`rejoin()`（重入不改 `joined_at`） |
| `teachloop-classroom/.../domain/ClassMemberRepository.java` | 查询一律带 `status`：默认口径 ACTIVE，含已离开走显式方法 |
| `teachloop-classroom/.../domain/ClassRepository.java` | `findForStudent` 只取 ACTIVE 成员（已结课班仍返回） |
| `teachloop-classroom/.../api/ClassEndpoint.java` | 退班 / 移出 / 结课 / 恢复 / 重入幂等 / 成员 `includeLeft` / `ClassView.archived` + 审计 |
| `teachloop-classroom/.../port/ClassLifecycleImpl.java` | 新增：`Ports.ClassLifecycle` 实现（`archived` + 当前请求者是否 ACTIVE 成员） |
| `teachloop-classroom/.../port/ClassMembershipImpl.java` | `classIdsOf` / `membersOf` 只取 ACTIVE |
| `teachloop-shared/.../error/{ErrorCode,ApiException,GlobalExceptionHandler}.java` | 新增 `CLASS_ARCHIVED`（409）与 `ApiException.classArchived` |
| `teachloop-shared/src/main/resources/messages{,_zh_CN}.properties` | `error.class.{studentOnly,notMember,memberNotFound,archived,teacherOnly}` |
| `teachloop-sitting/.../service/OfferingWindows.java` | `classesOf` / `requireSubmittable`（多班规则）/ `archived`（查询失败按未归档放行） |
| `teachloop-sitting/.../api/ExamSessionEndpoint.java` | `submit` / `submitFile` / `submitVideo` 接入结课拦截 |
| `teachloop-sitting/.../api/OfferingEndpoint.java` | 新建作业：选中任一已结课班 → 409 `CLASS_ARCHIVED` |
| `teachloop-sitting/.../api/ClassLeavePreviewEndpoint.java` | 新增：`GET /api/classes/{id}/leave-preview`（未交/已交/总数） |
| `teachloop-sitting/.../domain/SubmissionRepository.java` | `countSubmittedOfferings` |
| `teachloop-vocab/.../assign/VocabularyAssignmentEndpoint.java` | 已结课班不能作为发词目标（409，FR-224） |
| `teachloop-app/.../DemoSeedRunner.java` | 演示数据成员存在性判断带 ACTIVE |
| `teachloop-app/src/test/java/com/teachloop/ClassLifecycleTest.java` | 新增 5 个集成用例 |

### 前端

| 文件 | 改动 |
|---|---|
| `web/src/teacher/ClassesPage.tsx` | 学生「进行中/已结束」两分区 + 进入班级/查看成绩（本页展开作业）；老师状态 Tag、结课/恢复、成员表「含已离开」、移出；三套二次确认 |
| `web/src/teacher/AssignmentCreatePage.tsx` / `OfferingCreatePage.tsx` | 已结课班留在名单但禁用 + 「· 已结束」+ 原因说明（AC-98.7 前半） |
| `web/src/vocab/AssignPage.tsx` | 发词目标里的已结课班禁用（FR-224 前端一半） |
| `web/src/api/types.ts` | `ClassView.archived`、`ClassMemberView.status/leftAt`、`ClassLeavePreviewView`、`splitClassesByLifecycle`/`offeringsForClass`/`assignableClasses` |
| `web/src/locales/{zh-CN,en}.json` | `class.lifecycle.*` 55 键，中英 1955/1955 对齐 |
| `web/tests/class-lifecycle.test.mjs` | 10 条纯函数用例 |

### 运维 / 文档

| 文件 | 内容 |
|---|---|
| `ops/rollback/012-class-lifecycle.sh` | `--dry-run` / `--apply` / `--verify`，单事务、幂等、退出码 0/2/3/4 |
| `docs/reports/012-rollback-drill.md` | 阶段 A（scratch 库）+ 阶段 B（qa 真库）演练记录与取舍 |
| `specs/012-class-lifecycle/spec.md` | AC 勾选 15/16，AC-98.4 标「待联调」 |
| `specs/012-class-lifecycle/tasks.md` | T581–T591 执行记录 + 已知取舍 |

## 2. 命令与退出码

| 命令 | 退出码 | 说明 |
|---|---|---|
| `./mvnw -q -DskipTests -o compile` | **0** | 全模块编译 |
| `./mvnw -o -q -DskipTests package -pl teachloop-app -am` | **0** | 打 jar（用于 qa 迁移与本地联调） |
| `./mvnw -o -q -pl teachloop-app -am -Dtest=ClassLifecycleTest test` | **0** | `Tests run: 5, Failures: 0, Errors: 0` |
| `./mvnw -o -pl teachloop-app -am test`（全量） | **1** | `Tests run: 219, Failures: 2, Errors: 0, Skipped: 2` — 失败是 `AiSetupTest` / `AiReadinessTest`；两者在**纯净 main 2e9ce2b** 单独跑同样失败（各自 exit 1），属存量红，见 §5 |
| `python3 scripts/check-deps.py` | **0** | 通过 |
| `python3 scripts/check-boundaries.py` | **0** | 62 张表归属清晰 / 14 条跨模块外键 / 8 个端口只收发 record |
| `python3 scripts/check-ui.py` | **0** | 无新增违规（ClassesPage 的 `LEGACY_BTN_CLASS` 少 2 处） |
| `python3 scripts/check-i18n.py` | **1** | 唯一一处 `web/src/api/auth.tsx:108`「useAuth 必须在 AuthProvider 内使用」；在**纯净 main** 同样 exit 1（基线里没有这条指纹），非本次新增。本分支新增中文字面量为 0（`OfferingWindows` 的日志已改英文） |
| `cd web && npx tsc --noEmit` | **0** | 前端类型检查 |
| `cd web && node --experimental-strip-types --test tests/*.test.mjs` | **0** | `# tests 162 / # pass 162 / # fail 0`（本分支树）；共享树为 191/191（含别人新增用例） |
| 浏览器平板实测脚本（D3 两行） | — | 见 §4 |

> 说明：本机同时有 011/013 分支在同一共享工作区写未跟踪代码（`teachloop-vocab/.../books/`、`teachloop-app/.../student/`、`web/src/import/` 等），共享树的 Maven 构建会被它们的半成品打断。所以上面所有 Java 相关的数字都在**独立 worktree**（`/tmp/tl012`，本分支 HEAD）里取；前端数字在把本分支前端改动同步进该 worktree 后取。这是环境事实，不是跳过验证。

## 3. 多班规则：作业发给两个班、只结其中一个班（必贴）

**规则**：允许提交的条件是「至少一个**未归档**班仍然包含该生」，不是「这个作业有没有班结课」。

集成用例 `ClassLifecycleTest.multiClassArchiveKeepsSubmittable` 的实际断言与结果：

| 步骤 | 操作 | 断言 | 结果 |
|---|---|---|---|
| 1 | 老师建 A、B 两个班，学生同时加入；同一作业发到 A+B（另建第二份同构作业备用） | — | — |
| 2 | 只结课 B 班（`POST /api/classes/{B}/archive`） | 200，`archived=true` | ✅ |
| 3 | 学生提交第一份作业（A 仍进行中） | **200**（不能因为 B 结课就锁死） | ✅ 关键断言 |
| 4 | 老师向 **B 班**新建作业 | **409**，`body.code == "CLASS_ARCHIVED"` | ✅ |
| 5 | 结课 A 班后，学生提交第二份作业 | **409**，`body.code == "CLASS_ARCHIVED"` | ✅ |
| 6 | 恢复 A 班，学生再提交第二份作业 | **200** | ✅ |

用例输出：`Tests run: 5, Failures: 0, Errors: 0`（含本条）。同一文件里另有 `leaveKeepsRowAndRejoinReusesIt`、`teacherRemoveOnlyOwnerAndListFilters`、`archiveAndRestoreLifecycle`、`leavePreviewCountsPendingAssignments`。

## 4. 界面验收（真实浏览器，dev 前端 5188 + 本分支后端 8098 + 独立库 `teachloop_verify012`）

判定口径（AGENTS.md D3 两行）：

```js
document.documentElement.scrollWidth <= innerWidth + 2
[...document.querySelectorAll('button,a,[role=button]')].filter(e => e.offsetParent)
  .map(e => e.getBoundingClientRect()).filter(r => r.bottom > innerHeight).length === 0
```

| 屏 | 视口 | `innerWidth×innerHeight` | `scrollWidth` | 横向溢出 | 首屏外按钮数 |
|---|---|---|---|---|---|
| 学生·进行中（含「进入班级/退出班级」） | iPad Air 11 | 834×1194 | 834 | 无 | 0 |
| 学生·进行中 | iPad Pro 12.9 | 1024×1366 | 1024 | 无 | 0 |
| 学生·已结束（展开作业，只读） | iPad Air 11 | 834×1194 | 834 | 无 | 0 |
| 学生·已结束 | iPad Pro 12.9 | 1024×1366 | 1024 | 无 | 0 |
| 老师·班级 + 成员表（含已离开） | iPad Air 11 | 834×1194 | 834 | 无 | 0 |
| 老师·班级 + 成员表 | iPad Pro 12.9 | 1024×1366 | 1024 | 无 | 0 |

成员表容器：834 宽下 `scrollWidth 762 / clientWidth 762`、1024 下 `716 / 716` —— 容器内不需要横滚，整页也不溢出。

界面行为实测（文案逐字取自弹窗/页面）：

- 退出确认：`退出「012 验收班」？` + `还有 1 份作业未交。` + 三条后果（看不到该班作业 / 已提交的作业与成绩保留 / 词库与复习进度不受影响）；确认后班级从「进行中」消失。
- 结课确认：`结课「012 验收班」？` + 四条后果（学生仍可查看历史作业与成绩但不能再提交 / 邀请码失效 / 不能再布置新作业 / 可以随时恢复）。
- 恢复确认三条后果；恢复后学生用同一邀请码重新加入成功（200）。
- 已结束班学生视角：`1 人 · 已结课` + `已结束` Tag + 只有「查看成绩」，展开后提示「这个班已结课，只能查看作业与成绩。」，**无提交/去完成入口**。
- 结课班邀请码：学生 `POST /api/classes/join` → **404**，`message="邀请码无效"`，不含「归档/结课」字样。
- 老师成员表：默认「成员数 1 → 移出后 0」；勾「含已离开」→ 1 行，状态 `已离开`、`离开于 2026/10/30 15:12`、操作列为 `—`（无移出按钮）。
- 布置作业页：已结课班 chip 显示 `012 验收班 (0) · 已结束`、`disabled=true`，下方 hint「这个班已结课，不能再布置作业；可在班级页恢复。」

截图（已入库）：`docs/reports/012-shots/` 下 5 张 —— `012-student-834.png`、`012-student-archived-834.png`、`012-student-archived-1024.png`、`012-teacher-834.png`、`012-teacher-1024.png`。

## 5. 存量红（不是本次引入）

| 测试 | 本分支 | 纯净 main 2e9ce2b | 结论 |
|---|---|---|---|
| `AiSetupTest` | exit 1（`expected "local" but was null`，`AiSetupTest.java:98`） | exit 1，同一断言同一行 | 存量红 |
| `AiReadinessTest` | exit 1 | exit 1 | 存量红 |
| `check-i18n.py` | exit 1（`web/src/api/auth.tsx:108`） | exit 1，同一条 | 存量红（`scripts/i18n-web-baseline.txt` 未含该指纹） |

## 6. 未完成项

1. **AC-98.4（T586）待联调**：011 的 `GET /api/student/today` 由 011 分支实现在飞（`teachloop-app/.../app/student/StudentTodayEndpoint.java` + `teachloop-sitting/.../port/AgendaAdapter.java`，均未合并），且当前实现里没有任何 `archived` 过滤。按约定**没有造临时端点**。联调点：在 `StudentTodayEndpoint` 组装 `assignments` 时按 `Ports.ClassLifecycle.state(classId).archived` 过滤已结课班的作业（与提交拦截同一端口、同一失败放行策略）。
2. **「已开始的作答」是否允许交卷**未裁决（plan §11 待定项 1）。当前按 FR-220 / T585 采用「提交即校验」，结课后提交 409；若要「已开始允许交卷」，改动点在 `OfferingWindows#requireSubmittable`。
3. **回滚演练的「代码回退」一步没有真做**：只到 SQL 复位 + 旧查询语义验证，未重建 `2e9ce2b` 的旧 jar、未启动旧版本做接口/页面验证（见 `docs/reports/012-rollback-drill.md` §8）。
4. **发词收件人（AC-97.3）是端口级验证**：`membersOf` 只返回 ACTIVE 已由集成测试断言，发词入口循环取自该方法；没有新增「移出后新发一轮词」的端到端用例（需要词库夹具，性价比低）。
5. **qa 库迁移号风险**：为做 T591 演练，qa（55433/`teachloop_qa`）已从 V074 迁到 **V081**（本分支迁移集合）。其它 spec 分支部署 qa 时，其迁移集合必须 ⊇ 已应用集合，否则 Flyway 拒绝启动；合并前按 tasks.md 约定统一重排迁移号。

## 7. 共享工作区的 Git 事故与清理（必读）

本机三个会话共用同一个工作副本，期间发生了两次「分支被切走」：

1. 我建的 `feat/012-class-lifecycle` 在提交前被别的会话切到了 `feat/011-today`，导致我的后端提交落在了 **`feat/011-today` 的 `ab74e7d`**（父提交是别人的 `2c64b14`）。
2. 我在独立 worktree `/tmp/tl012` 里把它 cherry-pick 到 `feat/012-class-lifecycle` 上（现 HEAD `56f332b`，父 `2e9ce2b`），并剔除了别人并发写进 `ClassEndpoint` 的 011 付费班代码（那段依赖未提交的 `ClassPay`/`ClassEntity.isPaid()`，进了我的提交会让分支编译不过）。

**留给用户/其它会话的清理动作**（我没有动别人的分支和他们的未提交工作）：

```bash
# 1) 让 feat/011-today 回到它自己的提交（我的 ab74e7d 不该在这条分支上）
git checkout feat/011-today
git reset --soft 2c64b14     # 保留工作区文件；随后只把自己要的文件 git add
# 2) 确认我的分支独立且从 main 起
git log --oneline feat/012-class-lifecycle | head -3   # 期望：56f332b -> 2e9ce2b
```

另外：`teachloop-vocab/.../books/BookJoinService.java` 缺 `import java.util.Map` 是 013 在飞代码的编译错误，我在共享树里**只补了这一行 import**（未提交、未改语义），否则共享树连编译都过不了。
