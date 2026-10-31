#!/usr/bin/env python3
"""Split the basewords lexicon into V3 headwords and form families.

Input:
  fixtures-local/vocab-lexicon/lexicon-families.tsv
  fixtures-local/vocab-lexicon/lexicon-forms.tsv

Output (fixtures-local/vocab-content/):
  v3-headwords.txt       unique headwords, one per line
  v3-headwords.json      [{word, band, familyFreq, forms:[...]}] for the batch runner
  v3-forms-map.json      {headword: [accepted forms]} for prompt/validator
  v3-form-to-head.json   {form: headword} for deterministic form cards

The old 113-word v3 file is intentionally ignored: v3 is regenerated from scratch.
"""
from __future__ import annotations

import json
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LEXICON = ROOT / "fixtures-local/vocab-lexicon"
OUT = ROOT / "fixtures-local/vocab-content"


def read_tsv(path: Path):
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if line.strip():
                yield line.split("\t")


def main() -> int:
    families: dict[str, dict] = {}
    family_head: dict[str, str] = {}
    for row in read_tsv(LEXICON / "lexicon-families.tsv"):
        # family_id, headword, band, family_freq
        family_id, head, band, freq = row[0], row[1], int(row[2]), int(row[3])
        family_head[family_id] = head
        current = families.get(head)
        if current is None:
            families[head] = {"word": head, "band": band, "familyFreq": freq, "forms": []}
        else:
            current["band"] = min(current["band"], band)
            current["familyFreq"] = max(current["familyFreq"], freq)

    form_to_head: dict[str, str] = {}
    for row in read_tsv(LEXICON / "lexicon-forms.tsv"):
        # form, normalized, family_id, freq, is_head
        form, _normalized, family_id = row[0], row[1], row[2]
        head = family_head.get(family_id)
        if not head:
            continue
        key = form.strip()
        if not key:
            continue
        form_to_head[key] = head
        families[head]["forms"].append(key)

    for head, item in families.items():
        forms = sorted(set(item["forms"]) | {head})
        item["forms"] = forms
    heads = sorted(families.values(), key=lambda item: (item["band"], -item["familyFreq"], item["word"].lower()))

    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "v3-headwords.txt").write_text("".join(item["word"] + "\n" for item in heads), encoding="utf-8")
    (OUT / "v3-headwords.json").write_text(
        json.dumps(heads, ensure_ascii=False, indent=1), encoding="utf-8")
    (OUT / "v3-forms-map.json").write_text(
        json.dumps({item["word"]: item["forms"] for item in heads}, ensure_ascii=False), encoding="utf-8")
    (OUT / "v3-form-to-head.json").write_text(
        json.dumps(form_to_head, ensure_ascii=False, sort_keys=True), encoding="utf-8")

    non_head = sum(1 for form, head in form_to_head.items() if form.lower() != head.lower())
    print(f"headwords={len(heads)} families={len(families)} forms={len(form_to_head)} non_head_forms={non_head}")
    print(f"band counts: {dict(sorted((b, sum(1 for x in heads if x['band'] == b)) for b in sorted({x['band'] for x in heads})))}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
