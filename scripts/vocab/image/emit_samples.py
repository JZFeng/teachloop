#!/usr/bin/env python3
"""从已生成的 176px WebP 里挑 N 张等间隔抽样，写 manifest（词、scene、体积）。

用途：批量跑完（或跑到一半）时，把可进仓库的 20 张抽样一次挑出来，不用手翻 3000 个文件。

用法（在 ai-ubuntu 上，final-176 目录已就绪）：
  python3 scripts/vocab/image/emit_samples.py \
      --images ~/ai/vocab-images/qwen3000_176 \
      --scenes ~/ai/vocab-images/scenes-3000.json \
      --out ~/ai/vocab-images/qwen3000_samples --count 20 --max-kb 15
然后本地：
  scp -r jzfeng@ai-ubuntu:ai/vocab-images/qwen3000_samples \
      docs/reports/2026-10-31-bulk-3000/samples
"""

from __future__ import annotations

import argparse
import json
import os
import shutil


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--images", required=True)
    parser.add_argument("--scenes", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--count", type=int, default=20)
    parser.add_argument("--max-kb", type=float, default=15.0)
    args = parser.parse_args()

    scenes = json.load(open(args.scenes, encoding="utf-8"))
    names = sorted(os.path.splitext(name)[0] for name in os.listdir(args.images)
                   if name.lower().endswith(".webp"))
    if not names:
        print("没有可抽样的 webp")
        return 1
    wanted = min(args.count, len(names))
    step = max(1, len(names) // wanted)
    picked = [names[i * step] for i in range(wanted)]

    os.makedirs(args.out, exist_ok=True)
    manifest, oversized = [], []
    for word in picked:
        source = os.path.join(args.images, word + ".webp")
        size = os.path.getsize(source)
        if size > args.max_kb * 1024:
            oversized.append({"word": word, "kb": round(size / 1024, 1)})
            continue
        shutil.copy2(source, os.path.join(args.out, word + ".webp"))
        manifest.append({"word": word, "scene": scenes.get(word, ""), "kb": round(size / 1024, 2)})

    json.dump({"source": args.images, "pool": len(names), "picked": len(manifest),
               "max_kb": args.max_kb, "samples": manifest, "oversized": oversized},
              open(os.path.join(args.out, "samples.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    print(json.dumps({"pool": len(names), "picked": len(manifest),
                      "avg_kb": round(sum(m["kb"] for m in manifest) / max(1, len(manifest)), 2),
                      "oversized": oversized}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
