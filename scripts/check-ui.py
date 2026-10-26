#!/usr/bin/env python3
"""UI 标准守卫（check-ui）：让「样式随手一写」这件事在 CI 里直接失败。

规则（与 spec 的 AC 对应）：
  1. COLOR_LITERAL     颜色字面量只允许出现在 ui/tokens.ts 与 styles/tokens.css
  2. INLINE_STYLE      内联 style 里禁止写颜色/字号/圆角/阴影（允许布局）
  3. ANTD_DIRECT       页面禁止直接 import antd / @ant-design/icons，只能从 ui/ 引
  4. LEGACY_BTN_CLASS  禁止手写 className="btn…"（应使用组件层 Button）
  5. EMOJI_ICON        禁止用 emoji 当图标（应使用 @ant-design/icons，经 ui/ 引出）
  6. RAW_TABLE         页面不得新写 <table>（列表表格统一由 ui/DataTable 渲染，T441/US-68）
  7. MUTED_FAILURE     失败/阻断不许用灰字表达（决策 026 / AC-70.2）
  8. ROW_ACTION_OVERFLOW 操作列一级动作最多 3 个（AC-25.1 / T492）：留 1 个主操作，其余进「⋯」

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

# 注释里的示例代码不是代码：文档注释里常出现 `<table>`、`from 'antd'` 这类字面，
# 扫原文会误报（实例：web/src/exam/importToEditor.ts:10 的富文本白名单说明）。
# 只剥块注释与「整行 // 注释」；行内 // 不动，避免误伤 URL 与字符串。
BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT = re.compile(r"^[ \t]*//.*$", re.M)


def strip_comments(text: str) -> str:
    """剥掉注释但保留换行，避免行号与缩进结构漂移。"""

    def blank_block(match: re.Match[str]) -> str:
        return "\n" * match.group(0).count("\n")

    return LINE_COMMENT.sub("", BLOCK_COMMENT.sub(blank_block, text))


# 第七条（决策 026 / AC-70.2）：失败与阻断不许用灰字表达。
# 只抓「错误表达式直接落进灰色容器」这一种最容易犯的写法：
#   <p className="small muted">{error}</p>
#   <div className="inline-note">{membersError}</div>
# 抓不到的（条件渲染里的语义、第三方组件内部的文案）由 AC-70.4 与 review 兜。
JSX_EXPR = re.compile(r"\{([A-Za-z_$][\w$]*(?:\.[\w$]+)*)\}")
FAILURE_IDENT = re.compile(r"[A-Za-z_$]*(?:[Ee]rror|[Ff]ail|[Ww]arn|Blocked|blocked)[A-Za-z_$]*")
TONED_CONTAINER = re.compile(
    r"inline-note\s+(?:ok|warn|err|info)\b|chip\s+(?:ok|warn|err|info)\b"
)

# 第八条（AC-25.1 / T492）：操作列一级动作最多 3 个。
# 「操作列」= 从 `key: 'actions'` 起到下一个列对象或列数组结尾 `\n  ]` 为止的片段；
# 片段内 <Button> / <button> / <Link> 都算一级动作（<RowActions> 内部的菜单项不算）。
ACTIONS_COLUMN = re.compile(r"key: 'actions'")
NEXT_COLUMN = re.compile(r"\n    \{\n      key: '")
ACTION_TAG = re.compile(r"<(?:Button|button|Link)\b")
MAX_ROW_ACTIONS = 3


def actions_block(text: str, start: int) -> str:
    ends: list[int] = []
    nxt = NEXT_COLUMN.search(text, start + 1)
    if nxt:
        ends.append(nxt.start())
    close = text.find("\n  ]", start)
    if close != -1:
        ends.append(close)
    end = min(ends) if ends else min(len(text), start + 2000)
    return text[start:end]


def row_action_overflows(text: str) -> list[str]:
    """返回超过 3 个一级动作的操作列片段，用于报错定位。"""
    hits: list[str] = []
    for match in ACTIONS_COLUMN.finditer(text):
        block = actions_block(text, match.start())
        count = len(ACTION_TAG.findall(block))
        if count > MAX_ROW_ACTIONS:
            first = next((line.strip() for line in block.splitlines() if "<" in line), "")
            hits.append(f"{count} 个动作：{first[:60]}…")
    return hits

RULE_HINT = {
    "COLOR_LITERAL": "颜色只能来自 web/src/ui/tokens.ts 或 web/src/styles/tokens.css",
    "INLINE_STYLE": "内联 style 只写布局（flex/grid/width/margin），颜色/字号/圆角走令牌或组件",
    "ANTD_DIRECT": "页面请从 '../ui' 引组件，不要直接 import antd",
    "LEGACY_BTN_CLASS": "用 <Button variant=...>，不要手写 className=\"btn\"",
    "EMOJI_ICON": "图标用 @ant-design/icons（经 ui/ 引出），不要用 emoji",
    "RAW_TABLE": "列表表格用 ui 的 DataTable；页面不再新写 <table>（T441）",
    "MUTED_FAILURE": "错误/警告不能用灰字：改用 <InlineNote tone=\"error|warning\"> 或 <Alert type=\"error|warning\">"
                   "（决策 026 / AC-70.2）",
    "ROW_ACTION_OVERFLOW": "操作列最多 3 个一级动作（AC-25.1）：留 1 个主操作，其余收进 <RowActions items> 的「⋯」",
}


def muted_failures(text: str) -> list[str]:
    """返回「灰字容器里放错误表达式」的片段，用于报错定位。"""
    hits: list[str] = []
    for match in JSX_EXPR.finditer(text):
        ident = match.group(1)
        if not FAILURE_IDENT.fullmatch(ident.split(".")[0]) and not FAILURE_IDENT.fullmatch(ident):
            continue
        start = text.rfind("<", 0, match.start())
        if start < 0:
            continue
        tag = text[start:match.start()]
        if "className" not in tag:
            continue
        if "muted" not in tag and "inline-note" not in tag:
            continue
        if TONED_CONTAINER.search(tag) or "InlineNote" in tag:
            continue
        hits.append(f"{tag.strip()[:60]}…{{{ident}}}")
    return hits



def is_ui_layer(rel: str) -> bool:
    return rel.startswith("web/src/ui/")


def scan() -> dict[str, dict[str, int]]:
    """返回 {相对路径: {规则: 次数}}。"""
    counts: dict[str, dict[str, int]] = collections.defaultdict(lambda: collections.defaultdict(int))
    for path in WEB.rglob("*"):
        if path.suffix not in {".ts", ".tsx", ".css"} or not path.is_file():
            continue
        rel = path.relative_to(ROOT).as_posix()
        # 所有文本规则都基于「剥掉注释」的文本：注释里的示例不是真实代码。
        text = strip_comments(path.read_text(encoding="utf-8"))

        if rel not in COLOR_TOKEN_FILES:
            counts[rel]["COLOR_LITERAL"] += len(COLOR_LITERAL.findall(text))

        if path.suffix in {".ts", ".tsx"}:
            for body in INLINE_STYLE.findall(text):
                counts[rel]["INLINE_STYLE"] += len(INLINE_COLOR_KEY.findall(body))
            if path.suffix == ".tsx":
                counts[rel]["MUTED_FAILURE"] += len(muted_failures(text))
                counts[rel]["ROW_ACTION_OVERFLOW"] += len(row_action_overflows(text))
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
        if rule == "MUTED_FAILURE":
            # 这条规则最需要看到具体那一行，否则不知道改哪。
            text = (ROOT / path).read_text(encoding="utf-8")
            for snippet in muted_failures(text)[:3]:
                print(f"    命中：{snippet}")
        if rule == "ROW_ACTION_OVERFLOW":
            text = (ROOT / path).read_text(encoding="utf-8")
            for snippet in row_action_overflows(text)[:3]:
                print(f"    命中：{snippet}")
    print(f"\n共 {len(offenders)} 处新增。改完再跑一次；确实要放宽请改基线文件并在 PR 里说明理由。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
