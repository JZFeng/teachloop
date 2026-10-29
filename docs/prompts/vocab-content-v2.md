# 词内容生成 Prompt v2.5（英语 + 简体中文）

用途：离线批量生成 `word_detail_page` v2 内容（每个词一条），供内容包/delta 导入。
配合工具：[`scripts/vocab/generate_content.py`](../../scripts/vocab/generate_content.py)。
**权威文件**：`teachloop-vocab/src/main/resources/vocab/content-prompt-v2.5.json`（Java 运行时与批量工具同源，本文是便于阅读的副本）。

## v2.5 相对 v2.3/v2.4 的变化

- 新增 `coreSemantics{core, notThis, mentalImage}`：语义核心（≤30 字，用来和近义词区分）、「不是什么」、一句话画面（≤60 字）。
- `wordFormation.parts[]` 增加 `origin`（Latin/Greek/Old Norse/Old English/French/uncertain）。
- 新增 `wordFormation.chain[]`：派生阶梯 `from[] → to`，**每一步的 from 只能是 parts 里的文本或前一步的 to**；第一步只列构成第一个词所需的词素。
- `synonyms/antonyms[]` 增加 `nuance`（中文侧重说明）。
- `memoryAids.mnemonic`：只允许不透明词；出现 `+`、词根/词缀/前缀/后缀、root/prefix/suffix 视为伪词源，**直接清空**。
- 只产 `zh` + `en`，不产 `es`。

## 1. 调用参数

```json
{
  "model": "deepseek-v4.1-flash",
  "temperature": 0.2,
  "response_format": {"type": "json_object"},
  "max_tokens": 6000,
  "thinking": {"type": "disabled"}
}
```

- **必须关 thinking**：默认会先吐 9k–24k reasoning tokens，把 `max_tokens` 吃光导致正文截断。
- **必须带非默认 User-Agent**：Python urllib 默认 UA 会被 Cloudflare 拦成 `403 error 1010`。
- OpenCode Go 需要每请求随机 `x-opencode-session`（缺失返回 `400 MissingSessionID`，它只是路由头）。
- 单请求超时按 provider：opencode 20s、deepseek 60s、本地单槽 90s。

### provider 档案（`providers`，按 baseUrl 前缀匹配）

| 档案 | baseUrl 前缀 | 关思考字段 | 超时 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| `opencode` | `https://opencode.ai/inference/go/openai/v1` | `thinking` | 20s | 另需每请求随机 `x-opencode-session` |
| `deepseek` | `https://api.deepseek.com` | `thinking` | 60s | 2026-10-29 补；前缀只写主机名，`/v1` 形态也能命中 |
| `local` | `http://192.168.68.101:8080/v1` | `chat_template_kwargs` | 90s | 单槽模型，排队会超时 |

**系统设置里换 provider 时，契约里必须已有同前缀的档案**：没命中档案就不发关思考字段，模型默认开思考会把 `max_tokens` 吃满——2026-10-29 prod 切到 `deepseek` 后 10 个词 9 个 `内容生成失败`（worker 日志 `thinking not disabled (reasoning_tokens=…)`）。失败信息现在会直接点名「不在 prompt 契约的 providers 档案里」。

## 2. 结构（v2.5）

- `meanings`：zh + en；英语词的例句 `translations` 只给 zh。
- `coreSemantics`：结构化「语义核心 / 不是什么 / 一句话画面」，替代故事式联想。
- `wordFormation`：`parts`（prefix|root|suffix|combining form + meaning + origin）+ `chain`（派生阶梯）+ 可选 `analysis`、`confidence`。
- `examples`：1–6 条，`popularity` 1..N，`usagePattern` 可重复但句子不重复。
- `memoryAids`：同反义词（带 `nuance`）、可选 `mnemonic`（仅不透明词）、`supplement`。
- `wordFamily.items`：≤6 个真实派生/屈折，带词性与 zh/en 释义。

## 3. System prompt（权威副本见 JSON）

