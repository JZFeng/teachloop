#!/usr/bin/env python3
"""UI 标准守卫（check-ui）：让「样式随手一写」这件事在 CI 里直接失败。

规则（与 spec/003 的 AC 对应）：
  1. COLOR_LITERAL     颜色字面量只允许出现在 ui/tokens.ts 与 styles/tokens.css
  2. INLINE_STYLE      内联 style 里禁止写颜色/字号/圆角/阴影（允许布局）
  3. ANTD_DIRECT       页面禁止直接 import antd / @ant-design/icons，只能从 ui/ 引
  4. LEGACY_BTN_CLASS  禁止手写 className="btn…"（应使用组件层 Button）
  5. EMOJI_ICON        禁止用 emoji 当图标（应使用 @ant-design/icons，经 ui/ 引出）
  6. RAW_TABLE         页面不得新写 <table>（列表表格统一由 ui/DataTable 渲染，T441/US-68）

存量用基线文件 `scripts/ui-baseline.txt`（格式 `路径|规则|数量`）冻结：
  - 基线内的数量可以下降，不能上升；
  - 新文件出现违规直接失败；
  - 生成/更新基线：`python3 scripts/check-ui.py --write-baseline`

退出码：0 通过，1 有新增违规。
"""
from __future__ import annotations

import argparse
import collections
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WEB = ROOT / "web" / "src"
BASELINE = ROOT / "scripts" / "ui-baseline.txt"

# 令牌唯一真源：这两个文件允许出现颜色字面量。
COLOR_TOKEN_FILES = {
    "web/src/ui/tokens.ts",
    "web/src/styles/tokens.css",
}

COLOR_LITERAL = re.compile(r"#[0-9a-fA-F]{3,8}\b|\brgba?\(|\bhsla?\(")
INLINE_STYLE = re.compile(r"style=\{\{(.*?)\}\}", re.S)
INLINE_COLOR_KEY = re.compile(
    r"\b(color|background|backgroundColor|fontSize|borderRadius|boxShadow|border|borderColor)\s*:"
)
ANTD_IMPORT = re.compile(r"from\s+['\"](antd|@ant-design/icons)")
LEGACY_BTN = re.compile(r'className=\{?["`]([^"`]*\bbtn\b)')
EMOJI = re.compile("[\U0001f300-\U0001faff\u2600-\u27bf]")
RAW_TABLE = re.compile(r"<table\b")

RULE_HINT = {
    "COLOR_LITERAL": "颜色只能来自 web/src/ui/tokens.ts 或 web/src/styles/tokens.css",
    "INLINE_STYLE": "内联 style 只写布局（flex/grid/width/margin），颜色/字号/圆角走令牌或组件",
    "ANTD_DIRECT": "页面请从 '../ui' 引组件，不要直接 import antd",
    "LEGACY_BTN_CLASS": "用 <Button variant=...>，不要手写 className=\"btn\"",
    "EMOJI_ICON": "图标用 @ant-design/icons（经 ui/ 引出），不要用 emoji",
    "RAW_TABLE": "列表表格用 ui 的 DataTable；页面不再新写 <table>（T441）",
}


def is_ui_layer(rel: str) -> bool:
    return rel.startswith("web/src/ui/")


def scan() -> dict[str, dict[str, int]]:
    """返回 {相对路径: {规则: 次数}}。"""
    counts: dict[str, dict[str, int]] = collections.defaultdict(lambda: collections.defaultdict(int))
    for path in WEB.rglob("*"):
        if path.suffix not in {".ts", ".tsx", ".css"} or not path.is_file():
            continue
        rel = path.relative_to(ROOT).as_posix()
        text = path.read_text(encoding="utf-8")

        if rel not in COLOR_TOKEN_FILES:
            counts[rel]["COLOR_LITERAL"] += len(COLOR_LITERAL.findall(text))

        if path.suffix in {".ts", ".tsx"}:
            for body in INLINE_STYLE.findall(text):
                counts[rel]["INLINE_STYLE"] += len(INLINE_COLOR_KEY.findall(body))
            if not is_ui_layer(rel):
                counts[rel]["ANTD_DIRECT"] += len(ANTD_IMPORT.findall(text))
                counts[rel]["LEGACY_BTN_CLASS"] += len(LEGACY_BTN.findall(text))
                counts[rel]["EMOJI_ICON"] += len(EMOJI.findall(text))
                counts[rel]["RAW_TABLE"] += len(RAW_TABLE.findall(text))
    return {k: dict(v) for k, v in counts.items() if v}


def read_baseline() -> dict[tuple[str, str], int]:
    data: dict[tuple[str, str], int] = {}
    if not BASELINE.exists():
        return data
    for line in BASELINE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("|")
        if len(parts) != 3:
            continue
        path, rule, count = parts
        data[(path, rule)] = int(count)
    return data


def write_baseline(counts: dict[str, dict[str, int]]) -> None:
    lines = [
        "# UI 守卫基线：这份清单是「存量违规」，只允许减少、不允许增加。",
        "# 生成：python3 scripts/check-ui.py --write-baseline（新增违规时不要用它掩盖，先改代码）",
        "# 格式：路径|规则|数量",
    ]
    for path in sorted(counts):
        for rule in sorted(counts[path]):
            lines.append(f"{path}|{rule}|{counts[path][rule]}")
    BASELINE.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"[check-ui] 已写入基线：{BASELINE.relative_to(ROOT)}（{sum(sum(v.values()) for v in counts.values())} 处存量）")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--write-baseline", action="store_true", help="把当前状态写成基线（仅用于首次接入）")
    args = ap.parse_args()

    counts = scan()
    if args.write_baseline:
        write_baseline(counts)
        return 0

    baseline = read_baseline()
    offenders: list[tuple[str, str, int, int]] = []
    for path, rules in counts.items():
        for rule, count in rules.items():
            allowed = baseline.get((path, rule), 0)
            if count > allowed:
                offenders.append((path, rule, count, allowed))

    stale = [
        (path, rule, allowed - counts.get(path, {}).get(rule, 0))
        for (path, rule), allowed in baseline.items()
        if counts.get(path, {}).get(rule, 0) < allowed
    ]

    if stale:
        # 基线过期不算失败，但提示可以收紧，避免基线永久虚高。
        print("[check-ui] 提示：以下文件比基线更干净了，可以更新基线：")
        for path, rule, delta in sorted(stale)[:8]:
            print(f"  - {path} {rule} 少 {delta} 处")

    if not offenders:
        total = sum(sum(v.values()) for v in counts.values())
        print(f"[check-ui] 通过：没有新增违规（当前存量 {total} 处，见 scripts/ui-baseline.txt）")
        return 0

    print("[check-ui] 失败：以下位置新增了违规")
    for path, rule, count, allowed in sorted(offenders):
        print(f"  - {path} · {rule}：现在 {count} 处，基线 {allowed} 处")
        print(f"    改法：{RULE_HINT[rule]}")
    print(f"\n共 {len(offenders)} 处新增。改完再跑一次；确实要放宽请改基线文件并在 PR 里说明理由。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
