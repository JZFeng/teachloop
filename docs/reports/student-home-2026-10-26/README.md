# 学生首页去重（T503 / T504 / US-72 / 决策 027）

账号：`student01@teachloop.local`（学生 01，4 门作业）
环境：QA 快照 `http://127.0.0.1:5174`（后端 8011），`./scripts/env.sh deploy qa` 各轮各一次
日期：2026-10-26

> 两轮改动：**第一轮**（T503）只接回入口、不动结构；**第二轮**（T504）按用户睡前拍板改排序、
> 改页名、删掉 `/feedback` 列表页。下面的「改动后」分两节。

## 1. 改动前

| 截图 | 说明 |
| :-- | :-- |
| `student-home-before.png` | 页头四层：`DASHBOARD` / 我的学习 / 副标题 / 共 4 场汇总；右上角「我的反馈」按钮；第 3 张卡同时挂「已公布」「我的分数 14 / 14」「有反馈」 |
| `student-feedback-before.png` | `/feedback` 列表：与首页**同一份数据的 4 条**，只差排序（按提交时间倒序） |

## 2. 第一轮改动后（T503）

| 截图 | 说明 |
| :-- | :-- |
| `student-home-after.png` | 页头只剩标题 + ⓘ；四枚 chip `全部 4 / 未完成 0 / 已交卷 3 / 已公布 1`；点「已公布」后 4 行筛成 1 行；该行有「看评语」按钮 |
| `student-home-info.png` | ⓘ 气泡：三态含义 + 「AI 批完不等于公布」 |
| `feedback-detail-after.png` | 点「看评语」落到 `/feedback/2150b208-…`（不是作业页）；eyebrow 改为 `ASSESS · 成绩`；侧栏当时还是「成绩与评语」 |

## 3. 第二轮改动后（T504，用户 00:45 拍板的三件事）

| 截图 | 说明 |
| :-- | :-- |
| `student-home-round2.png` | 标题 = **首页**；侧栏只剩 4 项（首页 / 班级 / 我的词库 / 今日复习），「成绩与评语」菜单项已删；四行按截止时间**升序**：10/24 17:47 → 17:51 → 10/25 12:35 → 23:52 |
| `teacher-home-no-eyebrow.png` | 教师首页同样没有 eyebrow（`PageHeader` 的 `^/$` 规则两个角色共用），标题也是「首页」——这是第一轮「未验证」项的补证 |

## 4. 浏览器实测（脚本断言，`ego_script` + `page.evaluate`）

第二轮在一台**独立 origin**（`192.168.68.99:5300`，临时 `vite preview` 代理到 QA 后端 8011）上跑，
避免和另一条并行会话抢同一个登录态（共享浏览器里 127.0.0.1 / localhost 的 cookie 都是它的教师账号）。

| 断言 | 结果 |
| :-- | :-- |
| `h1` / eyebrow / sub | `首页` / `null` / `null` |
| 侧栏项 | `首页, 班级, 我的词库, 今日复习`（4 项，无「成绩与评语」） |
| chip 文案 | `全部4` / `未完成0` / `已交卷3` / `已公布1` |
| 行顺序（截止时间） | `10/24 17:47 → 10/24 17:51 → 10/25 12:35 → 10/25 23:52`（升序） |
| 「看评语」出现范围 | 只有已公布那一行（`111111`）有 |
| 点「看评语」 | `/feedback/2150b208-3437-4718-bc7d-5b253ddc5065`，eyebrow `ASSESS · 成绩`，动作区只有「返回首页」 |
| 直接访问 `/feedback` | 重定向到 `/`（没有死路由，旧书签与旧通知不会白屏） |
| 第一轮：点「已公布」chip | 行数 4 → 1，`aria-pressed=[false,false,false,true]` |

命令结果（第二轮）：`pnpm typecheck` 0、`pnpm build` 0、`pnpm test` **117/117**、`check-i18n` **1702 键对齐**、
`check-ui` 0 新增（存量 78）、`RoleNavMatrixTest` **6/6**、`FeaturesEndpointTest` **5/5**、`RoleAccessTest` **4/4**。

## 5. 未验证 / 已知缺口

- **英文界面未实测**（`en.json` 同步改了 6 处、删 10 键、加 0 键；`check-i18n` 只保证键对齐）。
- **768px 以下未测**：chips 行在窄屏会折行，未看实际效果。
- **`RoleAccessTest`（testcontainers）4/4 绿**，`RoleNavMatrixTest` 6/6、`FeaturesEndpointTest` 5/5。
- **ⓘ 气泡在页面最顶部，向上展开会压住侧栏品牌区**：`InfoHint` 的 `placement="top"` 是组件级统一设置，本次未改。
- **`./scripts/env.sh deploy qa` 打进快照的是工作区当前状态**，两轮都同时包含另一条并行会话未提交的改动
  （T505 作业页、T500 批改详情页、`TableToolbar`/`ViewTabs` 等）。
- **共享浏览器里我改了登录态**：第一轮用 127.0.0.1 / localhost 的 student01 会话验证；第二轮另起独立 origin，
  没有动并行会话的教师登录态。

