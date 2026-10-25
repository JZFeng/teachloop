# 决策 018 — 词详情内容结构 v2：多语言、例句配置、可配置页面模板

日期：2026-10-24　状态：生效（2026-10-24 用户确认 v2.2；2026-10-25 用户确认 v2.3 改 zh/en 双语言）　相关：US-60、AC-60.x、AC-48.14、T432/T433
输入：用户 2026-10-24 提供的 `word_detail_page` 样例 JSON 与语言/例句配置要求。

## 1. 内容结构 v2（每条内容 = 一个词，语言无关的键）

用户样例里的 `Chinese`/`English` 键改成 **ISO 语言代码**（`zh`/`en`，`es` 留作扩展位），
这样同一份内容能服务「中文母语学英语」「英文母语学中文」两种画像，显示顺序由用户档案决定。
**V1 批量只生成 zh/en 两种语言（2026-10-25 v2.3 修订，见 §8）。**

```json
{
  "schemaVersion": 2,
  "word": "struggle",
  "language": "en",
  "basicInfo": {
    "pronunciation": { "uk": "/ˈstrʌɡ(ə)l/", "us": "/ˈstrʌɡəl/" },
    "partOfSpeech": ["verb", "noun"],
    "image": null
  },
  "meanings": {
    "zh": "奋斗；挣扎；努力；艰难的努力；斗争",
    "en": "to try very hard to do something difficult; ..."
  },
  "examples": [
    {
      "text": "She struggled to open the jar.",
      "translations": { "zh": "她费力地想打开罐子。" },
      "partOfSpeech": "verb",
      "usagePattern": "struggle to do something",
      "popularity": 1
    }
  ],
  "wordFormation": {
    "analysis": "struggle 源自古法语 estrangler…",
    "parts": [{ "text": "strive", "type": "root", "meaning": "努力" }],
    "confidence": "high"
  },
  "memoryAids": {
    "synonyms": ["strive", "fight", "battle", "endeavor"],
    "antonyms": ["surrender", "yield", "give up"],
    "mnemonic": "struggle = strive + trouble：努力去做，但过程中有困难。",
    "supplement": "注意区别：struggle to do 强调…；struggle with 强调…"
  },
  "wordFamily": {
    "items": [
      { "word": "struggled", "partOfSpeech": ["verb (past tense / past participle)"],
        "meanings": { "zh": "奋斗了；挣扎了" } }
    ]
  }
}
```

约定：

- `language` 是**词本身**的语言（目标语言）；`meanings` / `translations` 的键是解释语言。
- 例句 `text` 用目标语言，`translations` 至少含主语言；`partOfSpeech`、`usagePattern`、`popularity`
  用于排序与展示；前端在 `text` 里高亮该词。
- `examples` **1–6 条**：自然情况下给 6 条，虚词/罕见词/单义具体名词给 1–3 条也接受，绝不硬凑；
  `usagePattern` 可重复（词的搭配本来就少时），但句子必须不同；`popularity` 1..N。
  页面默认显示 3 条（不足 3 条就显示实际条数），「More」展开剩余。
- `wordFormation` 单独顶层字段（不放进 `memoryAids`）：`analysis` 构词/来源说明，`parts[]` 词根词缀，
  `confidence` high/medium/low；没把握就整个省略，不编词源。
- `schemaVersion` 每记录一份；ContentStore 只返回与配置一致的版本，换结构 = 出新包/新 prompt 版本。

## 2. 语言档案（per user，不属于词内容）

```json
{
  "mainLanguage": "zh",
  "targetLanguage": "en",
  "displayOrder": ["zh", "en"],
  "languageLabels": { "zh": "Chinese", "en": "English" }
}
```

- 默认 `zh` + `en`，可在个人资料里改；支持第三种语言（如儿子学中文：main=en、target=zh；
  学西语：main=zh、target=es）。
- 存 vocab 自己的表 `vocab_language_profile(user_id, main_language, target_language, display_order jsonb)`，
  不污染 identity 的 `users`；前端在个人资料页调用 vocab 的语言设置接口。
- **缓存键与主语言无关**：内容按 `(target 语言, normalized_form)` 存；`meanings`/`translations`
  默认生成 `teachloop.vocab.languages`（zh,en,es）三种，换主语言不需要重新生成。

## 3. 例句配置（全局可调，per-word 可覆盖）

```json
{
  "aiRequestCount": 6,
  "defaultVisibleCount": 3,
  "moreVisibleCount": 3,
  "sortRule": "按词性常用度和用法常见度排序，常用动词用法优先，名词用法随后"
}
```

- 作为 vocab 的 UI/生成配置（`vocab_ui_config` 行），管理员接口可改，改完前端下次请求生效（不改代码、不重启）。
- 生成 prompt 里要求 AI 按 `sortRule` 给出 `popularity`；后端保存时按 `popularity` 稳定排序，展示时先 3 条。

## 4. 词详情页面模板（不写死 section 顺序）

```json
{
  "sections": [
    { "key": "basicInfo",  "enabled": true },
    { "key": "examples",   "enabled": true, "options": { "defaultVisible": 3, "moreVisible": 3 } },
    { "key": "meanings",   "enabled": true, "options": { "order": "profile" } },
    { "key": "memoryAids", "enabled": true },
    { "key": "wordFamily", "enabled": true }
  ]
}
```

- 存 `vocab_ui_config(key='word_detail_template', value jsonb)`，管理员接口 `PUT /api/admin/vocab/ui-config`；
  前端 `GET /api/vocab/ui-config/word-detail` 取模板后按顺序渲染。以后要把例句放最前、构词放后面，
  改配置即可，不需要发版。
