#!/usr/bin/env python3
"""令牌与对比度守卫：两套主题的颜色必须是同一份值，且对比度达标。

为什么要有这个脚本：
  1. `web/src/ui/tokens.ts` 给 antd 组件用；`web/src/styles/tokens.css` 给
     16 个还没迁移的页面用。同一个颜色写两处，改一处忘另一处就会出现
     「卡片是新的蓝、按钮是旧的紫」。这里直接断言两边相等。
  2. 对比度是算出来的，不是看出来的。`#1677FF` 这种「看起来没问题」的主色
     对白字只有 4.10:1，达不到 WCAG AA 的 4.5。

用法：python3 scripts/ui/contrast.py [--verbose]
退出码：0 通过；1 有不达标项。
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
TOKENS_TS = ROOT / "web" / "src" / "ui" / "tokens.ts"
TOKENS_CSS = ROOT / "web" / "src" / "styles" / "tokens.css"

WHITE = "#ffffff"

# 正文类文字要求 4.5:1；控件边界与图标要求 3:1（WCAG 1.4.3 / 1.4.11）。
TEXT_MIN = 4.5
BORDER_MIN = 3.0

# tokens.css 变量 → tokens.ts 令牌名（同一个值两个出口）
CSS_TO_TS = {
    "accent": "colorPrimary",
    "text": "colorText",
    "muted": "colorTextSecondary",
    "muted-2": "colorTextTertiary",
    "line-strong": "colorBorder",
    "bg": "colorBgLayout",
    "panel": "colorBgContainer",
    "on-accent": "colorTextLightSolid",
}


def parse_ts() -> dict[str, dict[str, str]]:
    """从 tokens.ts 里抽出 lightToken / darkToken 的颜色值。"""
    text = TOKENS_TS.read_text(encoding="utf-8")
    out: dict[str, dict[str, str]] = {}
    for name in ("lightToken", "darkToken"):
        match = re.search(rf"const {name} = \{{(.*?)\n\}} satisfies", text, re.S)
        if not match:
            raise SystemExit(f"[contrast] 找不到 {name} 定义")
        pairs = dict(re.findall(r"\b(\w+)\s*:\s*'(#[0-9a-fA-F]{3,8})'", match.group(1)))
        out["light" if name == "lightToken" else "dark"] = pairs
    return out


def parse_css() -> dict[str, dict[str, str]]:
    """从 tokens.css 里抽出 :root 与 :root[data-theme='dark'] 的颜色值。"""
    text = TOKENS_CSS.read_text(encoding="utf-8")
    light_block = re.search(r":root \{(.*?)\n\}", text, re.S)
    dark_block = re.search(r":root\[data-theme='dark'\] \{(.*?)\n\}", text, re.S)
    if not light_block or not dark_block:
        raise SystemExit("[contrast] tokens.css 缺少 :root 或暗色块")

    def grab(block: str) -> dict[str, str]:
        out = {}
        for key, value in re.findall(r"--([\w-]+)\s*:\s*(#[0-9a-fA-F]{3,8})\s*;", block):
            out[key] = value
        return out

    return {"light": grab(light_block.group(1)), "dark": grab(dark_block.group(1))}


def normalize(hex_value: str) -> str:
    value = hex_value.lstrip("#").lower()
    if len(value) == 3:
        value = "".join(c * 2 for c in value)
    return "#" + value


def luminance(hex_value: str) -> float:
    value = normalize(hex_value).lstrip("#")
    channels = [int(value[i : i + 2], 16) / 255 for i in (0, 2, 4)]

    def linear(c: float) -> float:
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4

    r, g, b = (linear(c) for c in channels)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a: str, b: str) -> float:
    la, lb = luminance(a), luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    ts = parse_ts()
    css = parse_css()
    problems: list[str] = []

    # ---- 1. 两个出口的值必须一致 ----
    for mode in ("light", "dark"):
        for css_key, ts_key in CSS_TO_TS.items():
            css_value = css[mode].get(css_key)
            ts_value = ts[mode].get(ts_key)
            if ts_key == "colorTextLightSolid":
                continue  # antd 自带，不在 tokens.ts 里重复定义
            if css_value is None:
                problems.append(f"{mode}: tokens.css 缺少 --{css_key}")
            elif ts_value is None:
                problems.append(f"{mode}: tokens.ts 缺少 {ts_key}")
            elif normalize(css_value) != normalize(ts_value):
                problems.append(
                    f"{mode}: --{css_key} ({css_value}) 与 tokens.ts {ts_key} ({ts_value}) 不一致"
                )

    # ---- 2. 对比度 ----
    checks = [
        # (模式, 前景令牌, 背景, 用途, 下限)
        ("light", "colorPrimary", WHITE, "主按钮白字", TEXT_MIN),
        ("light", "colorText", WHITE, "正文", TEXT_MIN),
        ("light", "colorTextSecondary", WHITE, "次级文字", TEXT_MIN),
        ("light", "colorTextTertiary", WHITE, "辅助文字（小字）", TEXT_MIN),
        ("light", "colorBorder", WHITE, "输入框边框", BORDER_MIN),
        ("light", "colorText", css["light"]["bg"], "正文 / 页面底", TEXT_MIN),
        ("dark", "colorPrimary", WHITE, "主按钮白字", TEXT_MIN),
        ("dark", "colorText", "#141414", "正文", TEXT_MIN),
        ("dark", "colorTextSecondary", "#141414", "次级文字", TEXT_MIN),
        ("dark", "colorTextTertiary", "#141414", "辅助文字（小字）", TEXT_MIN),
        ("dark", "colorBorder", "#1f1f1f", "输入框边框", BORDER_MIN),
    ]

    rows = []
    for mode, token, bg, label, minimum in checks:
        value = ts[mode].get(token)
        if not value:
            problems.append(f"{mode}: 缺少 {token}，无法核对对比度")
            continue
        ratio = contrast(value, bg)
        ok = ratio >= minimum
        rows.append((mode, token, value, bg, label, ratio, minimum, ok))
        if not ok:
            problems.append(f"{mode}: {token} 对 {bg} = {ratio:.2f}:1，低于 {minimum}:1（{label}）")

    if args.verbose or problems:
        print(f"{'主题':<6}{'令牌':<22}{'值':<10}{'对比度':>8}  {'下限':<5}结果  用途")
        for mode, token, value, bg, label, ratio, minimum, ok in rows:
            print(
                f"{mode:<6}{token:<22}{value:<10}{ratio:>8.2f}  {minimum:<5}{'通过' if ok else '失败'}  {label}"
            )

    if problems:
        print("\n[contrast] 失败：")
        for item in problems:
            print(f"  - {item}")
        return 1

    print(f"[contrast] 通过：{len(rows)} 组对比度全部达标，两套令牌与旧变量层一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
