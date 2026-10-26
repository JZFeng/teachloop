# 行内动作层级修复：RowActions 收口为 `main`

- 日期：2026-10-26
- 触发：用户在 `/vocab/lists` 观察到「新建词单」（页面级）与「开始复习（10）」（行内级）
  **同色同权重**，判为「风格上根本没区分，但本质是两类按钮」
- 基线：`6b8e637`（T515）
- 相关：AC-25.1、AC-25.5、A1a/A1b（`docs/process/ui-review-checklist.md`）

---

## 1. 结论

1. **用户的判断成立，且根因是可定位的一行代码**：`RowActions` 组件默认就是描边
   （`RowActions.tsx:66` 原文 `variant={primary.variant ?? 'default'}`），是 `ListsPage` 主动把它
   覆盖成了 `primary`。整件事**不是审美分歧，是调用方绕过了组件的默认契约**。
2. **修法从 API 层收口，不只打补丁**：`RowActions` 的 `primary` prop 改名 `main`，并删掉
   `variant` 字段。这样「行内实色主按钮」在类型层面就写不出来了，同类问题不会复发。
3. **验证到端到端**：`/vocab/lists` 浏览器实测，行内按钮 class 由
   `ant-btn-primary ant-btn-variant-solid` → `ant-btn-default ant-btn-variant-outlined`；
   全屏 primary 只剩「新建词单」一个（另有顶栏「补全资料」，见 §5.2 待决）。

## 2. 问题与证据

用户给出的两个 DOM 片段，唯一差别是 `ant-btn-sm`：

```
新建词单   ant-btn-primary ant-btn-color-primary ant-btn-variant-solid
开始复习   ant-btn-primary ant-btn-color-primary ant-btn-variant-solid ant-btn-sm
```

即：**颜色、文字色、悬停色完全相同，只差 8px 高度**。扫视时两个蓝块争同一注意力预算。

### 2.1 五条证据链

| # | 位置 | 原文 | 含义 |
| :-- | :-- | :-- | :-- |
| 1 | `web/src/ui/Button.tsx:9` | 「primary 主操作，**一屏只该有一个**」 | 组件自己的文档 |
| 2 | `web/src/ui/Button.tsx:11` | 「text **表格行内**、工具条上的轻操作 → type="text"」 | 行内该用什么，文档写了 |
| 3 | `web/src/ui/RowActions.tsx:66` | `variant={primary.variant ?? 'default'}` | **默认描边，组件层是对的** |
| 4 | `web/src/vocab/ListsPage.tsx:248` | `variant: count > 0 ? 'primary' : 'default'` | **这一行覆盖了默认值** |
| 5 | `web/src/vocab/ListsPage.tsx:242` | `// AC-25.1：行内只留 1 个主操作（开始复习）` | 把「结构上的主操作」实现成了「视觉 primary」 |

对照组：`PapersPage.tsx` 的 `RowActions` 没传 `variant`，走默认描边，所以那页看起来正常。
同一个组件、两个页面，差别就在那一行覆盖。

### 2.2 根因：一个词的两个含义被焊在一起

- **结构义**：一块区域里最重要的**那个动作**（`RowActions` 的 main 槽位）——信息架构；
- **视觉义**：实色填充的 `primary` 按钮——视觉权重。

`RowActions` 的 prop 原本叫 `primary`。名字里带 primary，调用方就会顺手补 `variant: 'primary'`。
**命名把两个概念焊在一起，于是每次调用都是一次踩雷机会。**

## 3. 改了什么

| 文件 | 改动 | 理由 |
| :-- | :-- | :-- |
| `web/src/ui/RowActions.tsx` | `primary` → `main`；删 `RowAction.variant`；按钮固定 `variant="default"` | 从类型层禁止行内实色主按钮 |
| `web/src/vocab/ListsPage.tsx` | `main={{...}}`；删 `variant: count > 0 ? 'primary' : 'default'` | 原 bug 所在 |
| `web/src/teacher/GradingDetailPage.tsx` | `main={canGrade ? {...}}`；删 `variant: 'primary'` | 第二处同类覆盖 |
| `web/src/teacher/PapersPage.tsx` | `primary={{` → `main={{` | API 同步（本来就对） |
| `web/src/teacher/GradingOverviewPage.tsx` | 同上 | API 同步 |
| `web/src/teacher/ClassesPage.tsx` | 同上 | API 同步 |
| `web/src/app/Shell.tsx` | 「去配置 AI」按钮 `primary` → `default` | 与「补全资料」同屏时两个实色按钮争注意力 |
| `web/src/app/AdminAiPage.tsx` | 卡片内「设为默认」`primary` → `default` | 多个非活跃 provider 会并排出现多个实色按钮 |

改动合计 **8 个文件**，其中 2 个是真正的 bug 修复，1 个是 API 收口，5 个是同步/同类收敛。

## 4. 验证记录

