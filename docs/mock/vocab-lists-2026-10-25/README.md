# 学生词单原型（2026-10-25）

对应规格：[`specs/004-vocab/spec.md`](../../../specs/004-vocab/spec.md) US-69 / AC-69.1–69.11（在约第 239–256 行）
对应决策：[`docs/decisions/020-vocab-word-lists.md`](../../decisions/020-vocab-word-lists.md)

单文件、无依赖、无构建：**双击 `index.html` 就能点**。

## 怎么 review

1. 顶部固定原型工具条：**五屏直达**（我的词单 / 词单详情 / 查词页 / 复习 / 小结）+ **状态**（有数据 / 空 / 出错）+ 「示例数据 · 可点击」标记 + 右侧暗色开关。
2. 重点看三处：**0 词词单的空态**、**暂停词在词单里的呈现**、「加入词单」弹层里的**重复加入与新建**。
3. 全部为示例数据，没有任何后端调用。

## 屏与深链

| 屏 | 深链 | 这一屏看什么 | 截图 |
| :--- | :--- | :--- | :--- |
| 我的词单 | `#lists` | 页头说明、「＋ 新建词单」、表格（名称/词数/最近更新/操作）、每行「开始复习（N）」「重命名」「删除」、上限 100 的页脚、以及「词库页工具条 → 词单」的入口示意 | `shots/lists.png` |
| 我的词单 · 空 | `#lists?ds=empty` | 空态给「新建词单 / 去加词」两个入口 | `shots/lists-empty.png` |
| 我的词单 · 出错 | `#lists?ds=error` | 出错态可重试，不假装有数据 | `shots/lists-error.png` |
| 词单详情 | `#listDetail` | 名称/描述/「12 词」「2 个已暂停不在复习范围」「顺序 = 加入顺序」、主按钮「开始复习（10）」、右上「＋ 加词」、逐词表（含暂停 chip）、移出、删除整份 | `shots/list-detail.png` |
| 词单详情 · 空 | `#listDetail?ds=empty` | 0 词时主按钮禁用、给「＋ 加词」 | `shots/list-detail-empty.png` |
| 查词页 · 加入词单 | `#wordDetail?add=1` | 头部 `＋` 图标展开页内弹层：现有词单多选（已在词单的置灰打勾）、底部新建词单输入、「加入」 | `shots/word-detail-add.png` |
| 从词单复习 | `#review` | 窄条「本次范围：小说生词 · 10 词（已排除暂停）」、续上次进度提示、**`review-front` 居中版式**、两档评分与 1/2 快捷键 | `shots/review.png` |
| 从词单复习 · 翻面 | `#review?flip=1` | 翻面后是 `review-back`（释义/例句/构词），与产品当前版式一致 | `shots/review-flipped.png` |
| 结束小结 | `#summary` | 看过 8 / 认识 6 / 不认识 2 / 跳过 0；「返回词单」「再看一遍」 | `shots/summary.png` |

暗色：任意深链后加 `&theme=dark`，例如 `#lists?theme=dark`。

## 画的时候补出来的界面细节（与 AC-69.x 对应）

| 细节 | 依据 |
| :--- | :--- |
| 0 词词单：列表行「开始复习」禁用；详情页给「**还没有词**」+「＋ 加词」 | AC-69.10 的空态要求 |
| 暂停词**仍留在词单表里、保持加入顺序**，只加「已暂停」chip；「N 个已暂停不在复习范围」放详情 chips，「（已排除暂停）」放复习窄条，按钮数量 = 排除后数字 | AC-69.7 + AC-61.1 的同一排除口径 |
| 暂停是**全局进度**：暂停 `brave` 后「小说生词」变 10 可复习、「课堂生词」也从 8 变 7 | AC-69.1「进度只有一份」的可视证据 |
| 弹层里已属该词单的行置灰并标「已在词单」，重复加入不报错、不重复计数 | AC-69.2 幂等 |
| 词单上限 100 写在列表页脚；到上限时「新建词单」禁用 | AC-69.4 |
| 画了「我的词库工具条 → 词单」的入口关系，侧栏仍只有两项 | AC-69.11 / FR-104 |
| 「删除词单」的确认条写明「**不会删除词条与学习进度**」 | AC-69.3 |

## 待拍板的 10 条（画的时候发现 spec 没写清，未自行定规则）

见 [`spec.md` §25](../../../specs/004-vocab/spec.md) 的「Mock 回写（2026-10-25 学生词单）」——那里是同一份清单，拍板后直接回写 AC。

## 截图

`shots/` 下 9 张，1440×1000，无头 Chrome 打（与 `docs/mock/vocab-2026-10-23` 同一套命令）：

```bash
cd docs/mock/vocab-lists-2026-10-25
node -e "const fs=require('fs');const h=fs.readFileSync('index.html','utf8');
const m=h.match(/<script>([\s\S]*)<\/script>/);new Function(m[1]);console.log('JS OK')"

CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
"$CHROME" --headless=new --no-sandbox --disable-gpu --hide-scrollbars \
  --user-data-dir="$PWD/.chrome-profile" --virtual-time-budget=2500 \
  --window-size=1440,1000 --screenshot="$PWD/shots/lists.png" \
  "file://$PWD/index.html#lists"
```

`.chrome-profile/` 是临时数据，已在 `.gitignore` 里。

## 验证档位

**界面入口 + 交互**：五屏与三态都在真实 Chrome 里从 `file://` 打开并截图确认；点击交互（新建/重命名/删除/移出/加入词单/复习闭环）由原型自身状态机驱动。
**未验证**：端到端（没有后端、没有接口联调）、像素级与 `web/src` 组件的一致性（真实页面用 TinyVue 组件，原型是手写 CSS）。
