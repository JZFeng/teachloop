#!/usr/bin/env python3
"""Derive V3 content for inflected/derived forms from their family headword.

Input:
  --headwords    v3.1 JSONL generated from v3-headwords.txt
  --forms-map    {form: headword} from basewords (80,579 entries)
  --out          JSONL with only non-head forms (52,306 expected)

Design:
  - The headword owns the full V3 card (meanings, core, examples, morphology,
    etymology). A form card is a deterministic projection, not another AI call.
  - If the form appears in the head's morphology.family / derivatives /
    inflections, use that POS + Chinese meaning + label.
  - Otherwise fall back to the head POS/meaning and label "派生/屈折形式".
  - Keep head examples only when the sentence actually contains the form.
  - `formOf`, `formKind`, `formLabel` are extra fields; ContentView ignores
    unknown fields and still builds the six v3 blocks.
"""
from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from pathlib import Path


def norm(value: str | None) -> str:
    return (value or "").strip().lower()


def pos_list(value) -> list[str]:
    if isinstance(value, list):
        return [str(x).strip() for x in value if str(x).strip()] or ["word"]
    text = str(value or "").strip()
    if not text:
        return ["word"]
    return [x.strip() for x in re.split(r"[,/]", text) if x.strip()] or ["word"]


def sentence_has(text: str, form: str) -> bool:
    return bool(re.search(rf"(?<![a-z]){re.escape(form.lower())}(?![a-z])", text.lower()))


def form_label(head: dict, form: str) -> tuple[str, str, str]:
    """Return (kind, partOfSpeech, meaningZh) from the head's morphology."""
    morphology = head.get("morphology") or {}
    key = norm(form)
    for item in morphology.get("derivatives") or []:
        if norm(item.get("word")) == key:
            return "derivative", str(item.get("partOfSpeech") or "").strip(), str(item.get("meaning") or "").strip()
    for item in morphology.get("inflections") or []:
        if norm(item.get("word")) == key:
            return "inflection", "", str(item.get("label") or "").strip()
    for item in morphology.get("family") or []:
        if norm(item.get("word")) == key:
            return "family", str(item.get("partOfSpeech") or "").strip(), str(item.get("meaning") or "").strip()
    return "form", "", ""


def derive(head: dict, form: str) -> dict:
    kind, pos, meaning = form_label(head, form)
    head_word = str(head.get("word") or "")
    head_morph = head.get("morphology") or {}
    head_meanings = head.get("meanings") or {}
    head_basic = head.get("basicInfo") or {}

    if meaning:
        zh = meaning
    elif kind == "inflection":
        zh = f"{head_word} 的{meaning or '屈折形式'}"
    else:
        zh = head_meanings.get("zh") or head_word

    if kind == "inflection" and meaning:
        modern = f"{form} 是 {head_word} 的{meaning}。"
    elif kind == "derivative":
        modern = f"{form} 是 {head_word} 的派生词（{pos or '派生'}）。"
    elif kind == "family":
        modern = f"{form} 是 {head_word} 的词族形式。"
    else:
        modern = f"{form} 是 {head_word} 的屈折/派生形式。"

    family = [{
        "word": head_word,
        "partOfSpeech": str((head_basic.get("partOfSpeech") or ["word"])[0]),
        "meaning": str(head_meanings.get("zh") or head_word),
    }]
    seen = {head_word.lower()}
    for item in head_morph.get("family") or []:
        word = str(item.get("word") or "").strip()
        if not word or word.lower() in seen:
            continue
        seen.add(word.lower())
        family.append({
            "word": word,
            "partOfSpeech": str(item.get("partOfSpeech") or "").strip(),
            "meaning": str(item.get("meaning") or "").strip(),
        })
    if form.lower() not in seen and len(family) < 8:
        family.append({"word": form, "partOfSpeech": pos or "", "meaning": zh})
        seen.add(form.lower())

    derivatives = []
    for item in head_morph.get("derivatives") or []:
        word = str(item.get("word") or "").strip()
        if word:
            derivatives.append({
                "word": word,
                "partOfSpeech": str(item.get("partOfSpeech") or "").strip(),
                "meaning": str(item.get("meaning") or "").strip(),
            })
    inflections = []
    for item in head_morph.get("inflections") or []:
        word = str(item.get("word") or "").strip()
        if word:
            inflections.append({"word": word, "label": str(item.get("label") or "").strip()})
    if kind == "inflection" and form.lower() not in {x["word"].lower() for x in inflections} and len(inflections) < 6:
        inflections.append({"word": form, "label": meaning or "屈折形式"})
    if kind == "derivative" and form.lower() not in {x["word"].lower() for x in derivatives} and len(derivatives) < 6:
        derivatives.append({"word": form, "partOfSpeech": pos or "word", "meaning": zh})

    examples = []
    for example in head.get("examples") or []:
        text = str(example.get("text") or "").strip()
        if text and sentence_has(text, form):
            examples.append(json.loads(json.dumps(example, ensure_ascii=False)))
            if len(examples) >= 6:
                break

    head_pos = pos_list(head_basic.get("partOfSpeech"))
    return {
        "schemaVersion": 3,
        "word": form,
        "language": head.get("language") or "en",
        "basicInfo": {
            "pronunciation": head_basic.get("pronunciation") or {"uk": "", "us": ""},
            "partOfSpeech": pos_list(pos) if pos else head_pos,
            "image": None,
        },
        "meanings": {
            "zh": zh,
            "en": str(head_meanings.get("en") or "").strip() or head_word,
        },
        "coreSemantics": head.get("coreSemantics") or {"core": zh, "notThis": "", "mentalImage": ""},
        "examples": examples,
        "morphology": {
            "modernNote": modern,
            "parts": head_morph.get("parts") or [],
            "chain": head_morph.get("chain") or [],
            "derivatives": derivatives,
            "inflections": inflections,
            "family": family[:8],
        },
        "memoryAids": head.get("memoryAids") or {"synonyms": [], "antonyms": [], "mnemonic": "", "supplement": ""},
        "etymology": head.get("etymology") or {"text": ""},
        "formOf": head_word,
        "formKind": kind,
        "formLabel": meaning,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--headwords", required=True)
    parser.add_argument("--forms-map", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    heads: dict[str, dict] = {}
    for line in Path(args.headwords).read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        record = json.loads(line)
        heads[str(record.get("word", "")).lower()] = record
    form_to_head: dict[str, str] = json.loads(Path(args.forms_map).read_text(encoding="utf-8"))

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    written = 0
    skipped_missing_head = 0
    missing_examples = 0
    kinds = Counter()
    with out_path.open("w", encoding="utf-8") as out:
        for form, head_word in sorted(form_to_head.items(), key=lambda pair: pair[0].lower()):
            if form.lower() == head_word.lower():
                continue
            head = heads.get(head_word.lower())
            if head is None:
                skipped_missing_head += 1
                continue
            record = derive(head, form)
            if not record["examples"]:
                missing_examples += 1
            kinds[record["formKind"]] += 1
            out.write(json.dumps(record, ensure_ascii=False) + "\n")
            written += 1
    print(f"forms_written={written} skipped_missing_head={skipped_missing_head} "
          f"forms_without_examples={missing_examples} kinds={dict(kinds)}")
    print(f"heads={len(heads)} forms_map={len(form_to_head)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
