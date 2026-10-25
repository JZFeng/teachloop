#!/usr/bin/env python3
"""校验批量生成产物：结构、覆盖、异常字符、分布（复用 generate_content.validate）。

用法：
  python3 scripts/vocab/check_content.py \
      --input fixtures-local/vocab-content/vocab-content-en-v2.3.jsonl \
      --words fixtures-local/vocab-content/words-top3000.txt \
      --failures fixtures-local/vocab-content/vocab-content-en-v2.3.failures.jsonl
"""

from __future__ import annotations

import argparse
import collections
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import generate_content as g  # noqa: E402

ARTIFACT = re.compile(r"<\|[a-z]+\|>|\ufffd")
CJK = re.compile(r"[\u4e00-\u9fff]")
LATIN = re.compile(r"[A-Za-z]")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--words", required=True)
    parser.add_argument("--failures", default=None)
    parser.add_argument("--forms-map", default="fixtures-local/vocab-content/forms-top10k.json")
    args = parser.parse_args()

    forms = json.loads(Path(args.forms_map).read_text(encoding="utf-8")) if Path(args.forms_map).exists() else {}
    words = [w.strip() for w in Path(args.words).read_text(encoding="utf-8").splitlines() if w.strip()]

    records: dict[str, dict] = {}
    bad_lines = 0
    for line in Path(args.input).read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        try:
            obj = json.loads(line)
        except Exception:
            bad_lines += 1
            continue
        records[str(obj.get("word", "")).lower()] = obj

    problems: collections.Counter = collections.Counter()
    examples_hist: collections.Counter = collections.Counter()
    pos_hist: collections.Counter = collections.Counter()
    artifacts = []
    missing_zh = []
    no_word = []

    for word in words:
        obj = records.get(word.lower())
        if obj is None:
            problems["missing_record"] += 1
            continue
        issues = g.validate(obj, word, "en", 6, forms.get(word.lower()))
        for issue in issues:
            problems[issue.split(":")[0]] += 1
        examples_hist[len(obj.get("examples") or [])] += 1
        for tag in obj["basicInfo"].get("partOfSpeech") or []:
            pos_hist[tag] += 1
        blob = json.dumps(obj, ensure_ascii=False)
        if ARTIFACT.search(blob):
            artifacts.append(word)
        for example in obj.get("examples") or []:
            if not CJK.search(str(example.get("translations", {}).get("zh", ""))):
                missing_zh.append(word)
                break
            if not g.looks_like_word(str(example.get("text", "")), word, forms.get(word.lower())):
                no_word.append(word)
                break

    print(f"words={len(words)} records={len(records)} bad_json_lines={bad_lines}")
    print(f"missing={len(words) - len(records)} completeness={len(records)/max(len(words),1):.1%}")
    print(f"examples_histogram={dict(sorted(examples_hist.items()))}")
    print(f"top_pos={pos_hist.most_common(10)}")
    print(f"validation_issues={dict(problems.most_common(10))}")
    print(f"artifact_chars={len(artifacts)} {artifacts[:10]}")
    print(f"zh_missing={len(missing_zh)} {missing_zh[:10]}")
    print(f"example_missing_word={len(no_word)} {no_word[:10]}")

    if args.failures and Path(args.failures).exists():
        failures = [json.loads(x) for x in Path(args.failures).read_text(encoding="utf-8").splitlines() if x.strip()]
        print(f"failures={len(failures)} {[f['word'] for f in failures][:20]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
