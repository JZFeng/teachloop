# Playwright 端到端测试设计

日期：2026-10-30　状态：**设计已定稿，代码未开始**（用户当天拍板：数据准备走「纯 API 编排 + CLI 造账号」）

配套：`AGENTS.md` 的 UI 约束第 5 条（平板必检）、`docs/process/ui-review-checklist.md` 的 D3。

---

## 0. 一句话

**spec 里不出现选择器；一个用例最多 1–2 个 UI 动作；其余全部 API 前置 + URL 深链。**

这条规则的由来不是洁癖：UI 前置越多，用例越脆（等不到元素、被弹层挡住、改版即红），而
「造一份已布置的写作作业」和 UI 的呈现方式本来无关，不该让测试替用户把菜单点一遍。

## 1. 边界

**做**

- 业务链路的端到端验收（学生作答 → 提交 → 教师批改 → 发布 → 学生看反馈）。
- 平板守卫（横向溢出、主操作在首屏），作为同一套 Playwright 的一个 spec。
- 登录态复用（`storageState`）、失败留 `trace`/截图。

**不做**

- 单元 / 组件测试（已有 `web/tests/*.test.mjs`，`node --test` **141 条**，继续用）。
- 性能与压力（`scripts/load-test.py` 另有分工）。
- AI 判分结果的**正确性**（真实模型输出不可控，只断言状态流转：待批改 → 已批改）。
- 在生产环境跑 e2e。

## 2. 现状盘点（先看已经有什么，别重复造）

### 2.1 后端 REST 面

`teachloop-*/src/main/java` 下共 **45 个 Endpoint 类**（`@RequestMapping` 一级前缀 + 方法级路径）。
核心链路的关键端点：

| 动作 | 端点 | 身份 |
| :--- | :--- | :--- |
| 登录 | `POST /api/auth/login` | — |
| 建班级 / 学生进班 | `POST /api/classes`、`POST /api/classes/join`、`POST /api/classes/{id}/invite/regenerate` | 教师 / 学生 |
| 造试卷 | `POST /api/papers` → `/{id}/sections`、`/{id}/items`、`/{id}/answers`、`/{id}/criteria` → `POST /{id}/publish` | 教师 |
| 布置 | `POST /api/offerings` → `/{id}/publish` | 教师 |
| 学生开始 / 存答案 / 提交 | `POST /api/offerings/{offeringId}/start`、`PUT /answers`、`POST /submissions` | 学生 |
| 批改与发布 | `GET /api/grading/queue`、`POST /api/grading/bulk-approve`、`POST /.../review/publish` | 教师 |

### 2.2 CSRF：环境不同，行为不同（实测配置）

| 环境 | `teachloop.security.csrf` | 对 e2e 的含义 |
| :--- | :--- | :--- |
| dev | `false`（`application-dev.yml:7`） | `request` 上下文直接发写请求 |
| qa | `true`（`application-qa.yml:13`） | 必须先 `GET /api/csrf` 拿 token，再随写请求带上 |
| prod | `true`（`application-prod.yml:17`） | 不在 prod 跑 e2e |

→ `api/client.ts` 必须把这个握手封装掉，并靠环境变量决定要不要发（`E2E_CSRF=1`）。

### 2.3 账号从哪来

- dev 种子：`teacher@teachloop.local` / `student@teachloop.local`，密码 `teachloop123`（`DevDataSeeder.java:41-42`）。
- CLI 造号：`java -jar teachloop-app.jar --create-user=a@b.c --role=STUDENT --display-name=小明`，
  密码取 `USER_PASSWORD`（`AdminCli.java:15-23`）；`--create-admin` 用 `ADMIN_PASSWORD`。
- **注册链路不能用于自动化**：`POST /api/auth/register` 之后要收激活邮件。

## 3. 目录结构与依赖方向

