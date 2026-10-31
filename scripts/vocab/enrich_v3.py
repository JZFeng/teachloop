#!/usr/bin/env python3
"""Enrich the 113-word v2.5 content pack into the v3 card shape.

V3 的目标不是重写整条内容，而是把「构词与派生」讲对：
- modernNote：现代英语里到底能不能拆、怎么拆；
- parts / chain：现代词素与派生链，不再把历史词源混进来；
- derivatives / inflections：派生与屈折分开，带词性 + 简短含义；
- family：同族词，带词性 + 含义（不再只留词形）；
- etymology：历史来源单独一段，中文解释，不列裸词源链。

输入：fixtures-local/vocab-content/vocab-content-en-v2.5.jsonl（113 条）
输出：fixtures-local/vocab-content/vocab-content-en-v3.0.jsonl（可断点续跑）

用法：
  LLM_API_KEY=... python3 scripts/vocab/enrich_v3.py --limit 1
  LLM_API_KEY=... python3 scripts/vocab/enrich_v3.py --workers 4
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
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_INPUT = ROOT / "fixtures-local/vocab-content/vocab-content-en-v2.5.jsonl"
DEFAULT_OUTPUT = ROOT / "fixtures-local/vocab-content/vocab-content-en-v3.0.jsonl"

SYSTEM = """You enrich an existing English vocabulary record into the v3 learning schema.
Return ONLY one JSON object, no markdown, no comments, no extra keys.

The output object MUST have exactly these keys:
{
  "modernNote": string,
  "parts": [{"text": string, "type": string, "meaning": string, "origin": string}],
  "chain": [{"from": [string], "to": string, "partOfSpeech": string, "meaning": string}],
  "derivatives": [{"word": string, "partOfSpeech": string, "meaning": string}],
  "inflections": [{"word": string, "label": string}],
  "family": [{"word": string, "partOfSpeech": string, "meaning": string}],
  "etymology": string
}

Rules:
1. modernNote is Chinese, 1-2 short sentences, about MODERN English only.
   If the word cannot be reliably split into modern English morphemes (loanword,
   opaque stem, historical suffix only), say so plainly, e.g.
   "现代英语中 algorithm 是整体借词，不作现代英语词素拆分。"
   and leave parts/chain empty. Never invent a modern root to fill the field.
2. parts are PRODUCTIVE MODERN English morphemes only (e.g. un- + happy,
   happy + -ness). A Latin/Greek root that only survives historically
   (hap in happy, tract in abstract, strug- in struggle) is NOT a modern part:
   leave parts empty and explain it in etymology. A single part equal to the
   whole head is not a split. If uncertain, use [].
3. chain is a MODERN derivational chain. Each step's from[] must use a part
   from parts or a previous step's to; no empty step; no inflection. If uncertain,
   use [].
4. derivatives are derived words (not inflections), with partOfSpeech and a
   short Chinese meaning. A participial adjective such as abandoned / abstracted
   is a derivative (adjective), not a past-tense inflection. Max 6.
5. inflections are undoubted inflected forms (plural, past, participle,
   comparative, ...), each with a short Chinese label. If the record lists the
   form as an adjective, it is a derivative, not an inflection. Max 6.
6. family is all related forms INCLUDING the head word, deduped, each with
   partOfSpeech and a short Chinese meaning. Max 8. It must include every
   derivative and inflection you list.
7. etymology is Chinese, historical origin only. You may name Latin/Greek/Old
   French forms with a Chinese gloss. Do not repeat the modernNote and do not
   output a bare "Latin ... · Old French ..." chain. If modernNote says the word
   is opaque/整体/单语素, the historical roots belong here, not in parts.
8. Use the supplied v2 wordFormation / wordFamily as the primary evidence, but
   correct it when it is historically right and modernly wrong.
9. Do not invent a morpheme split, a derivative, an inflection or a family
   relation you are not confident about. Empty arrays are valid.
10. Keep the correct target word and its part of speech. Preserve the meaning
    supplied in the v2 record; do not translate the English definition.
