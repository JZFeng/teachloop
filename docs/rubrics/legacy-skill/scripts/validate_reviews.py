#!/usr/bin/env python3
"""Validate generated review files before the teacher reads them.

Checks structure, binary scoring, arithmetic, coverage, and the class summary.
With --manifest (from collect_submissions.py) it also cross-checks the
"Required Length" score against the counted paragraph count and escalates
boundary-sensitive submissions instead of silently trusting either side.

This script never edits reviews. It reports problems so the agent can fix them
and the teacher can audit.

Exit codes:
    0 = all checks passed
    1 = errors found (reviews must be fixed before delivery)
    2 = bad usage / missing inputs

Usage:
    python3 validate_reviews.py --submissions ./Submissions --reviews ./reviews
    python3 validate_reviews.py --submissions ./Submissions --reviews ./reviews \\
        --manifest ./_grading/submissions.json --json report.json
"""

from __future__ import annotations

import argparse
import json
import re
import statistics
import sys
from pathlib import Path

CRITERIA = [
    ("Required Length", 2),
    ("Clear Position", 4),
    ("Explanation and Supporting Details", 4),
]

CRITERION_RE = re.compile(
    r"^\s*(\d)\s*[.)]\s*(.+?)\s*:\s*(\d+)\s*/\s*(\d+)\s*$", re.MULTILINE
)
STUDENT_RE = re.compile(r"^\s*Student\s*:\s*(.*)$", re.IGNORECASE | re.MULTILINE)
TOTAL_RE = re.compile(
    r"^\s*Total\s+Score\s*:\s*(\d+)\s*/\s*(\d+)\s*$", re.IGNORECASE | re.MULTILINE
)
AVG_RE = re.compile(
    r"Average\s+score\s*:?\s*\**\s*([0-9]+(?:\.[0-9]+)?)", re.IGNORECASE
)
COUNT_RE = re.compile(
    r"Number\s+of\s+submissions\s+reviewed\s*:?\s*\**\s*(\d+)", re.IGNORECASE
)
PLACEHOLDER_RE = re.compile(r"^\s*\[.*\]\s*$")

REQUIRED_SECTIONS = ("Strength", "Suggestion")


def norm_label(text: str) -> str:
    return re.sub(r"[^a-z]", "", text.lower())


def strip_md(text: str) -> str:
    return re.sub(r"[*_`#]", "", text).strip()


def section_body(text: str, heading: str) -> str | None:
    """Return the text after `heading:` up to the next heading or blank-heavy stop."""
    pattern = re.compile(
        rf"^\s*\**\s*{re.escape(heading)}\s*\**\s*:?\s*$", re.IGNORECASE | re.MULTILINE
    )
    m = pattern.search(text)
    if not m:
        # allow inline form "Strength: something"
        inline = re.compile(
            rf"^\s*\**\s*{re.escape(heading)}\s*\**\s*:\s*(.+)$",
            re.IGNORECASE | re.MULTILINE,
        )
        mi = inline.search(text)
        return mi.group(1).strip() if mi else None
    rest = text[m.end():]
    stop = re.search(
        r"^\s*\**(Rubric|Total\s+Score|Strength|Suggestion|Student)\s*\**\s*:",
        rest,
        re.IGNORECASE | re.MULTILINE,
    )
    body = rest[: stop.start()] if stop else rest
    return body.strip()


