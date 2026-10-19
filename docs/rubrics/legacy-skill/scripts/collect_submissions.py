#!/usr/bin/env python3
"""Inventory a Submissions folder before batch grading.

Reads every submission once, reports a machine-auditable table, and (optionally)
writes a JSON manifest that later steps can consume. Read-only: never writes
inside the Submissions folder.

Usage:
    python3 collect_submissions.py --submissions ./Submissions
    python3 collect_submissions.py --submissions ./Submissions \\
        --extract-to ./_extracted --manifest ./_grading/submissions.json

Length counting follows references/LENGTH_COUNTING_POLICY.md.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import zipfile
from pathlib import Path

TEXT_EXT = {".txt", ".md", ".text", ".rtf"}
DOCX_EXT = {".docx"}
PDF_EXT = {".pdf"}
VIDEO_EXT = {".mp4", ".mov", ".m4v", ".avi", ".mkv", ".webm"}
AUDIO_EXT = {".mp3", ".m4a", ".wav", ".aac", ".ogg"}
IMAGE_EXT = {".jpg", ".jpeg", ".png", ".heic", ".webp"}

GREETING_RE = re.compile(
    r"^\s*(hi|hello|hey|good\s+(morning|afternoon|evening)|dear)\b", re.IGNORECASE
)
SELF_INTRO_RE = re.compile(
    r"^\s*(my\s+name\s+is|i\s+am\s+[A-Z]|i'm\s+[A-Z]|this\s+is\s+[A-Z])", re.IGNORECASE
)
LIST_ITEM_RE = re.compile(r"^\s*([-*\u2022]|\(?[a-zA-Z0-9]{1,3}[.)])\s+\S")
SENTENCE_END_RE = re.compile(r"[.!?\"'\u201d\u2019)\]]\s*$")

# A "greeting/intro block" only counts as a greeting when it is short; a long
# paragraph that merely begins with "Hello" is a real paragraph.
GREETING_MAX_WORDS = 25


def normalize(raw: str) -> str:
    """Normalize line endings and non-breaking spaces without touching wording."""
    text = raw.replace("\r\n", "\n").replace("\r", "\n").replace("\u00a0", " ")
    return text


def split_blocks(text: str) -> list[str]:
    """Blank-line separated blocks (list blocks are kept whole)."""
    text = normalize(text)
    blocks = re.split(r"\n[ \t]*\n", text)
    return [b.strip() for b in blocks if b.strip()]


def is_greeting_block(block: str) -> bool:
    single_line = "\n" not in block.strip()
    words = len(block.split())
    if not single_line or words > GREETING_MAX_WORDS:
        return False
    return bool(GREETING_RE.match(block) or SELF_INTRO_RE.match(block))


def merge_list_block(block: str) -> str:
    """Merge a bulleted/numbered block into one paragraph."""
    return " ".join(line.strip() for line in block.split("\n") if line.strip())


def is_list_block(block: str) -> bool:
    """True for a bulleted/numbered block, including a single bullet item that
    was separated from its siblings by blank lines (a common paste artifact)."""
    lines = [l for l in block.split("\n") if l.strip()]
    if not lines:
        return False
    hits = sum(1 for l in lines if LIST_ITEM_RE.match(l))
    if hits == 0:
        return False
    if len(lines) == 1:
        return True
    return hits >= max(2, len(lines) // 2)


def merge_hard_wraps(block: str) -> str:
    """Join lines that are soft-wrapped mid-sentence into one paragraph.

    A newline is treated as a hard break (a real paragraph boundary) when the
    previous line ends with sentence-final punctuation AND the next line starts
    a new sentence with a capital letter. Everything else is a soft wrap.
    """
    lines = [l.strip() for l in block.split("\n") if l.strip()]
    if not lines:
        return ""
    out = [lines[0]]
    for line in lines[1:]:
        prev = out[-1]
        hard_break = bool(SENTENCE_END_RE.search(prev)) and (
            line[:1].isupper() or line[:1] in "\u201c\"'"
        )
        if hard_break:
            out.append(line)
        else:
            out[-1] = prev + " " + line
    return "\n".join(out)


def _continuation(prev: str, nxt: str) -> bool:
    """True when `nxt` looks like the same paragraph as `prev`, split by a stray
    blank line: `prev` has no sentence-final punctuation and `nxt` starts lower."""
    if not prev or not nxt:
        return False
    if SENTENCE_END_RE.search(prev):
        return False
    first = nxt.lstrip()[:1]
    return bool(first) and (first.islower() or first in "\u201c\"'([")


def count_paragraphs_model(text: str, model: str) -> tuple[int, list[str]]:
    """Count paragraphs under one named, documented counting model.

    Models exist because paragraph counting is genuinely ambiguous for
    hard-wrapped text, greeting lines, bulleted lists, and stray blank lines.
    Rather than pretend one answer is objective, the caller compares models and
    escalates the cases where the choice changes a score.
    """
    raw_blocks = split_blocks(text)
    merge_wraps = model != "strict_blocks"
    merge_greeting = model in {"policy_default", "lenient"}
    coalesce_lists = model in {"policy_default", "lenient"}
    merge_continuation = model == "lenient"
    list_block_as_one = model != "strict_blocks"

    paragraphs: list[str] = []
    pending_list: list[str] = []

    def flush_list() -> None:
        if not pending_list:
            return
        if coalesce_lists:
            paragraphs.append(" ".join(pending_list))
        else:
            paragraphs.extend(pending_list)
        pending_list.clear()

    for block in raw_blocks:
        if is_list_block(block):
            if list_block_as_one:
                pending_list.append(merge_list_block(block))
            else:
                pending_list.extend(
                    l.strip() for l in block.split("\n") if l.strip()
                )
            continue
        flush_list()
        body = merge_hard_wraps(block) if merge_wraps else block
        for piece in body.split("\n"):
            piece = piece.strip()
            if piece:
                paragraphs.append(piece)
    flush_list()

    if merge_continuation:
        merged: list[str] = []
        for piece in paragraphs:
            if merged and _continuation(merged[-1], piece):
                merged[-1] = merged[-1] + " " + piece
            else:
                merged.append(piece)
        paragraphs = merged

    if merge_greeting and len(paragraphs) > 1 and is_greeting_block(paragraphs[0]):
        paragraphs[1] = (paragraphs[0] + " " + paragraphs[1]).strip()
        paragraphs.pop(0)

    return len(paragraphs), paragraphs


MODELS = ("strict_blocks", "wraps_merged", "policy_default", "lenient")
MODEL_NOTES = {
    "strict_blocks": "each blank-line block = 1 paragraph; greeting = own paragraph",
    "wraps_merged": "soft-wrapped lines joined; greeting = own paragraph",
    "policy_default": "wraps joined + greeting merged into opening paragraph "
                      "+ bulleted list = 1 paragraph",
    "lenient": "policy_default + stray blank lines inside a sentence joined",
}


def count_paragraphs(text: str, model: str = "policy_default",
                     in_range: tuple[int, int] = (1, 4)) -> dict:
    """Auditable length metrics, including how sensitive the verdict is."""
    lo, hi = in_range
    per_model = {m: count_paragraphs_model(text, m)[0] for m in MODELS}
    paragraphs = count_paragraphs_model(text, model)[1]
    verdicts = {m: (lo <= n <= hi) for m, n in per_model.items()}
    sensitive = len(set(verdicts.values())) > 1
    default_n = per_model[model]
    return {
        "blocks": len(split_blocks(text)),
        "nonempty_lines": len([l for l in text.split("\n") if l.strip()]),
        "words": len(text.split()),
        "paragraphs": default_n,
        "paragraphs_by_model": per_model,
        "in_range": lo <= default_n <= hi,
        "boundary_sensitive": sensitive,
        "in_range_by_model": verdicts,
        "paragraph_preview": [
            (p[:70] + ("..." if len(p) > 70 else "")) for p in paragraphs
        ],
    }


def read_docx(path: Path) -> str:
    try:
        from docx import Document  # type: ignore
    except ImportError:
        return _read_docx_zip(path)
    doc = Document(str(path))
    parts = [p.text for p in doc.paragraphs]
    for table in doc.tables:
        for row in table.rows:
            parts.append(" | ".join(c.text for c in row.cells))
    return "\n\n".join(p for p in parts if p.strip())


def _read_docx_zip(path: Path) -> str:
    """Fallback: pull text out of word/document.xml with the stdlib only."""
    try:
        with zipfile.ZipFile(path) as zf:
            xml = zf.read("word/document.xml").decode("utf-8", "ignore")
    except Exception as exc:  # noqa: BLE001
        raise RuntimeError(f"cannot read docx: {exc}") from exc
    xml = re.sub(r"</w:p>", "\n\n", xml)
    xml = re.sub(r"<w:tab[^>]*/>", "\t", xml)
    text = re.sub(r"<[^>]+>", "", xml)
    text = (
        text.replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", '"')
        .replace("&apos;", "'")
    )
    return re.sub(r"\n{3,}", "\n\n", text)


def read_pdf(path: Path) -> tuple[str | None, str | None]:
    """Best-effort PDF text. Returns (text, note)."""
    try:
        from pypdf import PdfReader  # type: ignore
    except ImportError:
        try:
            from PyPDF2 import PdfReader  # type: ignore
        except ImportError:
            PdfReader = None  # type: ignore
    if PdfReader is not None:
        try:
            reader = PdfReader(str(path))
            pages = [(pg.extract_text() or "") for pg in reader.pages]
            text = "\n\n".join(pages).strip()
            if text:
                return text, None
            return None, "PDF has no extractable text layer; needs OCR or manual read"
        except Exception as exc:  # noqa: BLE001
            return None, f"PDF parse failed ({exc}); needs manual read"
    return None, "no PDF library available; convert to text or read manually"


def probe_video_duration(path: Path) -> tuple[float | None, str | None]:
    """Optional ffprobe lookup. Returns (minutes, note)."""
    import shutil
    import subprocess

    if shutil.which("ffprobe") is None:
        return None, "ffprobe not installed; check video length manually"
    try:
        out = subprocess.run(
            [
                "ffprobe", "-v", "error", "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1", str(path),
            ],
            capture_output=True, text=True, timeout=60, check=True,
        )
        return round(float(out.stdout.strip()) / 60.0, 2), None
    except Exception as exc:  # noqa: BLE001
        return None, f"ffprobe failed ({exc}); check video length manually"


def classify(path: Path) -> str:
    ext = path.suffix.lower()
    if ext in TEXT_EXT:
        return "text"
    if ext in DOCX_EXT:
        return "docx"
    if ext in PDF_EXT:
        return "pdf"
    if ext in VIDEO_EXT:
        return "video"
    if ext in AUDIO_EXT:
        return "audio"
    if ext in IMAGE_EXT:
        return "image"
    return "unsupported"


def iter_submissions(root: Path) -> list[Path]:
    files = [
        p
        for p in root.rglob("*")
        if p.is_file() and not p.name.startswith(".") and p.name != ".DS_Store"
    ]
    return sorted(files, key=lambda p: p.name.lower())


OPTIONAL_DEPS = [
    ("python-docx", "docx", "reads .docx submissions"),
    ("pypdf", "pypdf", "reads .pdf submissions with a text layer"),
    ("PyPDF2", "PyPDF2", "legacy PDF fallback"),
]


def check_deps() -> dict:
    """Report which optional readers are importable by THIS interpreter."""
    import importlib
    import platform
    import shutil

    deps: dict = {
        "interpreter": sys.executable,
        "python_version": platform.python_version(),
        "libraries": {},
    }
    for label, module, purpose in OPTIONAL_DEPS:
        entry = {"purpose": purpose, "available": False, "version": None}
        try:
            mod = importlib.import_module(module)
            entry["available"] = True
            entry["version"] = getattr(mod, "__version__", "unknown")
        except Exception as exc:  # noqa: BLE001
            entry["error"] = str(exc)
        deps["libraries"][label] = entry

    ffprobe = shutil.which("ffprobe")
    deps["ffprobe"] = {
        "purpose": "reads video/audio duration for the 1-4 minute rule",
        "available": bool(ffprobe),
        "path": ffprobe,
    }
    return deps


def print_deps(deps: dict) -> None:
    print("Optional dependency check")
    print(f"  interpreter:      {deps['interpreter']}")
    print(f"  python version:   {deps['python_version']}\n")

    rows = []
    for label, info in deps["libraries"].items():
        rows.append((label, f"OK  {info['version']}" if info["available"] else "MISSING",
                     info["purpose"], label))
    ff = deps["ffprobe"]
    rows.append(("ffprobe", f"OK  {ff['path']}" if ff["available"] else "MISSING",
                 ff["purpose"], "ffprobe"))

    label_w = max(len(r[0]) for r in rows) + 2
    status_w = max(len(r[1]) for r in rows) + 2
    for label, status, purpose, _ in rows:
        print(f"  {label:<{label_w}}{status:<{status_w}}{purpose}")

    # Only packages that actually matter are reported as missing.
    pip_missing = [label for label, info in deps["libraries"].items()
                   if not info["available"] and label != "PyPDF2"]
    ff_missing = not ff["available"]

    print()
    if not pip_missing and not ff_missing:
        print("All optional readers available for this interpreter.")
        return

    print("Missing for this interpreter: "
          + ", ".join(pip_missing + (["ffprobe"] if ff_missing else [])))
    print("Grading still runs; affected file types are reported as needing manual "
          "reading instead of being scored.")
    if pip_missing:
        print("Install the missing Python readers for exactly this interpreter:")
        print(f"  {deps['interpreter']} -m pip install {' '.join(pip_missing)}")
        print("  (if the interpreter is externally managed, add "
              "--break-system-packages, or use DSH's bundled runtime instead)")
    if ff_missing:
        print("Install ffprobe (needed only for video/audio length checks):")
        print("  brew install ffmpeg")


def main() -> int:
    ap = argparse.ArgumentParser(description="Inventory student submissions.")
    ap.add_argument("--submissions", help="Submissions folder")
    ap.add_argument("--check-deps", action="store_true",
                    help="Report optional reader availability and exit")
    ap.add_argument("--manifest", help="Write JSON manifest to this path")
    ap.add_argument("--extract-to", help="Write extracted text files here (docx/pdf)")
    ap.add_argument("--model", default="policy_default", choices=MODELS,
                    help="Paragraph-counting model (see LENGTH_COUNTING_POLICY.md)")
    ap.add_argument("--min-paragraphs", type=int, default=1)
    ap.add_argument("--max-paragraphs", type=int, default=4)
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    if args.check_deps:
        print_deps(check_deps())
        return 0

    if not args.submissions:
        print("ERROR: --submissions is required (or use --check-deps)",
              file=sys.stderr)
        return 2

    in_range = (args.min_paragraphs, args.max_paragraphs)

    root = Path(args.submissions).expanduser().resolve()
    if not root.is_dir():
        print(f"ERROR: submissions folder not found: {root}", file=sys.stderr)
        return 2

    files = iter_submissions(root)
    if not files:
        print(f"ERROR: no submission files found in {root}", file=sys.stderr)
        return 2

    records: list[dict] = []
    for path in files:
        kind = classify(path)
        rec: dict = {
            "file": path.name,
            "relpath": str(path.relative_to(root)),
            "kind": kind,
            "bytes": path.stat().st_size,
            "warnings": [],
        }

        if kind in {"text", "docx", "pdf"}:
            if kind == "text":
                text = normalize(path.read_text(encoding="utf-8", errors="replace"))
            elif kind == "docx":
                try:
                    text = read_docx(path)
                except Exception as exc:  # noqa: BLE001
                    text = None  # type: ignore[assignment]
                    rec["warnings"].append(f"docx read failed: {exc}")
            else:
                text, note = read_pdf(path)
                if note:
                    rec["warnings"].append(note)
            if text:
                rec.update(count_paragraphs(text, args.model, in_range))
                if args.extract_to:
                    out_dir = Path(args.extract_to).expanduser().resolve()
                    out_dir.mkdir(parents=True, exist_ok=True)
                    (out_dir / f"{path.stem}.txt").write_text(text, encoding="utf-8")
        elif kind in {"video", "audio"}:
            minutes, note = probe_video_duration(path)
            rec["duration_minutes"] = minutes
            if note:
                rec["warnings"].append(note)
        elif kind == "image":
            rec["warnings"].append("image submission; needs manual read or OCR")
        else:
            rec["warnings"].append(f"unsupported extension {path.suffix!r}")

        records.append(rec)

    if not args.quiet:
        width = max(len(r["file"]) for r in records) + 2
        print(f"Submissions folder: {root}")
        print(f"Files found: {len(records)}")
        print(f"Counting model: {args.model} | length range: "
              f"{in_range[0]}-{in_range[1]} paragraphs\n")
        header = (f"{'FILE':<{width}}{'TYPE':<8}{'WORDS':>6}"
                  f"{'STRICT':>8}{'WRAPS':>7}{'POLICY':>8}{'LENIENT':>9}  "
                  f"{'VERDICT':<10}NOTE")
        print(header)
        print("-" * len(header))
        for r in records:
            note = "; ".join(r["warnings"])
            by_model = r.get("paragraphs_by_model", {})
            if by_model:
                cells = "".join(f"{by_model.get(m, '-'):>8}" if m != "wraps_merged"
                                else f"{by_model.get(m, '-'):>7}"
                                for m in MODELS)
            else:
                cells = f"{'-':>8}{'-':>7}{'-':>8}{'-':>9}"
            if "paragraphs" in r:
                verdict = "in range" if r["in_range"] else "OUT OF RANGE"
                if r["boundary_sensitive"]:
                    verdict = "BOUNDARY"
            elif r.get("duration_minutes") is not None:
                mins = r["duration_minutes"]
                ok = 1 <= mins <= 4
                verdict = "in range" if ok else "OUT OF RANGE"
                note = (f"duration {mins} min; length judged on minutes"
                        + (f"; {note}" if note else ""))
            else:
                verdict = "-"
            print(f"{r['file']:<{width}}{r['kind']:<8}{r.get('words', '-'):>6}"
                  f"{cells}  {verdict:<10}{note}")

        para_known = [r for r in records if "paragraphs" in r]
        video_known = [r for r in records if r.get("duration_minutes") is not None]
        out_of_range = [f"{r['file']} ({r['paragraphs']})"
                        for r in para_known if not r["in_range"]]
        out_of_range += [f"{r['file']} ({r['duration_minutes']} min)"
                         for r in video_known
                         if not 1 <= r["duration_minutes"] <= 4]
        sensitive = [r for r in para_known if r["boundary_sensitive"]]
        if para_known or video_known:
            print(f"\nOut of range under '{args.model}': "
                  + (", ".join(out_of_range) or "none"))
            print("Boundary-sensitive (model choice changes the length verdict): "
                  + (", ".join(f"{r['file']} {r['paragraphs_by_model']}"
                               for r in sensitive) or "none"))
            if sensitive:
                print("  -> These MUST be recorded explicitly in the review file "
                      "and flagged for the teacher.")
        flagged = [r for r in records if r["warnings"]]
        if flagged:
            print("\nNeeds attention before grading:")
            for r in flagged:
                print(f"  - {r['file']}: {'; '.join(r['warnings'])}")

    if args.manifest:
        out = Path(args.manifest).expanduser().resolve()
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(
            json.dumps(
                {
                    "submissions_dir": str(root),
                    "count": len(records),
                    "length_policy": {
                        "model": args.model,
                        "in_range": list(in_range),
                        "models": {m: MODEL_NOTES[m] for m in MODELS},
                    },
                    "records": records,
                },
                indent=2,
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )
        if not args.quiet:
            print(f"\nManifest written: {out}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