```
web/e2e/
  api/                  # 只发请求、只断言状态码与结构；不含业务断言
    client.ts           # baseURL / cookie jar / CSRF 握手 / 失败时打印 method+path+响应体
    auth.api.ts         # loginAs(role) → storageState
    class.api.ts  paper.api.ts  offering.api.ts  submission.api.ts
  fixtures/             # 场景工厂（given*）：把多个 API 串成"已布置的写作作业"这种状态
    auth.setup.ts       # teacher / student 各登录一次，写 .auth/*.json
    scenario.ts
  pages/                # POM：一个路由一个 class
    base/
      BasePage.ts       # goto(url) / waitSettled() / screenshot() / expectNoHorizontalOverflow()
      DataTable.ts      # 页内模块：行、排序、分页、行点击（多页复用）
    login.page.ts  papers.page.ts  paper-wizard.page.ts  student-exam.page.ts …
  utils/                # 纯函数：随机中文姓名、日期、视口矩阵常量
  specs/                # 用例：只写"哪一步交给 UI"
    tablet.spec.ts
    student-writing.spec.ts
  playwright.config.ts
```

**依赖方向单向**：`specs → pages / fixtures → api / utils`。反向 import 一律禁止（POM 不许调 fixture，
api 层不许 import POM）。

## 4. POM 规范

1. **一个路由一个 page class**（`/papers` → `PapersPage`），文件名 `papers.page.ts`。
2. **页内模块单独成 class**：表格 `DataTable`、行内动作 `RowActions`、页头 `PageHeader`。
   页面 class 只做编排（`this.table.expectRow(...)`），不自己写 `td` 选择器。
3. **公用能力进 `base/` 与 `utils/`**：等待、截图、视口矩阵、随机数据。禁止每个 POM 自己写一遍。
4. **方法用动词、断言带信息量**：
   - `openCreate()`、`fillBasics({...})`、`submit()`；
   - `expectRowListed(name)` 失败时打印"期望的行名 + 实际前 5 行文本"，而不是裸 `toBeVisible()` 超时。
5. **选择器优先级**：`getByRole` / `getByLabel` > `data-testid` > CSS class。
   只在 CSS 类稳定且语义化（如 `.tl-table`）时才用 class；禁止用 `nth-child` 链。

## 5. API-first：一个用例的完整走法（学生写作）

| 步骤 | 怎么走 | 端点 |
| :--- | :--- | :--- |
| 1. 登录 | API | `POST /api/auth/login`（教师、学生各一份 `storageState`） |
| 2. 造班 + 拉学生 | API | `POST /api/classes` → `POST /api/classes/{id}/invite/regenerate` → `POST /api/classes/join` |
| 3. 造写作卷并发布 | API | `POST /api/papers` → `/{id}/sections` → `/{id}/items` → `/{id}/publish` |
| 4. 布置给班级 | API | `POST /api/offerings` → `/{id}/publish` |
| 5. 直接深链进作答页 | URL | `/exams/{offeringId}` |
| 6. **唯一 UI 动作** | UI | 写作编辑器里填内容 → 点提交 |
| 7. 回 API 验状态 | API | `GET /api/grading/queue` 里出现该提交 |

反例（不许这么写）：从 `/login` 登录 → 点「今日」→ 点作业卡片 → 再进作答页。前三步属于数据准备，
不是被测对象；用 UI 走一遍会让用例慢 3–5 倍，且每改一次导航就红一次。

## 6. 数据准备（方案 A 细则）

| 数据类型 | 来源 | 说明 |
| :--- | :--- | :--- |
| 业务数据（班级、试卷、布置、提交） | REST API 编排（`fixtures/scenario.ts`） | 每个用例用自己的随机命名空间（如 `班级-<6 位随机>`），避免与 dev 种子数据互撞 |
| 账号 | dev 种子 + CLI（`--create-user`） | 属**一次性环境准备**，不在每个用例里造；需要新角色时在 `auth.setup.ts` 里建一次 |
| 批量学生（30 人） | CLI 循环 | 只在"班级成员分页/排序"这类用例里需要，按需造 |
| 附件 / 音频素材 | `fixtures/files/` 里的小文件 | 上传走 API（`POST /submissions/file`） |
| AI 判分结果 | **不铺数据、不断言分数** | 只断言状态流转（待批改 → 已批改 → 已发布） |

**明确不做**：fixture 直连数据库写表；test-profile 的 `/api/test/seed` 端点。理由：前者让测试与表结构
耦合（改 schema 全体变红），后者在生产代码里留后门。这两条要改主意，必须走一次讨论并写进本文档。

