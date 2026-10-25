#!/usr/bin/env python3
"""T430：`build_content_pack.py` 的自测（只用合成数据，不依赖真实产物）。

跑法：`python3 scripts/vocab/test_build_content_pack.py -v`

覆盖：normalizedForm 与 Java WordNormalizer 同口径、按 normalizedForm 排序后 id 连续、
28 字节大端 idx 布局与 unsigned hash 升序、hash64 公式、offset/length 指向原始 JSON 字节、
重复词与坏行不静默丢、输入行序不影响产物、两次构建 pack/idx sha256 可复现、CLI 打印。
"""
from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
import json
import shutil
import struct
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "build_content_pack.py"
IDX_RECORD = struct.Struct(">QQIQ")
FORMAT = "teachloop-content-pack-v1"
PROMPT_VERSION = "vocab-content-v2.3"


def load_builder():
    spec = importlib.util.spec_from_file_location("build_content_pack", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


B = load_builder()

#: 3 条可用（café / 全角 Ａｐｐｌｅ / banana）+ 1 条重复（BANANA）+ 1 行坏 JSON。
LINES = [
    json.dumps({"schemaVersion": 2, "word": "café", "meanings": {"zh": "咖啡馆"}}, ensure_ascii=False),
    json.dumps({"schemaVersion": 2, "word": "Ａｐｐｌｅ", "meanings": {"zh": "苹果"}}, ensure_ascii=False),
    json.dumps({"schemaVersion": 2, "word": "banana", "meanings": {"zh": "香蕉"}}, ensure_ascii=False),
    json.dumps({"schemaVersion": 2, "word": "BANANA", "meanings": {"zh": "重复"}}, ensure_ascii=False),
    "{not json",
]

#: 排序后的 normalizedForm → contentId（1..N）。
EXPECTED_FORMS = {1: "apple", 2: "banana", 3: "cafe"}


class BuildContentPackTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="vocab-content-pack-"))
        self.input = self.tmp / "input.jsonl"
        self.input.write_text("\n".join(LINES) + "\n", encoding="utf-8")
        self.out = self.tmp / "out"
        self.manifest = B.build(self.input, self.out, "en", "v2.3", PROMPT_VERSION)
        self.pack = (self.out / "content-en-v2.3.pack").read_bytes()
        self.idx = (self.out / "content-en-v2.3.idx").read_bytes()

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp, ignore_errors=True)

    def entries(self, idx: bytes | None = None) -> list[tuple[int, int, int, int]]:
        raw = self.idx if idx is None else idx
        return [IDX_RECORD.unpack_from(raw, offset)
                for offset in range(0, len(raw), IDX_RECORD.size)]

    def test_manifest_fields_and_source_accounting(self) -> None:
        manifest = self.manifest
        self.assertEqual(manifest["format"], FORMAT)
        self.assertEqual(manifest["language"], "en")
        self.assertEqual(manifest["version"], "v2.3")
        self.assertEqual(manifest["promptVersion"], PROMPT_VERSION)
        self.assertEqual(manifest["count"], 3)
        self.assertEqual(manifest["packFile"], "content-en-v2.3.pack")
        self.assertEqual(manifest["idxFile"], "content-en-v2.3.idx")
        self.assertEqual(manifest["bytes"]["pack"], len(self.pack))
        self.assertEqual(manifest["bytes"]["idx"], len(self.idx))
        self.assertEqual(manifest["packSha256"], hashlib.sha256(self.pack).hexdigest())
        self.assertEqual(manifest["idxSha256"], hashlib.sha256(self.idx).hexdigest())
        self.assertTrue(manifest["createdAt"].endswith("Z"))

        source = manifest["source"]
        self.assertEqual(source["name"], "input.jsonl")
        self.assertEqual(source["lines"], 5)
        self.assertEqual(source["skipped"], 1)
        self.assertEqual(source["duplicates"], 1)
        self.assertEqual(manifest["skippedLines"][0]["line"], 5)
        self.assertEqual(manifest["duplicates"][0]["normalizedForm"], "banana")
        self.assertEqual(manifest["duplicates"][0]["line"], 4)

        on_disk = json.loads((self.out / B.MANIFEST).read_text(encoding="utf-8"))
        self.assertEqual(on_disk["packSha256"], manifest["packSha256"])
        self.assertEqual(on_disk["count"], 3)

    def test_idx_is_28_bytes_big_endian_and_sorted(self) -> None:
        self.assertEqual(len(self.idx), 3 * 28)
        entries = self.entries()
        hashes = [entry[0] for entry in entries]
        self.assertEqual(hashes, sorted(hashes), "idx 必须按 unsigned hash64 升序")
        # idx 按 hash 排序，所以 id 在 idx 里不是 1,2,3；但排序后必须恰好是 1..N
        self.assertEqual(sorted(entry[3] for entry in entries), [1, 2, 3],
                         "按 normalizedForm 排序后 id 从 1 连续分配")
        # offset/length 首尾相接且覆盖整个 pack；每条都能解出 JSON
        self.assertEqual(entries[0][1], 0)
        for previous, current in zip(entries, entries[1:]):
            self.assertEqual(previous[1] + previous[2], current[1])
        self.assertEqual(entries[-1][1] + entries[-1][2], len(self.pack))
        for _hash, offset, length, content_id in entries:
            record = json.loads(self.pack[offset:offset + length].decode("utf-8"))
            self.assertEqual(B.normalize_form(record["word"]), EXPECTED_FORMS[content_id])
        # 同一 normalizedForm 只保留第一条：banana 的释义是「香蕉」，不是重复行的「重复」
        banana = next(entry for entry in entries if entry[3] == 2)
        kept = json.loads(self.pack[banana[1]:banana[1] + banana[2]].decode("utf-8"))
        self.assertEqual(kept["meanings"]["zh"], "香蕉")

    def test_hash64_formula_matches_normalized_form(self) -> None:
        for _hash, _offset, _length, content_id in self.entries():
            expected = hashlib.sha256(f"en|{EXPECTED_FORMS[content_id]}".encode("utf-8")).digest()
            self.assertEqual(_hash, int.from_bytes(expected[:8], "big", signed=False))

    def test_normalization_matches_java_word_normalizer(self) -> None:
        cases = {
            "café": "cafe",
            "  ＡＰＰＬＥ  ": "apple",
            "ÉCLAIR": "eclair",
            "naïve": "naive",
            "\u3000hello\u3000": "hello",
            "Banana": "banana",
        }
        for raw, expected in cases.items():
            self.assertEqual(B.normalize_form(raw), expected)
            self.assertEqual(B.normalize_form(B.normalize_form(raw)), expected, "规范化必须幂等")
        self.assertEqual(B.to_half_width("ＡＢＣ　１２３"), "ABC 123")

    def test_input_order_does_not_change_pack(self) -> None:
        # 注意：有重复词时「保留第一条」本身依赖行序，所以这里用无重复的 3 条验证排序确定性。
        unique = LINES[:3]
        forward = self.tmp / "forward.jsonl"
        forward.write_text("\n".join(unique) + "\n", encoding="utf-8")
        backward = self.tmp / "backward.jsonl"
        backward.write_text("\n".join(reversed(unique)) + "\n", encoding="utf-8")
        forward_manifest = B.build(forward, self.tmp / "forward-out", "en", "v2.3", PROMPT_VERSION)
        backward_manifest = B.build(backward, self.tmp / "backward-out", "en", "v2.3", PROMPT_VERSION)
        self.assertEqual(backward_manifest["packSha256"], forward_manifest["packSha256"])
        self.assertEqual(backward_manifest["idxSha256"], forward_manifest["idxSha256"])
        self.assertEqual((self.tmp / "backward-out" / "content-en-v2.3.pack").read_bytes(),
                         (self.tmp / "forward-out" / "content-en-v2.3.pack").read_bytes())

    def test_reproducible_across_runs_and_out_dirs(self) -> None:
        second = self.tmp / "second"
        manifest = B.build(self.input, second, "en", "v2.3", PROMPT_VERSION)
        self.assertEqual(manifest["packSha256"], self.manifest["packSha256"])
        self.assertEqual(manifest["idxSha256"], self.manifest["idxSha256"])
        self.assertEqual(manifest["count"], self.manifest["count"])
        self.assertEqual((second / "content-en-v2.3.pack").read_bytes(), self.pack)
        self.assertEqual((second / "content-en-v2.3.idx").read_bytes(), self.idx)

    def test_empty_input_is_rejected(self) -> None:
        empty = self.tmp / "empty.jsonl"
        empty.write_text("{not json\n\n", encoding="utf-8")
        with self.assertRaises(ValueError):
            B.build(empty, self.tmp / "empty-out", "en", "v2.3", PROMPT_VERSION)

    def test_cli_prints_report(self) -> None:
        out_dir = self.tmp / "cli-out"
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            code = B.main(["--input", str(self.input), "--language", "en", "--version", "v2.3",
                           "--prompt-version", PROMPT_VERSION, "--out-dir", str(out_dir)])
        output = buffer.getvalue()
        self.assertEqual(code, 0)
        self.assertIn("count=3", output)
        self.assertIn("skipped=1", output)
        self.assertIn("duplicates=1", output)
        self.assertIn(self.manifest["packSha256"], output)
        self.assertIn(self.manifest["idxSha256"], output)
        self.assertTrue((out_dir / B.MANIFEST).is_file())
        # CLI 与直接调用 build 的产物一致（同一输入、同一版本）
        self.assertEqual((out_dir / "content-en-v2.3.idx").read_bytes(), self.idx)

    def test_default_version_comes_from_prompt_version(self) -> None:
        self.assertEqual(B.default_version("vocab-content-v2.3"), "v2.3")
        self.assertEqual(B.default_version("custom"), "v1")
        other = self.tmp / "default-version"
        manifest = B.build(self.input, other, "en", None, PROMPT_VERSION)
        self.assertEqual(manifest["version"], "v2.3")
        self.assertTrue((other / "content-en-v2.3.pack").is_file())


if __name__ == "__main__":
    unittest.main(verbosity=2)
