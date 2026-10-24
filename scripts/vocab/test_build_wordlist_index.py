#!/usr/bin/env python3
"""T388：`build-wordlist-index.py` 的自测（只用合成小数据，不依赖真实词表）。

跑法：`python3 scripts/vocab/test_build_wordlist_index.py -v`
"""
from __future__ import annotations

import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "build-wordlist-index.py"
TESTDATA = HERE / "testdata" / "basewords-mini"


def load_builder():
    spec = importlib.util.spec_from_file_location("build_wordlist_index", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


B = load_builder()


def read_tsv(path: Path) -> list[list[str]]:
    return [line.split("\t") for line in path.read_text(encoding="utf-8").splitlines()]


class BuildWordlistIndexTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="vocab-lexicon-"))
        self.out = self.tmp / "out"
        self.manifest = B.build(TESTDATA, self.out, [1, 2])
        self.forms = {
            row[0]: row for row in read_tsv(self.out / "lexicon-forms.tsv")
        }
        self.families = {
            row[1]: row for row in read_tsv(self.out / "lexicon-families.tsv")
        }

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_counts_match_expected(self) -> None:
        counts = self.manifest["counts"]
        self.assertEqual(5, counts["families"], "band1 三族 + band2 两族")
        self.assertEqual(10, counts["forms"], "12 行去重后 10 个词形")
        self.assertEqual(2, counts["duplicate_forms"], "hunger / hungered 各重复一次")
        self.assertEqual(1, counts["families_without_forms"], "band2 的 HUNGER 族全部被高频段收走")
        self.assertEqual(4, counts["head_forms"], "hunger 作为族首被去重掉，只剩 4 个族首词形")
        self.assertEqual(1, counts["skipped_lines"], "TRISECTS 行尾不是频次")

    def test_parse_rules(self) -> None:
        # 每行只取首尾 token；族首词自身频次 = 族频次 - 成员合计。
        self.assertEqual("2062", self.forms["hungry"][3])
        self.assertEqual("20", self.forms["run"][3])
        self.assertEqual("22", self.forms["heft"][3])
        # 同一词族：run / running 共用一个 family_id；band 来自文件号。
        self.assertEqual(self.forms["run"][2], self.forms["running"][2])
        self.assertEqual("1", self.families["run"][2])
        self.assertEqual(self.families["run"][0], self.forms["run"][2])

    def test_fold_index(self) -> None:
        # ÉCLAT 归一化为 éclat，另存去变音符形态 eclat。
        self.assertIn("éclat", self.forms)
        self.assertEqual("eclat", self.forms["éclat"][1])
        self.assertEqual("éclat", self.forms["éclat"][0])

    def test_duplicate_keeps_higher_frequency(self) -> None:
        # hunger：band1 1124 击败 band2 10，词形归 band1 的族。
        self.assertEqual(self.families["hungry"][0], self.forms["hunger"][2])
        duplicates = {item["form"]: item for item in self.manifest["duplicates"]}
        self.assertEqual(1, duplicates["hunger"]["kept"]["band"])
        self.assertEqual(2, duplicates["hunger"]["dropped"]["band"])

    def test_artifacts_and_content_hash_are_deterministic(self) -> None:
        again = self.tmp / "again"
        second = B.build(TESTDATA, again, [1, 2])
        for name in ("lexicon-families.tsv", "lexicon-forms.tsv"):
            self.assertEqual(
                (self.out / name).read_bytes(),
                (again / name).read_bytes(),
                f"{name} 两次构建必须逐字节一致",
            )
        self.assertEqual(self.manifest["content_sha256"], second["content_sha256"])
        # generated_at 不参与 content_sha256，否则每次构建都会触发重建。
        self.assertNotIn("generated_at", B.CONTENT_RULE)

    def test_ignored_supplementary_files_are_reported(self) -> None:
        source = self.tmp / "source"
        shutil.copytree(TESTDATA, source)
        (source / "basewrd31.txt").write_text("POLYNESIA 135\n", encoding="utf-8")
        manifest = B.build(source, self.tmp / "out2", [1, 2])
        names = [item["name"] for item in manifest["scope"]["ignored_files"]]
        self.assertEqual(["basewrd31.txt"], names)
        self.assertIn("补充表", manifest["scope"]["ignored_files"][0]["reason"])

    def test_verify_passes_and_catches_tampering(self) -> None:
        self.assertEqual(0, B.verify(self.out, TESTDATA), "刚构建的产物应通过校验")
        tampered = self.out / "lexicon-forms.tsv"
        tampered.write_text(tampered.read_text(encoding="utf-8") + "zzz\tzzz\t1\t0\t0\n",
                            encoding="utf-8")
        self.assertEqual(1, B.verify(self.out, None), "产物被改动后验签必须失败")

    def test_manifest_records_source_hashes_and_scope(self) -> None:
        files = self.manifest["source"]["files"]
        self.assertEqual(["basewrd1.txt", "basewrd2.txt"], [item["name"] for item in files])
        for item in files:
            self.assertRegex(item["sha256"], r"^[0-9a-f]{64}$")
        self.assertEqual([1, 2], self.manifest["scope"]["bands"])
        self.assertIsNone(self.manifest["source"]["url"], "来源页没有记录，就写 null 不编造")
        json.dumps(self.manifest)  # 必须可序列化


if __name__ == "__main__":
    unittest.main(argv=["test_build_wordlist_index.py", "-v"])
