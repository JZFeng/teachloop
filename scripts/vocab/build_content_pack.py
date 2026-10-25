#!/usr/bin/env python3
"""T430 / decisions/017 P2：把 v2.3 词内容 JSONL 构建成只读内容包（pack + idx + manifest）。

输入：一行一个 JSON（v2 内容结构，必须有 `word`），例如
`fixtures-local/vocab-content/vocab-content-en-v2.3.jsonl`。

输出（产物不进 git，部署时构建或挂卷）：

    <out-dir>/content-<language>-<version>.pack   UTF-8 JSON 记录顺序拼接，无分隔符
    <out-dir>/content-<language>-<version>.idx    每条 28 字节大端：hash64(8)+offset(8)+length(4)+contentId(8)
    <out-dir>/manifest.json                       格式 / 语言 / 条数 / prompt 版本 / 两个 sha256 / 来源

口径（必须与 Java 端 `ContentPackReader`、`WordNormalizer` 逐字节一致，见 work/plan-content-tts.md §2）：

1. `normalizedForm` = NFC → 全角转半角 → trim → lower → NFD 去掉 Mn → 拼回 NFC；
2. `contentId` 按 `normalizedForm` 排序后从 1 连续分配（与输入行序无关，稳定可复现）；
3. `hash64` = `sha256(UTF-8(language + "|" + normalizedForm))` 前 8 字节，按大端无符号解释；
4. `idx` 按 unsigned hash64 升序，同 hash 按 contentId 升序；`offset/length` 指向 pack 里的原始 JSON 字节；
5. 同一 `normalizedForm` 只保留输入里第一条，重复项写进 manifest 的 `duplicates`；
6. 坏行（非 JSON、缺 word、规范化后为空）跳过并写进 manifest 的 `skippedLines`，不静默丢；
7. pack/idx 先写同目录 `.tmp-<pid>` 再 `os.replace`，manifest 最后写 —— manifest 出现即代表包完整。

用法：

    python3 scripts/vocab/build_content_pack.py \\
        --input fixtures-local/vocab-content/vocab-content-en-v2.3.jsonl \\
        --language en --version v2.3 --out-dir fixtures-local/vocab-content

    python3 scripts/vocab/build_content_pack.py --input scripts/vocab/testdata/...  # 测试用合成数据
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import struct
import sys
import unicodedata
from datetime import datetime, timezone
from pathlib import Path

FORMAT = "teachloop-content-pack-v1"
MANIFEST = "manifest.json"
DEFAULT_PROMPT_VERSION = "vocab-content-v2.3"
DEFAULT_LANGUAGE = "en"
IDX_RECORD = struct.Struct(">QQIQ")

#: manifest 里 skippedLines / duplicates 最多留这么多条，避免坏输入撑爆 manifest。
DETAIL_LIMIT = 100

SAFE_TOKEN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")


def to_half_width(text: str) -> str:
    """全角 → 半角（与 Java WordNormalizer.toHalfWidth 同口径：U+3000 → 空格，FF01–FF5E 平移）。"""
    out: list[str] = []
    for ch in text:
        code = ord(ch)
        if code == 0x3000:
            out.append(" ")
        elif 0xFF01 <= code <= 0xFF5E:
            out.append(chr(code - 0xFEE0))
        else:
            out.append(ch)
    return "".join(out)


def normalize_form(raw: str) -> str:
    """去重键：与 Java WordNormalizer.normalized 一致（NFC → 全角转半角 → trim → lower → 去 Mn）。"""
    text = unicodedata.normalize("NFC", raw)
    text = to_half_width(text).strip()
    text = text.lower()
    decomposed = unicodedata.normalize("NFD", text)
    kept = "".join(ch for ch in decomposed if unicodedata.category(ch) != "Mn")
    return unicodedata.normalize("NFC", kept)


def hash64(language: str, normalized_form: str) -> int:
    """sha256(UTF-8(language + '|' + normalizedForm)) 的前 8 字节，大端无符号。"""
    digest = hashlib.sha256(f"{language}|{normalized_form}".encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big", signed=False)


def _sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _sha256_file(path: Path) -> tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
            size += len(chunk)
    return digest.hexdigest(), size


def _atomic_write(path: Path, data: bytes) -> None:
    tmp = path.with_name(f".{path.name}.tmp-{os.getpid()}")
    try:
        with tmp.open("wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp, path)
    finally:
        if tmp.exists():
            tmp.unlink()


def _fsync_dir(path: Path) -> None:
    try:
        fd = os.open(path, os.O_RDONLY)
    except OSError:
        return
    try:
        os.fsync(fd)
    except OSError:
        pass
    finally:
        os.close(fd)


def _safe_token(value: str, field: str) -> str:
    if not isinstance(value, str) or not SAFE_TOKEN.match(value):
        raise ValueError(f"{field} 只能是 [A-Za-z0-9._-]（用于文件名）：{value!r}")
    return value


def default_version(prompt_version: str) -> str:
    """没给 --version 时用 prompt 版本的后缀（vocab-content-v2.3 → v2.3），否则 v1。"""
    prefix = "vocab-content-"
    if prompt_version.startswith(prefix) and len(prompt_version) > len(prefix):
        return prompt_version[len(prefix):]
    return "v1"


def load_records(input_path: Path):
    """读 JSONL → (records, skipped, duplicates, line_count, source_bytes, source_sha256)。

    records 里每条是 {'normalizedForm': str, 'json': 原始行文本}，顺序暂按输入行序。
    """
    records: list[dict] = []
    skipped: list[dict] = []
    duplicates: list[dict] = []
    seen: dict[str, int] = {}
    line_count = 0

    source_sha256, source_bytes = _sha256_file(input_path)
    with input_path.open("r", encoding="utf-8") as handle:
        for line_number, raw_line in enumerate(handle, start=1):
            line_count = line_number
            text = raw_line.strip()
            if not text:
                continue
            try:
                record = json.loads(text)
            except json.JSONDecodeError as error:
                skipped.append({"line": line_number, "reason": f"JSON 解析失败：{error.msg}"})
                continue
            word = record.get("word") if isinstance(record, dict) else None
            if not isinstance(word, str) or not word.strip():
                skipped.append({"line": line_number, "reason": "缺少 word 字段"})
                continue
            normalized = normalize_form(word)
            if not normalized:
                skipped.append({"line": line_number, "reason": "word 规范化后为空"})
                continue
            if normalized in seen:
                duplicates.append({
                    "line": line_number,
                    "normalizedForm": normalized,
                    "firstLine": seen[normalized],
                })
                continue
            seen[normalized] = line_number
            records.append({"normalizedForm": normalized, "json": text})
    return records, skipped, duplicates, line_count, source_bytes, source_sha256


def build(input_path: Path, out_dir: Path, language: str = DEFAULT_LANGUAGE,
          version: str | None = None, prompt_version: str = DEFAULT_PROMPT_VERSION) -> dict:
    """构建一个内容包，返回写进 manifest 的 dict（同时把三个文件落到 out_dir）。"""
    input_path = Path(input_path)
    out_dir = Path(out_dir)
    language = _safe_token(language, "language")
    version = _safe_token(version or default_version(prompt_version), "version")
    prompt_version = _safe_token(prompt_version, "prompt-version")
    if not input_path.is_file():
        raise ValueError(f"输入文件不存在：{input_path}")

    records, skipped, duplicates, line_count, source_bytes, source_sha256 = load_records(input_path)
    if not records:
        raise ValueError(f"输入里没有可用记录（每行必须有 word）：{input_path}")

    # contentId 按 normalizedForm 排序后 1..N（与输入行序无关，可复现）
    records.sort(key=lambda item: item["normalizedForm"])
    for content_id, record in enumerate(records, start=1):
        record["id"] = content_id

    # pack 按 idx 顺序（unsigned hash 升序、同 hash 按 id 升序）写：offset 单调递增，读取局部性更好。
    # hash 是 int.from_bytes(signed=False) 得到的非负值，直接比较即无符号序。
    records.sort(key=lambda item: (hash64(language, item["normalizedForm"]), item["id"]))
    pack = bytearray()
    entries: list[tuple[int, int, int, int]] = []
    for record in records:
        payload = record["json"].encode("utf-8")
        entries.append((hash64(language, record["normalizedForm"]), len(pack), len(payload), record["id"]))
        pack += payload
    idx = b"".join(IDX_RECORD.pack(*entry) for entry in entries)

    pack_name = f"content-{language}-{version}.pack"
    idx_name = f"content-{language}-{version}.idx"
    out_dir.mkdir(parents=True, exist_ok=True)
    pack_bytes = bytes(pack)
    _atomic_write(out_dir / pack_name, pack_bytes)
    _atomic_write(out_dir / idx_name, idx)
    _fsync_dir(out_dir)

    manifest = {
        "format": FORMAT,
        "language": language,
        "version": version,
        "promptVersion": prompt_version,
        "count": len(records),
        "packFile": pack_name,
        "idxFile": idx_name,
        "packSha256": _sha256_bytes(pack_bytes),
        "idxSha256": _sha256_bytes(idx),
        "createdAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "bytes": {"pack": len(pack_bytes), "idx": len(idx)},
        "source": {
            "name": input_path.name,
            "sha256": source_sha256,
            "bytes": source_bytes,
            "lines": line_count,
            "skipped": len(skipped),
            "duplicates": len(duplicates),
        },
        "skippedLines": skipped[:DETAIL_LIMIT],
        "duplicates": duplicates[:DETAIL_LIMIT],
    }
    manifest_bytes = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    # manifest 最后写：它出现 = pack/idx 已经完整落盘
    _atomic_write(out_dir / MANIFEST, manifest_bytes)
    _fsync_dir(out_dir)
    return manifest


def _print_report(manifest: dict, out_dir: Path) -> None:
    source = manifest["source"]
    print(f"内容包已生成：{out_dir}")
    print(f"  language={manifest['language']} version={manifest['version']} "
          f"promptVersion={manifest['promptVersion']}")
    print(f"  count={manifest['count']} lines={source['lines']} "
          f"skipped={source['skipped']} duplicates={source['duplicates']}")
    print(f"  {manifest['packFile']} {manifest['bytes']['pack']} bytes "
          f"sha256={manifest['packSha256']}")
    print(f"  {manifest['idxFile']} {manifest['bytes']['idx']} bytes "
          f"sha256={manifest['idxSha256']}")
    print(f"  {MANIFEST}（最后写入，出现即代表包完整）")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="把 v2.3 词内容 JSONL 构建成只读内容包（pack/idx/manifest）")
    parser.add_argument("--input", required=True, help="输入 JSONL（一行一个内容 JSON，必须有 word）")
    parser.add_argument("--language", default=DEFAULT_LANGUAGE, help="包语言，默认 en")
    parser.add_argument("--version", default=None,
                        help="包版本，默认取 prompt-version 的后缀（vocab-content-v2.3 → v2.3）")
    parser.add_argument("--prompt-version", default=DEFAULT_PROMPT_VERSION,
                        help=f"内容对应的提示词版本，默认 {DEFAULT_PROMPT_VERSION}")
    parser.add_argument("--out-dir", default=None,
                        help="输出目录，默认与输入同目录")
    args = parser.parse_args(argv)

    input_path = Path(args.input)
    out_dir = Path(args.out_dir) if args.out_dir else input_path.parent
    try:
        manifest = build(input_path, out_dir, args.language, args.version, args.prompt_version)
    except ValueError as error:
        print(f"构建失败：{error}", file=sys.stderr)
        return 2
    _print_report(manifest, out_dir)
    return 0


if __name__ == "__main__":
    sys.exit(main())
