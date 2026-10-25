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
- 组件级覆盖：Button 去掉三种 shadow；Input 聚焦光环用 `colorPrimaryBg`。
  *（原第 3 处「Table 表头/行悬浮用浅灰、单元格内边距 10/14」随 §3.1 决定退场——表格不再由 antd
  渲染，密度与表头样式改由 `ui/ui.css` 的 `tl-table*` 规则负责；`tokens.ts` 里 `components.Table`
  若无其他消费者应删除。）*
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
| `DataTable.tsx` | **手写表体**（以词库页为唯一真源）+ 受控排序分页 + 选择列 + 页脚 | 保持 `useListState` 的 URL 同步；**2026-10-25 由 antd Table 改为手写表体，见 §3.1** |
| `States.tsx` | Skeleton / Alert / Empty + `useConfirm` / `useToast` | 弃用 `window.confirm` 与 alert |
| `index.ts` | 唯一出口 | check-ui 第 3 条守 |

`web/src/components/States.tsx` 保留为**兼容转发层**，让 15 个未迁移页面不必当夜改 import；
迁完 16 屏后删除（T310）。

### 3.1 表格视图：以词库页为唯一真源（2026-10-25 决定，推翻 §3 原选型）

**背景**：仓库里同时存在三套表格实现——antd 版 `ui/DataTable.tsx`（6 页）、旧手写版
`components/DataTable.tsx`（5 页）、以及「我的词库」页自研的 `vocab/LibraryPage.tsx:599` +
`vocab.css`（选择列、排序、页脚 20/50/100、批量条、常驻工具栏）。用户要求把表格视图统一到词库页那一套。

**决定**：`ui/DataTable.tsx` 重写为**手写表体**，直接采用词库页已验证的 DOM 与样式
（类名 `tl-table*`，样式从 `vocab/vocab.css` 迁到 `ui/ui.css`）；`components/DataTable.tsx` 删除；
全仓不再使用 antd `Table`。

**为什么不是「antd Table + 令牌对齐」**（此前推荐、已被否决）：

| 维度 | antd Table + 令牌 | 手写表体（采纳） |
| :--- | :--- | :--- |
| 真源数量 | 表体 antd、页脚词库自绘 → 两套 | 表体 + 页脚 + 批量条同源 |
| 词库页自身 | 要改成 antd 渲染，表头/排序箭头/hover 变化，只能「调到接近」 | 零变化 |
| antd 升级面 | 依赖 v6 内部 DOM 行为（`docs/research/2026-10-21-design-system.md:30` 的警告） | 不受影响 |
| a11y | 白拿 `aria-sort`、`rowSelection`、全选 indeterminate | 自补（`scope` / `aria-sort` / 键盘触发 / indeterminate），一次性约 2–3h |
| 失去的能力 | — | 固定列、虚拟滚动（当前 15 张表均 <1000 行，无使用场景；sticky 表头用 CSS 即可） |

**代价**：与 §2「组件级覆盖只做三处（含 Table）」不一致——Table 的 antd token 覆盖随之退场，
`tokens.ts` 中 `components.Table` 若不再被使用应删除，`contrast.py` 的断言组数需同步更新。

**范围**：12 个 `<DataTable>` 站点 + 4 张手写列表表（词集 ×2、发词预览、班级成员）；
3 张只读分析表只统一视觉；编辑器/表单表、Markdown 渲染表、快捷键图例不动。

**列配置不加进组件**：列显隐与上下移仍只在词库页（用户 2026-10-25：其它页面不需要定制列）。

**AC → 承接映射（Plan review 第 1 条）**

| AC | 由谁承接 |
| :--- | :--- |
| AC-68.1 | 重写 `ui/DataTable.tsx` + 删 `components/DataTable.tsx`（T441、T443） |
| AC-68.2 | 组件内表头排序 / 页脚 / 空态 / 行悬浮（T441） |
| AC-68.3 | `ui/useListState.ts` 参数归一（`sort/dir/page/size`，读兼容 `pageSize`）（T441） |
| AC-68.4 | `selection` + `batchBar` 两个可选 prop（T441）；词库页 T442、词集页 T444 启用 |
| AC-68.5 | 词库页迁移，DOM 与视觉不变（T442） |
| AC-68.6 | `ui/ui.css` 的 `tl-table*` 样式 + 守卫与「不动清单」核对（T445） |

本变更**零数据库、零外部依赖、零跨模块调用**，Plan review 第 2、3、4、5、6 条不涉及（无表/无端口/无拆服务）。

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
