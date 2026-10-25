#!/usr/bin/env python3
"""批量生成 v2 词内容（一次性工具，不属于业务代码）。

配合 docs/prompts/vocab-content-v2.md：用本地 OpenAI 兼容端点逐词生成 word_detail_page v2 JSON，
写 JSONL（一行一条词），可断点续跑，自带结构校验与失败记录。

用法：
  LLM_API_KEY=... python3 scripts/vocab/generate_content.py \
      --words fixtures-local/words.txt --language en \
      --output /tmp/vocab-content-v2.jsonl \
      --base-url http://192.168.68.101:8080/v1 --model qwen3.8-flash-next-iq2_xs

注意：
- 单槽服务串行跑；跑之前停掉应用的 vocab 生成 worker（或停应用），别抢模型。
- 输出是 JSONL，可重复执行；已成功的词（word+language）自动跳过。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

PROMPT_VERSION = "vocab-content-v2.1"
LANGUAGES = ("zh", "en", "es")

SYSTEM = """You write vocabulary learning content as strict JSON.
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
"""


def user_prompt(word: str, language: str, example_count: int) -> str:
    others = [lang for lang in LANGUAGES if lang != language]
    return (
        f"Word: {word}\n"
        f"Target language: {language}\n"
        f"Explanation languages: zh, en, es (translations exclude {language}; use {', '.join(others)})\n"
        f"Example count: {example_count}\n"
        "Sort rule: order examples by part-of-speech frequency and usage commonness; "
        "the most common verb patterns first, noun uses after."
    )


def request_payload(word: str, language: str, model: str, example_count: int, max_tokens: int) -> dict:
    return {
        "model": model,
        "temperature": 0.2,
        "response_format": {"type": "json_object"},
        "chat_template_kwargs": {"enable_thinking": False},
        "max_tokens": max_tokens,
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": user_prompt(word, language, example_count)},
        ],
    }


def call_model(url: str, key: str, payload: dict, timeout: int) -> dict:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def looks_like_word(text: str, word: str) -> bool:
    lowered = text.lower()
    w = word.lower()
    if w in lowered:
        return True
    # 常见屈折：去 e + ing/ed、双写、复数、比较级等，宽松匹配词干
    stems = {w, w.rstrip("e"), w[:-1] if len(w) > 3 else w, w[:-2] if len(w) > 4 else w}
    return any(stem and stem in lowered for stem in stems)


def validate(obj: dict, word: str, language: str, example_count: int) -> list[str]:
    problems: list[str] = []
    if obj.get("schemaVersion") != 2:
        problems.append("schemaVersion != 2")
    if str(obj.get("word", "")).strip().lower() != word.lower():
        problems.append(f"word mismatch: {obj.get('word')!r}")
    if obj.get("language") != language:
        problems.append(f"language mismatch: {obj.get('language')!r}")

    basic = obj.get("basicInfo") or {}
    pronunciation = basic.get("pronunciation") or {}
    if not (pronunciation.get("uk") and pronunciation.get("us")):
        problems.append("basicInfo.pronunciation missing uk/us")
    if not basic.get("partOfSpeech"):
        problems.append("basicInfo.partOfSpeech empty")

    meanings = obj.get("meanings") or {}
    for lang in LANGUAGES:
        if not str(meanings.get(lang, "")).strip():
            problems.append(f"meanings.{lang} empty")

    examples = obj.get("examples")
    if not isinstance(examples, list) or len(examples) != example_count:
        problems.append(f"examples count != {example_count}")
    else:
        popularity = []
        patterns = []
        for index, example in enumerate(examples):
            text = str(example.get("text", ""))
            if not text.strip():
                problems.append(f"examples[{index}].text empty")
            elif not looks_like_word(text, word):
                problems.append(f"examples[{index}].text missing word")
            if not str(example.get("partOfSpeech", "")).strip():
                problems.append(f"examples[{index}].partOfSpeech empty")
            pattern = str(example.get("usagePattern", "")).strip()
            if not pattern:
                problems.append(f"examples[{index}].usagePattern empty")
            if pattern.lower() in patterns:
                problems.append(f"examples[{index}].usagePattern duplicated")
            patterns.append(pattern.lower())
            translations = example.get("translations") or {}
            for lang in LANGUAGES:
                if lang == language:
                    continue
                if not str(translations.get(lang, "")).strip():
                    problems.append(f"examples[{index}].translations.{lang} empty")
            value = example.get("popularity")
            if not isinstance(value, int):
                problems.append(f"examples[{index}].popularity is not integer")
            else:
                popularity.append(value)
        if sorted(popularity) != list(range(1, example_count + 1)):
            problems.append("popularity must be unique 1..N")

    family = (obj.get("wordFamily") or {}).get("items")
    if not isinstance(family, list):
        problems.append("wordFamily.items missing")
    else:
        if len(family) > 6:
            problems.append("wordFamily.items > 6")
        seen = set()
        for index, item in enumerate(family):
            form = str(item.get("word", "")).strip().lower()
            if not form:
                problems.append(f"wordFamily[{index}].word empty")
            elif form in seen:
                problems.append(f"wordFamily[{index}] duplicated")
            seen.add(form)
            if not item.get("partOfSpeech"):
                problems.append(f"wordFamily[{index}].partOfSpeech empty")
            for lang in LANGUAGES:
                if not str((item.get("meanings") or {}).get(lang, "")).strip():
                    problems.append(f"wordFamily[{index}].meanings.{lang} empty")

    return problems


def load_words(path: Path) -> list[str]:
    words: list[str] = []
    text = path.read_text(encoding="utf-8")
    stripped = text.strip()
    if stripped.startswith("["):
        data = json.loads(stripped)
        for item in data:
            if isinstance(item, str):
                words.append(item)
            elif isinstance(item, dict) and item.get("word"):
                words.append(str(item["word"]))
    else:
        for line in text.splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            words.append(line)
    seen: set[str] = set()
    unique: list[str] = []
    for word in words:
        key = word.strip().lower()
        if key and key not in seen:
            seen.add(key)
            unique.append(word.strip())
    return unique


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--words", required=True, help="word list: one per line, or JSON array")
    parser.add_argument("--language", default="en", help="target language code (en/zh/es)")
    parser.add_argument("--output", required=True, help="JSONL output path")
    parser.add_argument("--base-url", default=os.environ.get("LLM_BASE_URL", "http://192.168.68.101:8080/v1"))
    parser.add_argument("--model", default=os.environ.get("LLM_MODEL", "qwen3.8-flash-next-iq2_xs"))
    parser.add_argument("--example-count", type=int, default=6)
    parser.add_argument("--max-tokens", type=int, default=4000)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--limit", type=int, default=0, help="only run the first N words (0 = all)")
    parser.add_argument("--sleep", type=float, default=0.0)
    args = parser.parse_args()

    key = os.environ.get("LLM_API_KEY")
    if not key:
        print("缺少 LLM_API_KEY 环境变量", file=sys.stderr)
        return 2

    words = load_words(Path(args.words))
    if args.limit > 0:
        words = words[: args.limit]
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    failures = output.with_suffix(".failures.jsonl")

    done: set[tuple[str, str]] = set()
    if output.exists():
        for line in output.read_text(encoding="utf-8").splitlines():
            try:
                record = json.loads(line)
                done.add((str(record.get("word", "")).lower(), str(record.get("language", ""))))
            except Exception:
                continue

    pending = [w for w in words if (w.lower(), args.language) not in done]
    print(f"total={len(words)} done={len(done)} pending={len(pending)}", flush=True)
    if not pending:
        return 0

    url = args.base_url.rstrip("/") + "/chat/completions"
    started = time.time()
    completed = 0
    failed = 0
    tokens = 0

    with output.open("a", encoding="utf-8") as out, failures.open("a", encoding="utf-8") as fail_out:
        for index, word in enumerate(pending, start=1):
            attempts = [(args.max_tokens, 0.2), (max(args.max_tokens, 6000), 0.1)]
            record = None
            reasons: list[str] = []
            for max_tokens, temperature in attempts:
                payload = request_payload(word, args.language, args.model, args.example_count, max_tokens)
                payload["temperature"] = temperature
                try:
                    response = call_model(url, key, payload, args.timeout)
                except urllib.error.HTTPError as error:
                    reasons.append(f"HTTP {error.code}")
                    continue
                except Exception as error:  # timeout / connection
                    reasons.append(f"request failed: {error}")
                    continue
                if "error" in response:
                    reasons.append(str(response["error"].get("message", response["error"])))
                    continue
                usage = response.get("usage") or {}
                tokens += int(usage.get("completion_tokens") or 0)
                try:
                    obj = json.loads(response["choices"][0]["message"]["content"])
                except Exception as error:
                    reasons.append(f"not json: {error}")
                    continue
                problems = validate(obj, word, args.language, args.example_count)
                if problems:
                    reasons.append("; ".join(problems))
                    continue
                record = obj
                break

            if record is None:
                failed += 1
                fail_out.write(json.dumps({"word": word, "language": args.language,
                                           "reasons": reasons}, ensure_ascii=False) + "\n")
                fail_out.flush()
            else:
                completed += 1
                out.write(json.dumps(record, ensure_ascii=False) + "\n")
                out.flush()

            if index % 10 == 0 or index == len(pending):
                elapsed = time.time() - started
                rate = elapsed / index
                remaining = rate * (len(pending) - index)
                print(
                    f"[{index}/{len(pending)}] ok={completed} failed={failed} "
                    f"avg={rate:.1f}s eta={remaining/60:.1f}min tokens={tokens}",
                    flush=True,
                )
            if args.sleep:
                time.sleep(args.sleep)

    elapsed = time.time() - started
    manifest = {
        "promptVersion": PROMPT_VERSION,
        "model": args.model,
        "baseUrl": args.base_url,
        "language": args.language,
        "exampleCount": args.example_count,
        "completed": completed,
        "failed": failed,
        "completionTokens": tokens,
        "elapsedSeconds": round(elapsed, 1),
        "wordsPerMinute": round(completed / max(elapsed, 1) * 60, 2),
        "finishedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    }
    output.with_suffix(".manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False), flush=True)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
