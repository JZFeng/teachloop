#!/usr/bin/env python3
"""卡片配图的确定性后处理：levels 拉伸 + 轻微提亮 + 锐化。

把生成图统一到「88px 下可读」的区间：亮度 ≈210、对比度 ≥55（见
docs/reports/2026-10-27-vocab-image-models/README.md 的实测）。

用法：
  python3 scripts/vocab/image/normalize_images.py --input ~/ai/vocab-images/flux113 --output ~/ai/vocab-images/final
"""

from __future__ import annotations

import argparse
import os

import numpy as np
from PIL import Image, ImageFilter


def normalize(image: Image.Image, black: float = 2.0, white: float = 98.0,
              gain: float = 0.92, lift: float = 0.06, highlight: float = 0.965,
              warm_red: float = 1.03, warm_blue: float = 0.97,
              sharpen: bool = True) -> Image.Image:
    """C1 暖纸底曲线：保住高光细节（不推到纯白），整体略暖。

    实测 113 张：88px 亮度 ≈196、对比度 ≈55、近白像素（≥250）≈0.4%。
    白底会把图「融进」浅色卡片，所以默认把高光压到 ~246，而不是拉满 255。
    """
    array = np.asarray(image.convert("RGB")).astype(np.float32) / 255.0
    luminance = 0.299 * array[..., 0] + 0.587 * array[..., 1] + 0.114 * array[..., 2]
    low, high = np.percentile(luminance, black), np.percentile(luminance, white)
    array = np.clip((array - low) / max(high - low, 1e-3), 0, 1)
    array = np.clip(array * gain + lift, 0, 1)
    array = np.clip(array * highlight, 0, 1)
    array[..., 0] = np.clip(array[..., 0] * warm_red, 0, 1)
    array[..., 2] = np.clip(array[..., 2] * warm_blue, 0, 1)
    out = Image.fromarray((array * 255).astype(np.uint8))
    if sharpen:
        out = out.filter(ImageFilter.UnsharpMask(radius=1.2, percent=60, threshold=2))
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--size", type=int, default=0, help="可选：统一缩放到该边长")
    args = parser.parse_args()

    os.makedirs(args.output, exist_ok=True)
    names = sorted(name for name in os.listdir(args.input) if name.lower().endswith(".png"))
    for name in names:
        image = Image.open(os.path.join(args.input, name))
        image = normalize(image)
        if args.size > 0:
            image = image.resize((args.size, args.size), Image.LANCZOS)
        image.save(os.path.join(args.output, name), optimize=True)
    print(f"normalized {len(names)} images -> {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
