# T337 题目要求 / 评分标准放大编辑（2026-10-22）

## 现象

教师反馈 `/assignments/new` 里「题目要求」和「评分标准」两个窗口太小：正文区只有 960px 宽、
6 / 9 行高，长 rubric 要来回滚动，看不到排版结果。

## 改法

`MarkdownEditor` 新增两个可选 prop（不传时嵌入式编辑器行为完全不变）：

- `expandable`：工具条右侧出现「放大编辑」按钮；
- `heading`：放大态顶部显示字段名（题目要求 / 评分标准）。

放大态用 `createPortal` 铺在右侧内容区（`--app-sidebar-w: 238px` 起，侧栏保留可见；≤900px 无侧栏时铺满整屏）：

- 顶部三选一：**编辑 / 并排 / 预览**，默认并排，边写边看；
- 「全屏」再调浏览器原生 Fullscreen API（`md-overlay-panel` 进全屏）；
- Esc 收起（全屏时先由浏览器退全屏）、页面滚动锁定、焦点回到「放大编辑」按钮；
- 评分标准的 `footer`（「AI 将看到」评分项与合计）在放大态照样显示。

i18n 新增 `editor.expand / collapse / fullscreen / exitFullscreen / split` 五个 key，zh/en 各 1158 键对齐。

## 验证（QA 5174，Playwright + 本机 Chrome）

| 检查 | 结果 |
| :--- | :--- |
| 放大态覆盖右侧、侧栏可见 | `.md-overlay` `x=238, width=1202, height=900`；侧栏 `x=0, width=238` 可见 |
| 并排 | `.md-pane` 两栏（编辑 + 预览） |
| 实时渲染 | 输入 `# 题目要求` + `- 列表` + `**加粗**` 后，预览出现标题、列表、加粗，没有保存动作 |
| 单栏切换 | 选「预览」/「编辑」后 `.md-pane` 各 1 栏 |
| 原生全屏 | 点「全屏」后 `document.fullscreenElement` = `md-overlay-panel`；点「退出全屏」后为 null |
| Esc 收起 | 遮罩 detached，焦点回到 `aria-label="放大编辑"`，`#assign-instructions` 值不变 |
| 评分标准 | 放大态标题「评分标准」，底部「AI 将看到」与合计提示可见 |
| 窄屏 760px | 遮罩 `x=0, width=760`，侧栏隐藏 |

守卫与构建：`pnpm typecheck` 0、`pnpm build` 0、`check-i18n` 通过（1158 键）、`check-ui` 通过（无新增违规）。

## 截图

| 文件 | 内容 |
| :--- | :--- |
| `01-create-normal.png` | 原样：两个编辑器仍是小窗，工具条右侧新增放大图标 |
| `02-instructions-expanded.png` | 题目要求放大态：并排为空 |
| `03-expanded-typed.png` | 输入 Markdown 后实时预览 |
| `04-expanded-preview-only.png` | 只预览 |
| `07-rubric-expanded.png` | 评分标准放大态 + 「AI 将看到」 |
| `08-narrow-expanded.png` | 760px 窄屏：铺满整屏 |

## 未做 / 边界

- 放大态不做焦点陷阱（侧栏仍可点，`role="dialog"` + `aria-modal="true"` 只表达语义）；
- 浏览器原生全屏在 Safari/iPad 上由系统决定，未逐端验证；
- 没有单元测试（前端没有测试框架），验证靠上述浏览器实测与三条守卫脚本。
