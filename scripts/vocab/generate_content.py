#!/usr/bin/env python3
"""批量生成 v2.3 词内容（一次性工具，不属于业务代码）。

配合 docs/prompts/vocab-content-v2.md：逐词生成 word_detail_page v2 JSON，写 JSONL（一行一条词），
可断点续跑、可并发、自带结构校验与失败记录。

两种后端：
- local：本地 OpenAI 兼容端点（单槽，串行），用 chat_template_kwargs.enable_thinking=false 关思考；
- opencode：OpenCode Go 网关（可并发），用 "thinking": {"type": "disabled"} 关思考，
  每个请求带随机 x-opencode-session（网关路由头，不是对话上下文），必须带非默认 User-Agent。

用法：
  LLM_API_KEY=... python3 scripts/vocab/generate_content.py \
      --words fixtures-local/vocab-content/words-top3000.txt --language en \
      --output fixtures-local/vocab-content/vocab-content-en-v2.3.jsonl \
      --provider opencode --model deepseek-v4.1-flash --workers 4

注意：
- 输出是 JSONL，可重复执行；已成功的词（word+language）自动跳过。
- 并发只在单一进程内（线程池），不要同时起多个进程写同一个文件。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

PROMPT_VERSION = "vocab-content-v2.3"
LANGUAGES = ("zh", "en")

OPENCODE_BASE_URL = "https://opencode.ai/inference/go/openai/v1"
DEFAULT_USER_AGENT = "python-requests/2.31"
RETRYABLE_STATUS = {408, 409, 425, 429, 500, 502, 503, 504}

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
"""


def user_prompt(word: str, language: str, example_count: int) -> str:
    others = [lang for lang in LANGUAGES if lang != language]
    return (
        f"Word: {word}\n"
        f"Target language: {language}\n"
        f"Explanation languages: zh, en (translations exclude {language}; use {', '.join(others)})\n"
        f"Example count: up to {example_count} (fewer is fine when the word has few natural patterns)\n"
        "Sort rule: order examples by part-of-speech frequency and usage commonness; "
        "the most common verb patterns first, noun uses after."
    )


def request_payload(provider: str, word: str, language: str, model: str,
                    example_count: int, max_tokens: int, temperature: float) -> dict:
    payload = {
        "model": model,
        "temperature": temperature,
        "response_format": {"type": "json_object"},
        "max_tokens": max_tokens,
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": user_prompt(word, language, example_count)},
        ],
    }
    if provider == "local":
        payload["chat_template_kwargs"] = {"enable_thinking": False}
    else:
        # 关掉 thinking：默认会先吐 9k–24k reasoning tokens，把 max_tokens 吃光导致正文截断
        payload["thinking"] = {"type": "disabled"}
    return payload


def call_model(url: str, key: str, payload: dict, timeout: int, provider: str) -> dict:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {key}",
        "User-Agent": DEFAULT_USER_AGENT,
    }
    if provider == "opencode":
        # 网关要求该头；只是路由，不是对话上下文，每个请求随机即可
        headers["x-opencode-session"] = str(uuid.uuid4())
    request = urllib.request.Request(url, data=body, headers=headers, method="POST")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


SUFFIXES = ("", "s", "es", "ed", "d", "ing", "ly", "er", "est", "n", "en", "ies", "ied", "ier", "iest", "ings", "ers")


def derived_forms(word: str) -> set[str]:
    """词表里没收录规则屈折（willed / outed / owns）时，按常规词形变化补一遍。"""
    w = word.lower().strip()
    if not w:
        return set()
    forms = {w}
    for suffix in SUFFIXES:
        forms.add(w + suffix)
    if w.endswith("e"):
        forms.update({w + "d", w + "s", w[:-1] + "ing"})
    if w.endswith("y") and len(w) > 2:
        forms.update({w[:-1] + "ies", w[:-1] + "ied", w[:-1] + "ier", w[:-1] + "iest"})
    if len(w) > 2 and w[-1] not in "aeiou" and w[-2] in "aeiou":
        # stop → stopped / stopping
        forms.update({w + w[-1] + "ed", w + w[-1] + "ing", w + w[-1] + "er", w + w[-1] + "est"})
    return forms


