# 词内容生成 Prompt v2.3（zh/en 双语言批量）

用途：离线批量生成 `word_detail_page` v2 JSON（每个词一条），供内容包（pack）导入。
配合脚本：[`scripts/vocab/generate_content.py`](../../scripts/vocab/generate_content.py)。

## v2.3 相对 v2.2 的变化

- **只生成英语 + 简体中文**：去掉 `es`。`meanings` 只有 `zh`/`en`，例句 `translations` 只有 `zh`
  （目标语言是英语时，英语句子本身就是 `text`，不再重复翻译）。
- 其余结构不变（v2 已于 2026-10-24 定稿，见 `docs/decisions/018-vocab-content-schema-v2.md`）。
- 生成端从「本地单槽串行」扩到「OpenCode Go 4 并发」；每个请求独立、无会话上下文。

## 1. 调用参数

> **权威文件**：`teachloop-vocab/src/main/resources/vocab/content-prompt-v2.3.json`
> （system prompt + 全部请求参数）。Java 运行时与 `scripts/vocab` 都读这一份，
> 本文下面的内容是便于阅读的副本——两边不一致时以 JSON 为准，改 prompt 只改 JSON。

### 1.1 本地 LLM（单槽，串行）

```json
{
  "model": "qwen3.8-flash-next-iq2_xs",
  "temperature": 0.2,
  "response_format": {"type": "json_object"},
  "chat_template_kwargs": {"enable_thinking": false},
  "max_tokens": 4000
}
```

### 1.2 OpenCode Go（并发）

```json
{
  "model": "deepseek-v4.1-flash",
  "temperature": 0.2,
  "response_format": {"type": "json_object"},
  "thinking": {"type": "disabled"},
  "max_tokens": 6000
}
```

请求头：

```
Authorization: Bearer <OPENCODE_GO_KEY>
Content-Type: application/json
x-opencode-session: <每个请求随机 UUID>
User-Agent: python-requests/2.31
```

- **必须关 thinking**：默认会先吐 9k–24k reasoning tokens，吃掉 `max_tokens`，正文被截断或为空。
  关掉后正文约 1,000–1,300 tokens，`finish_reason=stop`。
- **`x-opencode-session` 只是网关路由头，不是对话上下文**：不带会 400 `MissingSessionID`；
  每个请求随机 UUID 即可，词与词之间没有任何历史。
- **必须带非默认 User-Agent**：urllib 默认 UA 会被 Cloudflare 拦成 `403 error code: 1010`。
- `max_tokens` 6000 足够（实测 1,000–1,300）。

## 2. 结构（v2.3）

- **例句 1–6 条**：自然情况下给 6 条；虚词、罕见词、单义具体名词给 1–3 条也行。**绝不硬凑**，
  `usagePattern` 可以重复（词的搭配本来就少时），但句子必须各不相同。
- **构词单独顶层字段 `wordFormation`**：不再塞进 `memoryAids`；和 `wordFamily`（同族词列表）也不是一回事。
  不确定就整个省略，不要编词源。
- `memoryAids` 只留同反义词、口诀、易混点。

## 3. System prompt

下面是便于阅读的副本；权威内容在 `teachloop-vocab/src/main/resources/vocab/content-prompt-v2.3.json` 的 `system` 字段。

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
    "items": [{"word": string, "partOfSpeech": [string], "meanings": {"zh": string, "en": string}}]
  }
}