- 词详情页/复习卡共用同一份内容，但卡面只取 `meanings` + 前 1–2 条例句（模板可配）。

## 5. 与现有结构的关系

| 现状 | v2 |
| :--- | :--- |
| `content.senses[{pos,zh,en}]` | `basicInfo.partOfSpeech` + `meanings{lang: text}` |
| `content.examples[{en,zh}]` | `examples[{text, translations, partOfSpeech, usagePattern, popularity}]` |
| `content.analysis` | `memoryAids.supplement`（构词/用法提示） |
| `content.family[]` | `wordFamily.items[{word, partOfSpeech, meanings}]` |
| `content.phonetic{uk,us}` | `basicInfo.pronunciation{uk,us}` |

迁移：新预生成包直接按 v2 生成；存量 delta 的 v1 内容打 `schemaVersion:1`，UI 过渡期兼容渲染，
或在下次 prepare 时按 v2 重新生成（用户后续定）。

## 6. 已确认（2026-10-24）

1. `meanings`/`translations` 生成 **zh/en 两种**（v2.3 起；es 不再生成）：缓存键与主语言无关，换主语言不重新生成。
2. 语言档案存 `vocab_language_profile`（vocab 模块），学生在个人资料页设置。
3. 页面模板与例句参数存 DB 配置（`vocab_ui_config`），管理员接口可改，免发版。
4. 存量 v1 内容：过渡期 UI 兼容渲染；新包直接产出 v2，存量词在下次 prepare 时按需重生成（待实现时确认）。

## 7. 生成 prompt v2（草案）

系统提示（要点）：

- 角色：为「目标语言」的一个词生成学习内容；输出**严格 JSON**，字段与 v2 完全一致，禁止 markdown/解释。
- `meanings`：给出 zh / en 两种语言的简明释义（学习者视角，含主要义项顺序）。
- `examples`：**1–6 条**（自然给 6，虚词/罕见词/单义名词给 1–3，绝不硬凑）；`text` 用目标语言、必须包含该词（或其标准变形）；`translations` 给解释语言（英语词给 zh，中文词给 en）；
  `partOfSpeech` 必须是该词真实词性；`usagePattern` 写搭配（如 `struggle to do something`）；
  `popularity` 从 1 到 N，按「词性常用度 + 用法常见度」排序（常用动词用法优先，名词用法随后）；
  多词性时每个主要词性至少 1 条，搭配可重复、句子不重复，句子自然、不过长。
- `memoryAids`：同义词/反义词只给真实存在的词（与目标词词性一致）；`mnemonic` 一句话、可记忆；
  `supplement` 写常见混淆/搭配差异（如 struggle to do vs struggle with）。
- `wordFamily`：只给真实派生/变形（含 inflections），每条带词性与 zh/en 释义，最多 8 条。
- `basicInfo`：英美音标；词性按常用度排序；`image` 目前给 null。

用户提示：`word`、`targetLanguage`、解释语言列表、例句数量与排序规则（来自配置）。

输出校验（生成后、写库前）：

- JSON 结构/字段类型校验；`examples` 数量 1..配置上限；`popularity` 1..N 不重复；
- `text` 必须包含目标词（大小写/屈折容错）；`translations` 覆盖 zh/en（目标语言自身不重复翻译）；
- 不合格 → 重试一次，再失败标 FAILED（不写半成品）。

待讨论：是否需要按学生 CEFR/年级限制例句难度；同义词是否要标注词性；`usagePattern` 的写法规范。

## 8. 修订（2026-10-25，v2.3）：只生成 zh/en

用户 2026-10-25 决定：**只要英语 + 中文，不要西班牙语**。落地内容：

- `meanings` 只有 `zh`/`en`；英语词的例句 `translations` 只给 `zh`（`text` 本身就是英文，不重复翻译）；
- schema 里**不出现 `es` 键**（不生成、不校验、不做扩展位；2026-10-25 再确认「不上 es」）；语言档案默认 `zh` + `en`；
- 生成脚本与 prompt 升到 v2.3：`scripts/vocab/generate_content.py`、`docs/prompts/vocab-content-v2.md`；
- 2026-10-24 用 v2.2 生成的 46 条三语言记录**作废**，按 v2.3 重新生成；
- 同步：AC-60.9/AC-60.10、`plan.md` §4.2.2/§4.2.4、T432。

## 9. 修订（2026-10-25）：v2.5 语义核心与构词链

用户要求「词根 + 前后缀必须要有，这是记忆的关键」，并给了 Gemini 式样例。落地：

- 新增 `coreSemantics{core, notThis, mentalImage}`：语义核心（≤30 字，用来和近义词区分）、「不是什么」、一句话画面（≤60 字）。
- `wordFormation.parts[].origin`：每个词素写来源（Latin/Greek/Old Norse/Old English/uncertain）。
- `wordFormation.chain`：派生阶梯（`from[] → to`），**每步 from 的元素只能是 parts 里的文本或前一步的 to**，第一步只列构成第一个词所需的词素（inductively: `in- + duc → induce → inductive → inductively`）。
- `memoryAids.mnemonic`：只允许不透明词；出现 `+`、词根/词缀/前缀/后缀、root/prefix/suffix 视为伪词源，**直接清空字段**（不因可选字段丢弃整词）。
- `synonyms/antonyms[].nuance`：中文侧重说明。
- 读路径 `ContentView` 带出 `coreSemantics` 与 `derivation`；`review-back` / `word-details` 渲染「语义核心/画面」与「构词与派生」两块，**不渲染 mnemonic**（用户 2026-10-25 定）。
- 契约：`teachloop-vocab/src/main/resources/vocab/content-prompt-v2.5.json`；delta/包 `prompt_version=vocab-content-v2.5`。
