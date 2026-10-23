# T356 界面标准收进资料页偏好设置

- 日期：2026-10-22
- 起因：用户反馈 `http://localhost:5173/ui-kit` 的组件标准占了侧栏入口，「放 home page 始终是不合适」；
  用户要求把它与资料页现有的「偏好设置」合并，怎么合由实现方判断（原话：use your best judgement）。
- 结论：组件标准不再是一条产品导航，收进「我的资料 → 偏好设置」里按需展开；旧链接保留。

## 改了什么

| 文件 | 改动 |
| :--- | :--- |
| `web/src/app/UiKitPanel.tsx` | 由 `UiKitPage.tsx` 改名而来，去掉页头与「组件标准」导航图标行；分区从 `.card` 改为 `.tl-kit-section`（分隔线 + h3），便于嵌入资料页卡片 |
| `web/src/app/ProfilePage.tsx` | 偏好设置卡片末尾加「界面标准（组件与样式）」行：默认收起，按钮 `aria-expanded` / `aria-controls`；展开后渲染 `UiKitPanel`，状态写进 `?panel=ui-kit`；展开时滚到面板 |
| `web/src/app/Shell.tsx` | 删掉 `/ui-kit` 导航项（教师导航回到 7 项） |
| `web/src/App.tsx` | `/ui-kit` 改为 `<Navigate to="/profile?panel=ui-kit" replace />`，旧书签/文档链接仍可达 |
| `web/src/ui/PageHeader.tsx` | 删掉 `/ui-kit` 的 eyebrow 规则 |
| `web/src/ui/ui.css` | 新增 `.tl-pref-*` / `.tl-kit-embed` / `.tl-kit-section*` 布局；两处注释从 `/ui-kit` 改为界面标准面板 |
| `web/src/locales/{zh-CN,en}.json` | 新增 `profile.designTitle/designHint/designShow/designHide`；删掉不再使用的 `ui.navKit` 与 `eyebrow.uiKit`；`ui.kit.sub` / `ui.kit.note` 改成面板口径 |
| `scripts/ui-baseline.txt` | 基线路径 `UiKitPage.tsx` → `UiKitPanel.tsx`（计数不变） |
| `scripts/brand/shoot-app.mjs` | `ui-kit-*` 截图路由改为 `/profile?panel=ui-kit` |

行为边界：只有教师/管理员能看到这一行（与原来导航项的可见范围一致）；学生仍可在资料页改语言与主题，
但看不到组件标准。默认收起，不影响资料页主流程。

## 验证（真浏览器，dev 5173）

脚本：`node scripts/verify/ui-kit-in-profile.mjs`（本机 Chrome + 有数据的后端）。

| # | 断言 | 结果 |
| :--- | :--- | :--- |
| 1 | 教师登录 | PASS（HTTP 200） |
| 2 | 侧栏不再有组件标准入口 | PASS（`/ui-kit` 不在导航 href 里） |
| 3 | 教师导航回到 7 项 | PASS（首页/班级/作业/试卷/批改/复核/报表） |
| 4 | 资料页默认收起 | PASS（无 `#ui-kit-panel`，`aria-expanded=false`） |
| 5 | 展开后 10 个分区完整 | PASS（按钮/字段/统计卡/标签/表格/状态块/品牌/图标/排版/令牌） |
| 6 | 展开状态同步 URL | PASS（`/profile?panel=ui-kit`） |
| 7 | 旧链接 `/ui-kit` 跳转 | PASS（`/profile?panel=ui-kit` 且面板已展开） |
| 8 | 收起后 URL 干净 | PASS（`/profile`） |
| 9 | 教师侧无控制台异常 | PASS |
| 10 | 学生看不到界面标准行 | PASS（语言与主题仍在） |
| 11 | 亮/暗两套面板渲染 | 截图见下 |

截图：

| 文件 | 内容 |
| :--- | :--- |
| `profile-preferences-collapsed.png` | 侧栏 7 项 + 偏好设置里的「界面标准」行（收起） |
| `profile-ui-kit-light.png` | 展开后的界面标准面板（亮色，1440px） |
| `profile-ui-kit-dark.png` | 展开后的界面标准面板（暗色，1440px） |

## 其它检查

- `pnpm typecheck`：0
- `pnpm build`：通过（antd chunk 300.88 kB gzip，与改动前同量级）
- `python3 scripts/check-ui.py`：0 新增（122 处存量，比基线更干净）
- `python3 scripts/check-i18n.py`：zh/en 各 1202 键对齐；无新增中文字面量
- `python3 scripts/ui/contrast.py`：40 组对比度全过
- `pnpm test`：28/28
- `make test`：退出码 0（check-deps / check-boundaries / check-i18n / check-ui + 后端 surefire + 前端 28/28）

## 未验证 / 已知限制

- 只截了 1440px 桌面视口；未在窄屏/移动端复核（面板网格用 `auto-fit`，理论上会折行，但没有截图证据）。
- `/ui-kit` 现在是一条重定向，不再有独立页头；依赖该路由的截图脚本已改为直接访问资料页展开态。
- T308（21 屏 × 亮暗截图基线）仍未建立，本次只做了受影响页面前后对照，不是像素基线。