def looks_like_word(text: str, word: str, forms: list[str] | None = None) -> bool:
    """目标词或它的标准屈折形式出现在句子里。

    forms 来自词表（basewords 的成员表，如 be → am/is/are/was/were），但词表并不全
    （will → willed、out → outed、own → owns 都缺），所以再补一层规则屈折。
    """
    lowered = text.lower()
    candidates = {c.lower().strip() for c in (forms or []) if c and c.strip()}
    candidates |= derived_forms(word)
    for form in candidates:
        # 用非字母边界，避免 "is" 命中 "this"
        if re.search(rf"(?<![a-z]){re.escape(form)}(?![a-z])", lowered):
            return True
    return False


def validate(obj: dict, word: str, language: str, example_count: int,
             forms: list[str] | None = None) -> list[str]:
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
    if not isinstance(examples, list) or not (1 <= len(examples) <= example_count):
        problems.append(f"examples count must be 1..{example_count}")
    else:
        popularity = []
        sentences = []
        for index, example in enumerate(examples):
            text = str(example.get("text", ""))
            if not text.strip():
                problems.append(f"examples[{index}].text empty")
            elif not looks_like_word(text, word, forms):
                problems.append(f"examples[{index}].text missing word")
            if not str(example.get("partOfSpeech", "")).strip():
                problems.append(f"examples[{index}].partOfSpeech empty")
            if not str(example.get("usagePattern", "")).strip():
                problems.append(f"examples[{index}].usagePattern empty")
            if text.strip().lower() in sentences:
                problems.append(f"examples[{index}].text duplicated")
            sentences.append(text.strip().lower())
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
        if sorted(popularity) != list(range(1, len(examples) + 1)):
            problems.append("popularity must be unique 1..N")

    formation = obj.get("wordFormation")
    if formation is not None:
        if not isinstance(formation, dict):
            problems.append("wordFormation must be an object")
        else:
            if not str(formation.get("analysis", "")).strip():
                problems.append("wordFormation.analysis empty")
            if formation.get("confidence") not in ("high", "medium", "low"):
                problems.append("wordFormation.confidence invalid")
            parts = formation.get("parts", [])
            if not isinstance(parts, list):
                problems.append("wordFormation.parts must be a list")
            else:
                for index, part in enumerate(parts):
                    if not isinstance(part, dict) or not all(
                            str(part.get(key, "")).strip() for key in ("text", "type", "meaning")):
                        problems.append(f"wordFormation.parts[{index}] incomplete")

    family = (obj.get("wordFamily") or {}).get("items")
    if not isinstance(family, list):
        problems.append("wordFamily.items missing")
    else:
        # 同族词里重复词头、或同一条重复出现，属于可直接清洗的噪音，不算硬错误
        cleaned: list[dict] = []
        seen = {word.lower()}
        for item in family:
            if not isinstance(item, dict):
                problems.append("wordFamily item is not an object")
                continue
            form = str(item.get("word", "")).strip().lower()
            if not form:
                problems.append("wordFamily item word empty")
                continue
            if form in seen:
                continue
            seen.add(form)
            cleaned.append(item)
        obj["wordFamily"] = {"items": cleaned[:6]}
        family = cleaned[:6]
        for index, item in enumerate(family):
            form = str(item.get("word", "")).strip().lower()
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


