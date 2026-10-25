#!/usr/bin/env python3
"""把真实试卷 Markdown 抽成 mockup 用的数据结构 -> data.js。

为什么单独写脚本而不是手抄：本仓库的教训是「原型数据与真实数据不符」最容易被一眼看穿。
这里只做**抽取**，不做美化：
  - 试卷标题 / 命题人 / 审题人 / 总分 直接取原文头部；
  - 题干、选项、答案、分值全部来自原文（`english-grade10-2026-with-answers.md`）；
  - 材料型题目（七选五 36-40、完形 41-55、语法填空 56-65）按 AC-4.16 把材料并入题干、
    空位按出现顺序标成 `___(题号)___`——与后端 PaperTextParser 的输出形状一致，
    但**不是**调用生产解析器（生产解析器有 77 条自己的单测）；
  - 题型按后端 fillSection 的同一条规则推断（有选项 + 单字母答案 = 单选，多字母 = 多选，
    无选项 = 填空，写作 = ESSAY）。

用法：python3 build-data.py   （产物 data.js 与 index.html 同目录，file:// 直接双击可开）
"""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / "teachloop-paper/src/test/resources/exam/english-grade10-2026-with-answers.md"
OUT = Path(__file__).resolve().parent / "data.js"

ANSWER_HEADING = re.compile(r"参考答案|标准答案|正确答案|Answer", re.I)
NUMBERED = re.compile(r"^(\d{1,3})\s*[.、)）]\s*(.*)$")
OPTION_LINE = re.compile(r"^([A-G])\s*[.、]\s*(.+)$")
RANGE_ANSWER = re.compile(r"(\d{1,3})\s*[-–]\s*(\d{1,3})\s*[:：]\s*([A-G](?:\s+[A-G])*)")
PAIR_ANSWER = re.compile(r"(\d{1,3})\s*[.、)）]\s*([^\s].*?)(?=\s+\d{1,3}\s*[.、)）]|$)")
BLANK = re.compile(r"_{2,}")
SECTION = re.compile(r"^##\s+(.+)$")
SUBSECTION = re.compile(r"^#{3,4}\s+(.+)$")
PER_ITEM_POINTS = re.compile(r"每(?:小)?题\s*(\d+(?:\.\d+)?)\s*分")


def read_lines() -> list[str]:
    return SOURCE.read_text(encoding="utf-8").splitlines()


def split_body_answers(lines: list[str]) -> tuple[list[str], list[str]]:
    for index, line in enumerate(lines):
        if index > len(lines) * 0.6 and ANSWER_HEADING.search(line) and line.lstrip().startswith("#"):
            return lines[:index], lines[index:]
    raise SystemExit("答案区没找到：原文结构与预期不符，先核对 fixture")


def parse_answers(answer_lines: list[str]) -> dict[int, str]:
    answers: dict[int, str] = {}
    for raw in answer_lines:
        line = raw.strip().replace("**", "")
        if not line or line.startswith("#") or not re.search(r"\d", line):
            continue
        for start, end, letters in RANGE_ANSWER.findall(line):
            values = letters.split()
            if len(values) != int(end) - int(start) + 1:
                continue
            for offset, letter in enumerate(values):
                answers[int(start) + offset] = letter
        for number, text in PAIR_ANSWER.findall(line):
            answers.setdefault(int(number), text.strip())
    return answers


def region(body: list[str], heading_pattern: str, stop_at_part: bool,
           after_part: str | None = None) -> list[str]:
    """取某个小节/部分的正文行；`stop_at_part` 表示到下一个 `##` 为止，否则到下一个标题。

    `after_part` 用来消歧：听力第一节也写「共 5 小题」，七选五必须从「第二部分」之后开始找。
    """
    begin = 0
    if after_part:
        begin = next(
            (index for index, line in enumerate(body) if SECTION.match(line) and re.search(after_part, line)),
            None,
        )
        if begin is None:
            raise SystemExit(f"没找到部分：{after_part}")
    start = None
    for index in range(begin, len(body)):
        line = body[index]
        if SUBSECTION.match(line) and re.search(heading_pattern, line):
            start = index + 1
            break
    if start is None:
        raise SystemExit(f"没找到小节：{heading_pattern}")
    stop = start
    while stop < len(body):
        line = body[stop]
        if SECTION.match(line) or (not stop_at_part and SUBSECTION.match(line)):
            break
        stop += 1
    return body[start:stop]


def paragraphs(lines: list[str]) -> list[str]:
    blocks, current = [], []
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#") or set(line) <= set("-—"):
            if current:
                blocks.append(" ".join(current))
                current = []
            continue
        current.append(line)
    if current:
        blocks.append(" ".join(current))
    return blocks


