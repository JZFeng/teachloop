# 决策 026 — 反馈语义色：四档、判据与守卫

日期：2026-10-25　状态：**生效**（已实现并被 `check-ui` 守住）
相关：AC-70.1–70.4、FR-151–152、宪法第十五条「反馈必须带语义色」、
`docs/decisions/022-ai-provider-failover.md`（探测失败分级）
代码锚点：`web/src/ui/InlineNote.tsx`、`web/src/styles/mockup.css`、`web/src/styles/app.css`、
`web/src/app/UiKitPanel.tsx`、`scripts/check-ui.py`

## 问题

2026-10-25 用户报：`/admin/ai` 添加模型失败时，页面显示

> 加载失败：HTTP 401；请确认地址与 Key 后重试。

这行是**灰字**（`<p className="small muted">`），和旁边的说明文字长得一样。
用户的要求是通用规则，不是单点修补：**错误与警告消息都要 color coded**。

盘点时发现这不是一处的事，有三类：

1. **12 处失败/阻断用灰字或无色提示**：SetupPage 错误条（而且它用的 `.danger` 类在 CSS 里
   根本不存在，等于完全没颜色）、考试交卷检查的报错、复核页与班级页的 actionError、
   建作业的 formError、词卡「内容生成失败」、顶栏「AI 未配置」、资料页「补全后才能保存」、
   学生评语缺失、缺少评分标准、结果页缺错题、考试入口的「不能开始 / 已锁定 / 没有次数」。
2. **`.chip` 从来没有全局样式**：只有 `.teacher-page .chip` 带作用域，于是词库的
   「未准备 / 内容生成中 / 内容生成失败」渲染出来就是一行灰字。
3. **错误只说「失败了」，不说下一步**：401 与 404、429、5xx、超时在页面上是同一条文案，
   用户不知道该改 Key 还是改地址。

## 决策

### D1 · 四档语义，判据写死

| 档 | 含义 | 判据（满足任一即此档） | 载体 |
| :--- | :--- | :--- | :--- |
| `error` | 红 | 操作失败、被拦住、数据没生成成功、需要人立刻处理：HTTP 4xx/5xx、校验不过、生成失败、导出被禁止 | `InlineNote tone="error"` / `Alert type="error"` |
| `warning` | 黄 | 还能继续，但有风险或有事待办：AI 未配置、成绩未发布、次数用完、内容过期、该能力尚未接入 | `InlineNote tone="warning"` / `Alert type="warning"` |
| `success` | 绿 | 刚完成且有时效的反馈：已保存、已派发 | `InlineNote tone="success"` / `Alert type="success"` |
| `info` | 蓝/中性 | 纯解释：怎么用、数据从哪来、为什么这么设计 | 无色 `.inline-note` / `.muted` |

**只有前两档强制上色。** 灰字只允许承载第三、四类的解释性文字。

### D2 · 载体分工，不混用

- 页面级、需要一条说明整件事 → `Alert`（antd，带图标与底色）；
- 行内、卡片里、表格单元格旁 → `ui/InlineNote`（`.inline-note` + `.ok/.warn/.err`）；
- 表单字段自身的错误 → `.tl-field-error`（红字，字段下方），**不套提示条**；
- 状态标签 → `.chip` 与 `.chip.ok/.warn/.err/.info`（比 antd `Tag` 轻，用在表格里）。

### D3 · 探测类失败按原因分级（不是一律红）

`/admin/ai` 拉模型与测试连接共用一套分类（`AdminAiPage.tsx` 的 `classifyProbe`）：
401/403/`MissingSessionID` → 红 + 「检查 Key / 需要 session 头」；
404 → 黄 + 「填到 `/v1`，没有 `/models` 就手填模型」；429 → 黄 + 限流；
5xx → 红 + 对端异常；超时/连不上 → 黄 + 「容器里访问宿主要用局域网 IP」。

### D4 · 守卫：写不进检查的约束不算约束

宪法第十五条自己写着这句话，所以本决策同时落一条静态规则 `MUTED_FAILURE`
（`scripts/check-ui.py`）：**错误表达式落进灰色容器就失败**。

```tsx
// 失败
<p className="small muted">{error}</p>
<div className="inline-note">{membersError}</div>

// 通过
<InlineNote tone="error">{error}</InlineNote>
<Alert type="error" showIcon message={error} />
```

规则**能抓**：`{error}`、`{membersError}`、`{actionError}` 这类标识符（含 `error.message`
链式访问）落在 `className` 含 `muted` 或无色调 `inline-note` 的元素上。
规则**抓不到**：条件渲染里的语义（`{ok ? 'A' : 'B'}`）、第三方组件内部的错误文案、
`toast.error()` 之外的动态拼接——这些靠 review 与 AC-70 兜，不能因为「守卫过了」就当合规。

基线**不豁免**这条：接入时全仓命中 0 处，所以以后任何新增都是真新增。

## 反证（守卫真的会拦）

临时放入 `web/src/__guard_probe.tsx`：

```tsx
<p className="small muted">{error}</p>
<div className="inline-note">{membersError}</div>
```

`python3 scripts/check-ui.py` → 退出码 **1**，输出
`MUTED_FAILURE：现在 2 处，基线 0 处` 并逐条打印命中片段；删除该文件后退出码回到 **0**。

## 验证

- 颜色实测（亮色）：err `#b42338` on `#fde6e8`、warn `#8a5a00` on `#fff3d6`、
  ok `#17703f` on `#e6f5ec`、info 中性；暗色：err `#ff7d8a` on `#331218`、
  warn `#ffc85c` on `#2a2110`、ok `#6ee7a5` on `#0c2a1d`。
- 截图：`docs/reports/2026-10-25-message-colors/ai-401.png`（401 红色 + 鉴权提示）、
  `inline-notes.png`（四档提示条）、`tag-and-chip.png`（Tag 与 chip 两套密度）。
- 活参考：「界面标准」面板（资料页 → 偏好设置 → 展开界面标准）里的「提示条」「状态标签」两节；
  改这四档的颜色，第一处就改 `styles/mockup.css` / `styles/app.css`，第二处同步面板。