Rules:
1. examples: 1 to 6 entries. Give 6 when the word naturally supports it; 1 to 3 is acceptable for function words, rare words or concrete nouns with few collocations. Never pad with unnatural sentences. Each sentence must be natural, idiomatic and short, the kind a native speaker actually says, and must contain the word or a standard inflection. Cover every major part of speech at least once when you give more than one example. usagePattern is the real phrase the sentence uses, starting with the word or its article: "struggle to do something", "the apple of one's eye". usagePattern may repeat when the word has few patterns, but the sentences must be distinct. popularity is a unique integer starting at 1, ordered by part-of-speech frequency and usage commonness, common verb patterns first, noun uses after.
2. translations: only the explanation languages that differ from the target language (for an English word: zh only, never en). The target-language sentence is already in text.
3. meanings: concise learner-facing definitions in Simplified Chinese (zh) and English (en), covering the main senses and their order.
4. wordFormation: real morphology only (root, prefix, suffix, derivation, origin). Use "parts" only when you are sure of the segmentation; set confidence high/medium/low. Omit the whole wordFormation object when you are not confident; never invent etymology.
5. synonyms and antonyms: only real words with the same part of speech as the target; arrays may be empty; never invent.
6. wordFamily: at most 6 real derived forms or inflections, no duplicates and no invented compounds, each with part of speech and meanings in zh and en.
7. pronunciation: IPA in slashes (UK and US).
```

## 4. User prompt 模板

```text
Word: {word}
Target language: {language}
Explanation languages: zh, en (translations exclude {language}; use {other_languages})
Example count: up to 6 (fewer is fine when the word has few natural patterns)
Sort rule: order examples by part-of-speech frequency and usage commonness; the most common verb patterns first, noun uses after.
```

`{other_languages}` 对 `en` 是 `zh`；目标是中文时是 `en`。

## 5. 输出校验（脚本内置）

- 顶层字段齐全、`schemaVersion=2`、`word`/`language` 一致；
- `meanings` zh/en 非空；`basicInfo.partOfSpeech` 非空；
- `examples` **1–6 条**，`popularity` 为 1..N 不重复，句子不重复；`text` 含该词（大小写/屈折容错）；
  `translations` 覆盖非目标语言；`usagePattern` 非空；
- `wordFormation` 可选；出现时 `analysis` 非空、`confidence ∈ {high,medium,low}`、`parts` 每项含 text/type/meaning；
- `wordFamily.items` ≤6 且去重；每项 `meanings` 含 zh/en；
- 不合格重试一次（temperature 0.1、`max_tokens` 6000），仍失败写 `failures.jsonl`，不写半成品。

## 6. 实测

本地单槽（qwen3.8-flash-next-iq2）：

| 词 | completion tokens | 耗时 | 结果 |
| :--- | ---: | ---: | :--- |
| struggle | 1247 | 13.7s | 6 条例句，verb 用法在前、noun 在后 |
| apple | 1381 | 14.6s | 6 条例句（count noun / proverb / idiom / 复合词） |

本地单槽吞吐 ≈ 89 tokens/s、约 13–15 秒/词 → 8 小时约 1,900–2,200 个词。

OpenCode Go（deepseek-v4.1-flash，thinking 关闭，4 并发）：约 1.9 秒/词（含并发摊薄），
`finish_reason=stop`，输出 1,000–1,300 tokens。

## 7. 无人值守跑批（3,000 → 10,000）

脚本已把「踩过的坑」变成默认行为，跑 10K 不需要人盯。关键开关：

| 开关 | 作用 |
| :--- | :--- |
| preflight（默认开） | 开跑前花 1 次请求验证：HTTP 200 / `finish_reason=stop` / thinking 已关 / JSON 合法 / zh+en 都在。401/403/400/429 直接给结论，不用跑到一半才发现 key 或 UA 不对 |
| `--max-cost-usd 8` | 估算成本到上限就停止提交新词（在途请求仍会完成，最多超出 `workers×2` 个词），退出码 3 |
| `--retry-failures` | 把 `.failures.jsonl` 里的词排到最前面重试 |
| `--check-words` | 只打印词表体检（去重数、短词、非字母噪声），不请求模型 |
| `.status.json` | 心跳：完成/失败/速率/ETA/tokens/成本/finish_reason 分布，每 10 个词刷新 |
| `.runs.jsonl` | 每轮追加一行 manifest，多次续跑的成本与耗时不会互相覆盖 |

`finish_reason=length`、`completion_tokens` 顶到 `max_tokens`、`reasoning_tokens > 200`、空正文，
一律判为失败并进入重试，**不会被当成成功写进 JSONL**——这是「截断混进内容包」的唯一防线。

10K 标准跑法：

```bash
export LLM_API_KEY="$(cat /tmp/oc_key.txt)"

# 1) 先体检（不发请求）
python3 scripts/vocab/generate_content.py \
  --words fixtures-local/vocab-content/words-top10k.txt \
  --output fixtures-local/vocab-content/vocab-content-en-v2.3.jsonl \
  --check-words

# 2) 正式跑：preflight 自动执行，成本上限 $8，6 并发
python3 scripts/vocab/generate_content.py \
  --words fixtures-local/vocab-content/words-top10k.txt \
  --output fixtures-local/vocab-content/vocab-content-en-v2.3.jsonl \
  --provider opencode --model deepseek-v4.1-flash \
  --workers 6 --max-cost-usd 8 \
  --forms-map fixtures-local/vocab-content/forms-top10k.json

# 3) 收尾：补失败词 + 全量校验
python3 scripts/vocab/generate_content.py ... --retry-failures
python3 scripts/vocab/check_content.py \
  --input fixtures-local/vocab-content/vocab-content-en-v2.3.jsonl \
  --words fixtures-local/vocab-content/words-top10k.txt
```

脚本自测（纯离线，22 条，覆盖词形匹配/清洗/截断识别/半行修复）：

```bash
python3 scripts/vocab/test_generate_content.py -v
```

## 8. 版本

`prompt_version = vocab-content-v2.3`。写进内容包 manifest 与 delta 的 `prompt_version`；
以后改 prompt 必须升版本，旧内容不参与新版本命中。
v2.2 的三语言产物（46 词）已作废，不再使用。