```text
You write vocabulary learning content as strict JSON.
Return ONLY one JSON object. No markdown, no comments, no extra keys.

Schema:
{
  "schemaVersion": 2,
  "word": string,
  "language": string,
  "basicInfo": {
    "pronunciation": {"uk": string, "us": string},
    "partOfSpeech": [string],
    "image": null
  },
  "meanings": {"zh": string, "en": string},
  "coreSemantics": {
    "core": string,
    "notThis": string,
    "mentalImage": string
  },
  "examples": [
    {
      "text": string,
      "translations": {"zh": string},
      "partOfSpeech": string,
      "usagePattern": string,
      "popularity": integer
    }
  ],
  "wordFormation": {
    "analysis": string,
    "parts": [{"text": string, "type": string, "meaning": string, "origin": string}],
    "chain": [
      {"from": [string], "to": string, "partOfSpeech": string, "meaning": string}
    ],
    "confidence": "high"
  },
  "memoryAids": {
    "synonyms": [{"word": string, "partOfSpeech": string, "nuance": string}],
    "antonyms": [{"word": string, "partOfSpeech": string, "nuance": string}],
    "mnemonic": string,
    "supplement": string
  },
  "wordFamily": {
    "items": [{"word": string, "partOfSpeech": [string], "meanings": {"zh": string, "en": string}}]
  }
}

Rules:
1. examples: 1 to 6 entries. Give 6 when the word naturally supports it; 1 to 3 is acceptable for function words, rare words or concrete nouns with few collocations. Never pad with unnatural sentences. Each sentence must be natural, idiomatic and short, the kind a native speaker actually says, and must contain the word or a standard inflection. Cover every major part of speech at least once when you give more than one example. usagePattern is the real phrase the sentence uses, starting with the word or its article: "struggle to do something", "the apple of one's eye". usagePattern may repeat when the word has few patterns, but the sentences must be distinct. popularity is a unique integer starting at 1, ordered by part-of-speech frequency and usage commonness, common verb patterns first, noun uses after.
2. translations: only the explanation languages that differ from the target language (for an English word: zh only, never en). The target-language sentence is already in text.
3. meanings: concise learner-facing definitions in Simplified Chinese (zh) and English (en), covering the main senses and their order.
4. wordFormation: real morphology only, never invent roots.
   "parts" lists each productive unit: text, type (prefix|root|suffix|combining form), meaning, origin when known. Do not list a silent final -e as a suffix. A monomorphemic word gets exactly one root part.
   "chain" is the derivation ladder, one or more steps, always starting from the smallest units and ending at the derived words:
   step 1 combines the morphemes (e.g. {"from":["in-","duc"],"to":"induce","partOfSpeech":"verb","meaning":"引导出"});
   every following step adds exactly one explicit affix or combining form, which MUST also appear in "parts"
   (e.g. {"from":["induce","-tive"],"to":"inductive",...}, {"from":["inductive","-ly"],"to":"inductively",...}).
   Every derivational item in wordFamily MUST appear as the "to" of some step; plurals and past forms may be skipped.
   Never emit a step whose only "from" element equals "to".
   BINDING RULE (mandatory): every element in any chain step's "from" MUST be either a text that appears in "parts" or the "to" of an earlier step. The FIRST step's "from" lists only the morphemes that build the first word (for inductively: ["in-","duc"] → induce, then ["induce","-tive"] → inductive, then ["inductive","-ly"] → inductively). Never introduce a morpheme in "chain" that is absent from "parts", and never list a suffix on the step that does not use it.
   Worked example of the expected shape (different word, format only):
   "parts":[{"text":"in-","type":"prefix","meaning":"向内","origin":"Latin"},{"text":"duc","type":"root","meaning":"引导","origin":"Latin"},{"text":"-tive","type":"suffix","meaning":"有……性质的","origin":"Latin"}],
   "chain":[{"from":["in-","duc"],"to":"induce","partOfSpeech":"verb","meaning":"引导出"},{"from":["induce","-tive"],"to":"inductive","partOfSpeech":"adjective","meaning":"归纳的"}]
   English verb-forming suffixes -le (frequentative: struggle, wrestle), -en, -ify, -ate, and noun/adjective/adverb suffixes -er/-ing/-ness/-al/-ive/-ity/-ous/-ly must be split off when the stem is recognizable.
   Never map a bound root onto a different modern English word to make the split look neat: struggle is strug- (bound root, origin uncertain) + frequentative -le, never "strive + -le". Write bound roots as they appear and mark origin as uncertain.
   If the etymology is genuinely uncertain, say so in "analysis" and use confidence low/medium, but keep the morphological pattern you can defend.
5. synonyms and antonyms: only real words with the same part of speech as the target; add "nuance": a short Simplified Chinese note saying how it differs from the target (e.g. "比 struggle 正式，强调持续努力"). Arrays may be empty; never invent.
6. wordFamily: at most 6 real derived forms or inflections, no duplicates and no invented compounds, each with part of speech and meanings in zh and en.
7. pronunciation: IPA in slashes (UK and US).
8. coreSemantics: "core" is the semantic core in Simplified Chinese, at most 12 characters, that separates this word from its nearest synonyms (struggle → "困难 + 努力，在阻力中持续用力"); "notThis" states what the word is NOT (struggle → "不是泛指'努力'，而是'努力应对具体阻力'"); "mentalImage" is ONE concrete scene in at most 40 characters, no story, no extra characters (struggle → "一个人使劲推一扇卡住的门，门几乎纹丝不动"). Do not tell the learner what to imagine more than once.
9. memoryAids.mnemonic: ONLY for opaque words that cannot be decomposed (proper nouns, loanwords, bound roots with unknown meaning). At most 30 characters. It must be a sound or spelling hook, and it MUST NOT claim any morphological relationship — never write "stretch + tug", "root", "prefix", "词根", "词缀", "+". For transparent words (two or more parts in wordFormation) omit "mnemonic" entirely.

```

