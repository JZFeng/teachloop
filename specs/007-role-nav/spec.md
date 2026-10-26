# 规格说明 — 角色导航矩阵（007-role-nav）

- 特性编号：`007-role-nav`
- 状态：**草稿（2026-10-24），待用户 review**
- 上游：宪法第十五条（UI）、`specs/003-ui-standard/spec.md` AC-27.6（本规格取代其「管理员 = 教师 + AI」的表述）
- 下游：plan（6 条清单）→ tasks
- 相关：`specs/008-flags-service/spec.md`（实验开关独立服务，本规格不含实验能力）

---

## 1. 摘要

「谁能看到哪个菜单」现在是硬编码的：前端 `web/src/app/nav.ts` 里 ADMIN 和 TEACHER 共用同一个 `return`，ADMIN 只在末尾追加 AI 设置；后端 `SecurityConfig` 只有两条角色 matcher。

用户 2026-10-24 拍板的目标矩阵：**管理员只保留班级与 AI 设置**，登录后落在班级页；教师九项、学生五项不变。

本规格只解决**角色**这一层。新功能的「部分学生自愿试用」是另一个问题，已拆到独立的 `008-flags-service`——一个不属于 TeachLoop 业务的辅助服务，通过 HTTP 调用。

### 1.1 关键设计立场

| 议题 | 立场 | 理由 |
| :--- | :--- | :--- |
| 矩阵形态 | 代码里的**常量矩阵**，不是数据库配置 | 只有 3 个角色，运行时配置是过度设计 |
| 矩阵位置 | **后端唯一真源**（Java 常量），前端只消费 key | 前端与后端各存一份角色规则必然漂移；且「谁不能看什么」属于业务，不该散在 UI 层 |
| 角色语义归属 | **必须留在 TeachLoop** | 「admin 只看班级和 AI」是业务知识，不是通用规则 |
| 实验能力 | **不在本规格内** | 它是辅助工具，独立成服务（008） |
| 收口粒度 | **方法级**，不是路径前缀 | `/api/offerings/**` 学生与教师共用，一刀切会拦死学生答题 |

## 2. 角色与菜单矩阵

| 菜单项 | 能力 key | ADMIN | TEACHER | STUDENT |
| :--- | :--- | :---: | :---: | :---: |
| 首页 | `nav.home` | — | ✓ | ✓ |
| 班级 | `nav.classes` | ✓ **落地页** | ✓ | ✓ |
| 作业 | `nav.assignments` | — | ✓ | — |
| 试卷 | `nav.papers` | — | ✓ | — |
| 词集 | `nav.vocab.sets` | — | ✓（vocab 开） | — |
| 发词 | `nav.vocab.assign` | — | ✓（vocab 开） | — |
| 批改 | `nav.grading` | — | ✓ | — |
| 复核 | `nav.review` | — | ✓ | — |
| 报表 | `nav.reports` | — | ✓ | — |
| 我的词库 | `nav.vocab.library` | — | — | ✓（vocab 开） |
| 今日复习 | `nav.vocab.review` | — | — | ✓（vocab 开） |
| AI 设置 | `nav.ai` | ✓ | — | — |

合计：ADMIN 2 项；TEACHER 7 项（vocab 开 9）；STUDENT 2 项（vocab 开 4）。资料页与通知在侧栏底部常驻，不计入矩阵。

> **2026-10-26（AC-72.8）**：删掉原「成绩与评语 | `nav.feedback`」一行——`/feedback` 列表页与首页是同一份数据，
> 删页时同步删菜单项；反馈详情（`/feedback/:offeringId`）仍从首页卡片与作业页行内进入，不是侧栏入口。

## 3. 用户故事

### US-63 作为管理员，我的侧栏只放我真正在做的事

我只做班级查看与 AI 配置，不看作业、试卷、批改、复核、报表。登录后直接落在班级页，手动输入受限地址也不会出现白屏或原始错误页。
**验收**：见 AC-63.1–63.9。

## 4. 验收标准

#### US-63 角色矩阵