## 7. 平板守卫

`specs/tablet.spec.ts`，三个 project（对应 `AGENTS.md` 第 5 条的两档断点）：

| project | 视口 | 覆盖的断点 |
| :--- | :--- | :--- |
| `desktop-1440` | 1440×900 | ≥1101 两栏 |
| `ipad-air-11` | 834×1194 | ≤1023 抽屉 |
| `ipad-pro-portrait` | 1024×1366 | 1024–1100 单列 |

每条路由断言两件事：

```ts
expect(await page.evaluate(() => document.documentElement.scrollWidth))
  .toBeLessThanOrEqual(viewport.width + 2)              // 横向溢出：0 容忍
await expect(page.locator('.ant-btn-primary').first()).toBeInViewport()   // 主操作在首屏
```

**取舍**：6 个视口合成 3 个 project，省一半运行时间；代价是 iPad 10（820）与 mini（744）只在人工
D3 矩阵里验。要机器全覆盖就再加两个 project（约 +30 秒）。

## 8. 运行与 CI

```bash
make web-e2e            # 起 dev（或复用已起的 5173）→ 跑全部 spec
make web-e2e-tablet     # 只跑平板守卫
```

- `webServer` 配 `reuseExistingServer: !process.env.CI`，本地不重复拉 dev。
- 首次需要 `npx playwright install chromium`（CI 里配 `~/.cache/ms-playwright` 缓存，未命中时约 1–2 分钟）。
- `trace: 'retain-on-failure'`，`screenshot: 'only-on-failure'`；失败产物进 `web/e2e/test-results/`（已 gitignore）。

## 9. 踩过的坑（2026-10-30 实测，写下来省下次的时间）

1. **Chrome 141 `headless=new` + `Target.createTarget` 会 `SIGTRAP` 自杀**。零依赖 CDP 版必须走
   `/json/list` 里 `type=page` 的那条 WS（Playwright 自己管这一层，不受影响）。
2. **基准不能用 `innerWidth`**：页面没有 `<meta name="viewport">` 时 Chrome 把布局视口撑到内容宽度
   （实测 2000px 的 div 让 `innerWidth=2008`），"溢出"永远测不出来。平板断言要拿**我们设定的期望宽高**比。
3. 受限环境里 Chrome 需要 `--no-sandbox` 才能起来（Playwright 同理，`launchOptions.args` 里加）。

## 10. 分阶段任务（要排期时再取全局 T 号，别直接写死）

| 阶段 | 内容 | 产出 | 估算 |
| :--- | :--- | :--- | :--- |
| 1 | Playwright 骨架：config + `auth.setup.ts` + `BasePage` + `tablet.spec.ts`（3 projects） | 平板守卫可跑，`make web-e2e-tablet` 绿 | 半天 |
| 2 | API 层 + 场景工厂：`client.ts`（含 CSRF 握手）+ 一个完整场景（学生写作） | 1 条端到端用例，spec 里只有 1 个 UI 动作 | 1 天 |
| 3 | 逐页 POM：教师出卷、批改、复核、报表；学生作答、词表 | 每页 1–2 小时 | 按页计 |

阶段 1 完成时**删除** `scripts/ui/tablet-matrix.mjs`（零依赖过渡版，未提交），断言搬进 `tablet.spec.ts`。

## 11. 风险

| 风险 | 判断 | 应对 |
| :--- | :--- | :--- |
| 用例之间互相污染（同一个班级/试卷被多个 spec 改） | 中高 | 每条用例自己的随机命名空间；不共享可变数据 |
| qa 环境有 CSRF，dev 没有 | 中 | `client.ts` 用 `E2E_CSRF` 开关 + 握手封装，两套环境都能跑 |
| AI 判分让用例变慢/不稳 | 高 | 用例只断言状态；需要"已批改"态时用 `bulk-approve` 直接推进，不等真实模型 |
| Playwright 首次安装体积/时间 | 低 | CI 缓存；本地一次安装 |
| 选择器随 UI 改版而红 | 中 | POM 收敛选择器；优先 role/label；改版只改一处 |
