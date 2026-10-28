# 示范评审：组卷向导 vs 试卷列表（2026-10-26）

> 目的：用清单（`docs/process/ui-review-checklist.md`）评两个真实页面，示范**怎么把「不好看」变成可核查的判定**。
> 同时暴露清单自身的边界：17 条里只有 9 条能靠源码判，其余必须上浏览器。

---

## 1. 结论

1. **两个页面差距不在审美，在迁移阶段**。`PapersPage.tsx`（160 行，已接组件层）17 条里 12 条过、
   0 条不过、4 条待浏览器实测、1 条不适用；`PaperWizardPage.tsx`（1072 行，半迁移）5 条过、
   **5 条不过**、2 条部分、5 条待实测。
2. **全仓的 UI 底子比「没设计过」的印象好**：颜色字面量 **0 处**、emoji **0 处**、对比度 **44 组全过**；
   欠债只剩手写 `btn` 类 **52 处**（13 个文件）和手写 `<table>` **11 处**（5 个文件）。
3. **`make check-ui` 当前退出码 2，但那是误报**——`RAW_TABLE` 规则把注释里的 `<table>` 算成违规
   （`web/src/exam/importToEditor.ts:10`）。详见 §4.1。

## 2. 方法与口径

| 手段 | 覆盖 | 能否独立复核 |
| :-- | :-- | :-- |
| `make check-ui` | 8 条硬规则 + 基线对比 | 能，退出码 + 明细 |
| `python3 scripts/ui/contrast.py` | 44 组对比度、两处令牌一致性 | 能 |
| grep 量化 | 手写 `btn` 类、手写 `<table>`、emoji、内联样式 | 能，命令在 §5 |
| `docs/reports/brand-app-2026-10-22/*.png` | 视觉观察 | 能看，但**截图日期 2026-10-22** |
| 浏览器 DevTools | 对比度、触达面积、Tab 顺序、焦点环 | **本次未做** |

**截图时效警告**：`paper-wizard-light.png` 与 `papers-light.png` 拍于 2026-10-22。此后至少有两处
已知改动——操作列由 4 个横排文字按钮改为 `RowActions`（1 主 + ⋯，AC-25.1）、全站删除 eyebrow
（决策 028）。**所以涉及视觉的判定只能算「当时的观察」，不等于当前渲染。**

## 3. 逐条判定

判定三态：`过` / `不过` / `待实测`（另标 `不适用`）。每条都能追到清单编号与证据。

### 3.1 `PaperWizardPage.tsx`（组卷向导，1072 行）

| 条目 | 判定 | 证据 |
| :-- | :-- | :-- |
| 0 风格声明 | 过 | `specs/006-brand-visual/spec.md` 已定义 |
| A1 一屏一个主操作 | **不过** | 4 处 `.btn.pri`：`:748` `:1037` `:1050` `:1066` |
| A2 层级只靠三样 | 待实测 | 需看当前渲染（截图有面包屑 + eyebrow + 标题 + 副标题四层，eyebrow 已删） |
| A3 对齐一条线 | 待实测 | 需 DevTools 量 `padding-left` |
| A4 间距来自刻度 | **不过** | `margin: '6px 0'`、`paddingLeft: 20`、`maxHeight: 420`（`:476`、`:809`） |
| A5 字号 ≤4 级 | 过 | 源码 0 处内联 `fontSize` |
| A6 一屏要填满 | **不过** | 截图 `paper-wizard-light.png`：2158px 高，第 1 步只有 2 个字段，卡片约占上半屏 |
| B1 同操作同组件 | **不过** | 8 处手写 `btn` 类名、`RowActions` 0 处 |
| B2 颜色只来自令牌 | 过 | `COLOR_LITERAL` 0 处 |
| B3 图标不用 emoji | 过 | `EMOJI_ICON` 0 处 |
| B4 状态组件同位置 | 部分 | import 了 `ErrorState` / `Loading`（`:7`），未接 `Empty` |
| C1 五态齐全 | 部分 | 有加载/错误；空态与超长未接 |
| C2 禁用要有原因 | 待实测 | 有 `disabled={step >= 4}`（`:1066`），原因是否可见需看渲染 |
| C3 灰字讲失败 | 过 | `MUTED_FAILURE` 0 处 |
| C4 表格四件套 | **不过** | 2 处手写 `<table>`（`:810`、`:982`）——但属 AC-68.6 明列的「编辑器/表单表」已知例外 |
| D1 对比度 | 待实测 | 需 DevTools 或存疑色的渲染值 |
| D2 可操作性 | 待实测 | Tab 顺序、焦点环、触达面积 |