class Stats:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.completed = 0
        self.failed = 0
        self.tokens_in = 0
        self.tokens_out = 0
        self.attempts = 0
        self.retries = 0
        self.http_errors: dict[str, int] = {}
        self.latencies: list[float] = []
        self.example_hist: dict[int, int] = {}
        self.started_at = time.time()

    def record_attempt(self, error: str | None = None, status: int | None = None) -> None:
        with self.lock:
            self.attempts += 1
            if status is not None:
                key = f"HTTP {status}"
                self.http_errors[key] = self.http_errors.get(key, 0) + 1

    def record_success(self, usage: dict, latency: float, examples: int) -> None:
        with self.lock:
            self.completed += 1
            self.tokens_in += int(usage.get("prompt_tokens") or 0)
            self.tokens_out += int(usage.get("completion_tokens") or 0)
            self.latencies.append(latency)
            self.example_hist[examples] = self.example_hist.get(examples, 0) + 1

    def record_failure(self) -> None:
        with self.lock:
            self.failed += 1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--words", required=True, help="word list: one per line, or JSON array")
    parser.add_argument("--language", default="en", help="target language code (en)")
    parser.add_argument("--output", required=True, help="JSONL output path")
    parser.add_argument("--provider", choices=("local", "opencode"), default="local")
    parser.add_argument("--base-url", default=None)
    parser.add_argument("--model", default=None)
    parser.add_argument("--example-count", type=int, default=6)
    parser.add_argument("--max-tokens", type=int, default=4000)
    parser.add_argument("--timeout", type=int, default=150)
    parser.add_argument("--workers", type=int, default=1, help="parallel requests (opencode: 4)")
    parser.add_argument("--limit", type=int, default=0, help="only run the first N pending words (0 = all)")
    parser.add_argument("--forms-map", default=None,
                        help="JSON map head word -> accepted inflected forms (from basewords)")
    parser.add_argument("--price-in", type=float, default=0.15, help="USD per 1M input tokens (estimate)")
    parser.add_argument("--price-out", type=float, default=0.60, help="USD per 1M output tokens (estimate)")
    args = parser.parse_args()

    if args.provider == "opencode":
        base_url = args.base_url or OPENCODE_BASE_URL
        model = args.model or "deepseek-v4.1-flash"
    else:
        base_url = args.base_url or os.environ.get("LLM_BASE_URL", "http://192.168.68.101:8080/v1")
        model = args.model or os.environ.get("LLM_MODEL", "qwen3.8-flash-next-iq2_xs")
    max_tokens = max(args.max_tokens, 6000) if args.provider == "opencode" else args.max_tokens

    key = os.environ.get("LLM_API_KEY")
    if not key:
        print("缺少 LLM_API_KEY 环境变量", file=sys.stderr)
        return 2

    forms_map: dict[str, list[str]] = {}
    if args.forms_map:
        forms_map = json.loads(Path(args.forms_map).read_text(encoding="utf-8"))
    words = load_words(Path(args.words))
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
    if args.limit > 0:
        pending = pending[: args.limit]
    print(f"provider={args.provider} model={model} workers={args.workers} "
          f"total={len(words)} done={len(done)} pending={len(pending)}", flush=True)
    if not pending:
        return 0

    url = base_url.rstrip("/") + "/chat/completions"
    stats = Stats()

    def work(word: str) -> None:
        # 第一次正常温度；结构不合格时降温度、加预算再试一次
        attempts = [(max_tokens, 0.2), (max(max_tokens, 6000), 0.1)]
        record = None
        reasons: list[str] = []
        for max_tokens_try, temperature in attempts:
            payload = request_payload(args.provider, word, args.language, model,
                                      args.example_count, max_tokens_try, temperature)
            response = None
            call_started = time.time()
            for backoff in range(4):
                try:
                    stats.record_attempt()
                    call_started = time.time()
                    response = call_model(url, key, payload, args.timeout, args.provider)
                    break
                except urllib.error.HTTPError as error:
                    stats.record_attempt(status=error.code)
                    reasons.append(f"HTTP {error.code}")
                    if error.code in RETRYABLE_STATUS and backoff < 3:
                        time.sleep(min(2 ** backoff * 2, 20))
                        continue
                    break
                except Exception as error:  # timeout / connection
                    stats.record_attempt()
                    reasons.append(f"request failed: {error}")
                    if backoff < 3:
                        time.sleep(min(2 ** backoff * 2, 20))
                        continue
                    break
            if response is None:
                continue
            if "error" in response:
                reasons.append(str(response["error"].get("message", response["error"])))
                continue
            usage = response.get("usage") or {}
            try:
                obj = json.loads(response["choices"][0]["message"]["content"])
            except Exception as error:
                reasons.append(f"not json: {error}")
                with stats.lock:
                    stats.tokens_in += int(usage.get("prompt_tokens") or 0)
                    stats.tokens_out += int(usage.get("completion_tokens") or 0)
                continue
            problems = validate(obj, word, args.language, args.example_count,
                                forms_map.get(word.lower()))
            if problems:
                reasons.append("; ".join(problems))
                with stats.lock:
                    stats.tokens_in += int(usage.get("prompt_tokens") or 0)
                    stats.tokens_out += int(usage.get("completion_tokens") or 0)
                continue
            stats.record_success(usage, time.time() - call_started, len(obj.get("examples") or []))
            record = obj
            break

        if record is None:
            stats.record_failure()
            with failures_lock:
                fail_out.write(json.dumps({"word": word, "language": args.language,
                                           "reasons": reasons}, ensure_ascii=False) + "\n")
                fail_out.flush()
        elif record is not None:
            with out_lock:
                out.write(json.dumps(record, ensure_ascii=False) + "\n")
                out.flush()

    out_lock = threading.Lock()
    failures_lock = threading.Lock()

    def report(index: int) -> None:
        with stats.lock:
            elapsed = time.time() - stats.started_at
            done_n = stats.completed + stats.failed
            rate = elapsed / max(done_n, 1)
            remaining = rate * (len(pending) - done_n)
            print(f"[{done_n}/{len(pending)}] ok={stats.completed} failed={stats.failed} "
                  f"avg={rate:.1f}s eta={remaining/60:.1f}min "
                  f"tok_in={stats.tokens_in} tok_out={stats.tokens_out}", flush=True)

    with output.open("a", encoding="utf-8") as out, failures.open("a", encoding="utf-8") as fail_out:
        with ThreadPoolExecutor(max_workers=max(args.workers, 1)) as pool:
            futures = {}
            for index, word in enumerate(pending, start=1):
                futures[pool.submit(work, word)] = index
            for count, future in enumerate(futures, start=1):
                future.result()
                if count % 10 == 0 or count == len(pending):
                    report(count)

    elapsed = time.time() - stats.started_at
    cost = stats.tokens_in / 1_000_000 * args.price_in + stats.tokens_out / 1_000_000 * args.price_out
    latencies = sorted(stats.latencies)
    manifest = {
        "promptVersion": PROMPT_VERSION,
        "provider": args.provider,
        "model": model,
        "baseUrl": base_url,
        "language": args.language,
        "workers": args.workers,
        "exampleCount": args.example_count,
        "completed": stats.completed,
        "failed": stats.failed,
        "attempts": stats.attempts,
        "httpErrors": stats.http_errors,
        "completionTokens": stats.tokens_out,
        "promptTokens": stats.tokens_in,
        "estimatedCostUsd": round(cost, 4),
        "elapsedSeconds": round(elapsed, 1),
        "wordsPerMinute": round(stats.completed / max(elapsed, 1) * 60, 2),
        "latencyP50Seconds": round(latencies[len(latencies) // 2], 1) if latencies else None,
        "latencyP90Seconds": round(latencies[int(len(latencies) * 0.9)], 1) if latencies else None,
        "examplesHistogram": dict(sorted(stats.example_hist.items())),
        "finishedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    }
    output.with_suffix(".manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False), flush=True)
    return 0 if stats.failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
