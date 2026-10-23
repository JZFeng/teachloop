#!/usr/bin/env python3
"""令牌与对比度守卫：两套主题的颜色必须是同一份值，且对比度达标。

为什么要有这个脚本：
  1. `web/src/ui/tokens.ts` 给 antd 组件用；`web/src/styles/tokens.css` 给
     16 个还没迁移的页面用。同一个颜色写两处，改一处忘另一处就会出现
     「卡片是新的蓝、按钮是旧的紫」。这里直接断言两边相等。
  2. 对比度是算出来的，不是看出来的。`#1677FF` 这种「看起来没问题」的主色
     对白字只有 4.10:1，达不到 WCAG AA 的 4.5。
  3. 品牌亮蓝、青绿、黄对白分别只有 3.34 / 2.48 / 1.65：它们只能做填充，
     不能做文字。脚本把「功能色 ink × 粉彩底」「状态色 ink × 浅底」逐对算一遍。

覆盖范围（2026-10-22 品牌层）：
  - 语义令牌 16 组（亮/暗，暗色背景改成读令牌，不再写死 #141414）；
  - 四个功能位 ink × tint 亮暗各 4 组；
  - 判分三态 ink × 浅底、亮色 / 白、暗色 / 面板共 12 组；
  - 链接色 accent-text 亮暗各 2 组；
  合计 40 组。

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
    "accent-weak": "colorPrimaryBg",
    "accent-text": "colorLink",
    "text": "colorText",
    "muted": "colorTextSecondary",
    "muted-2": "colorTextTertiary",
    "line-strong": "colorBorder",
    "bg": "colorBgLayout",
    "panel": "colorBgContainer",
    "on-accent": "colorTextLightSolid",
}

# 功能位 / 状态 / 字标 / 原始品牌色：CSS 变量名 → TS 常量字段名
FEATURE_CSS_TO_TS = {
    "create": "create",
    "create-weak": "createWeak",
    "assess": "assess",
    "assess-weak": "assessWeak",
    "practice": "practice",
    "practice-weak": "practiceWeak",
    "insights": "insights",
    "insights-weak": "insightsWeak",
}
STATUS_CSS_TO_TS = {
    "green": "pass",
    "green-weak": "passWeak",
    "amber": "partial",
    "amber-weak": "partialWeak",
    "red": "fail",
    "red-weak": "failWeak",
}
LOGO_CSS_TO_TS = {
    "logo-navy": "navy",
    "logo-blue-text": "blue",
    "logo-teal-text": "teal",
}
BRAND_CSS_TO_TS = {
    "brand-navy": "navy",
    "brand-azure": "azure",
    "brand-teal": "teal",
    "brand-yellow": "yellow",
    "brand-rose": "rose",
}


def parse_hex_block(body: str) -> dict[str, str]:
    return dict(re.findall(r"\b(\w+)\s*:\s*'(#[0-9a-fA-F]{3,8})'", body))


def parse_ts() -> tuple[dict[str, dict[str, str]], dict[str, dict[str, str]], dict[str, str]]:
    """返回 ({light, dark} 语义令牌, {light, dark} 功能/状态/字标, 原始品牌色)。"""
    text = TOKENS_TS.read_text(encoding="utf-8")
    semantic: dict[str, dict[str, str]] = {}
    for name in ("lightToken", "darkToken"):
        match = re.search(rf"const {name} = \{{(.*?)\n\}} satisfies", text, re.S)
        if not match:
            raise SystemExit(f"[contrast] 找不到 {name} 定义")
        semantic["light" if name == "lightToken" else "dark"] = parse_hex_block(match.group(1))

    def const(name: str) -> dict[str, str]:
        match = re.search(rf"export const {name} = \{{(.*?)\n\}} as const", text, re.S)
        if not match:
            raise SystemExit(f"[contrast] 找不到 {name} 定义")
        return parse_hex_block(match.group(1))

    extra = {
        "light": {**const("FEATURE_LIGHT"), **const("STATUS_LIGHT"), **const("LOGO_LIGHT")},
        "dark": {**const("FEATURE_DARK"), **const("STATUS_DARK"), **const("LOGO_DARK")},
    }
    return semantic, extra, const("BRAND")


def parse_css() -> dict[str, dict[str, str]]:
    """从 tokens.css 里抽出 :root 与 :root[data-theme='dark'] 的颜色值。"""
    text = TOKENS_CSS.read_text(encoding="utf-8")
    light_block = re.search(r":root \{(.*?)\n\}", text, re.S)
    dark_block = re.search(r":root\[data-theme='dark'\] \{(.*?)\n\}", text, re.S)
    if not light_block or not dark_block:
        raise SystemExit("[contrast] tokens.css 缺少 :root 或暗色块")

    def grab(block: str) -> dict[str, str]:
        return dict(re.findall(r"--([\w-]+)\s*:\s*(#[0-9a-fA-F]{3,8})\s*;", block))

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

    ts, extra, brand = parse_ts()
    css = parse_css()
    problems: list[str] = []

    # ---- 1. 两个出口的值必须一致 ----
    for mode in ("light", "dark"):
        for css_key, ts_key in CSS_TO_TS.items():
            css_value = css[mode].get(css_key)
            ts_value = ts[mode].get(ts_key)
            if ts_key == "colorTextLightSolid":
                continue  # antd 自带，不在 tokens.css 里重复定义
            if css_value is None:
                problems.append(f"{mode}: tokens.css 缺少 --{css_key}")
            elif ts_value is None:
                problems.append(f"{mode}: tokens.ts 缺少 {ts_key}")
            elif normalize(css_value) != normalize(ts_value):
                problems.append(
                    f"{mode}: --{css_key} ({css_value}) 与 tokens.ts {ts_key} ({ts_value}) 不一致"
                )
        for mapping, label in (
            (FEATURE_CSS_TO_TS, "FEATURE"),
            (STATUS_CSS_TO_TS, "STATUS"),
            (LOGO_CSS_TO_TS, "LOGO"),
        ):
            for css_key, ts_key in mapping.items():
                css_value = css[mode].get(css_key)
                ts_value = extra[mode].get(ts_key)
                if css_value is None:
                    problems.append(f"{mode}: tokens.css 缺少 --{css_key}")
                elif ts_value is None:
                    problems.append(f"{mode}: tokens.ts {label} 缺少 {ts_key}")
                elif normalize(css_value) != normalize(ts_value):
                    problems.append(
                        f"{mode}: --{css_key} ({css_value}) 与 tokens.ts {label} ({ts_value}) 不一致"
                    )

    # 原始品牌色主题无关，只检查 :root 一处
    for css_key, ts_key in BRAND_CSS_TO_TS.items():
        css_value = css["light"].get(css_key)
        ts_value = brand.get(ts_key)
        if css_value is None:
            problems.append(f"light: tokens.css 缺少 --{css_key}")
        elif ts_value is None:
            problems.append(f"light: tokens.ts BRAND 缺少 {ts_key}")
        elif normalize(css_value) != normalize(ts_value):
            problems.append(
                f"light: --{css_key} ({css_value}) 与 tokens.ts BRAND ({ts_value}) 不一致"
            )

    # ---- 2. 对比度（40 组） ----
    light_bg = css["light"]["bg"]
    dark_bg = css["dark"]["bg"]
    dark_panel = css["dark"]["panel"]
    light_weak = css["light"]["accent-weak"]
    dark_weak = css["dark"]["accent-weak"]

    checks: list[tuple[str, str, str, str, float]] = []

    def add(mode: str, fg: str | None, bg: str, label: str, minimum: float) -> None:
        if not fg:
            problems.append(f"{mode}: 缺少用于「{label}」的颜色")
            return
        checks.append((mode, fg, bg, label, minimum))

    # 16 组语义令牌（暗色背景从令牌读，不再写死 #141414）
    add("light", ts["light"].get("colorPrimary"), WHITE, "主按钮白字", TEXT_MIN)
    add("light", ts["light"].get("colorText"), WHITE, "正文", TEXT_MIN)
    add("light", ts["light"].get("colorTextSecondary"), WHITE, "次级文字", TEXT_MIN)
    add("light", ts["light"].get("colorTextTertiary"), WHITE, "辅助文字（小字）", TEXT_MIN)
    add("light", ts["light"].get("colorBorder"), WHITE, "输入框边框", BORDER_MIN)
    add("light", ts["light"].get("colorText"), light_bg, "正文 / 页面底", TEXT_MIN)
    add("dark", ts["dark"].get("colorPrimary"), WHITE, "主按钮白字", TEXT_MIN)
    add("dark", ts["dark"].get("colorText"), dark_bg, "正文 / 暗色页面底", TEXT_MIN)
    add("dark", ts["dark"].get("colorTextSecondary"), dark_bg, "次级文字 / 暗色页面底", TEXT_MIN)
    add("dark", ts["dark"].get("colorTextTertiary"), dark_bg, "辅助文字 / 暗色页面底", TEXT_MIN)
    add("dark", ts["dark"].get("colorBorder"), dark_panel, "输入框边框 / 暗色面板", BORDER_MIN)
    add("light", ts["light"].get("colorTextTertiary"), css["light"]["panel"], "侧栏图标（未选中）", BORDER_MIN)
    add("dark", ts["dark"].get("colorTextTertiary"), dark_panel, "侧栏图标（未选中）", BORDER_MIN)
    add("light", ts["light"].get("colorPrimary"), light_weak, "侧栏当前项文字", TEXT_MIN)
    add("dark", ts["dark"].get("colorText"), dark_weak, "侧栏当前项文字", TEXT_MIN)
    add("dark", ts["dark"].get("colorPrimary"), dark_weak, "侧栏当前项图标", BORDER_MIN)

    # 4 个功能位 ink × tint，亮暗各 4 组
    for mode in ("light", "dark"):
        for key, label in (
            ("create", "Create"),
            ("assess", "Assess"),
            ("practice", "Practice"),
            ("insights", "Insights"),
        ):
            add(mode, extra[mode].get(key), extra[mode].get(f"{key}Weak"), f"{label} 功能色 / 粉彩底", TEXT_MIN)

    # 判分三态：亮色 on 浅底、暗色 on 浅底、亮色 on 白、暗色 on 面板
    for mode in ("light", "dark"):
        for key, label in (("pass", "对"), ("partial", "部分对"), ("fail", "错")):
            add(mode, extra[mode].get(key), extra[mode].get(f"{key}Weak"), f"判分 {label} / 浅底", TEXT_MIN)
    for key, label in (("pass", "对"), ("partial", "部分对"), ("fail", "错")):
        add("light", extra["light"].get(key), WHITE, f"判分 {label} / 白底", TEXT_MIN)
    for key, label in (("pass", "对"), ("partial", "部分对"), ("fail", "错")):
        add("dark", extra["dark"].get(key), dark_panel, f"判分 {label} / 暗色面板", TEXT_MIN)

    # 链接色：暗色面板、暗色页面底、亮色白底、亮色选中底
    add("dark", ts["dark"].get("colorLink"), dark_panel, "链接 / 暗色面板", TEXT_MIN)
    add("dark", ts["dark"].get("colorLink"), dark_bg, "链接 / 暗色页面底", TEXT_MIN)
    add("light", ts["light"].get("colorLink"), WHITE, "链接 / 白底", TEXT_MIN)
    add("light", ts["light"].get("colorLink"), light_weak, "链接 / 选中底", TEXT_MIN)

    rows = []
    for mode, fg, bg, label, minimum in checks:
        ratio = contrast(fg, bg)
        ok = ratio >= minimum
        rows.append((mode, fg, bg, label, ratio, minimum, ok))
        if not ok:
            problems.append(
                f"{mode}: {fg} 对 {bg} = {ratio:.2f}:1，低于 {minimum}:1（{label}）"
            )

    if args.verbose or problems:
        print(f"{'主题':<6}{'前景':<10}{'背景':<10}{'对比度':>8}  {'下限':<5}结果  用途")
        for mode, fg, bg, label, ratio, minimum, ok in rows:
            print(
                f"{mode:<6}{fg:<10}{bg:<10}{ratio:>8.2f}  {minimum:<5}{'通过' if ok else '失败'}  {label}"
            )

    if problems:
        print("\n[contrast] 失败：")
        for item in problems:
            print(f"  - {item}")
        return 1

    print(
        f"[contrast] 通过：{len(rows)} 组对比度全部达标；"
        f"语义令牌、功能色、判分状态、字标与原始品牌色两边一致"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
