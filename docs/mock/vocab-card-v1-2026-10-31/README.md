# 单词卡 V1 改版 Mock（2026-10-31）

> 目标：验证「按学习顺序重排卡背 + 构词与派生成为核心 + 例句 3 条局部展开 + 页面只留 ⓘ 快捷键入口」。
> 本目录只有静态原型，**没有改业务代码**；内容用 `eager` 的示例数据（当前 113 词试验阶段，正式内容按本文契约重新生成）。

## 1. 最终顺序

| 顺序 | 模块 | 默认状态 |
|---|---|---|
| 1 | 单词与发音（卡头，不是 block） | 展示 |
| 2 | `senses` 词性与中文释义 | 展示 |
| 3 | `morphology` 构词与派生 | 展示，不整体折叠 |
| 4 | `core` 核心理解 | 展示 |
| 5 | `examples` 核心例句 | 默认 `min(3, total)`，余下局部展开 |
| 6 | `synonyms` 近义词辨析（可含反义子行） | 展示 |
| 7 | `etymology` 词源补充 | 短则展开；长可局部折叠 |

页面级操作不变：卡片正面「不认识 / 认识」、右侧「下一个 →」、状态机与快捷键映射都不动。
本轮只删掉自由复习页底部的常驻快捷键行，并把快捷键入口收进页头 ⓘ（今日复习页本来就没有这一行）。

## 2. Block 契约（T594 envelope，内容 v3）

`content.blocks = [{id, payload}]`。顺序由后端固定为：

```js
const CARD_BLOCKS = ['senses', 'morphology', 'core', 'examples', 'synonyms', 'etymology']
```

`hidden` 只决定「有无」，不决定顺序；payload 缺失/空数组的块不输出；一块都没有时输出 `blocks: []`。
旧 v1 内容的 fallback 保留一个版本，但 113 词会按 v3 重新生成，不把它当设计约束。

### payload 形状

```jsonc
// id: "senses"
[{"pos": "adj.", "zh": "渴望的；热切的；急切的"}]

// id: "morphology"
{
  "modernNote": "现代英语中 eager 是整体词，不按词素拆分；-er 只是比较级后缀残留。",
  "parts": [],                       // 只放现代英语可分析的词素；整体借词为空
  "chain": [],                       // 只放现代派生链；历史演变不属于这里
  "derivatives": [
    {"word": "eagerly", "pos": "adv.", "meaning": "热切地"},
    {"word": "eagerness", "pos": "n.", "meaning": "渴望"}
  ],
  "inflections": [
    {"word": "more eager", "label": "比较级"},
    {"word": "most eager", "label": "最高级"}
  ],
  "family": ["eager", "eagerly", "eagerness"]
}

// id: "core"
{"core": "热切期盼，迫不及待",
 "notThis": "不是泛指「有兴趣」，而是带着急切、兴奋的渴望。",
 "mentalImage": "孩子盯着未拆的礼物，身体前倾，眼睛发亮。"}

// id: "examples"
[{"en": "She is eager to learn new languages.", "zh": "她渴望学习新的语言。"}]

// id: "synonyms"（反义作为子行；若最终删反义，这里只留 items）
{"items": [{"word": "keen", "pos": "adj.", "nuance": "比 eager 更强调兴趣浓厚，英式英语常用"}],
 "antonyms": [{"word": "indifferent", "pos": "adj.", "nuance": "漠不关心，与 eager 的热切相反"}]}

// id: "etymology"
{"text": "来自拉丁语 acer（尖锐、敏锐），经古法语 aigre 进入英语；-er 是比较级后缀的残留。",
 "sources": ["Latin acer", "Old French aigre"]}
```

内容规则：

- **`morphology` 讲现代构词**；`etymology` 讲历史来源。两者不互相替代，也不重复同一句。
- `parts` / `chain` 只在现代英语里站得住时才有值；整体借词（如 algorithm）允许为空，用 `modernNote` 说明「不强行拆分」。
- `derivatives` 是派生（eagerly / eagerness），`inflections` 是屈折（more eager / algorithms），不能混。
- 派生词最好带 `pos` + 简短 `meaning`；词族 `family` 只做去重后的词形集合。
- 例句 1–6 条；默认显示 3 条是前端策略，payload 仍给全量，展开不重新请求。
- 近义词 `nuance` 写「与目标词的差别」；反义如果保留，作为 `synonyms.antonyms` 子行，不新增第 8 块。
- `image` 退出本次 7 步卡片；T594 的 image block 代码可保留但默认不启用。

### 与 T594 的关系

保留：

- `{id, payload}` envelope、`hidden` 过滤、空 payload 跳过、前端 `resolveCardBlocks` 遍历；
- v1 fallback 一个版本（安全垫，不是设计约束）。

要改：

- 顺序：从「用户 `prefs.blocks`」改成固定 `CARD_BLOCKS`；profile 的「反面顺序」上下移删除，只留显示开关；
- 块集：9 个旧 id → 6 个新 id；`family + derivation` 合并进 `morphology`，`analysis` 改名 `etymology`，`antonyms` 并入 `synonyms`，`image` 退出；
- payload：T594 是「view 同名字段原样值」；新块是组合出来的，T594 里 `isSameAs` 的断言要换成形状断言；
- 内容：`ContentView` 的 v2 判定扩到 v3；`ReviewPrefs.ALL_BLOCKS` / `wordBlocks.ts` / i18n / 设置页同步；
- 测试：扩 `ContentViewBlocksTest`、`web/tests/review-blocks.test.mjs`，再加 v3 schema 校验。

## 3. 页面 chrome