def parse_review(path: Path) -> dict:
    text = path.read_text(encoding="utf-8", errors="replace")
    text = text.replace("\r\n", "\n")
    data: dict = {"file": path.name, "errors": [], "warnings": [], "criteria": {}}

    m = STUDENT_RE.search(text)
    student = strip_md(m.group(1)) if m else ""
    data["student"] = student
    if not student:
        data["errors"].append("missing or empty 'Student:' line")
    elif PLACEHOLDER_RE.match(student) or student.lower() in {"[student name]", "name"}:
        data["errors"].append(f"'Student:' still a placeholder: {student!r}")

    for num, (name, mx) in enumerate(CRITERIA, start=1):
        found = None
        for cm in CRITERION_RE.finditer(text):
            label = norm_label(cm.group(2))
            if norm_label(name) in label or label in norm_label(name):
                found = cm
                break
        if not found:
            data["errors"].append(f"missing criterion line for '{name}'")
            continue
        score, maxv = int(found.group(3)), int(found.group(4))
        data["criteria"][name] = {"score": score, "max": maxv, "expected_max": mx}
        if maxv != mx:
            data["errors"].append(
                f"'{name}' max is {maxv}, rubric says {mx} (possible rubric drift)"
            )
        if score not in (0, maxv):
            data["warnings"].append(
                f"'{name}' is {score}/{maxv} but this rubric is binary "
                f"(0 or {maxv}); confirm with the teacher"
            )

        # Reason lines follow their criterion; capture the contiguous Reason block.
        after = text[found.end():]
        rm = re.search(r"^\s*Reason\s*:\s*(.+?)(?=\n\s*\n|\n\s*\d\s*[.)]|\Z)",
                       after, re.IGNORECASE | re.MULTILINE | re.DOTALL)
        reason = strip_md(rm.group(1)) if rm else ""
        data["criteria"][name]["reason"] = reason
        if not reason:
            data["errors"].append(f"criterion '{name}' has no Reason")
        elif PLACEHOLDER_RE.match(reason):
            data["errors"].append(f"criterion '{name}' Reason is still a placeholder")

    tm = TOTAL_RE.search(text)
    if not tm:
        data["errors"].append("missing 'Total Score: X/Y' line")
    else:
        data["total"], data["total_max"] = int(tm.group(1)), int(tm.group(2))
        if data["criteria"]:
            s = sum(c["score"] for c in data["criteria"].values())
            if s != data["total"]:
                data["errors"].append(
                    f"Total Score {data['total']} does not equal the sum of "
                    f"criteria ({s})"
                )
            emax = sum(c["expected_max"] for c in data["criteria"].values())
            if data["total_max"] != emax:
                data["errors"].append(
                    f"Total max is {data['total_max']}, rubric total is {emax}"
                )
        if data["total"] > data["total_max"]:
            data["errors"].append("Total Score exceeds the maximum")

    for heading in REQUIRED_SECTIONS:
        body = section_body(text, heading)
        if body is None:
            data["errors"].append(f"missing '{heading}:' section")
            continue
        body = strip_md(body)
        data[heading.lower()] = body
        if not body:
            data["errors"].append(f"'{heading}:' section is empty")
        elif PLACEHOLDER_RE.match(body):
            data["errors"].append(f"'{heading}:' is still a placeholder")

    return data


def check_summary(path: Path, reviews: list[dict], expected_max: int) -> dict:
    res: dict = {"file": path.name, "errors": [], "warnings": []}
    text = strip_md(path.read_text(encoding="utf-8", errors="replace"))
    res["text"] = text

    cm = COUNT_RE.search(text)
    if not cm:
        res["errors"].append("class summary missing 'Number of submissions reviewed'")
    else:
        res["count"] = int(cm.group(1))
        if res["count"] != len(reviews):
            res["errors"].append(
                f"summary says {res['count']} submissions reviewed, "
                f"but {len(reviews)} review files exist"
            )

    am = AVG_RE.search(text)
    scored = [r["total"] for r in reviews if "total" in r]
    if not am:
        res["errors"].append("class summary missing an average score")
    else:
        res["average"] = float(am.group(1))
        if scored:
            actual = round(statistics.fmean(scored), 2)
            res["computed_average"] = actual
            if abs(res["average"] - actual) > 0.05:
                res["errors"].append(
                    f"summary average {res['average']} does not match computed "
                    f"average {actual}"
                )

    lower = text.lower()
    for label, needles in {
        "common strengths": ["common strength"],
        "common weaknesses": ["common weakness"],
        "teaching points": ["teaching point"],
    }.items():
        if not any(n in lower for n in needles):
            res["errors"].append(f"class summary missing '{label}' section")

    tp = re.search(r"teaching\s+points?[^\n]*\n(.*?)(?:\Z|\n\s*\n\s*[A-Z])",
                   text, re.IGNORECASE | re.DOTALL)
    if tp:
        items = re.findall(r"^\s*\d+\s*[.)]", tp.group(1), re.MULTILINE)
        res["teaching_point_count"] = len(items)
        if len(items) != 3:
            res["warnings"].append(
                f"expected 3 teaching points, found {len(items)}"
            )

    if reviews:
        dist: dict[int, int] = {}
        for r in reviews:
            if "total" in r:
                dist[r["total"]] = dist.get(r["total"], 0) + 1
        res["score_distribution"] = dict(sorted(dist.items(), reverse=True))
        res["max_possible"] = expected_max
    return res


