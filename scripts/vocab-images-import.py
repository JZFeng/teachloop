#!/usr/bin/env python3
"""2026-10-31 / task-1：把 113 张定稿 WebP 按 content_id 落到后端读得到的位置。

做三件事：

1. 取词表：`docs/reports/2026-10-31-word-selection-100/qa-113.csv` 的 113 个词
   （与 `.envs/qa/content-delta.db` 的 `normalized_form` 取交集；本脚本会核对交集 = 113）；
2. 取 content_id：SQLite `word_content_delta`（v2.5）里每个词的 `content_id`
   —— 这就是后端 `GET /api/vocab/contents/{contentId}/image` 用的那个 id；
3. 复制到上传目录：`<uploads>/vocab/images/<content_id>.webp`
   —— 与 `VocabImageStore.keyFor()` 的约定路径一字不差（`vocab/images/<id>.webp`）。

为什么直接写文件、不走上传接口：图片是**构建产物**（113 张已定稿），不是用户内容；
后端读图片只按 key 找文件（`BlobStore`），所以把文件放对位置就是「落库」。
生产的 S3 后端要另走一次对象上传，见 README 的遗留问题。

用法：

    # qa（默认）
    python3 scripts/vocab-images-import.py

    # dev（./uploads）
    python3 scripts/vocab-images-import.py --uploads ./uploads

    # 只看看会做什么，不写文件
    python3 scripts/vocab-images-import.py --dry-run

退出码：0 = 113 张全部就位；1 = 有词缺图或缺 content_id（打印清单，不静默跳过）。
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import shutil
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPORT = ROOT / "docs/reports/2026-10-31-word-selection-100"
DEFAULT_CSV = REPORT / "qa-113.csv"
DEFAULT_IMAGES = REPORT / "final-176"
DEFAULT_DB = ROOT / ".envs/qa/content-delta.db"
DEFAULT_UPLOADS = ROOT / ".envs/qa/uploads"
DEFAULT_MANIFEST = ROOT / "docs/reports/2026-10-31-image-wiring/image-manifest.json"

# 与 VocabImageStore.KEY_PREFIX / EXTENSION 必须一致；改这里等于让存量图片全部失效。
KEY_PREFIX = "vocab/images"


def display(path: Path) -> str:
    """日志/清单里的路径：仓库内的显示相对路径，仓库外（如 /tmp）原样显示。"""
    try:
        return str(path.resolve().relative_to(ROOT))
    except ValueError:
        return str(path)


def load_words(csv_path: Path) -> list[str]:
    with csv_path.open(newline="", encoding="utf-8") as handle:
        return [row["word"].strip().lower() for row in csv.DictReader(handle) if row["word"].strip()]


def load_content_ids(db_path: Path, prompt_version: str) -> dict[str, int]:
    """词形 → content_id；同一词形有多版时取 created_at 最新的那版。"""
    query = """
        select normalized_form, content_id, prompt_version, created_at
          from word_content_delta
         where prompt_version = ?
         order by created_at asc
    """
    found: dict[str, int] = {}
    connection = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        for form, content_id, _version, _created in connection.execute(query, (prompt_version,)):
            found[form] = content_id  # 升序遍历，后面的（更新的）覆盖前面的
    finally:
        connection.close()
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description="113 张词卡 WebP 按 content_id 落到上传目录")
    parser.add_argument("--csv", type=Path, default=DEFAULT_CSV, help="抽检清单（词形来源）")
    parser.add_argument("--images", type=Path, default=DEFAULT_IMAGES, help="176px WebP 目录")
    parser.add_argument("--content-db", type=Path, default=DEFAULT_DB, help="内容 delta（SQLite）")
    parser.add_argument("--prompt-version", default="vocab-content-v2.5", help="只认这一版内容")
    parser.add_argument("--uploads", type=Path, default=DEFAULT_UPLOADS, help="STORAGE_LOCAL_DIR")
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST, help="映射清单输出路径")
    parser.add_argument("--dry-run", action="store_true", help="只打印计划，不写文件")
    args = parser.parse_args()

    for path, label in ((args.csv, "抽检清单"), (args.images, "图片目录"), (args.content_db, "内容 delta")):
        if not path.exists():
            print(f"找不到{label}：{path}", file=sys.stderr)
            return 1

    words = load_words(args.csv)
    content_ids = load_content_ids(args.content_db, args.prompt_version)
    target_dir = args.uploads / KEY_PREFIX

    missing_content = [word for word in words if word not in content_ids]
    missing_image = [word for word in words if not (args.images / f"{word}.webp").is_file()]
    if missing_content or missing_image:
        if missing_content:
            print(f"缺 content_id（{len(missing_content)}）：{', '.join(missing_content)}", file=sys.stderr)
        if missing_image:
            print(f"缺图片（{len(missing_image)}）：{', '.join(missing_image)}", file=sys.stderr)
        return 1

    if not args.dry_run:
        target_dir.mkdir(parents=True, exist_ok=True)

    entries = []
    copied = 0
    total_bytes = 0
    for word in words:
        content_id = content_ids[word]
        source = args.images / f"{word}.webp"
        target = target_dir / f"{content_id}.webp"
        payload = source.read_bytes()
        digest = hashlib.sha256(payload).hexdigest()
        total_bytes += len(payload)
        if not args.dry_run:
            shutil.copyfile(source, target)
        copied += 1
        entries.append({
            "word": word,
            "contentId": content_id,
            "key": f"{KEY_PREFIX}/{content_id}.webp",
            "bytes": len(payload),
            "sha256": digest,
        })

    entries.sort(key=lambda entry: entry["word"])
    manifest = {
        "generatedAt": "2026-10-31",
        "source": {
            "wordsCsv": display(args.csv),
            "imagesDir": display(args.images),
            "contentDb": display(args.content_db),
            "promptVersion": args.prompt_version,
        },
        "storage": {
            "uploadsDir": str(args.uploads),
            "keyPrefix": f"{KEY_PREFIX}/<content_id>.webp",
            "api": "/api/vocab/contents/{contentId}/image",
        },
        "count": len(entries),
        "totalBytes": total_bytes,
        "words": entries,
    }
    if not args.dry_run:
        args.manifest.parent.mkdir(parents=True, exist_ok=True)
        args.manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    action = "将写入" if args.dry_run else "已写入"
    print(f"{action} {copied} 张 WebP → {target_dir}（{total_bytes / 1024:.1f} KB）")
    print(f"映射清单：{display(args.manifest)}")
    print(f"首个：{entries[0]['word']} → {entries[0]['key']}；末个：{entries[-1]['word']} → {entries[-1]['key']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