def blank_stems(lines: list[str], numbers: list[int]) -> dict[int, str]:
    """按空出现顺序把材料段落配给题号，空位标成 ___题号___，返回 {题号: 材料段落}。

    同一段材料里有多个空时（完形/语法填空），每个题号都拿到**整段**材料——与后端
    AC-4.16「材料并入题干」一致，单题取出即可作答。
    """
    blocks = paragraphs(lines)
    queue = list(numbers)
    stems: dict[int, str] = {}
    for block in blocks:
        if not queue:
            break
        marked, cursor, owned = "", 0, []
        for match in BLANK.finditer(block):
            if not queue:
                break
            number = queue.pop(0)
            owned.append(number)
            marked += block[cursor:match.start()] + f"___{number}___"
            cursor = match.end()
        if owned:
            text = marked + block[cursor:]
            for number in owned:
                stems[number] = text
    if queue:
        raise SystemExit(f"空位不够：还缺题号 {queue}")
    return stems


def numbered_items(lines: list[str]) -> tuple[dict[int, str], dict[int, list[str]], dict[int, list[str]]]:
    """返回 (题干, 每题的选项, 只剩选项行的题目)。"""
    stems: dict[int, str] = {}
    options: dict[int, list[str]] = {}
    options_only: dict[int, list[str]] = {}
    index = 0
    while index < len(lines):
        raw = lines[index].strip()
        match = NUMBERED.match(raw)
        if not match:
            index += 1
            continue
        number, text = int(match.group(1)), match.group(2).strip()
        collected: list[str] = []
        cursor = index + 1
        while cursor < len(lines):
            option = OPTION_LINE.match(lines[cursor].strip())
            if not option:
                break
            collected.append(f"{option.group(1)}. {option.group(2).strip()}")
            cursor += 1
        if re.match(r"^[A-G]\s*[.、]", text):
            # `41. A. old  B. famous  C. different  D. fresh`：这一行是选项，不是题干
            parts = re.split(r"\s+(?=[A-G]\s*[.、])", " ".join([text] + lines[index + 1:index + 1 + len(collected)]).strip())
            options_only[number] = [part.strip() for part in parts if part.strip()]
        else:
            if not collected and cursor > index + 1 and re.match(r"^A\s*[.、]", lines[index + 1].strip()):
                inline = lines[index + 1].strip()
                collected = [part.strip() for part in re.split(r"\s+(?=[A-G]\s*[.、])", inline) if part.strip()]
                cursor = index + 2
            stems[number] = text
            options[number] = collected
        index = cursor
    return stems, options, options_only


def shared_options(lines: list[str]) -> list[str]:
    found = []
    for raw in lines:
        match = OPTION_LINE.match(raw.strip())
        if match:
            found.append(f"{match.group(1)}. {match.group(2).strip()}")
    return found


def section_meta(body: list[str]) -> list[dict]:
    """部分标题 + 首个「每小题 X 分」。"""
    metas: list[dict] = []
    current = None
    for line in body:
        part = SECTION.match(line)
        if part:
            current = {"title": part.group(1).strip(), "pointsPerItem": None}
            metas.append(current)
            continue
        sub = SUBSECTION.match(line)
        if sub and current is not None and current["pointsPerItem"] is None:
            hint = PER_ITEM_POINTS.search(sub.group(1).replace(" ", ""))
            if hint:
                current["pointsPerItem"] = float(hint.group(1))
    return metas


