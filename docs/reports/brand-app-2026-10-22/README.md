# 品牌视觉真页面截图（2026-10-22）

这是 `specs/006-brand-visual/` 的验收证据：把品牌层落到真前端之后，用 CDP 登录并逐路由截的图。
不是像素基线（21 屏 × 亮暗基线仍是 003 的 T308，未做），是「改完长什么样」的评审材料。

## 怎么重跑

前置：本机 Chrome + 一个 dev server（代理到有数据的后端）：

```bash
# 终端 A：起前端（示例：代理到 QA 后端 8011）
cd web && TEACHLOOP_API=http://127.0.0.1:8011 WEB_PORT=5173 pnpm dev

# 终端 B：截图（默认写到本目录；数据未恢复时请用 OUT_DIR 避免覆盖）
node scripts/brand/shoot-app.mjs
OUT_DIR=.cache/brand-app-empty node scripts/brand/shoot-app.mjs
```

脚本会：登出 → 截登录页亮/暗/焦点/英文 → 初始化页 → 教师登录 → 截教师全部路由
→ 学生登录 → 截学生路由（有考试可开始时自动点「开始答题」再截 live 页）。

## 截了什么

| 组 | 文件 | 证明什么 |
| :--- | :--- | :--- |
| 登录 | `login-light` / `login-dark` / `login-en` / `login-focus-light` | 整标 + brandLine + 四个功能位；暗色文本字标；英文界面；焦点环可见 |
| 壳层与首页 | `home-light` / `home-dark` / `home-en` / `focus-papers-light` | 侧栏品牌区、导航功能色胶囊、页头 eyebrow、键盘焦点环 |
| 教师 | `classes-*`、`assignments-*`、`papers-*`、`new-assignment-*`、`grading-*`、`review-*`、`reports-*` | 全站令牌继承 + 模块 eyebrow（CREATE/ASSESS/INSIGHTS） |
| 深层教师 | `grading-detail-light`、`class-summary-light`、`paper-wizard-light`、`paper-import-light` | 表单、表格、统计卡、步骤条在新令牌下不变形 |
| 学生 | `student-home-*`、`student-classes-light`、`student-feedback-light`、`student-feedback-detail-light`、`student-assignment-light`、`student-exam-entry-light`、`student-exam-live-light` | 学生端配色与教师端一致；作答须知用 ASSESS 青；live 页是专注布局 |
| 其他 | `profile-light`、`notifications-light`、`admin-ai-light`、`setup-light`、`ui-kit-*` | 中性页不上功能色；组件页含品牌色板 |

## 数据口径

- 图里的班级 / 试卷 / 作业是 **QA 环境的测试数据**（`exam11111`、`222222` 之类），不是生产数据；
  用户 2026-10-22 已把 QA 库清空准备重建，重建后重跑脚本即可刷新。
- 空态版本（清库后）在 `.cache/brand-app-empty/`，未入库；它证明空态/零值样式正常。

## 两张需要数据的图怎么来的

- `student-feedback-detail-light.png`：学生端 `/feedback/:offeringId`，取 `feedbackPublished=true` 的场次。
- `student-exam-live-light.png`：用户建的考试窗口在创建时就已过期（`startAt 02:56Z / dueAt 03:00Z`），
  入口页没有「开始答题」。为了截 live 页，临时把 `due_at` 延后 3 小时、经
  `POST /api/offerings/{id}/attempts` 开了一次作答，截完**删掉该 attempt 并把 `due_at` 还原**。
  这不动任何产品代码；脚本现在的逻辑是：已有进行中的 session 就复用，否则点「开始答题」，
  再否则才调 attempts 接口。

## 未验证

- **跨浏览器**：只在 Chrome（headless 1440×900 @2x）验证。