- **页头**：`今日复习` + ⓘ。ⓘ 里是「今日已复习 / 剩余 / 新卡」+ 快捷键表；今日复习页本来就有这个入口（[ReviewPage.tsx:358](../../../web/src/vocab/ReviewPage.tsx#L358-L380)），不需要新做。
- **删底部提示**：只有自由复习页有 `空格翻面 · 1 不认识 · 2 认识 · S 暂停这个词`（[FreeReviewPage.tsx:555](../../../web/src/vocab/FreeReviewPage.tsx#L555)）；删掉，改由首用提示 + ⓘ 承接。
- **首用提示**：首次进入显示一次可关闭的顶部条，之后不再出现；文案只做「快捷键说明」，不新增改键设置（本次默认）。
- **profile**：`背单词` 标题右侧放 ⓘ，把现在的副标题「每天背多少、先复习还是先学新词、卡面显示什么」收进气泡；删掉标题下的那行 `p`（[ProfilePage.tsx:317](../../../web/src/app/ProfilePage.tsx#L317-L319)）。
- **设置页**：保留「每日任务量 / 学习策略 / 卡面显示什么」；删「反面顺序」，顺序固定；显示开关按 6 个模块（单词/发音与词性释义固定展示）。

## 4. 本 mock 采用的默认（三个待拍板项）

1. **扩展区**：近义词展开；反义词作为 `synonyms.antonyms` 子行；词源短则展开、长则折叠。  
   → 若改「删反义」或「词源折叠」，只改 `synonyms` payload / `etymology` 的默认展开即可。
2. **快捷键**：只读 + 首次提示；不做可改键。  
   → 若要做真改键，需要另加设置面板、存储、冲突检测，工作量另算。
3. **宽度**：820px 窄卡（ChatGPT 方案的 760–850）；右侧按钮尺寸/样式/相对位置不变，绝对 x 随卡片左移。  
   → 若要求按钮绝对位置不动，选保留 78% 宽卡；mock 顶部有「宽卡」开关可对比。

这三项还没有最终拍板；mock 里都做了可切换/可删的默认，不改代码即可对照。

## 5. 查看方式

直接用浏览器打开 `index.html`。顶部有一条**原型预览条**（不属于页面设计）：

- `复习 · 反面` / `复习 · 正面` / `设置页`：三个视图；
- `首次进入`：显示/隐藏首用提示；
- `宽卡 78%`：在 820 与 78% 宽卡之间切换。

截图时加 `?shot=1`（或点「截图模式」）隐藏预览条。可直接用 URL 参数复现状态：

- `?shot=1&first=0`：稳态反面
- `?shot=1&first=1`：首次进入（首用提示）
- `?shot=1&face=front`：卡片正面（未改）
- `?shot=1&info=page`：页头 ⓘ 打开
- `?shot=1&examples=all`：例句全部展开
- `?shot=1&view=settings`：背单词设置页
- `?shot=1&view=settings&info=settings`：设置页 ⓘ 打开
- `?shot=1&wide=1`：78% 宽卡对照

### 截图

| 文件 | 内容 |
|---|---|
| [review-1440.png](shots/review-1440.png) | 桌面稳态：7 步顺序、3 条例句、无底部提示 |
| [review-834.png](shots/review-834.png) | iPad Air 11 竖屏：无横向溢出，右侧「下一个」仍可见 |
| [first-run-1440.png](shots/first-run-1440.png) | 首次进入：一次性快捷键提示 |
| [front-1440.png](shots/front-1440.png) | 卡片正面（单词居中、认识/不认识原位） |
| [info-1440.png](shots/info-1440.png) | 页头 ⓘ：进度 + 快捷键 |
| [examples-expanded-1440.png](shots/examples-expanded-1440.png) | 例句展开到 6 条 |
| [settings-1440.png](shots/settings-1440.png) | 设置页：任务量 / 策略 / 显示开关 / 快捷键 |
| [settings-info-1440.png](shots/settings-info-1440.png) | 设置页 ⓘ：副标题收进气泡 |
| [settings-834.png](shots/settings-834.png) | 设置页平板 |
| [wide-1440.png](shots/wide-1440.png) | 78% 宽卡对照（不是推荐态） |

## 6. 验收口径

- 七个模块顺序与上表一致；构词与派生默认展示、不整体折叠；
- 例句默认 3 条，`查看另外 3 个例句` 只影响例句块，收起后回到 3 条；
- 卡片正面、`不认识 / 认识`、右侧 `下一个 →` 与当前实现一致（168×58、18px、主色描边、白底/翻面后主按钮）；
- 1440×900 与 834×1194 无横向溢出，正文 14px，主操作首屏可见；
- 颜色只用 `web/src/styles/tokens.css` 的令牌；卡片 12px 圆角、1px 边框、无投影；正文 ≥14px。
- 不出现会员/推广/tab/弹窗；不把 `analysis` 与 `etymology` 写成同一句。

## 7. 实现备注（不在本 mock 内）

- 内容 v3：`morphology` / `etymology` / `derivatives` / `inflections` 进 prompt 与校验；113 词重新生成，不做 3000 词回填。
- 后端：`ContentView` v3 → 固定顺序 blocks；`ReviewPrefs` 新 id + hidden 迁移；`ReviewQueueEndpoint` / `ReviewSessionEndpoint` 不再传用户顺序。
- 前端：`wordBlocks` 6 块；`reviewBlocks` 映射；`VocabReviewCard` 加 `morphology`/`etymology` 渲染器；例句 3+展开；页面 chrome。
- 估：内容 2–3 天、后端 1–2 天、前端 3–4 天、QA 1 天，合计 7–10 个工作日。