| 检查 | 命令 | 结果 |
| :-- | :-- | :-- |
| 类型检查 | `cd web && pnpm run typecheck` | 退出码 **0** |
| 前端单测 | `cd web && pnpm test` | **125 / 125 通过**，退出码 0 |
| 样式守卫 | `make check-ui` | 退出码 **0**，新增违规 0（存量 78） |
| i18n 守卫 | `make check-i18n` | 退出码 **0**，新增中文字面量 0 |
| 对比度 | `python3 scripts/ui/contrast.py` | 44 组全部达标 |

### 4.1 浏览器实测（`/vocab/lists`，暗色主题）

实测环境：后端 `:8010`（`/healthz` 200）+ 前端 `:5173`，账号 `student01@teachloop.local`。
为得到非禁用态的行内按钮，临时建了一条词单并加入 1 个词（`struggle`），**验证后已删除**
（`DELETE /api/vocab/lists/6172b3f0-...` 返回 200，列表回到 `[]`）。

| 元素 | 修复前 class | 修复后 class | 视觉 |
| :-- | :-- | :-- | :-- |
| 行内「开始复习（1）」 | `ant-btn-primary ant-btn-color-primary ant-btn-variant-solid ant-btn-sm` | `ant-btn-default ant-btn-color-default **ant-btn-variant-outlined** ant-btn-sm` | 描边，透明底 |
| 页面级「新建词单」 | `ant-btn-primary ...` | `ant-btn-primary ...`（不变） | 实色，全屏唯一 |

实测统计（修复后）：全页 `ant-btn-primary` 只剩 2 个 ——「补全资料」（顶栏，见 §5.2）与
「新建词单」（页面级）；行内动作全部为 outlined。

截图：`after-lists-dark.png`（同目录）。

## 5. 判定为「保留、不改」的位置

### 5.1 `LibraryPage.tsx:561` 横幅内「认领作业」

横幅是**有截止时间的临时任务**（`pendingDue`），抢注意力是合理的；且它不在表格行内，
不构成「行内与页面级同权」。保留 `primary`。

### 5.2 `Shell.tsx:131` 顶栏「补全资料」

**保留 primary，但这是待决项**。理由：`profileComplete === false` 是阻塞性状态，需要吸引注意。
副作用是它会和每个页面的主操作同屏，形成全站性的 2 个 primary（实测已确认）。
若你希望页面主操作永远唯一，可选：把它改成 `InlineNote tone="warning"` 的可点版本
（教师端的 AI 提示已经是这个形态），但这会让提醒强度下降，属于产品决策。

## 6. 这轮新发现的 3 个问题（未改，需要你定）

### 6.1 空态与页头出现两个「新建词单」

`/vocab/lists` 空态实测到 **2 个完全相同的 `ant-btn-primary`「新建词单」**：
`PageHeader` 的 actions 一个（`ListsPage.tsx:274`），`Empty` 里一个（`ListsPage.tsx:295`）。
同色、同文案、同尺寸，一屏两个。

建议：空态那个降为 `default`，或空态时隐藏页头主按钮。**未改**，因为空态 CTA 的引导强度
属于产品判断，且改动会波及 `SetsPage` 等同类页面。

### 6.2 `check-i18n` 与 `check-ui` 有同一类误报

`check-i18n` 会把 `/* */` 块注释里的中文当成「新增界面文案」并失败（本次实际踩到，
已改为英文注释绕过）。这与 `check-ui` 的 `RAW_TABLE` 注释误报同源：**守卫都在扫原文，
没有剥注释**。`check-ui` 已修（`strip_comments`），`check-i18n` 未修。

### 6.3 「主操作」一词在文档里仍然混用

`RowActions.tsx` 的注释已说明「主要动作是结构概念、不是 `variant="primary"`」，但
`AC-25.1` 等文档里「主操作」仍两义并用。建议后续在 `docs/process/glossary.md` 增加一条
术语定义（`main` = 结构义，`primary` = 视觉义）。

## 7. 未验证项

1. **亮色主题未截图**。实测在暗色主题下完成（用户当时的主题），亮色下的描边对比度未单独截图。
2. **其余 4 个页面只做了类型检查，没有逐页浏览器验收**：`PapersPage`、`GradingOverviewPage`、
   `GradingDetailPage`、`ClassesPage`。它们只把 prop 改名（行为不变），但「行为不变」是推断，
   不是实测。
3. **窄屏 / 移动端未测**。
4. 分页、排序、空态等表格行为未回归（本次未动 `DataTable`）。

按 `AGENTS.md` 完成度三档：`/vocab/lists` 达**端到端验收**；其余 4 页为**仅类型检查**。

## 8. 回退方法

```bash
git checkout -- web/src/ui/RowActions.tsx \
  web/src/vocab/ListsPage.tsx web/src/teacher/GradingDetailPage.tsx \
  web/src/teacher/PapersPage.tsx web/src/teacher/GradingOverviewPage.tsx \
  web/src/teacher/ClassesPage.tsx web/src/app/Shell.tsx web/src/app/AdminAiPage.tsx
```

只回退 `Shell.tsx` / `AdminAiPage.tsx` 两处、保留 `RowActions` 收口也是自洽的
（前者是产品判断，后者是组件契约）。
