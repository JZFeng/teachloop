#!/usr/bin/env python3
"""从仓库里的位图 logo 生成可用的品牌资产（mark / 字标 / 整标 / favicon）。

为什么要有这个脚本，而不是手工裁几张大图：
  1. 位图 logo 一次裁完就散成 4 个文件，下次换图没人知道当初的裁切框；
     这里把裁切框、去背参数、产物尺寸都写成断言，换图会直接报错而不是静默出错图。
  2. 去背只去掉「与画布边缘连通」的近白像素，循环内部的白色笑脸必须保留，
     全局把白色抠掉会把笑脸也抠穿。
  3. 产物要能复现：任何人跑一次都得到同样的文件，脚本最后打印尺寸/字节/透明覆盖率。

用法：
  python3 scripts/brand/build-assets.py            # 生成到 web/public/brand/
  python3 scripts/brand/build-assets.py --check    # 只校验现有产物与源图一致

退出码：0 成功；1 源图/裁切框/产物校验失败。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections import deque
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs" / "brand" / "teachloop-logo-source.png"
OUT = ROOT / "web" / "public" / "brand"
EXPECTED_SHA = "93f02fd1b7d92da6a22d144117e0bf65ae2142d8fe072f401d1f344728c84484"

# 原图像素坐标（由 scripts/brand/probe-logo.py 量出；±8px 内允许，超出视为换了图）
EXPECTED_BOXES = {
    "mark": (370, 247, 960, 624),
    "wordmark": (112, 657, 1140, 848),
}
BOX_TOLERANCE = 12

# 去背参数：与边缘连通的、三通道都 >= BG_MIN 且通道差 <= BG_SPREAD 的像素判为背景。
BG_MIN = 244
BG_SPREAD = 6


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def background_mask(img: Image.Image) -> list[bool]:
    """返回与画布边缘连通的背景像素标记（True = 背景）。

    只从四边做 BFS：循环内部的白色笑脸不连边，因此不会被判成背景。
    """
    w, h = img.size
    px = img.load()
    mask = [False] * (w * h)
    queue: deque[tuple[int, int]] = deque()

    def is_bg(x: int, y: int) -> bool:
        r, g, b = px[x, y][:3]
        low = min(r, g, b)
        return low >= BG_MIN and max(r, g, b) - low <= BG_SPREAD

    for x in range(w):
        for y in (0, h - 1):
            if is_bg(x, y) and not mask[y * w + x]:
                mask[y * w + x] = True
                queue.append((x, y))
    for y in range(h):
        for x in (0, w - 1):
            if is_bg(x, y) and not mask[y * w + x]:
                mask[y * w + x] = True
                queue.append((x, y))

    while queue:
        x, y = queue.popleft()
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < w and 0 <= ny < h and not mask[ny * w + nx] and is_bg(nx, ny):
                mask[ny * w + nx] = True
                queue.append((nx, ny))
    return mask


def to_transparent(img: Image.Image) -> Image.Image:
    """去背 + 边缘去白。

    背景像素置全透明；与透明区相邻的像素做「去污染」：用邻域里最饱和的颜色当
    前景参考，按 p = a·f + (1-a)·white 解出 alpha，并把像素颜色换成 f。
    只把 alpha 调低、颜色仍留白，会在暗色主题上出现一圈灰白描边——
    这一圈正是第一次生成的产物在 #0A1120 上最难看的白边。
    """
    rgba = img.convert("RGBA")
    w, h = rgba.size
    px = rgba.load()
    bg = background_mask(rgba)

    for idx, is_bg in enumerate(bg):
        if is_bg:
            x, y = idx % w, idx // w
            r, g, b, _ = px[x, y]
            px[x, y] = (r, g, b, 0)

    def adjacent_bg(x: int, y: int) -> bool:
        idx = y * w + x
        if x == 0 or y == 0 or x == w - 1 or y == h - 1:
            return True
        return (
            bg[idx - 1] or bg[idx + 1] or bg[idx - w] or bg[idx + w]
            or bg[idx - w - 1] or bg[idx - w + 1] or bg[idx + w - 1] or bg[idx + w + 1]
        )

    for y in range(1, h - 1):
        for x in range(1, w - 1):
            if bg[y * w + x] or not adjacent_bg(x, y):
                continue
            r, g, b, _ = px[x, y]
            best = None
            best_min = 999
            for dy in range(-3, 4):
                for dx in range(-3, 4):
                    nx, ny = x + dx, y + dy
                    if not (0 <= nx < w and 0 <= ny < h):
                        continue
                    rr, gg, bb, aa = px[nx, ny]
                    if aa == 0:
                        continue
                    low = min(rr, gg, bb)
                    if low < best_min:
                        best_min = low
                        best = (rr, gg, bb)
            if best is None:
                continue
            ratios = [
                (255 - pc) / (255 - fc)
                for pc, fc in zip((r, g, b), best)
                if fc < 245
            ]
            if not ratios:
                continue
            alpha = max(0.0, min(1.0, sum(ratios) / len(ratios)))
            px[x, y] = (best[0], best[1], best[2], int(alpha * 255))
    return rgba


def bbox(img: Image.Image) -> tuple[int, int, int, int] | None:
    return img.convert("RGBa").getbbox()


def crop_region(stripped: Image.Image, box: tuple[int, int, int, int], pad: int) -> Image.Image:
    x0, y0, x1, y1 = box
    return stripped.crop((x0 - pad, y0 - pad, x1 + pad, y1 + pad))


def square_canvas(img: Image.Image, side: int) -> Image.Image:
    """把裁出的图形放到正方形画布中央：侧栏/图标位不需要长宽比不同的图。"""
    w, h = img.size
    scale = min((side * 0.94) / w, (side * 0.94) / h)
    resized = img.resize((max(1, round(w * scale)), max(1, round(h * scale))), Image.LANCZOS)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.alpha_composite(resized, ((side - resized.width) // 2, (side - resized.height) // 2))
    return canvas


def fit_width(img: Image.Image, width: int) -> Image.Image:
    scale = width / img.width
    return img.resize((width, max(1, round(img.height * scale))), Image.LANCZOS)


def rounded_tile(mark: Image.Image, side: int, bg: tuple[int, int, int, int]) -> Image.Image:
    """favicon：白底圆角方块 + 居中图形标，深色浏览器标签栏里也不会消失。"""
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    radius = round(side * 0.22)
    draw.rounded_rectangle((0, 0, side - 1, side - 1), radius=radius, fill=bg)
    inner = round(side * 0.78)
    scaled = mark.copy()
    scaled.thumbnail((inner, inner), Image.LANCZOS)
    canvas.alpha_composite(scaled, ((side - scaled.width) // 2, (side - scaled.height) // 2))
    return canvas


def find_boxes(img: Image.Image) -> dict[str, tuple[int, int, int, int]]:
    """按行分带找 mark / 字标：凭空猜裁切框，换图就会错。"""
    rgba = img.convert("RGBa")
    w, h = rgba.size
    px = rgba.load()
    rows: list[tuple[int, int]] = []
    start = None
    for y in range(h):
        count = 0
        for x in range(0, w, 2):
            r, g, b, _ = px[x, y]
            if not (r > 245 and g > 245 and b > 245):
                count += 1
        if count > 2 and start is None:
            start = y
        elif count <= 2 and start is not None:
            if y - start > 8:
                rows.append((start, y - 1))
            start = None
    if start is not None:
        rows.append((start, h - 1))

    def band_bbox(band: tuple[int, int]) -> tuple[int, int, int, int]:
        xs: list[int] = []
        ys: list[int] = []
        for y in range(band[0], band[1] + 1):
            for x in range(0, w, 2):
                r, g, b, _ = px[x, y]
                if not (r > 245 and g > 245 and b > 245):
                    xs.append(x)
                    ys.append(y)
        return (min(xs), min(ys), max(xs), max(ys))

    if len(rows) < 2:
        raise SystemExit("[brand] 找不到 mark 与字标两段，源图可能不对")
    return {"mark": band_bbox(rows[0]), "wordmark": band_bbox(rows[1])}


def check_box(name: str, got: tuple[int, int, int, int]) -> None:
    want = EXPECTED_BOXES[name]
    diff = max(abs(g - w) for g, w in zip(got, want))
    if diff > BOX_TOLERANCE:
        raise SystemExit(f"[brand] {name} 裁切框漂移 {diff}px：得到 {got}，期望 {want}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="只校验产物存在且非空，不重新生成")
    args = ap.parse_args()

    if not SOURCE.exists():
        raise SystemExit(f"[brand] 缺少源图：{SOURCE.relative_to(ROOT)}")
    digest = sha256(SOURCE)
    if digest != EXPECTED_SHA:
        raise SystemExit(
            f"[brand] 源图指纹不一致：{digest[:12]}…，期望 {EXPECTED_SHA[:12]}…；"
            "换图请改脚本里的 EXPECTED_SHA 与裁切框"
        )

    if args.check:
        missing = [p for p in ("logo-mark-512.png", "logo-lockup-960.png", "favicon-32.png") if not (OUT / p).exists()]
        if missing:
            raise SystemExit(f"[brand] 产物缺失：{', '.join(missing)}")
        print("[brand] 校验通过：产物齐全")
        return 0

    img = Image.open(SOURCE).convert("RGB")
    boxes = find_boxes(img)
    for name, box in boxes.items():
        check_box(name, box)

    stripped = to_transparent(img)
    OUT.mkdir(parents=True, exist_ok=True)

    mark_box = crop_region(stripped, boxes["mark"], 18)
    word_box = crop_region(stripped, boxes["wordmark"], 8)
    lock_box = crop_region(
        stripped,
        (
            min(boxes["mark"][0], boxes["wordmark"][0]),
            boxes["mark"][1],
            max(boxes["mark"][2], boxes["wordmark"][2]),
            boxes["wordmark"][3],
        ),
        8,
    )

    mark512 = square_canvas(mark_box, 512)
    mark128 = square_canvas(mark_box, 128)
    mark32 = square_canvas(mark_box, 32)
    lock960 = fit_width(lock_box, 960)
    word960 = fit_width(word_box, 960)

    mark512.save(OUT / "logo-mark-512.png", optimize=True)
    fit_width(mark_box, 512).save(OUT / "logo-mark-wide-512.png", optimize=True)
    mark512.resize((256, 256), Image.LANCZOS).save(OUT / "logo-mark-256.png", optimize=True)
    mark128.save(OUT / "logo-mark-128.png", optimize=True)
    mark32.save(OUT / "logo-mark-32.png", optimize=True)
    lock960.save(OUT / "logo-lockup-960.png", optimize=True)
    word960.save(OUT / "logo-wordmark-960.png", optimize=True)
    rounded_tile(mark512, 32, (255, 255, 255, 255)).save(OUT / "favicon-32.png", optimize=True)
    rounded_tile(mark512, 180, (255, 255, 255, 255)).save(OUT / "apple-touch-icon-180.png", optimize=True)

    manifest = {
        "source": "docs/brand/teachloop-logo-source.png",
        "sourceSha256": digest,
        "boxes": {k: list(v) for k, v in boxes.items()},
        "assets": {},
    }
    for path in sorted(OUT.glob("*.png")):
        with Image.open(path) as out:
            alpha = out.convert("RGBA").getchannel("A")
            coverage = round(sum(alpha.histogram()[1:]) / (out.width * out.height) * 100, 1)
            manifest["assets"][path.name] = {
                "size": list(out.size),
                "bytes": path.stat().st_size,
                "alphaCoveragePct": coverage,
            }
            print(f"  {path.name:<26} {out.width:>4}x{out.height:<4} {path.stat().st_size:>7} B  透明覆盖 {coverage:>5.1f}%")

    (OUT / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"[brand] 已生成 {len(manifest['assets'])} 个文件到 web/public/brand/")
    return 0


if __name__ == "__main__":
    sys.exit(main())