def main() -> int:
    ap = argparse.ArgumentParser(description="Validate batch-grading output.")
    ap.add_argument("--submissions", required=True)
    ap.add_argument("--reviews", required=True)
    ap.add_argument("--manifest", help="submissions.json from collect_submissions.py")
    ap.add_argument("--summary-name", default="class_summary.txt")
    ap.add_argument("--json", help="Write a machine-readable report here")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    subs_dir = Path(args.submissions).expanduser().resolve()
    rev_dir = Path(args.reviews).expanduser().resolve()
    if not subs_dir.is_dir():
        print(f"ERROR: submissions folder not found: {subs_dir}", file=sys.stderr)
        return 2
    if not rev_dir.is_dir():
        print(f"ERROR: reviews folder not found: {rev_dir}", file=sys.stderr)
        return 2

    expected_max = sum(mx for _, mx in CRITERIA)

    sub_stems = {
        p.stem
        for p in subs_dir.rglob("*")
        if p.is_file() and not p.name.startswith(".") and p.name != ".DS_Store"
    }
    review_files = sorted(
        p for p in rev_dir.glob("*_review.txt") if p.is_file()
    )
    summary_path = rev_dir / args.summary_name

    manifest = None
    if args.manifest:
        mp = Path(args.manifest).expanduser().resolve()
        if mp.is_file():
            manifest = json.loads(mp.read_text(encoding="utf-8"))
        else:
            print(f"WARNING: manifest not found, skipping length cross-check: {mp}",
                  file=sys.stderr)

    report: dict = {"errors": [], "warnings": [], "reviews": []}

    if not review_files:
        report["errors"].append(f"no '*_review.txt' files found in {rev_dir}")

    parsed = [parse_review(p) for p in review_files]

    # Coverage: every submission has a review, and every review has a submission.
    reviewed_stems = {p.name[: -len("_review.txt")] for p in review_files}
    for stem in sorted(sub_stems - reviewed_stems):
        report["errors"].append(f"no review file for submission '{stem}'")
    for stem in sorted(reviewed_stems - sub_stems):
        report["errors"].append(
            f"review '{stem}_review.txt' has no matching submission"
        )

    # Length cross-check against the manifest.
    boundary_students: list[str] = []
    if manifest:
        recs = {r["relpath"]: r for r in manifest.get("records", [])}
        by_stem = {Path(k).stem: v for k, v in recs.items()}
        model = manifest.get("length_policy", {}).get("model", "policy_default")
        lo, hi = manifest.get("length_policy", {}).get("in_range", [1, 4])
        for r in parsed:
            stem = r["file"][: -len("_review.txt")]
            rec = by_stem.get(stem)
            if not rec or "paragraphs" not in rec:
                continue
            n = rec["paragraphs"]
            in_range = lo <= n <= hi
            length = r["criteria"].get("Required Length")
            if not length:
                continue
            if rec.get("boundary_sensitive"):
                boundary_students.append(stem)
                r["warnings"].append(
                    f"boundary-sensitive length: models disagree "
                    f"{rec['paragraphs_by_model']}; review recorded "
                    f"{length['score']}/{length['max']}"
                )
            scored_full = length["score"] == length["max"]
            if in_range and not scored_full:
                r["warnings"].append(
                    f"counted {n} paragraphs (in range {lo}-{hi}) but "
                    f"'Required Length' was scored {length['score']}/"
                    f"{length['max']} - verify"
                )
            if not in_range and scored_full:
                r["warnings"].append(
                    f"counted {n} paragraphs (outside range {lo}-{hi}) but "
                    f"'Required Length' was scored {length['score']}/"
                    f"{length['max']} - verify"
                )
        report["length_model"] = model

    for r in parsed:
        report["errors"].extend(f"{r['file']}: {e}" for e in r["errors"])
        report["warnings"].extend(f"{r['file']}: {w}" for w in r["warnings"])
        report["reviews"].append(
            {
                "file": r["file"],
                "student": r.get("student", ""),
                "total": r.get("total"),
                "max": r.get("total_max"),
                "criteria": {
                    k: {"score": v["score"], "max": v["max"]}
                    for k, v in r["criteria"].items()
                },
                "errors": r["errors"],
                "warnings": r["warnings"],
            }
        )

    if summary_path.is_file():
        summary = check_summary(summary_path, parsed, expected_max)
        report["summary"] = {
            k: v for k, v in summary.items() if k != "text"
        }
        report["errors"].extend(f"{summary['file']}: {e}" for e in summary["errors"])
        report["warnings"].extend(
            f"{summary['file']}: {w}" for w in summary["warnings"]
        )
    else:
        report["errors"].append(f"missing class summary: {summary_path}")

    if not args.quiet:
        print(f"Submissions: {len(sub_stems)} file(s) in {subs_dir}")
        print(f"Reviews:     {len(parsed)} file(s) in {rev_dir}")
        print(f"Summary:     {'found' if summary_path.is_file() else 'MISSING'}")
        if manifest:
            print(f"Length model: {report.get('length_model')}")
        print()

        width = max([len(r["file"]) for r in parsed] + [9]) + 2
        print(f"{'REVIEW':<{width}}{'STUDENT':<14}{'SCORE':>7}  STATUS")
        print("-" * (width + 30))
        for r in parsed:
            score = f"{r.get('total', '?')}/{r.get('total_max', '?')}"
            flags = []
            if r["errors"]:
                flags.append(f"{len(r['errors'])} error(s)")
            if r["warnings"]:
                flags.append(f"{len(r['warnings'])} warning(s)")
            print(f"{r['file']:<{width}}{r.get('student', ''):<14}{score:>7}  "
                  f"{', '.join(flags) or 'ok'}")

        s = report.get("summary")
        if s:
            print(f"\nClass summary: count={s.get('count')} "
                  f"average={s.get('average')} "
                  f"(computed {s.get('computed_average')})")
            if s.get("score_distribution"):
                print(f"  Distribution: {s['score_distribution']} "
                      f"out of {s.get('max_possible')}")

        if boundary_students:
            print(f"\n[TEACHER REVIEW] Boundary-sensitive length scoring: "
                  f"{', '.join(sorted(boundary_students))}")
            print("  Paragraph count depends on the counting model. Confirm the "
                  "length score for these before returning feedback.")

        if report["warnings"]:
            print(f"\nWARNINGS ({len(report['warnings'])})")
            for w in report["warnings"]:
                print(f"  ! {w}")

        if report["errors"]:
            print(f"\nERRORS ({len(report['errors'])})")
            for e in report["errors"]:
                print(f"  x {e}")
        else:
            print("\nAll structural checks passed.")

    report["ok"] = not report["errors"]
    if args.json:
        jp = Path(args.json).expanduser().resolve()
        jp.parent.mkdir(parents=True, exist_ok=True)
        jp.write_text(json.dumps(report, indent=2, ensure_ascii=False),
                      encoding="utf-8")

    if report["errors"]:
        if not args.quiet:
            print(f"\nRESULT: FAIL - {len(report['errors'])} error(s). "
                  f"Fix them and re-run before delivery.")
        return 1
    if not args.quiet:
        print("\nRESULT: PASS - ready for teacher review.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
