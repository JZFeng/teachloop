# 词内容生成 Prompt v2.2（今晚批量用）

用途：用本地 LLM 离线批量生成 `word_detail_page` v2 JSON（每个词一条），供内容包（pack）导入。
配合脚本：[`scripts/vocab/generate_content.py`](../../scripts/vocab/generate_content.py)。

## 1. 调用参数

```json
{
  "model": "<你的模型名>",
  "temperature": 0.2,
  "response_format": {"type": "json_object"},
  "chat_template_kwargs": {"enable_thinking": false},
  "max_tokens": 4000
}
```

- **必须关闭 thinking**：同一条 prompt，开 thinking 用 4037 completion tokens / 45 秒，关掉后 1247 tokens / 13.5 秒。
- `max_tokens` 4000 足够（实测 1200–1400）；`finish_reason=length` 时脚本升到 6000 重试。
- 单槽服务串行跑；批量时不要和应用生成 worker 抢模型。

## 2. 结构（v2.2 变化）

- **例句 1–6 条**：自然情况下给 6 条；虚词、罕见词、单义具体名词给 1–3 条也行。**绝不硬凑**，
  `usagePattern` 可以重复（词的搭配本来就少时），但句子必须各不相同。
- **构词单独顶层字段 `wordFormation`**：不再塞进 `memoryAids`；和 `wordFamily`（同族词列表）也不是一回事。
  不确定就整个省略，不要编词源。
- `memoryAids` 只留同反义词、口诀、易混点。

## 3. System prompt

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
  "meanings": {"zh": string, "en": string, "es": string},
  "examples": [
    {
      "text": string,
      "translations": object,
      "partOfSpeech": string,
      "usagePattern": string,
      "popularity": integer
    }
  ],
  "wordFormation": {
    "analysis": string,
    "parts": [{"text": string, "type": string, "meaning": string}],
    "confidence": "high"
  },
  "memoryAids": {
    "synonyms": [{"word": string, "partOfSpeech": string}],
    "antonyms": [{"word": string, "partOfSpeech": string}],
    "mnemonic": string,
    "supplement": string
  },
  "wordFamily": {
    "items": [{"word": string, "partOfSpeech": [string], "meanings": {"zh": string, "en": string, "es": string}}]
  }
}

Rules:
1. examples: 1 to 6 entries. Give 6 when the word naturally supports it; 1 to 3 is acceptable for function words, rare words or concrete nouns with few collocations. Never pad with unnatural sentences. Each sentence must be natural, idiomatic and short, the kind a native speaker actually says, and must contain the word or a standard inflection. Cover every major part of speech at least once when you give more than one example. usagePattern is the real phrase the sentence uses, starting with the word or its article: "struggle to do something", "the apple of one's eye". usagePattern may repeat when the word has few patterns, but the sentences must be distinct. popularity is a unique integer starting at 1, ordered by part-of-speech frequency and usage commonness, common verb patterns first, noun uses after.
2. translations: only the explanation languages that differ from the target language (for an English word: zh and es, never en). The target-language sentence is already in text.
3. meanings: concise learner-facing definitions in Simplified Chinese (zh), English (en) and Spanish (es), covering the main senses and their order.
4. wordFormation: real morphology only (root, prefix, suffix, derivation, origin). Use "parts" only when you are sure of the segmentation; set confidence high/medium/low. Omit the whole wordFormation object when you are not confident; never invent etymology.
5. synonyms and antonyms: only real words with the same part of speech as the target; arrays may be empty; never invent.
6. wordFamily: at most 6 real derived forms or inflections, no duplicates and no invented compounds, each with part of speech and meanings in zh, en, es.
7. pronunciation: IPA in slashes (UK and US).
```

## 4. User prompt 模板

```text
Word: {word}
Target language: {language}
Explanation languages: zh, en, es (translations exclude {language}; use {other_languages})
Example count: up to 6 (fewer is fine when the word has few natural patterns)
Sort rule: order examples by part-of-speech frequency and usage commonness; the most common verb patterns first, noun uses after.
```

`{other_languages}` 对 `en` 是 `zh, es`；目标是中文时是 `en, es`。

## 5. 输出校验（脚本内置）

- 顶层字段齐全、`schemaVersion=2`、`word`/`language` 一致；
- `meanings` zh/en/es 非空；`basicInfo.partOfSpeech` 非空；
- `examples` **1–6 条**，`popularity` 为 1..N 不重复，句子不重复；`text` 含该词（大小写/屈折容错）；
  `translations` 覆盖非目标语言；`usagePattern` 非空；
- `wordFormation` 可选；出现时 `analysis` 非空、`confidence ∈ {high,medium,low}`、`parts` 每项含 text/type/meaning；
- `wordFamily.items` ≤6 且去重；
- 不合格重试一次（temperature 0.1），仍失败写 `failures.jsonl`，不写半成品。

## 6. 实测

| 词 | completion tokens | 耗时 | 结果 |
| :--- | ---: | ---: | :--- |
| struggle | 1247 | 13.7s | 6 条例句，verb 用法在前、noun 在后 |
| apple | 1381 | 14.6s | 6 条例句（count noun / proverb / idiom / 复合词） |

单槽吞吐 ≈ 89 tokens/s、约 13–15 秒/词 → 8 小时约 1,900–2,200 个词。

## 7. 版本

`prompt_version = vocab-content-v2.2`。写进内容包 manifest 与 delta 的 `prompt_version`；
以后改 prompt 必须升版本，旧内容不参与新版本命中。
