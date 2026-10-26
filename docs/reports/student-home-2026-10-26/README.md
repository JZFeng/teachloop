# 学生首页去重（T499 / US-72 / 决策 027）

账号：`student01@teachloop.local`（学生 01，4 门作业）
环境：QA 快照 `http://127.0.0.1:5174`（后端 8011），`./scripts/env.sh deploy qa` 前后各一次
日期：2026-10-26

## 1. 改动前

| 截图 | 说明 |
| :-- | :-- |
| `student-home-before.png` | 页头四层：`DASHBOARD` / 我的学习 / 副标题 / 共 4 场汇总；右上角「我的反馈」按钮；第 3 张卡同时挂「已公布」「我的分数 14 / 14」「有反馈」 |
| `student-feedback-before.png` | `/feedback` 列表：与首页**同一份数据的 4 条**，只差排序（按提交时间倒序） |

## 2. 改动后

| 截图 | 说明 |
| :-- | :-- |
| `student-home-after.png` | 页头只剩标题 + ⓘ；四枚 chip `全部 4 / 未完成 0 / 已交卷 3 / 已公布 1`；点「已公布」后 4 行筛成 1 行；该行有「看评语」按钮 |
| `student-home-info.png` | ⓘ 气泡：三态含义 + 「AI 批完不等于公布」 |
| `feedback-detail-after.png` | 点「看评语」落到 `/feedback/2150b208-…`（不是作业页）；eyebrow 改为 `ASSESS · 成绩`；侧栏改为「成绩与评语」 |

## 3. 浏览器实测（脚本断言，`ego_script` + `page.evaluate`）

| 断言 | 结果 |
| :-- | :-- |
| chip 文案 | `全部4` / `未完成0` / `已交卷3` / `已公布1` |
| 点「已公布」后行数 | `4 → 1`，`aria-pressed = [false,false,false,true]` |
| 点行内「看评语」后路径 | `/feedback/2150b208-3437-4718-bc7d-5b253ddc5065`（`stopPropagation` 生效，未跳作业页） |
| `/feedback` 页 h1 | `成绩与评语` |
| 侧栏第 5 项 | `成绩与评语` |
| ⓘ 触发 | 悬停（`hover`）打开，文案与 `student.homeInfo` 一致 |

命令结果：`pnpm typecheck` 0、`pnpm build` 0、`pnpm test` 108/108、`check-i18n` 1703 键对齐、`check-ui` 0 新增（存量 82）。

## 4. 未验证 / 已知缺口

- **教师首页去掉 eyebrow 后的观感未截图**：`PageHeader` 的 `^/$` 规则由两个角色共用，代码层确定，但没有单独登录教师账号看一眼。
- **英文界面未实测**（`en.json` 已同步改 5 处、加 4 键，`check-i18n` 只保证键对齐）。
- **768px 以下未测**：chips 行在窄屏会折行，未看实际效果。
- **排序没动**：`sortRowsByDue` 是截止时间倒序，最晚截止排最前（最急的沉到最下面）。这是本次看到但没改的缺陷，见决策 027 §「本次没做」第 2 条。
- **ⓘ 气泡在页面最顶部，向上展开会压住侧栏品牌区**：`InfoHint` 的 `placement="top"` 是组件级统一设置，本次未改。
- **`./scripts/env.sh deploy qa` 打进快照的是工作区当前状态**（版本 `6814a6c+dirty`），同时包含另一条并行会话未提交的作业页改动（`AssignmentPage.tsx` 等）。
