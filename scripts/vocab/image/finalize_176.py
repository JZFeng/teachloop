#!/usr/bin/env python3
"""后处理定稿：裁边 3% → levels 2–98% → ×1.08−0.02 → 缩到 176/96 → UnsharpMask(0.8/30/2) → WebP。

与 `normalize_images.py` 同一条曲线（`normalize()` 是它的 C1 暖纸底），差别只在
「先裁 3% 边、缩放前先做 levels、缩放后按 176/96 锐化」——这是
docs/reports/2026-10-31-word-selection-100/README.md 里 113 词 final-176 的实际配方。

用法：
  python3 scripts/vocab/image/finalize_176.py --input ~/ai/vocab-images/qwen3000 \
      --output ~/ai/vocab-images/qwen3000_176 --size 176 --also 96 --quality 92 \
      --stats ~/ai/vocab-images/qwen3000_176.stats.json
"""

from __future__ import annotations

import argparse
import json
import os
import time

import numpy as np
from PIL import Image, ImageFilter


def trim(image: Image.Image, margin: float) -> Image.Image:
    """四周各裁掉 margin（3% = 0.03）的边，去掉生成图常见的空边。"""
    width, height = image.size
    dx, dy = int(width * margin), int(height * margin)
    if width - 2 * dx < 8 or height - 2 * dy < 8:
        return image
    return image.crop((dx, dy, width - dx, height - dy))


def levels(image: Image.Image, black: float = 2.0, white: float = 98.0,
           gain: float = 1.08, lift: float = -0.02, highlight: float = 0.965,
           warm_red: float = 1.03, warm_blue: float = 0.97) -> Image.Image:
    array = np.asarray(image.convert("RGB")).astype(np.float32) / 255.0
    luminance = 0.299 * array[..., 0] + 0.587 * array[..., 1] + 0.114 * array[..., 2]
    low, high = np.percentile(luminance, black), np.percentile(luminance, white)
    array = np.clip((array - low) / max(high - low, 1e-3), 0, 1)
    array = np.clip(array * gain + lift, 0, 1)
    array = np.clip(array * highlight, 0, 1)
    array[..., 0] = np.clip(array[..., 0] * warm_red, 0, 1)
    array[..., 2] = np.clip(array[..., 2] * warm_blue, 0, 1)
    return Image.fromarray((array * 255).astype(np.uint8))


def finish(image: Image.Image, size: int) -> Image.Image:
    small = image.resize((size, size), Image.LANCZOS)
    return small.filter(ImageFilter.UnsharpMask(radius=0.8, percent=30, threshold=2))


def metrics(image: Image.Image) -> tuple[float, float, float]:
    """88px 验收口径：亮度 / 对比度(std) / 近白像素占比。"""
    small = image.convert("RGB").resize((88, 88), Image.LANCZOS)
    array = np.asarray(small).astype(np.float32)
    luminance = 0.299 * array[..., 0] + 0.587 * array[..., 1] + 0.114 * array[..., 2]
    return float(luminance.mean()), float(luminance.std()), float((luminance >= 250).mean() * 100)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--size", type=int, default=176)
    parser.add_argument("--also", type=int, default=0, help="额外输出一档尺寸（如 96）")
    parser.add_argument("--also-dir", default=None)
    parser.add_argument("--quality", type=int, default=92)
    parser.add_argument("--margin", type=float, default=0.03)
    parser.add_argument("--skip-existing", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--stats", default=None)
    parser.add_argument("--limit", type=int, default=0, help="只处理前 N 张（调试用）")
    args = parser.parse_args()

    os.makedirs(args.output, exist_ok=True)
    also_dir = args.also_dir or (args.output.rstrip("/") + f"_{args.also}")
    if args.also:
        os.makedirs(also_dir, exist_ok=True)

    names = sorted(name for name in os.listdir(args.input) if name.lower().endswith(".png"))
    if args.limit:
        names = names[:args.limit]

    started = time.time()
    done, skipped = 0, 0
    total_bytes, large_bytes = 0, 0
    sizes: list[int] = []
    bright, contrast, near_white = [], [], []
    for name in names:
        target = os.path.join(args.output, os.path.splitext(name)[0] + ".webp")
        if args.skip_existing and os.path.isfile(target) and os.path.getsize(target) > 512:
            skipped += 1
            sizes.append(os.path.getsize(target))
            total_bytes += os.path.getsize(target)
            continue
        image = Image.open(os.path.join(args.input, name)).convert("RGB")
        image = levels(trim(image, args.margin))
        if done < 400:                      # 只对前 400 张算 88px 指标，够看趋势
            brightness = metrics(finish(image, 88))
        else:
            brightness = (0.0, 0.0, 0.0)
        final = finish(image, args.size)
        final.save(target, "WEBP", quality=args.quality, method=6)
        if args.also:
            finish(image, args.also).save(os.path.join(also_dir, os.path.splitext(name)[0] + ".webp"),
                                          "WEBP", quality=args.quality, method=6)
        size = os.path.getsize(target)
        sizes.append(size)
        total_bytes += size
        large_bytes += os.path.getsize(os.path.join(args.input, name))
        if done < 400:
            bright.append(brightness[0])
            contrast.append(brightness[1])
            near_white.append(brightness[2])
        done += 1
        if done % 200 == 0:
            print(f"[{done}/{len(names)}] skipped={skipped} avg={total_bytes/max(1,len(sizes))/1024:.1f}KB "
                  f"{(time.time()-started)/done*1000:.0f}ms/img", flush=True)

    stats = {
        "input": args.input, "output": args.output, "size": args.size,
        "quality": args.quality, "margin": args.margin,
        "images": len(sizes), "converted": done, "skipped": skipped,
        "total_bytes": total_bytes, "total_mb": round(total_bytes / 1048576, 2),
        "avg_kb": round(total_bytes / max(1, len(sizes)) / 1024, 2),
        "min_kb": round(min(sizes) / 1024, 2) if sizes else 0,
        "max_kb": round(max(sizes) / 1024, 2) if sizes else 0,
        "source_mb": round(large_bytes / 1048576, 1),
        "elapsed_seconds": round(time.time() - started, 1),
        "metrics_88px": {
            "sampled": len(bright),
            "brightness": round(float(np.mean(bright)), 1) if bright else None,
            "contrast": round(float(np.mean(contrast)), 1) if contrast else None,
            "near_white_pct": round(float(np.mean(near_white)), 2) if near_white else None,
        },
    }
    if args.stats:
        json.dump(stats, open(args.stats, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(json.dumps(stats, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
