#!/usr/bin/env python3
"""把 coreSemantics.mentalImage（中文）转成英文画面描述，供文生图使用。

输入：v2.5 内容 JSONL（每行一条词内容）或 SQLite delta（--sqlite）。
输出：{"word": "scene_en", ...} 的 JSON。

用法：
  LLM_API_KEY=... python3 scripts/vocab/image/make_scene_prompts.py \
      --input fixtures-local/vocab-content/vocab-content-en-v2.5.jsonl --output /tmp/scenes.json
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import sys
import time
import urllib.request
import uuid

SYSTEM = (
    "You write ONE short English visual description for a flat vector illustration that will sit next to a vocabulary card.\n"
    "Rules:\n"
    "- one concrete scene: a single subject doing one visible action with one prop\n"
    "- 12 to 28 words, present tense, no abstractions, no metaphors explained\n"
    "- describe only what can be SEEN (posture, object, place); no feelings words\n"
    "- no text, no letters, no logo, no branding\n"
    "- must capture the given Chinese mental image, adapted if needed to stay simple\n"
    'Return ONLY JSON: {"scene": string}'
)


def load_records(path: str, sqlite_path: str | None) -> list[tuple[str, str, str]]:
    rows: list[tuple[str, str, str]] = []
    if sqlite_path:
        import sqlite3

        con = sqlite3.connect(sqlite_path)
        for word, content in con.execute(
                "select normalized_form, content from word_content_delta"
                " where prompt_version='vocab-content-v2.5'"):
            core = json.loads(content).get("coreSemantics") or {}
            rows.append((word, core.get("core", ""), core.get("mentalImage", "")))
        return rows
    for line in open(path, encoding="utf-8"):
        if not line.strip():
            continue
        obj = json.loads(line)
        core = obj.get("coreSemantics") or {}
        rows.append((obj["word"], core.get("core", ""), core.get("mentalImage", "")))
    return rows


def make_scene(base_url: str, key: str, model: str, word: str, core: str, image: str) -> tuple[str, str]:
    payload = {
        "model": model,
        "temperature": 0.4,
        "max_tokens": 300,
        "response_format": {"type": "json_object"},
        "thinking": {"type": "disabled"},
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": f"Word: {word}\nChinese core: {core}\nChinese mental image: {image}"},
        ],
    }
    request = urllib.request.Request(
        base_url.rstrip("/") + "/chat/completions",
        data=json.dumps(payload).encode(),
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {key}",
            "User-Agent": "teachloop-image-prompt/1.0",
            "x-opencode-session": str(uuid.uuid4()),
        },
    )
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                data = json.loads(response.read().decode())
            return word, json.loads(data["choices"][0]["message"]["content"])["scene"].strip()
        except Exception as error:  # 429/超时/坏 JSON 都重试一次
            if attempt == 2:
                print(f"  ! {word}: {error}", file=sys.stderr)
                return word, ""
            time.sleep(1 + attempt)
    return word, ""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", default=None, help="v2.5 JSONL")
    parser.add_argument("--sqlite", default=None, help="delta SQLite（与 --input 二选一）")
    parser.add_argument("--output", required=True)
    parser.add_argument("--workers", type=int, default=5)
    args = parser.parse_args()
    if not args.input and not args.sqlite:
        parser.error("需要 --input 或 --sqlite")

    key = os.environ.get("LLM_API_KEY")
    if not key:
        print("缺少 LLM_API_KEY", file=sys.stderr)
        return 2
    base_url = os.environ.get("LLM_BASE_URL", "https://opencode.ai/inference/go/openai/v1")
    model = os.environ.get("LLM_MODEL", "deepseek-v4.1-flash")

    records = load_records(args.input, args.sqlite)
    scenes: dict[str, str] = {}
    started = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(make_scene, base_url, key, model, word, core, image)
                   for word, core, image in records]
        for future in concurrent.futures.as_completed(futures):
            word, scene = future.result()
            scenes[word] = scene
    json.dump(scenes, open(args.output, "w"), ensure_ascii=False, indent=2)
    empty = sum(1 for scene in scenes.values() if not scene)
    print(f"words={len(scenes)} empty={empty} elapsed={time.time()-started:.1f}s -> {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