## 4. User prompt 模板

```text
Word: {word}
Target language: {language}
Explanation languages: zh, en (translations exclude {language}; use {otherLanguages})
Example count: up to {exampleCount} (fewer is fine when the word has few natural patterns)
Sort rule: order examples by part-of-speech frequency and usage commonness; the most common verb patterns first, noun uses after.
```

`{otherLanguages}` 对 `en` 是 `zh`。

## 5. 输出校验（脚本与 Java 运行时同规则）

- 顶层：`schemaVersion=2`、`word`/`language` 一致、`meanings.zh/en` 非空。
- `coreSemantics.core` 非空且 ≤30 字；`mentalImage` 非空且 ≤60 字。
- `examples` 1–6 条：不含目标词的句子丢弃、句子去重、`popularity` 重排 1..N、`translations.zh` 非空。
- `wordFormation.parts` 非空；单条 root 允许（单根词），否则必须给 `chain`。
- `chain` 每步 `from` 元素只能取自 parts（或该词本身）与前一步的 `to`；`from==[to]` 的空步丢弃。
- `mnemonic` 违规（可拆词出现、含构词声称）直接清空，不因可选字段丢弃整词。
- `wordFamily` 去重、去掉词头自身、超过 6 条截断；派生词未进 chain 只记告警。

## 6. 实测

| 批次 | 词数 | 结果 | 成本 | 耗时 |
| :--- | ---: | :--- | ---: | ---: |
| 2026-10-25 v2.5（13 个学生词 + 100 个学术词） | 113 | 113/113，0 失败 | $0.13 | 3 分钟 |
| 2026-10-25 v2.3（词频前 3,000） | 3,000 | 3,000/3,000 | $2.05 | 57 分钟 |

单请求 p50 ≈6.3s / p90 ≈7.7s（opencode，5 并发）；批量吞吐 ≈1.4 秒/词。

## 7. 无人值守跑批

- preflight（默认开）：先花 1 次请求验证 HTTP 200 / `finish_reason=stop` / thinking 已关 / JSON 合法 / zh+en 都在。
- `--max-cost-usd`：达到上限停止提交新词（退出码 3）。
- `--retry-failures`：把 `.failures.jsonl` 里的词排前面重试。
- 心跳 `.status.json`、每轮 manifest 追加 `.runs.jsonl`；`manifest.promptSha256` 可对内容溯源。
- 自测：`python3 scripts/vocab/test_generate_content.py -v`（26 条）。

## 8. 版本

`promptVersion = vocab-content-v2.5`。升版必须同时改文件名、`promptVersion` 字段与 `teachloop.vocab.content.prompt-version`；
旧版本内容（delta 行 / 内容包）不参与新版本命中。