"""


def strip_json(text: str) -> str:
    text = text.strip()
    if text.startswith("```"):
        text = re.sub(r"^```[a-zA-Z]*\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
    start, end = text.find("{"), text.rfind("}")
    if start >= 0 and end > start:
        text = text[start:end + 1]
    return text.strip()


def call_model(record: dict, base_url: str, model: str, key: str, timeout: int) -> dict:
    payload = {
        "model": model,
        "temperature": 0.2,
        "max_tokens": 2600,
        "response_format": {"type": "json_object"},
        "thinking": {"type": "disabled"},
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": "Transform this v2 record into the v3 morphology object:\n"
             + json.dumps(record, ensure_ascii=False)},
        ],
    }
    request = urllib.request.Request(
        base_url.rstrip("/") + "/chat/completions",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + key,
            "User-Agent": "teachloop-vocab-content/1.0",
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = json.loads(response.read().decode("utf-8"))
    content = body["choices"][0]["message"]["content"]
    return json.loads(strip_json(content))


def text(value) -> str:
    return value.strip() if isinstance(value, str) else ""


def norm_list(value) -> list:
    return value if isinstance(value, list) else []


def clean_part(item) -> dict | None:
    if not isinstance(item, dict):
        return None
    word = text(item.get("text"))
    if not word:
        return None
    return {
        "text": word,
        "type": text(item.get("type")) or "morpheme",
        "meaning": text(item.get("meaning")),
        "origin": text(item.get("origin")),
    }


def clean_chain_step(item) -> dict | None:
    if not isinstance(item, dict):
        return None
    to = text(item.get("to"))
    if not to:
        return None
    frm = item.get("from")
    sources = [text(x) for x in frm] if isinstance(frm, list) else []
    sources = [x for x in sources if x]
    if not sources:
        return None
    return {
        "from": sources,
        "to": to,
        "partOfSpeech": text(item.get("partOfSpeech")),
        "meaning": text(item.get("meaning")),
    }


def clean_derivative(item) -> dict | None:
    if not isinstance(item, dict):
        return None
    word = text(item.get("word"))
    if not word:
        return None
    return {
        "word": word,
        "partOfSpeech": text(item.get("partOfSpeech")),
        "meaning": text(item.get("meaning")),
    }


def clean_inflection(item) -> dict | None:
    if not isinstance(item, dict):
        return None
    word = text(item.get("word"))
    if not word:
        return None
    return {"word": word, "label": text(item.get("label")) or "屈折"}


def clean_family(item) -> dict | None:
    if not isinstance(item, dict):
        return None
    word = text(item.get("word"))
    if not word:
        return None
    return {
        "word": word,
        "partOfSpeech": text(item.get("partOfSpeech")),
        "meaning": text(item.get("meaning")),
    }


def dedupe(items: list, key: str = "word") -> list:
    out, seen = [], set()
    for item in items:
        k = item.get(key, "").lower()
        if not k or k in seen:
            continue
        seen.add(k)
        out.append(item)
    return out


def fallback(record: dict) -> dict:
    """Deterministic fallback when the model rejects twice. It is intentionally
    conservative: it exposes the v2 family POS/meaning instead of losing them,
    and treats the v2 analysis as etymology (the old read path did the same)."""
    word = text(record.get("word"))
    formation = record.get("wordFormation") if isinstance(record.get("wordFormation"), dict) else {}
    family_raw = record.get("wordFamily") if isinstance(record.get("wordFamily"), dict) else {}
    family_items = family_raw.get("items") if isinstance(family_raw.get("items"), list) else []
    parts = [p for p in (clean_part(x) for x in norm_list(formation.get("parts"))) if p]
    chain = [p for p in (clean_chain_step(x) for x in norm_list(formation.get("chain"))) if p]
    pos = ",".join(record.get("basicInfo", {}).get("partOfSpeech") or [])
    family = []
    for item in family_items:
        if not isinstance(item, dict):
            continue
        entry = {
            "word": text(item.get("word")),
            "partOfSpeech": ", ".join(item.get("partOfSpeech") or []),
            "meaning": text((item.get("meanings") or {}).get("zh")),
        }
        if entry["word"]:
            family.append(entry)
    if word and not any(f["word"].lower() == word.lower() for f in family):
        family.insert(0, {
            "word": word,
            "partOfSpeech": pos,
            "meaning": text((record.get("meanings") or {}).get("zh")),
        })
    family = dedupe(family)[:8]
    head = word.lower()
    derivatives = [f for f in family if f["word"].lower() != head and f not in []]
    inflections = []
    # Split obvious inflections by suffix; the model path is preferred.
    for f in derivatives[:]:
        w = f["word"].lower()
        if w.endswith(("s", "es", "ed", "ing", "er", "est")) and not w.endswith("ness"):
            inflections.append({"word": f["word"], "label": "屈折"})
            derivatives.remove(f)
    return {
        "modernNote": text(formation.get("analysis")) or
        ("现代英语中 %s 作为整体词使用，没有可靠的现代词素拆分。" % word if word else "暂无构词说明。"),
        "parts": parts,
        "chain": chain,
        "derivatives": derivatives[:6],
        "inflections": inflections[:6],
        "family": family,
        "etymology": text(formation.get("analysis")) or "暂无可靠词源说明。",
    }


def validate(result: dict, record: dict) -> list[str]:
    problems = []
    if not isinstance(result, dict):
        return ["result is not an object"]
    if not text(result.get("modernNote")):
        problems.append("modernNote empty")
    if not text(result.get("etymology")):
        problems.append("etymology empty")
    for field in ("parts", "chain", "derivatives", "inflections", "family"):
        if not isinstance(result.get(field), list):
            problems.append(field + " is not an array")
    if problems:
        return problems
    word = text(record.get("word")).lower()
    family = result.get("family") or []
    if word and not any(text(x.get("word")).lower() == word for x in family if isinstance(x, dict)):
        problems.append("family missing head word")
    return problems


INFLECTION_WORDS = (
    "plural", "past tense", "past participle", "third-person", "present participle",
    "comparative", "superlative", "复数", "过去式", "过去分词", "第三人称单数",
    "现在分词", "比较级", "最高级",
)


def v2_family(record: dict) -> list[dict]:
    raw = record.get("wordFamily") if isinstance(record.get("wordFamily"), dict) else {}
    items = raw.get("items") if isinstance(raw.get("items"), list) else []
    out = []
    for item in items:
        if not isinstance(item, dict):
            continue
        word = text(item.get("word"))
        if not word:
            continue
        out.append({
            "word": word,
            "partOfSpeech": ", ".join(item.get("partOfSpeech") or []),
            "meaning": text((item.get("meanings") or {}).get("zh")),
            "meanings_en": text((item.get("meanings") or {}).get("en")),
        })
    return out


def is_inflection(head: str, item: dict) -> bool:
    word = item["word"].lower()
    if word == head.lower():
        return False
    meaning = (item.get("meanings_en") or "").lower()
    if any(token in meaning for token in INFLECTION_WORDS):
        return True
    for suffix in ("s", "es", "ed", "ing", "er", "est"):
        if word == head.lower() + suffix:
            return True
    return False


def inflection_label(item: dict) -> str:
    meaning = (item.get("meanings_en") or "").lower()
    if "plural" in meaning or "复数" in meaning:
        return "复数"
    if "past" in meaning or "过去" in meaning:
        return "过去式 / 过去分词"
    if "present participle" in meaning or "现在分词" in meaning:
        return "现在分词"
    if "comparative" in meaning or "比较级" in meaning:
        return "比较级"
    if "superlative" in meaning or "最高级" in meaning:
        return "最高级"
    return "屈折"


def sanitize_parts(parts: list[dict], head: str) -> list[dict]:
    """Only keep parts that can plausibly belong to the modern head: an affix
    or a substring. This drops historical roots like 'trah' from abstract."""
    out = []
    for part in parts:
        raw = part["text"].strip("-")
        if not raw:
            continue
        if raw.lower() in head.lower():
            out.append(part)
            continue
        if part["type"] in ("prefix", "suffix") and (head.lower().startswith(raw.lower())
                                                     or head.lower().endswith(raw.lower())):
            out.append(part)
    return out


def merge(record: dict, enriched: dict) -> dict:
    word = text(record.get("word"))
    out = {
        "schemaVersion": 3,
        "word": word,
        "language": record.get("language") or "en",
        "basicInfo": record.get("basicInfo") or {},
        "meanings": record.get("meanings") or {},
        "coreSemantics": record.get("coreSemantics") or {},
        "examples": record.get("examples") or [],
        "morphology": {
            "modernNote": text(enriched.get("modernNote")),
            "parts": [],
            "chain": [],
            "derivatives": [],
            "inflections": [],
            "family": [],
        },
        "memoryAids": record.get("memoryAids") or {},
        "etymology": {"text": text(enriched.get("etymology"))},
    }
    # Hard rule (user 2026-10-31): the card must not invent word forms. Only the
    # head plus forms already present in the v2 wordFamily are allowed; the model
    # only classifies them and writes the modern note / etymology.
    head = word.lower()
    source = v2_family(record)
    allowed = {head: None}
    for item in source:
        allowed.setdefault(item["word"].lower(), item)
    if head:
        allowed[head] = {
            "word": word,
            "partOfSpeech": ", ".join(out["basicInfo"].get("partOfSpeech") or []),
            "meaning": text((out["meanings"] or {}).get("zh")),
            "meanings_en": "",
        }
    # Model's family is only used for POS/meaning corrections on allowed forms.
    model_family = {}
    for item in norm_list(enriched.get("family")):
        clean = clean_family(item)
        if clean and clean["word"].lower() in allowed:
            model_family[clean["word"].lower()] = clean
    family = []
    for key, base in allowed.items():
        if not key:
            continue
        chosen = model_family.get(key)
        family.append({
            "word": base["word"],
            "partOfSpeech": (chosen or {}).get("partOfSpeech") or base["partOfSpeech"],
            "meaning": (chosen or {}).get("meaning") or base["meaning"],
        })
    family = dedupe(family, "word")[:8]
    # Classify every allowed family form; model's classification wins when valid.
    model_deriv = {x["word"].lower(): x for x in
                   (clean_derivative(i) for i in norm_list(enriched.get("derivatives"))) if x}
    model_infl = {x["word"].lower(): x for x in
                  (clean_inflection(i) for i in norm_list(enriched.get("inflections"))) if x}
    derivatives, inflections = [], []
    for item in family:
        key = item["word"].lower()
        if key == head:
            continue
        if key in model_infl:
            inflections.append({"word": item["word"], "label": model_infl[key]["label"]})
        elif key in model_deriv:
            derivatives.append(item)
        elif is_inflection(head, allowed[key]):
            inflections.append({"word": item["word"], "label": inflection_label(allowed[key])})
        else:
            derivatives.append(item)
    # A form the v2 record lists as an adjective is a participial adjective,
    # not a pure past-tense inflection (abandoned, abstracted).
    moved = []
    for item in inflections[:]:
        base = allowed.get(item["word"].lower())
        if base and "adjective" in base.get("partOfSpeech", "").lower() and "verb" not in base.get("partOfSpeech", "").lower():
            inflections.remove(item)
            moved.append({"word": item["word"], "partOfSpeech": "adjective",
                          "meaning": base.get("meaning", "")})
    derivatives.extend(moved)
    # Drop model-invented forms not in family (eagerer/eagerest etc.).
    derivatives = dedupe(derivatives, "word")[:6]
    inflections = dedupe(inflections, "word")[:6]
    # Modern parts/chain only when the note actually claims a modern split.
    note = out["morphology"]["modernNote"]
    opaque = any(token in note for token in (
        "整体", "单语素", "不可拆", "不能再拆", "不拆分", "不能可靠拆", "不按词素",
        "整体借词", "历史词根", "已不独立", "非现代", "不产"))
    confidence = text((record.get("wordFormation") or {}).get("confidence")).lower()
    if confidence in ("medium", "low") and not opaque:
        # v2's medium/low parts are exactly where the old data got shaky
        # (jolly -ly, interpret -er). Prefer the conservative, accurate card.
        opaque = True
        out["morphology"]["modernNote"] = (
            "现代英语中 %s 作为整体词使用；构词历史见词源补充。" % word)
    if not opaque:
        parts = sanitize_parts([p for p in (clean_part(x) for x in norm_list(enriched.get("parts"))) if p], word)
        parts = dedupe(parts, "text")[:8]
        if len(parts) == 1 and parts[0]["text"].strip("-").lower() == word.lower():
            parts = []
        part_keys = {p["text"].strip("-").lower() for p in parts}
        chain = []
        previous = set(part_keys)
        for step in (clean_chain_step(x) for x in norm_list(enriched.get("chain"))):
            if not step:
                continue
            if any(src.strip("-").lower() not in previous for src in step["from"]):
                continue
            chain.append(step)
            previous.add(step["to"].lower())
        out["morphology"]["parts"] = parts
        out["morphology"]["chain"] = chain[:8]
    out["morphology"]["derivatives"] = derivatives
    out["morphology"]["inflections"] = inflections
    out["morphology"]["family"] = family
    if not any("\u4e00" <= ch <= "\u9fff" for ch in out["morphology"]["modernNote"]):
        out["morphology"]["modernNote"] = "现代英语中 %s 的构词说明待补。" % word
    if not any("\u4e00" <= ch <= "\u9fff" for ch in out["etymology"]["text"]):
        out["etymology"]["text"] = text((record.get("wordFormation") or {}).get("analysis")) or "暂无可靠词源说明。"
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--base-url", default=os.environ.get("LLM_BASE_URL", "https://api.deepseek.com"))
    parser.add_argument("--model", default=os.environ.get("LLM_MODEL", "deepseek-flash"))
    parser.add_argument("--workers", type=int, default=3)
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--retries", type=int, default=2)
    parser.add_argument("--timeout", type=int, default=90)
    parser.add_argument("--retry-failures", action="store_true")
    args = parser.parse_args()

    key = os.environ.get("LLM_API_KEY")
    if not key:
        print("缺少 LLM_API_KEY", file=sys.stderr)
        return 2
    records = [json.loads(line) for line in args.input.read_text(encoding="utf-8").splitlines() if line.strip()]
    records = [r for r in records if text(r.get("word"))]
    done = {}
    if args.output.exists():
        for line in args.output.read_text(encoding="utf-8").splitlines():
            try:
                row = json.loads(line)
                done[text(row.get("word")).lower()] = row
            except Exception:
                pass
    failed_path = args.output.with_suffix(".failures.jsonl")
    retry_words = set()
    if args.retry_failures and failed_path.exists():
        for line in failed_path.read_text(encoding="utf-8").splitlines():
            try:
                retry_words.add(text(json.loads(line).get("word")).lower())
            except Exception:
                pass
    pending = [r for r in records if text(r.get("word")).lower() not in done]
    if retry_words:
        pending.sort(key=lambda r: 0 if text(r.get("word")).lower() in retry_words else 1)
    if args.limit:
        pending = pending[: args.limit]
    print(f"input={len(records)} done={len(done)} pending={len(pending)} "
          f"model={args.model} workers={args.workers}", flush=True)
    if not pending:
        return 0

    lock = threading.Lock()
    failures = []
    completed = 0
    started = time.time()

    def work(record: dict) -> dict:
        problems = []
        for attempt in range(args.retries + 1):
            try:
                enriched = call_model(record, args.base_url, args.model, key, args.timeout)
                problems = validate(enriched, record)
                if not problems:
                    return merge(record, enriched)
                print(f"  reject {record.get('word')} attempt={attempt + 1}: {problems}", flush=True)
            except Exception as exc:
                problems = [str(exc)]
                print(f"  error {record.get('word')} attempt={attempt + 1}: {exc}", flush=True)
        return {"__fallback__": True, "record": record, "problems": problems}

    with ThreadPoolExecutor(max_workers=max(args.workers, 1)) as pool:
        futures = [pool.submit(work, r) for r in pending]
        for future in as_completed(futures):
            result = future.result()
            with lock:
                completed += 1
                if result.get("__fallback__"):
                    record = result["record"]
                    out = merge(record, fallback(record))
                    failures.append({"word": record.get("word"), "problems": result.get("problems")})
                    print(f"  fallback {record.get('word')} ({completed}/{len(pending)})", flush=True)
                else:
                    out = result
                    print(f"  ok {out.get('word')} ({completed}/{len(pending)})", flush=True)
                with args.output.open("a", encoding="utf-8") as handle:
                    handle.write(json.dumps(out, ensure_ascii=False) + "\n")
                if failures:
                    with failed_path.open("w", encoding="utf-8") as handle:
                        for f in failures:
                            handle.write(json.dumps(f, ensure_ascii=False) + "\n")
    print(f"done in {time.time() - started:.1f}s failures={len(failures)} output={args.output}", flush=True)
    return 3 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
