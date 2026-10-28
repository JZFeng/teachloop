# 实验：卡片背面 core-semantics 右侧小图（2026-10-26，branch `exp/vocab-card-image`）

## 结论

- **能拿到图**，但不是图像模型：OpenCode Go 网关没有 `/images/generations`（404），做法是让文本模型在同一份 JSON 里多输出一个 inline SVG 字段。
- **成本**：$0.00096 → $0.00109/词（**+13%**）；113 词约 +$0.01，3,000 词约 +$0.39。
- **效果**：几何/结构类隐喻可用（hierarchy 金字塔），具象动作类差（justify / struggle 变成抽象符号）。
- 结论建议：如果要做成收费 feature，优先「AI 选图标 + 现成图标库」，而不是让模型画 SVG。

## 1. 能力探测

| 端点 | 结果 |
| :--- | :--- |
| `GET /v1/models` | 404（网关不暴露模型列表） |
| `POST /v1/images/generations` | 404 |
| `POST /v1/images/edits` | 404 |

所以「要图片」只能是文本模型产出的可渲染格式 → 选了 inline SVG（`<svg viewBox="0 0 96 96">`，≤1200 字符，≤4 色，无脚本/外链）。

## 2. 实测（3 个词）

| 词 | 耗时 | 输出 tokens | 成本 | SVG 长度 | 观感 |
| :--- | ---: | ---: | ---: | ---: | :--- |
| justify | 8.7s | 1418 | $0.00113 | 479 | 抽象（画框+圆点，看不出“拿证据证明”） |
| struggle | 9.9s | 1326 | $0.00107 | 329 | 弱（棕色方块+金色线条，像抽象符号） |
| hierarchy | 9.4s | 1346 | $0.00108 | 546 | 可用（金字塔+分层小人） |

成本对比基线（同批无图）：$0.00096/词、输出 ~1187 tokens。

## 3. 渲染

`preview.png` 是同一套 `vocab.css` 的静态预览：`core-semantics` 变为两栏，左侧「语义核心 / 不是什么 / 画面」，右侧 88×88 的 SVG。

代码改动（本 branch）：

- `teachloop-vocab/.../ContentView.java`：`coreSemantics.image` 透传，`isSafeSvg()` 过滤（≤2KB、无 `<script>`/`foreignObject`/`href`/`on*=`）。
- `web/src/vocab/VocabReviewCard.tsx`：右侧渲染，`dangerouslySetInnerHTML`（依赖后端过滤）。
- `web/src/vocab/vocab.css`：`.core-semantics` 两栏布局，`.core-semantics-art` 88px。
- `teachloop-vocab/src/main/resources/vocab/content-prompt-v2.6-image-exp.json`：实验契约（在 v2.5 基础上加 `coreSemantics.image` 与第 10 条规则）。

## 4. 后续（收费 feature 的取舍）

| 方案 | 质量 | 成本 | 风险 |
| :--- | :--- | :--- | :--- |
| 文本模型画 SVG（本次） | 结构类可用、具象类差 | +13% | SVG 注入面（已过滤）、风格不稳 |
| AI 选图标（emoji / lucide / heroicons 名称） | 稳定、可预期 | ≈0 | 需要维护图标库与映射 |
| 接真正的图像模型（若套餐开放） | 最好 | 高（每张图 $0.01–0.04） | 延迟 + 内容审核 |

建议：V1 用「AI 选图标」，把「精致插画」做成付费/按需生成的增强项。
