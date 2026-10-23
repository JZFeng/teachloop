# T354 证据：所有弹层按 Esc 关闭

- 环境：dev `http://localhost:5173`（Vite dev，`/api` 代理到 8011），教师 `teacher@teachloop.local`
- 场次：`bfd90d97-4e47-484a-9ef7-12e9fc31b9e2`（ASSIGNMENT「111111」；演示数据会变动，2026-10-22 实测时表格 3 行）
- 命令：`node scripts/verify/escape-dismiss.mjs`
- 按键方式：CDP `Input.dispatchKeyEvent` 真键盘事件（`keyDown`/`keyUp`，`key=Escape`、`code=Escape`、VK 27），不是 JS 里 `dispatchEvent` 模拟

## 脚本断言（10 项全过）

| # | 断言 | 结果 |
| :--- | :--- | :--- |
| 1 | 教师登录 200 | 通过 |
| 2 | `/api/offerings` 有数据 | 通过（2 场） |
| 3 | 批改详情表格里点开「审计」 | 通过 |
| 4 | 审计抽屉是 `role="dialog"` + `aria-modal="true"` + `aria-label="审计时间线"`，标题「Jason · 审计」，加载 6 条事件 | 通过 |
| 5 | 按 Esc 后抽屉消失 | 通过 |
| 6 | 关闭后路由仍是 `/grading/bfd90d97-…`、表格行数不变（本次 3 行） | 通过 |
| 7 | 无弹层时再按 Esc 无副作用 | 通过 |
| 8 | `/assignments/new` 放大编辑器遮罩打开、页面滚动被锁 | 通过 |
| 9 | 按 Esc 收起遮罩并恢复滚动 | 通过 |
| 10 | 全程无未捕获异常 / 控制台 error（登录前 `/api/me` 的 401 是设计内噪音，已排除） | 通过 |

栈逻辑另有 `web/tests/escape-stack.test.mjs` 8 条单测：空栈、非 Esc 键、LIFO 只关最上层、
顶层拒绝关闭不穿透、注销幂等、乱序注销、输入法组合中的 Esc、已被 `preventDefault` 的 Esc。

## 截图

- `audit-open.png`：审计抽屉打开（Jason · 审计，6 条事件）
- `audit-closed.png`：按 Esc 之后，抽屉消失、页面与表格原样
- `editor-open.png`：放大编辑器遮罩（Vditor IR 工具条与占位提示，页面滚动已锁）
- `editor-closed.png`：按 Esc 之后，遮罩收起、滚动恢复

## 未验证

交卷前检查弹窗、答题页窄屏目录抽屉、完形/七选五 A–G 选项弹层：都已接同一个
`web/src/ui/useEscapeClose.ts`，但没有单独的浏览器按键证据（验证需要学生进入一场真实作答）。
