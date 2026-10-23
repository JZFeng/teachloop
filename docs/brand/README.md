# 品牌源图（唯一存放处）

这个目录放 TeachLoop 的**位图源文件**。前端不直接引用这里的 PNG：构建脚本把它们裁成
`web/public/brand/` 下的小尺寸资产，再给登录页、侧栏、favicon 用。

**不要复制到别处、不要改名、不要删**——两份源图的 sha256 已经写进
`scripts/brand/build-assets.py`，换图或改名会让 `--check` 直接失败；以后做品牌页、关于页、
商店图、邮件模板时，从这里取原图。

## 一、`teachloop-logo-source.png`（构建源）

- 尺寸 1254×1254，sha256 `93f02fd1b7d92da6a22d144117e0bf65ae2142d8fe072f401d1f344728c84484`
- 内容：铅笔循环 mark + `TeachLoop` 字标 + `Better Practice. More Insights.`
- 用途：**唯一**的构建源。`scripts/brand/build-assets.py` 从它裁出 9 个文件到
  `web/public/brand/`：

  | 产物 | 用途 |
  | :--- | :--- |
  | `logo-mark-{32,128,256,512}.png` | 侧栏 mark、登录页暗色 mark、`Brand` 组件 |
  | `logo-mark-wide-512.png` | 横向位图 mark（登录页左侧） |
  | `logo-wordmark-960.png` | 纯字标 |
  | `logo-lockup-960.png` | 登录页亮色整标 |
  | `favicon-32.png` / `apple-touch-icon-180.png` | 浏览器页签 / iOS 主屏 |

- 重新生成：`python3 scripts/brand/build-assets.py`（需要 Pillow）
- 只校验：`python3 scripts/brand/build-assets.py --check`
- 换图流程：改 `EXPECTED_SHA` 与 `EXPECTED_BOXES` → 重新生成 → 跑
  `--check` → 覆盖本文档里的 sha。

## 二、`teachloop-brand-sheet.png`（四功能位参考页）

- 尺寸 1254×1254，sha256 `910864125359a89086b2ed7c6b8fefa88bd5a8b38901b60082425fea221bf3bb`
- 内容：与构建源同一版 logo，下方是四个功能位 Create / Assess / Practice / Insights
  （粉彩圆底 + 深色图标 + 中文说明）。
- 用途：**品牌参考图，不参与构建、不进前端**。页面上的四个功能位是用 CSS 令牌 +
  antd 图标重建的（`web/src/app/LoginPage.tsx`、`web/src/app/UiKitPanel.tsx`），不是切这张图。
- 以后大概率用到的场景：品牌/关于页、登录页改版、商店素材、对外介绍页、设计交接。
- 改动它不会影响任何前端产物，但仍然要同步本文档与
  `scripts/brand/build-assets.py` 里的 `EXPECTED_BRAND_SHEET_SHA`。

![四功能位品牌页](teachloop-brand-sheet.png)

## 三、相关文档

- 决策：[`docs/decisions/006-brand-visual.md`](../decisions/006-brand-visual.md)
- 规格：`specs/006-brand-visual/spec.md`（§5 色板、§6 守卫与证据）
- 调研：[`docs/research/2026-10-22-brand-visual.md`](../research/2026-10-22-brand-visual.md)
- 可点样张：[`docs/mock/brand-2026-10-22/`](../mock/brand-2026-10-22/)（独立 HTML，不引用本目录）
