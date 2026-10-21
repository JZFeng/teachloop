# 技术方案 — UI 标准（003）

- 上游：`spec.md`（US-27–30 / AC-27.1–30.3 / FR-052–060）
- 决策：`.dsh/surveys/20261021-0000-ui-6-fb9093b8.json`
- 日期：2026-10-21

---

## 1. 分层与数据流

```
styles/tokens.css ──┐            (给未迁移的 16 屏：手写类名读这些变量)
                    ├─ 同一份值（contrast.py 断言相等）
ui/tokens.ts ───────┘            (给 antd：ConfigProvider theme.token)
        │
        ├── ui/ThemeProvider  → ConfigProvider(algorithm + token + locale) + html[data-theme]
        │
        └── ui/* 组件 → 页面（21 屏，先迁 5 屏）
```

**为什么令牌写两处**：antd 6 的 CSS 变量由 `token2CSSVar` 生成后挂在**每个 antd 组件的
hash class** 上（不是 `:root`）。若在 `:root` 写 `--panel: var(--ant-color-bg-container)`，
计算值阶段就是 guaranteed-invalid，16 个未迁移屏会整片掉色。因此改为「同一份值两个出口」。

## 2. 令牌

- 尺码共用（`shared`）：`fontFamily` 中文优先、`fontSize 14`、`lineHeight 1.65`、
  `borderRadius 6/LG 8/SM 4`、`controlHeight 32`、标题 24/20/16/14/13。
- 亮色：`colorPrimary #1D5FBF`（白字 6.10:1）、`colorTextTertiary #6B6B6B`（5.33:1）、
  `colorBorder #8C8C8C`（3.36:1）、`colorBgLayout #F5F6F8`。
- 暗色：`colorPrimary #2A6FD6`（白字 4.84:1）、`colorBorder #6B6B6B`（3.09:1）、
  `colorBgLayout #141414` / `colorBgContainer #1F1F1F`。
- 组件级覆盖只做三处：Button 去掉三种 shadow；Table 表头/行悬浮用浅灰、单元格内边距 10/14；
  Input 聚焦光环用 `colorPrimaryBg`。
- **antd 默认值有三处不达 AA，必须覆盖**：`colorPrimary #1677FF`(4.10)、
  `colorTextTertiary #8C8C8C`(3.36)、`colorBorder #D9D9D9`(1.41)。

## 3. 组件层（`web/src/ui/`）

| 文件 | 内容 | 关键约束 |
| :--- | :--- | :--- |
| `tokens.ts` | `buildTheme(mode)`、`CONTRAST_NOTES` | 颜色只在这里出现 |
| `ThemeProvider.tsx` | ConfigProvider + antd `App` + 主题状态 | 三处同步：theme / `html[data-theme]` / locale |
| `ThemeToggle.tsx` | Segmented 亮/暗 | 写 `localStorage['teachloop.theme']` |
| `Button.tsx` | `variant: primary/default/text/danger` + `busy` | 主操作一屏一个 |
| `Field.tsx` | label + 必填星 + hint + error | 不用 antd Form，避免重写业务校验 |
| `Input.tsx` / `Alert.tsx` | 重新导出 antd 控件 | 页面不直接引 antd |
| `PageHeader.tsx` | 标题 / 说明 / 操作 | |
| `StatCard.tsx` | 大数字卡，可点击时是 button 语义 | 支持 Enter/Space |
| `Tag.tsx` | tone → antd color | 颜色只表达语义 |
| `DataTable.tsx` | antd Table + 受控排序分页 | 保持 `useListState` 的 URL 同步 |
| `States.tsx` | Skeleton / Alert / Empty + `useConfirm` / `useToast` | 弃用 `window.confirm` 与 alert |
| `index.ts` | 唯一出口 | check-ui 第 3 条守 |

`web/src/components/States.tsx` 保留为**兼容转发层**，让 15 个未迁移页面不必当夜改 import；
迁完 16 屏后删除（T310）。

## 4. 守卫

`scripts/check-ui.py` 五条规则：颜色字面量位置、内联样式颜色键、antd 直引、手写 btn 类名、
emoji 图标。基线 `scripts/ui-baseline.txt`（145 处存量）只降不升。

`scripts/ui/contrast.py`：解析 `tokens.ts` 与 `tokens.css`，断言 7 组变量相等 + 11 组对比度达标。

`Makefile`：`test: check-deps check-boundaries check-i18n check-ui`。

## 5. 迁移路径（5 屏）

| 屏 | 改了什么 | 没改什么 |
| :--- | :--- | :--- |
| `/login` | antd Input/Password、Alert 错误条、Field、实色主按钮 | 表单提交逻辑 |
| `/` | 组件层 StatCard/DataTable/Button | 聚合口径、URL 状态 |
| `/papers` | 组件层按钮、antd Table | 列表接口与排序 |
| `/assignments/new` | Field + antd Input/TextArea、Alert 表单错误 | 提交参数 |
| `/review` | 组件层 Button/States/DataTable、Tag | 三态复核与发布逻辑 |

## 6. 包体积

拆分 vendor chunk：`antd` 922.64 kB（gzip 293.82）、`index` 367.55 kB（gzip 98.64）、
`react` 41.04 kB（gzip 14.79）。antd 单 chunk 触发 >500kB 提示，属预期；若要再降，
只能按需引入组件或改用 CDN，不在本次范围。

## 7. 未做与原因

- **截图基线（T308）**：需要安装 Playwright 并连跑 21 屏 × 2 主题；夜间不启动新服务、
  不下载浏览器内核，留待用户在场时执行。
- **剩余 16 屏组件化（T309）**：按用户决策「先 5 屏」，其余靠令牌层继承视觉。
- **antd Form 迁移**：会连带重写业务校验，风险高于收益。

## 8. 风险与对策

| 风险 | 现实情况 | 对策 |
| :--- | :--- | :--- |
| antd 版本是 6 不是 5 | pnpm 装到 6.6.7 | React 19 无需 patch 包；`cssVar`/`darkAlgorithm` 在 6 仍在 |
| 暗色下旧页面掉色 | 已用显式值兜底 | `tokens.css` 暗色块给全 21 个变量；contrast.py 断言非空 |
| 内联样式绕过组件 | 基线 145 处存量 | 规则 2 拦新增；逐屏迁移时清零 |
| emoji 图标 | 旧页面仍有 1 处（PaperWizard） | 已进基线，迁移该屏时替换 |
