#!/usr/bin/env python3
"""批量生成词卡配图的英文 scene（word -> 一句可画的定格画面）。

与 `make_scene_prompts.py` 的区别：那个脚本吃 v2.5 内容 JSONL 里的中文 mentalImage
（只覆盖 113 词）；本脚本直接吃词表（words-top3000/10k.txt），让模型自己判断「这词能不能
画成一张定格图」，不能画的词返回空 scene 被丢弃，再用 top10k 顺位补足。

规则来自 `prompts/styles.md` §3：
- 一个主体 + 一个定格姿势 + 一个道具 + 一个环境，12–24 词；
- 只用可画动词；禁止状态变化与交互（open/close/become/push/struggle…）；
- 抽象词用代理场景；禁文字/字母/数字/logo。

用法：
  LLM_API_KEY=... python3 scripts/vocab/image/make_bulk_scenes.py \
      --words fixtures-local/vocab-content/words-top3000.txt \
      --backfill fixtures-local/vocab-content/words-top10k.txt \
      --exclude scripts/vocab/image/prompts/scenes-113.json \
      --target 3000 --output docs/reports/2026-10-31-bulk-3000/scenes.json
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import re
import sys
import time
import urllib.request
import uuid

# 纯语法词：画不出来，也不该占一张卡（冠词/代词/介词/连词/助动词/情态/限定词/数词/程度副词）。
STOPWORDS = set("""
a an the this that these those
i me my mine myself we us our ours ourselves you your yours yourself yourselves
he him his himself she her hers herself it its itself they them their theirs themselves
who whom whose which what whatever whoever whichever
am is are was were be been being have has had having do does did done doing
will would shall should can could may might must ought
and or but nor so yet for if unless until while whereas because since though although as than
of in on at by to from with without within into onto upon about above across after against
along among around before behind below beneath beside between beyond during except inside
near off out outside over past per since through throughout till toward towards under
underneath up upon via
not no nor none never ever
all any both each either every few fewer less least many more most much neither other others
some such several own same
one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen
sixteen seventeen eighteen nineteen twenty thirty forty fifty sixty seventy eighty ninety
hundred thousand million billion first second third
very too quite rather almost enough just only also even still already again yet else
here there where when why how wherever whenever
mr mrs ms dr ok yes please
""".split())

BANNED = re.compile(r"\b(open|opens|opening|close|closes|closing|become|becomes|becoming|"
                    r"struggle|struggles|struggling|push|pushes|pushing|fight|fights|fighting|"
                    r"argue|argues|talking|talk|talks|interact|interacts|give|gives|giving|"
                    r"hand[s]?\s+to|shout|shouts|shouting)\b", re.I)
SAFE_FORM = re.compile(r"^[a-z][a-z-]{1,19}$")

SYSTEM = (
    "You write ONE short English visual description for a flat children's-book illustration that will sit\n"
    "next to a vocabulary card. The picture must be understood at 88 pixels.\n"
    "Rules:\n"
    "- one concrete scene: one subject, one frozen pose, one prop, one setting\n"
    "- 12 to 24 words, present tense, only things that can be SEEN (posture, object, place)\n"
    "- only still poses, e.g. stand, sit, kneel, hold, carry, lift, reach, point, climb, lean, look,\n"
    "  walk, dig, fold, peel, stir, squeeze, stretch, grip, ride, throw, catch, sleep\n"
    "- never describe a change of state or a two-person interaction (no open/close, become, push,\n"
    "  struggle, fight, talk, give, hand over)\n"
    "- no text, letters, numbers, logos or brands; no feelings or thoughts words\n"
    "- abstract words must become a PROXY scene (e.g. 'justify' -> a person holding a sheet of paper\n"
    "  up with one arm while pointing at it)\n"
    "- if the word cannot be shown as one frozen picture at all (grammar glue, pure quantity, pure\n"
    "  time word), return an empty scene\n"
    "- choose the meaning that a Chinese learner of English meets first\n"
    'Return ONLY JSON: {"scene": "..."}'
)

RETRY_HINT = ("\nThe previous answer was rejected ({reason}). Return 12 to 24 words, one frozen pose, "
              "one prop, no state change, no text.")


def load_words(path: str) -> list[str]:
    words: list[str] = []
    for line in open(path, encoding="utf-8"):
        word = line.strip().lower()
        if word and SAFE_FORM.match(word):
            words.append(word)
    return words


def call_llm(base_url: str, key: str, model: str, word: str, reason: str = "") -> str:
    payload = {
        "model": model,
        "temperature": 0.5,
        "max_tokens": 200,
        "response_format": {"type": "json_object"},
        "thinking": {"type": "disabled"},
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": f"Word: {word}" + (RETRY_HINT.format(reason=reason) if reason else "")},
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
    with urllib.request.urlopen(request, timeout=90) as response:
        data = json.loads(response.read().decode())
    return json.loads(data["choices"][0]["message"]["content"]).get("scene", "").strip()


def check(scene: str) -> str:
    """返回拒绝原因，空串表示通过。"""
    if not scene:
        return "empty"
    if not scene.isascii():
        return "non-ascii"
    if re.search(r"[0-9]", scene):
        return "digits"
    count = len(scene.split())
    if count < 12 or count > 24:
        return f"length:{count}"
    if BANNED.search(scene):
        return "banned-verb"
    return ""


def scene_for(base_url: str, key: str, model: str, word: str) -> tuple[str, str, str]:
    """返回 (word, scene, 状态)；状态 ok / 拒绝原因。"""
    last = ""
    for attempt, reason in enumerate(("", "rejected")):
        try:
            scene = call_llm(base_url, key, model, word, reason)
        except Exception as error:  # 网络/限流/坏 JSON
            last = f"error:{str(error)[:40]}"
            time.sleep(1 + attempt)
            continue
        problem = check(scene)
        if not problem:
            return word, scene, "ok"
        last = problem
        if problem == "empty":
            return word, "", "empty"       # 画不出来的词，不重试
    return word, "", last


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--words", required=True, help="主词表（按词频排序）")
    parser.add_argument("--backfill", default=None, help="补足词表（如 words-top10k.txt）")
    parser.add_argument("--exclude", default=None, help="已有 scene 的 JSON（键即词），这些词跳过")
    parser.add_argument("--target", type=int, default=3000)
    parser.add_argument("--output", required=True)
    parser.add_argument("--stats", default=None, help="统计 JSON 输出路径")
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--wave", type=int, default=96)
    args = parser.parse_args()

    key = os.environ.get("LLM_API_KEY")
    if not key:
        print("缺少 LLM_API_KEY", file=sys.stderr)
        return 2
    base_url = os.environ.get("LLM_BASE_URL", "https://opencode.ai/inference/go/openai/v1")
    model = os.environ.get("LLM_MODEL", "deepseek-v4.1-flash")

    excluded = set()
    if args.exclude and os.path.exists(args.exclude):
        excluded = {w.lower() for w in json.load(open(args.exclude, encoding="utf-8"))}

    primary = [w for w in load_words(args.words) if w not in STOPWORDS and w not in excluded]
    pool = list(dict.fromkeys(primary))
    backfill: list[str] = []
    if args.backfill:
        seen = set(primary) | excluded | STOPWORDS
        backfill = [w for w in load_words(args.backfill) if w not in seen]
        pool.extend(backfill)
    print(f"primary={len(primary)} backfill={len(backfill)} pool={len(pool)} target={args.target}", flush=True)

    scenes: dict[str, str] = {}
    used = set()          # 已占用的 scene 文本（去重）
    rejects: dict[str, str] = {}
    started = time.time()
    index = 0
    while len(scenes) < args.target and index < len(pool):
        wave = pool[index:index + args.wave]
        index += len(wave)
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
            futures = [executor.submit(scene_for, base_url, key, model, word) for word in wave]
            for future in concurrent.futures.as_completed(futures):
                word, scene, status = future.result()
                if status == "ok":
                    norm = re.sub(r"\s+", " ", scene.lower())
                    if norm in used or len(scenes) >= args.target:
                        rejects[word] = "duplicate"
                        continue
                    used.add(norm)
                    scenes[word] = scene
                else:
                    rejects[word] = status
        print(f"[{len(scenes)}/{args.target}] pool_used={index} rejects={len(rejects)} "
              f"{(time.time()-started)/max(1,len(scenes)):.2f}s/word", flush=True)

    os.makedirs(os.path.dirname(os.path.abspath(args.output)) or ".", exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as handle:
        json.dump(scenes, handle, ensure_ascii=False, indent=2, sort_keys=True)

    reasons: dict[str, int] = {}
    for reason in rejects.values():
        key_name = reason.split(":")[0]
        reasons[key_name] = reasons.get(key_name, 0) + 1
    stats = {
        "target": args.target, "got": len(scenes),
        "primary_candidates": len(primary), "backfill_candidates": len(backfill),
        "pool_consumed": index, "elapsed_seconds": round(time.time() - started, 1),
        "rejects_total": len(rejects), "rejects_by_reason": reasons,
        "from_primary": sum(1 for w in scenes if w in set(primary)),
        "from_backfill": sum(1 for w in scenes if w in set(backfill)),
        "word_count_min": min((len(s.split()) for s in scenes.values()), default=0),
        "word_count_max": max((len(s.split()) for s in scenes.values()), default=0),
        "avg_chars": round(sum(len(s) for s in scenes.values()) / max(1, len(scenes)), 1),
    }
    if args.stats:
        json.dump(stats, open(args.stats, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(json.dumps(stats, ensure_ascii=False))
    return 0 if len(scenes) >= args.target else 1


if __name__ == "__main__":
    raise SystemExit(main())