- [ ] AC-63.1 角色 × 菜单矩阵是**后端唯一真源**（Java 常量，位于 `teachloop-identity`）；`GET /api/features` 返回当前用户最终生效的 key 集合（含 `nav.*` 与实验项，扁平等）；前端 `web/src/app/nav.ts` 只保留「菜单目录」（key → 路径 / 图标 / 文案 / **全局顺序**），**不含任何角色判断**
- [ ] AC-63.2 ADMIN 侧栏恰好 2 项：班级（`/classes`）、AI 设置（`/admin/ai`），顺序固定
- [ ] AC-63.3 TEACHER 侧栏 7 项，顺序为 首页、班级、作业、试卷、批改、复核、报表；vocab 开关打开时词集、发词插在试卷之后（共 9 项）
- [ ] AC-63.4 STUDENT 侧栏 **2 项**，顺序为 首页、班级（**2026-10-26 修订，AC-72.8**：原第 3 项「成绩与评语」随 `/feedback` 列表页删除而移除；更早的名字是「我的反馈」，见 AC-72.4）；vocab 开关打开时我的词库、今日复习插在班级之后（共 4 项）
- [ ] AC-63.5 ADMIN 登录后落在 `/classes`；访问 `/` 时重定向到 `/classes`，不停留在教师首页
- [ ] AC-63.6 ADMIN 直接输入 `/papers`、`/grading`、`/review`、`/reports`、`/assignments` 时被重定向到 `/classes`，不出现白屏、不出现原始 403 错误页
- [ ] AC-63.7 后端接口按角色收口，细到方法：`/api/reports/**`、`/api/papers/**`、`/api/offerings/{id}/grading/**`、`/api/submissions/*/review*`、`/api/offerings/{id}/class-summary`、`/api/offerings/{id}/export/**`、`POST /api/offerings`、`POST /api/offerings/{id}/submissions/{sid}/reject` 对 ADMIN 返回 403，对 TEACHER 放行
- [ ] AC-63.8 学生答题链路不受收口影响：学生仍能 `GET /api/offerings`、`/api/offerings/{id}/entry`、`/api/offerings/{id}/paper`、`/session`、`/my-submissions`、`PUT /answers`、`POST /submissions`；`POST /api/offerings`（创建布置）对学生返回 403
- [ ] AC-63.9 管理员落到班级页后，页面内不出现会 403 的入口（布置作业、批改等按钮对 ADMIN 不可见或禁用），点击不产生报错
- [ ] AC-63.10 前端侧栏渲染 = 「菜单目录」按**全局顺序**过滤出 `features` 中为 true 的 key，再映射成菜单项；教师 9 项、学生 4 项、管理员 2 项这三套顺序由同一份目录自然得出，**不需要按角色的分支**
- [ ] AC-63.11 `features` 加载完成前不渲染侧栏菜单，避免登录后先闪出全量菜单再收缩；加载失败时侧栏只显示资料页与退出，不显示业务菜单

## 5. 功能需求

| ID | 需求 | 对应故事 |
| :--- | :--- | :--- |
| FR-123 | 角色 × 菜单矩阵：**后端 Java 常量，唯一真源**；`GET /api/features` 返回生效 key 集合；前端只有「菜单目录」（key → 路径/图标/文案/全局顺序），无角色分支 | US-63 |
| FR-124 | 前端路由守卫：受限路由对无权限角色重定向到 `/classes`，不渲染空白页 | US-63 |
| FR-125 | ADMIN 落地页改为 `/classes`；访问 `/` 重定向 | US-63 |
| FR-126 | 后端方法级角色收口：报表、试卷、批改、复核、班级汇总、导出、创建布置 | US-63 |
| FR-127 | 学生答题链路保持放行：读布置、作答、交卷、附件下载 | US-63 |
| FR-128 | 班级页内按角色收敛入口：ADMIN 不显示布置与批改类操作 | US-63 |

## 6. 非功能约束

- 角色矩阵变更必须同步更新 `web/tests/vocab-nav.test.mjs` 的断言，不得只改代码。
- 新增文案进 `web/src/locales/zh-CN.json` 与 `en.json`，键集对齐。
- 收口不得改变未登录、未认证、CSRF 的既有行为（401 / 403 契约见 002 spec）。

## 7. 范围外

- **实验开关与 opt-in**：整块移到 `specs/008-flags-service/`，本规格不含任何实验能力。
- 角色矩阵的运行时后台配置：只有 3 个角色，不做。
- 新增角色：本期不新增。

## 8. 开放问题

1. ~~`reject` 端点归属~~ **已定（2026-10-25）**：`ExamSessionEndpoint.reject` 方法体首行即 `if (me.is("STUDENT")) throw forbidden("学生不能拒收提交")`，唯一调用方是教师页 `GradingDetailPage.tsx:222`，语义为「教师拒收提交」。收口为 **TEACHER**。
2. ADMIN 落到班级页时，是否需要看到班级列表之外的任何操作（如导出）？当前矩阵只给「查看」。

## 9. 已知偏差与风险

- AC-63.7 的端点清单已逐个对 controller 核对（2026-10-25），结果见 plan §4；实现时若路径有变更，以代码为准重新核对。
- 管理员失去报表后，若 `AdminAiPage` 依赖报表接口会出现 403，实现前须 grep 该页的接口调用。
