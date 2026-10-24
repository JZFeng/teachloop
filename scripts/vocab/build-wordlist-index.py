#!/usr/bin/env python3
"""T388 / specs/004-vocab §13：把 Paul Nation BNC 词族表构建成可装载的索引。

输入（不入库、不进 git）：WORDLIST_DIR 下的 basewrd<band>.txt，默认
`fixtures-local/wordlists/basewords_130`。本脚本只读 **BNC 频段 1–30**；
`basewrd31–35` 是专有名词 / 前缀 / 学科词 / 缩写 / 法语借词补充表，按 2026-10-23
的决定不进词族索引（否则「第 31 千词族」这类标签会失真，且拼写校验词形集会偏离
spec §20 的 80,579）。未纳入的文件会写进 manifest 的 `ignored_files`，不静默丢弃。

输出（产物不进 git，部署时构建或挂卷）：

    <out-dir>/lexicon-families.tsv   列：id head band family_freq
    <out-dir>/lexicon-forms.tsv      列：normalized_form folded_form family_id form_freq is_head
    <out-dir>/manifest.json          来源逐文件 sha256、计数、产物 sha256、跳过行

解析口径（与 spec §14.2 / plan §3.2 逐条对应）：

1. 兼容 LF / CRLF；空行跳过；
2. 行首有 Tab = 族成员，否则 = 新族首；
3. 每行只取「第一个 token = 词」「最后一个 token = 频次」，中间空白忽略；
4. 族首行末尾数字是**整个词族**的频次，族首词自身频次 = 族频次 − 成员频次合计
   （为负时按原值记录并进 warnings，展示时只给族频次）；
5. 词形统一 NFC + 小写 + 去首尾空格；另建去变音符形态（NFD 去掉 Mn 后拼回）；
6. 不依赖文件内顺序；同一词形出现在多个频段时保留频次高者（同频次取低频段），
   被丢弃的一侧进 manifest 的 duplicates；
7. 行尾不是整数的行（源文件杂质）跳过，并逐条写进 manifest 的 skipped_lines。

用法：

    python3 scripts/vocab/build-wordlist-index.py                 # 构建
    python3 scripts/vocab/build-wordlist-index.py --verify        # 校验产物与 sha256
    python3 scripts/vocab/build-wordlist-index.py --bands 1-2 \\
        --wordlist-dir scripts/vocab/testdata/basewords-mini \\
        --out-dir /tmp/lexicon                                    # 测试用小数据
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import unicodedata
from datetime import datetime, timezone
from pathlib import Path

FORMAT = "teachloop-lexicon-v1"
ARTIFACT_FAMILIES = "lexicon-families.tsv"
ARTIFACT_FORMS = "lexicon-forms.tsv"
MANIFEST = "manifest.json"
SOURCE_NAME = "Paul Nation BNC/COCA word family lists"
SOURCE_URL = None  # 来源页没有记录在案，manifest 里显式写 null，不编造
DEFAULT_BANDS = "1-30"
DEFAULT_WORDLIST_DIR = "fixtures-local/wordlists/basewords_130"
DEFAULT_OUT_DIR = "fixtures-local/vocab-lexicon"

CONTENT_RULE = (
    "sha256('teachloop-lexicon-v1\\n' + 每个产物的 'name:sha256\\n'（按文件名排序）"
    " + 每个源文件的 'src:name:sha256\\n'（按文件名排序） + 'counts:families/forms\\n')"
)


def normalize(form: str) -> str:
    """spec §14.2：NFC + 小写 + 去首尾空格。"""
    return unicodedata.normalize("NFC", form).strip().lower()


def fold(form: str) -> str:
    """去变音符索引：NFD 去掉 Mn（组合附加符）后拼回 NFC。"""
    decomposed = unicodedata.normalize("NFD", form)
    stripped = "".join(ch for ch in decomposed if unicodedata.category(ch) != "Mn")
    return unicodedata.normalize("NFC", stripped)


def family_id_of(band: int, head: str) -> int:
    """确定性族 id：sha256(band|head) 前 8 字节，取正数（bigint）。

    不用「频段内序号」：词表更新插入一族时，后续所有 id 会整体平移，已写进
    vocab_words.family_id 的引用会静默指错族。内容哈希只随该族自身变化。
    """
    digest = hashlib.sha256(f"{band}|{head}".encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big") & 0x7FFF_FFFF_FFFF_FFFF


def parse_bands(text: str) -> list[int]:
    try:
        start, _, end = text.partition("-")
        first, last = int(start), int(end or start)
    except ValueError as exc:
        raise SystemExit(f"[build-wordlist-index] --bands 需要 N 或 N-M：{text}") from exc
    if first < 1 or last < first:
        raise SystemExit(f"[build-wordlist-index] --bands 区间不合法：{text}")
    return list(range(first, last + 1))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_text(path: Path, problems: list[str]) -> str | None:
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError as exc:
        problems.append(f"{path.name} 不是 UTF-8：{exc}（不静默替换，请先转码）")
        return None


def parse_file(path: Path, band: int, skipped: list[dict], warnings: list[dict],
               problems: list[str]) -> list[dict]:
    text = read_text(path, problems)
    if text is None:
        return []

    families: list[dict] = []
    current: dict | None = None
    for lineno, raw in enumerate(text.splitlines(), 1):
        if not raw.strip():
            continue
        tokens = raw.split()
        tail = tokens[-1] if tokens else ""
        if len(tokens) < 2 or not tail.isdigit():
            skipped.append({
                "file": path.name,
                "line": lineno,
                "text": raw,
                "reason": "行尾不是整数频次（源文件杂质），整行跳过",
            })
            continue
        word, freq = tokens[0], int(tail)
        if "\ufffd" in word:
            warnings.append({
                "file": path.name,
                "line": lineno,
                "text": raw,
                "reason": "词形含 U+FFFD 替换字符（源文件损坏），按原样收录，仅提示",
            })
        if raw.startswith("\t"):
            if current is None:
                skipped.append({
                    "file": path.name,
                    "line": lineno,
                    "text": raw,
                    "reason": "成员行前没有族首，无法归属，整行跳过",
                })
                continue
            current["members"].append({"form": word, "freq": freq, "line": lineno})
        else:
            current = {
                "head": word,
                "band": band,
                "family_freq": freq,
                "file": path.name,
                "line": lineno,
                "members": [],
            }
            families.append(current)
    return families


def build(wordlist_dir: Path, out_dir: Path, bands: list[int]) -> dict:
    problems: list[str] = []
    skipped: list[dict] = []
    warnings: list[dict] = []
    duplicates: list[dict] = []
    families: list[dict] = []
    source_files: list[dict] = []
    ignored_files: list[dict] = []

    missing = [f"basewrd{band}.txt" for band in bands
               if not (wordlist_dir / f"basewrd{band}.txt").is_file()]
    if missing:
        raise SystemExit("[build-wordlist-index] 缺少词表文件：" + ", ".join(missing))

    in_scope = {f"basewrd{band}.txt" for band in bands}
    for path in sorted(wordlist_dir.glob("basewrd*.txt")):
        if path.name in in_scope:
            continue
        ignored_files.append({
            "name": path.name,
            "sha256": sha256_file(path),
            "reason": "补充表（专有名词/前缀/学科词/缩写/法语借词），2026-10-23 决定不进 BNC 词族索引",
        })

    for band in bands:
        path = wordlist_dir / f"basewrd{band}.txt"
        parsed = parse_file(path, band, skipped, warnings, problems)
        families.extend(parsed)
        source_files.append({
            "name": path.name,
            "band": band,
            "bytes": path.stat().st_size,
            "sha256": sha256_file(path),
            "families": len(parsed),
            "form_rows": len(parsed) + sum(len(fam["members"]) for fam in parsed),
        })

    if problems:
        print("[build-wordlist-index] 失败：", file=sys.stderr)
        for problem in problems:
            print("  - " + problem, file=sys.stderr)
        raise SystemExit(1)

    # 族 id 与「族首自身频次」；按（频段, 族首）排序只为产物稳定。
    families.sort(key=lambda fam: (fam["band"], normalize(fam["head"]), fam["line"]))
    used_ids: dict[int, str] = {}
    for fam in families:
        head = normalize(fam["head"])
        fam["head"] = head
        fam["id"] = family_id_of(fam["band"], head)
        member_sum = sum(member["freq"] for member in fam["members"])
        fam["head_freq"] = fam["family_freq"] - member_sum
        if fam["head_freq"] < 0:
            warnings.append({
                "file": fam["file"],
                "line": fam["line"],
                "text": f"{fam['head']} family_freq={fam['family_freq']}",
                "reason": "族首词自身频次为负（族频次 < 成员合计，数据噪声），按原值记录，展示时只给族频次",
            })
        if fam["id"] in used_ids and used_ids[fam["id"]] != head:
            raise SystemExit(
                f"[build-wordlist-index] 族 id 哈希冲突：{head} 与 {used_ids[fam['id']]}"
            )
        used_ids[fam["id"]] = head

    # 词形去重：频次高者胜；同频次取低频段；再同则保留先出现的（文件内顺序）。
    forms: dict[str, dict] = {}
    for fam in families:
        rows = [{"form": fam["head"], "freq": fam["head_freq"], "is_head": True,
                 "family_id": fam["id"], "band": fam["band"], "file": fam["file"],
                 "line": fam["line"]}]
        for member in fam["members"]:
            rows.append({"form": member["form"], "freq": member["freq"], "is_head": False,
                         "family_id": fam["id"], "band": fam["band"], "file": fam["file"],
                         "line": member["line"]})
        for row in rows:
            row["normalized_form"] = normalize(row["form"])
            row["folded_form"] = fold(row["normalized_form"])
            previous = forms.get(row["normalized_form"])
            if previous is None:
                forms[row["normalized_form"]] = row
                continue
            candidate = (row["freq"], -row["band"])
            incumbent = (previous["freq"], -previous["band"])
            if candidate > incumbent:
                keep, drop = row, previous
                forms[row["normalized_form"]] = row
            else:
                keep, drop = previous, row
            duplicates.append({
                "form": row["normalized_form"],
                "kept": {key: keep[key] for key in ("band", "freq", "family_id", "file", "line")},
                "dropped": {key: drop[key] for key in ("band", "freq", "family_id", "file", "line")},
            })

    families_with_forms = {row["family_id"] for row in forms.values()}
    families_without_forms = len(families) - len(families_with_forms)

    out_dir.mkdir(parents=True, exist_ok=True)
    families_path = out_dir / ARTIFACT_FAMILIES
    forms_path = out_dir / ARTIFACT_FORMS

    with families_path.open("w", encoding="utf-8", newline="\n") as handle:
        for fam in families:
            handle.write(f"{fam['id']}\t{fam['head']}\t{fam['band']}\t{fam['family_freq']}\n")

    with forms_path.open("w", encoding="utf-8", newline="\n") as handle:
        for normalized_form in sorted(forms):
            row = forms[normalized_form]
            handle.write(
                f"{row['normalized_form']}\t{row['folded_form']}\t{row['family_id']}\t"
                f"{row['freq']}\t{1 if row['is_head'] else 0}\n"
            )

    artifacts = {
        "families": {
            "file": ARTIFACT_FAMILIES,
            "bytes": families_path.stat().st_size,
            "rows": len(families),
            "sha256": sha256_file(families_path),
        },
        "forms": {
            "file": ARTIFACT_FORMS,
            "bytes": forms_path.stat().st_size,
            "rows": len(forms),
            "sha256": sha256_file(forms_path),
        },
    }
    counts = {
        "families": len(families),
        "forms": len(forms),
        "head_forms": sum(1 for row in forms.values() if row["is_head"]),
        "duplicate_forms": len(duplicates),
        "families_without_forms": families_without_forms,
        "skipped_lines": len(skipped),
        "warnings": len(warnings),
    }
    content_sha256 = content_hash(source_files, artifacts, counts)

    manifest = {
        "format": FORMAT,
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "generated_by": "scripts/vocab/build-wordlist-index.py",
        "source": {
            "name": SOURCE_NAME,
            "url": SOURCE_URL,
            "local_dir": str(wordlist_dir),
            "note": "原始 txt 不入库、不进 git；仓库只保留脚本、manifest 与校验命令（spec §14.1）",
            "files": source_files,
        },
        "scope": {
            "bands": [bands[0], bands[-1]],
            "files": [item["name"] for item in source_files],
            "ignored_files": ignored_files,
        },
        "counts": counts,
        "artifacts": artifacts,
        "content_sha256": content_sha256,
        "content_sha256_rule": CONTENT_RULE,
        "skipped_lines": skipped,
        "warnings": warnings,
        "duplicates": duplicates,
    }
    (out_dir / MANIFEST).write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return manifest


def content_hash(source_files: list[dict], artifacts: dict, counts: dict) -> str:
    digest = hashlib.sha256()
    digest.update(b"teachloop-lexicon-v1\n")
    for name in sorted((artifacts["families"]["file"], artifacts["forms"]["file"])):
        item = artifacts["families"] if name == artifacts["families"]["file"] else artifacts["forms"]
        digest.update(f"{name}:{item['sha256']}\n".encode("utf-8"))
    for item in sorted(source_files, key=lambda entry: entry["name"]):
        digest.update(f"src:{item['name']}:{item['sha256']}\n".encode("utf-8"))
    digest.update(f"counts:{counts['families']}/{counts['forms']}\n".encode("utf-8"))
    return digest.hexdigest()


def verify(out_dir: Path, wordlist_dir: Path | None) -> int:
    manifest_path = out_dir / MANIFEST
    if not manifest_path.is_file():
        print(f"[verify] 找不到 {manifest_path}", file=sys.stderr)
        return 1
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    problems: list[str] = []

    if manifest.get("format") != FORMAT:
        problems.append(f"manifest format={manifest.get('format')}（期望 {FORMAT}）")
    for key in ("families", "forms"):
        item = manifest.get("artifacts", {}).get(key, {})
        path = out_dir / item.get("file", "")
        if not path.is_file():
            problems.append(f"缺少产物 {path}")
            continue
        if path.stat().st_size != item.get("bytes"):
            problems.append(f"{path.name} 字节数 {path.stat().st_size} != manifest {item.get('bytes')}")
        actual = sha256_file(path)
        if actual != item.get("sha256"):
            problems.append(f"{path.name} sha256 {actual} != manifest {item.get('sha256')}")
        with path.open("rb") as handle:
            rows = sum(1 for _ in handle)
        if rows != item.get("rows"):
            problems.append(f"{path.name} 行数 {rows} != manifest {item.get('rows')}")

    expected = content_hash(manifest.get("source", {}).get("files", []),
                            manifest.get("artifacts", {}), manifest.get("counts", {}))
    if expected != manifest.get("content_sha256"):
        problems.append(f"content_sha256 重算 {expected} != manifest {manifest.get('content_sha256')}")

    if wordlist_dir is not None:
        for item in manifest.get("source", {}).get("files", []):
            path = wordlist_dir / item["name"]
            if not path.is_file():
                problems.append(f"源文件不在 {path}（只校验产物时用 --no-source 跳过）")
                continue
            actual = sha256_file(path)
            if actual != item["sha256"]:
                problems.append(f"源文件 {item['name']} 已变化：{actual} != {item['sha256']}")

    if problems:
        print("[verify] 失败：", file=sys.stderr)
        for problem in problems:
            print("  - " + problem, file=sys.stderr)
        return 1
    counts = manifest["counts"]
    print(f"[verify] 通过：{counts['families']:,} 族 / {counts['forms']:,} 形 / "
          f"content_sha256={manifest['content_sha256'][:16]}…")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="构建 TeachLoop 本地词族索引（T388）")
    parser.add_argument("--wordlist-dir", default=os.environ.get("WORDLIST_DIR", DEFAULT_WORDLIST_DIR))
    parser.add_argument("--out-dir", default=os.environ.get("WORDLIST_INDEX_DIR", DEFAULT_OUT_DIR))
    parser.add_argument("--bands", default=DEFAULT_BANDS, help="频段范围，默认 1-30")
    parser.add_argument("--verify", action="store_true", help="只校验已有产物与 sha256")
    parser.add_argument("--no-source", action="store_true", help="verify 时跳过源文件校验")
    args = parser.parse_args(argv)

    out_dir = Path(args.out_dir)
    if args.verify:
        wordlist_dir = None if args.no_source else Path(args.wordlist_dir)
        if wordlist_dir is not None and not wordlist_dir.is_dir():
            wordlist_dir = None
        return verify(out_dir, wordlist_dir)

    wordlist_dir = Path(args.wordlist_dir)
    if not wordlist_dir.is_dir():
        print(f"[build-wordlist-index] 词表目录不存在：{wordlist_dir}", file=sys.stderr)
        return 1
    bands = parse_bands(args.bands)
    manifest = build(wordlist_dir, out_dir, bands)
    counts = manifest["counts"]
    families_mb = manifest["artifacts"]["families"]["bytes"] / 1024 / 1024
    forms_mb = manifest["artifacts"]["forms"]["bytes"] / 1024 / 1024
    print(f"[build-wordlist-index] 词表 {wordlist_dir}（band {bands[0]}–{bands[-1]}）")
    print(f"[build-wordlist-index] {counts['families']:,} 族 / {counts['forms']:,} 形"
          f"（去重前 {counts['forms'] + counts['duplicate_forms']:,}，重复 {counts['duplicate_forms']}）")
    print(f"[build-wordlist-index] 产物 {out_dir}（{families_mb + forms_mb:.1f} MB）"
          f" sha256={manifest['content_sha256'][:16]}…")
    if counts["skipped_lines"]:
        print(f"[build-wordlist-index] 跳过 {counts['skipped_lines']} 行（见 manifest skipped_lines）")
    if counts["warnings"]:
        print(f"[build-wordlist-index] 提示 {counts['warnings']} 条（见 manifest warnings）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
