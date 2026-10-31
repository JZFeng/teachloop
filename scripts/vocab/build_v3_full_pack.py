#!/usr/bin/env python3
"""Build the full V3 pack: 28,273 headword cards + 52,306 form cards = 80,579.

This is the V3-only path: old 113 v2-enrich content is not read.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PACK_BUILDER = ROOT / "scripts/vocab/build_content_pack.py"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--headwords", required=True)
    parser.add_argument("--forms", required=True)
    parser.add_argument("--out-jsonl", required=True)
    parser.add_argument("--out-dir", required=True)
    parser.add_argument("--version", default="v3.1")
    parser.add_argument("--prompt-version", default="vocab-content-v3.0")
    args = parser.parse_args()

    combined: dict[str, dict] = {}
    for path in (args.headwords, args.forms):
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            record = json.loads(line)
            word = str(record.get("word") or "").strip()
            if not word:
                continue
            key = word.lower()
            # headword wins if a malformed duplicate exists
            if key not in combined or record.get("formOf") is None:
                combined[key] = record
    ordered = sorted(combined.values(), key=lambda item: str(item.get("word", "")).lower())
    out = Path(args.out_jsonl)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as handle:
        for record in ordered:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")
    print(f"combined={len(ordered)} -> {out}")
    command = [
        sys.executable, str(PACK_BUILDER),
        "--input", str(out),
        "--language", "en",
        "--version", args.version,
        "--prompt-version", args.prompt_version,
        "--out-dir", args.out_dir,
    ]
    result = subprocess.run(command, check=False)
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