**小计**：过 5 · 不过 5 · 部分 2 · 待实测 5 = 17。

### 3.2 `PapersPage.tsx`（试卷列表，160 行）

| 条目 | 判定 | 证据 |
| :-- | :-- | :-- |
| 0 风格声明 | 过 | 同上 |
| A1 一屏一个主操作 | 过 | 1 个 `variant="primary"`（「新建试卷」） |
| A2 / A3 | 待实测 | 需渲染 |
| A4 间距刻度 | 过 | 唯一内联值是 `marginBottom: 8`（刻度内） |
| A5 字号 | 过 | 0 处内联字号 |
| A6 一屏填满 | 不适用 | 列表页数据少时留白属正常 |
| B1 同操作同组件 | 过 | 0 处手写 `btn`；`DataTable` + `RowActions` |
| B2 颜色令牌 | 过 | 0 处 |
| B3 图标 | 过 | 0 处 emoji |
| B4 状态组件 | 过 | `Loading` / `ErrorState` / `Empty` 齐（`:5`） |
| C1 五态 | 过 | 加载、错误、空态、有数据四路都写了 |
| C2 禁用有原因 | 过 | 归档项带 `reason`（AC-25.5 对应处） |
| C3 灰字失败 | 过 | 0 处 |
| C4 表格四件套 | 过 | `DataTable` + `useListState`（排序分页写进 URL） |
| D1 / D2 | 待实测 | 需渲染 |

**小计**：过 12 · 不过 0 · 不适用 1 · 待实测 4 = 17。

## 4. 超出清单的两个真问题

### 4.1 守卫误报：`RAW_TABLE` 没排除注释

- **现象**：`make check-ui` 退出码 **2**，报 `web/src/exam/importToEditor.ts · RAW_TABLE：现在 1 处，基线 0 处`。
- **实情**：那处匹配在**文档注释**里——`importToEditor.ts:10` 是
  ``  * `p br strong em u ol ul li blockquote h1-h3`，且不留任何属性。填进去的 `<h4>` / `<table>` / ``
  ，讲的是富文本白名单，不是真的写了 `<table>`。
- **根因**：`scripts/check-ui.py:138` 把整份文件原文直接交给正则（`text = path.read_text(...)`），
  注释没被剥离。
- **为什么必须修**：AC-30.2 规定「基线只允许下降，新增违规必须改代码而不是改基线」。这条误报
  改代码改不掉（除非改注释措辞），人就会被逼着去动基线——正好破掉 AC-30.2 的约束。
- **修法**（待确认后动手）：`scan()` 里对 `.ts/.tsx` 先剥 `//` 与 `/* */`，再跑 `RAW_TABLE` 等文本规则。

### 4.2 `PapersPage.tsx:141` 的死样式

```jsx
<div className="row" style={{ justifyContent: 'space-between', marginBottom: 8 }}>
  <span className="small muted">{t('teacher.papers.orderHint')}</span>
</div>
```

- `space-between` 作用在**只有一个子元素**的容器上，无效果。
- 它同时是内联样式，违反「样式只来自 CSS/令牌」的方向（`INLINE_STYLE` 规则只统计**含颜色**的
  内联样式，所以没被抓到——这是规则的覆盖缺口，不是本行的错）。
- **修法**：删掉 `justifyContent`，`marginBottom` 换成 CSS 类。

## 5. 未验证项（必须写出来，不许默认通过）

1. **没有起服务，没有做浏览器测量**。D1（对比度）、D2（Tab / 触达）、A2、A3、C2 五条全部待实测。
2. **截图是 2026-10-22 的**，与当前源码有已知差异（操作列、eyebrow）。A6 的判定基于旧截图，
   当前可能已改善。
3. 本次只评 2 个页面，不是全仓结论。全仓欠债按文件分布见 `scripts/ui-baseline.txt`（注意该文件
   已部分过期：`check-ui` 自己报告 `PaperWizardPage` 的 `LEGACY_BTN_CLASS` 比基线少 17 处）。

按 `AGENTS.md` 的完成度三档，本次属**「仅结构校验」**，不是端到端验收。

## 6. 复核命令

```bash
make check-ui                                      # 退出码 2，来自 §4.1 的误报
python3 scripts/ui/contrast.py                     # 44 组全过
grep -coE 'className=\{?["`][^"`]*\bbtn\b' web/src/teacher/PaperWizardPage.tsx   # 8
grep -c '<table' web/src/teacher/PaperWizardPage.tsx                            # 2
grep -c 'RowActions' web/src/teacher/PapersPage.tsx                             # 2
```