def main() -> None:
    lines = read_lines()
    title = next(line.lstrip("# ").strip() for line in lines if line.startswith("# "))
    header = next(line for line in lines if "命题人" in line)
    author = re.search(r"命题人[：:]\s*\*{0,2}\s*([^\s*|]+)", header).group(1)
    reviewer = re.search(r"审题人[：:]\s*\*{0,2}\s*([^\s*|]+)", header).group(1)
    target = float(re.search(r"总分[：:]\s*\*{0,2}\s*(\d+(?:\.\d+)?)", header).group(1))

    body, answer_lines = split_body_answers(lines)
    answers = parse_answers(answer_lines)
    # 写作部分里的 `1. 社团简介；` 是内容要点，不是新题（AC-4.21③），客观题解析必须止于写作部分之前
    written_start = next(
        (index for index, line in enumerate(body)
         if SECTION.match(line) and re.search(r"写作|作文|应用文|口语|演讲", line)),
        len(body),
    )
    per_item_stem, per_item_options, options_only = numbered_items(body[:written_start])
    metas = section_meta(body)

    # 听力 1-20 / 阅读 21-35 / 单句语法填空 66-75：题号自带题干
    stems: dict[int, str] = {number: text for number, text in per_item_stem.items()}
    options: dict[int, list[str]] = {number: opts for number, opts in per_item_options.items()}

    # 七选五 36-40：材料段落 + 共用 A-G 选项
    seven_region = region(body, r"共\s*5\s*小题", stop_at_part=True, after_part=r"第二部分")
    seven_stems = blank_stems(seven_region, list(range(36, 41)))
    seven_options = shared_options(seven_region)
    for number, stem in seven_stems.items():
        stems[number] = stem
        options[number] = seven_options

    # 完形 41-55：材料段落 + 每题自己的选项（选项行来自 `41. A. old …`）
    cloze_region = region(body, r"完形填空", stop_at_part=False)
    for number, stem in blank_stems(cloze_region, list(range(41, 56))).items():
        stems[number] = stem
        options[number] = options_only.get(number, [])

    # 语法填空 56-65：材料段落，无选项（答案是文本）
    grammar_region = region(body, r"语法填空（共\s*10\s*小题", stop_at_part=False)
    for number, stem in blank_stems(grammar_region, list(range(56, 66))).items():
        stems[number] = stem
        options[number] = []

    # 写作 76-77：一节一题（AC-4.21②）
    essays = []
    for index, line in enumerate(body):
        sub = SUBSECTION.match(line)
        if not sub or "满分" not in line or not re.search(r"写作|作文|应用文|口语|演讲", line):
            continue
        chunk, cursor = [], index + 1
        while cursor < len(body) and not SECTION.match(body[cursor]):
            if SUBSECTION.match(body[cursor]):
                break
            chunk.append(body[cursor].strip())
            cursor += 1
        essays.append(
            {
                "label": sub.group(1).strip(),
                "text": "\n".join(entry for entry in chunk if entry and not set(entry) <= set("-—")),
                "points": float(re.search(r"满分\s*(\d+(?:\.\d+)?)\s*分", line).group(1)),
            }
        )

    rows = []
    for number in sorted(stems):
        if number not in answers and not options[number]:
            continue
        answer = answers.get(number, "")
        kind = (
            "FILL_BLANK"
            if not options[number]
            else ("MULTIPLE_CHOICE" if len(answer.replace(" ", "")) > 1 else "SINGLE_CHOICE")
        )
        rows.append(
            {
                "number": number,
                "type": kind,
                "points": 0.0,
                "stem": stems[number],
                "options": options[number],
                "answer": answer,
            }
        )

    essay_rows, next_number = [], (max(row["number"] for row in rows) + 1)
    for essay in essays:
        essay_rows.append(
            {
                "number": next_number,
                "type": "ESSAY",
                "points": essay["points"],
                "stem": essay["label"] + "\n" + essay["text"],
                "options": [],
                "answer": "",
            }
        )
        next_number += 1
    rows.extend(essay_rows)

    # 分值：按题号区间取小节标题里的「每小题 X 分」；写作题取「满分 X 分」
    bands = [(1, 20, 1.5), (21, 40, 2.5), (41, 55, 1.0), (56, 65, 1.5), (66, 75, 1.0)]
    for row in rows:
        if row["type"] == "ESSAY":
            continue
        row["points"] = next(points for low, high, points in bands if low <= row["number"] <= high)

    total = sum(row["points"] for row in rows)
    if abs(total - target) > 0.001:
        raise SystemExit(f"抽出来的合计 {total} 与卷面总分 {target} 不一致，先核对")

    section_defs = [
        {"title": metas[0]["title"], "pointsPerItem": 1.5, "from": 1, "to": 20},
        {"title": metas[1]["title"], "pointsPerItem": 2.5, "from": 21, "to": 40},
        {"title": metas[2]["title"], "pointsPerItem": 1.0, "from": 41, "to": 75},
        {"title": metas[3]["title"], "pointsPerItem": 15.0, "from": 76, "to": next_number - 1},
    ]
    payload = {
        "paper": {
            "title": title,
            "authorName": author,
            "reviewerName": reviewer,
            "targetTotalPoints": target,
            "durationMinutes": 120,
            "itemCount": len(rows),
            "itemsTotal": total,
            "missingAnswers": [row["number"] for row in rows if row["type"] != "ESSAY" and not row["answer"]],
            "typeCounts": {
                kind: sum(1 for row in rows if row["type"] == kind)
                for kind in ("SINGLE_CHOICE", "MULTIPLE_CHOICE", "FILL_BLANK", "ESSAY")
            },
        },
        "sections": [
            {
                **section,
                "items": [row for row in rows if section["from"] <= row["number"] <= section["to"]],
            }
            for section in section_defs
        ],
        "editorText": "\n".join(lines[:78]).rstrip() + "\n…（下略，原文共 %d 行）" % len(lines),
        "source": SOURCE.relative_to(ROOT).as_posix(),
        "sourceLines": len(lines),
    }
    OUT.write_text(
        "// 由 build-data.py 从真实试卷抽取，勿手改。源："
        + payload["source"]
        + "\nwindow.PAPER = "
        + json.dumps(payload, ensure_ascii=False, indent=1)
        + ";\n",
        encoding="utf-8",
    )
    print(f"{OUT.name}: {len(rows)} 题 / 合计 {total} 分 / 缺答案 {len(payload['paper']['missingAnswers'])} 题")
    print("部分：" + "；".join(f"{s['title']}（{len(s['items'])} 题）" for s in payload["sections"]))
    print("题型：" + json.dumps(payload["paper"]["typeCounts"], ensure_ascii=False))
    print(f"命题人 {author} / 审题人 {reviewer} / 目标总分 {target}")


if __name__ == "__main__":
    main()
