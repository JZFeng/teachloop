# 词内容生成 Prompt v2.1（今晚批量用）

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

- **必须关闭 thinking**：同一条 prompt，开 thinking 用 4037 completion tokens / 45 秒，关掉后 1247 tokens / 13.5 秒，
  内容一样（实测 `enable_thinking: false` 生效，`reasoning_content` 为空）。
- `max_tokens` 4000 足够（实测 1247–1400）；遇到 `finish_reason=length` 时脚本会升到 6000 重试。
- 单槽服务串行跑；批量时不要和应用的生成 worker 抢同一个模型（今晚先停应用或关掉 vocab 生成）。

## 2. System prompt

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
1. examples: exactly 6 entries. Each sentence must be natural, idiomatic and short, the kind a native speaker actually says, and must contain the word or a standard inflection. Cover every major part of speech of the word at least once. usagePattern is the real phrase the sentence uses, starting with the word or its article: "struggle to do something", "struggle with something", "the apple of one's eye", "an apple a day". Do not repeat a usagePattern. popularity is a unique integer 1..6 ordering examples by part-of-speech frequency and usage commonness, common verb patterns first, noun uses after.
2. translations: only the explanation languages that differ from the target language (for an English word: zh and es, never en). The target-language sentence is already in text.
3. meanings: concise learner-facing definitions in Simplified Chinese (zh), English (en) and Spanish (es), covering the main senses and their order.
4. synonyms and antonyms: only real words with the same part of speech as the target; arrays may be empty; never invent.
5. wordFamily: at most 6 real derived forms or inflections, no duplicates and no invented compounds, each with part of speech and meanings in zh, en, es.
6. pronunciation: IPA in slashes (UK and US).
```

## 3. User prompt 模板

```text
Word: {word}
Target language: {language}
Explanation languages: zh, en, es (translations exclude {language}; use {other_languages})
Example count: 6
Sort rule: order examples by part-of-speech frequency and usage commonness; the most common verb patterns first, noun uses after.
```

`{other_languages}` 对 `en` 是 `zh, es`；目标是中文时是 `en, es`。

## 4. 输出校验（脚本内置）

- 顶层字段齐全、`schemaVersion=2`、`word` 与输入一致（不区分大小写）、`language` 一致；
- `meanings` 的 zh/en/es 都非空；`basicInfo.partOfSpeech` 非空；
- `examples` 恰好 6 条，`popularity` 为 1..6 且不重复；
- 每条 `text` 含该词（大小写或常见屈折）；`translations` 覆盖非目标语言；`usagePattern` 非空；
- `wordFamily.items` ≤6 且去重；
- 不合格重试一次（temperature 0.1），仍失败写 `failures.jsonl`，不写半成品。

## 5. 实测（`struggle` / `apple`，本地 qwen3.8-flash-next-iq2_xs）

- `struggle`：1247 completion tokens，13.7s；6 条例句按 `struggle to do / with / against / through` 排序，
  名词用法在后；三种语言释义齐全。
- `apple`：1381 tokens，14.6s；例句覆盖 count noun、proverb、idiom（the apple of my eye）、食物复合词。
- 单槽吞吐 ≈ **89 tokens/s，约 13–15 秒/词** → 8 小时约 **1,900–2,200 个词**。
  如果今晚要跑更多，需要多进程/多模型实例，或减少语言/例句（当前是 3 语言 × 6 例句）。

## 6. 版本

`prompt_version = vocab-content-v2.1`。写进内容包 manifest 与 delta 的 `prompt_version`；
以后改 prompt 必须升版本，旧内容不参与新版本命中。
