#!/usr/bin/env python3
"""generate_content.py 的自测（纯离线，不发请求）。

锁住写进脚本的那些「踩过的坑」：词形匹配、清洗策略、截断识别、半行修复。
跑法：`python3 scripts/vocab/test_generate_content.py -v`
"""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "generate_content.py"


def load_module():
    spec = importlib.util.spec_from_file_location("generate_content", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


g = load_module()


def make_example(text: str, popularity: int = 1) -> dict:
    return {
        "text": text,
        "translations": {"zh": "译文"},
        "partOfSpeech": "noun",
        "usagePattern": text.lower().rstrip("."),
        "popularity": popularity,
    }


def make_record(word: str = "apple", examples: list[dict] | None = None) -> dict:
    return {
        "schemaVersion": 2,
        "word": word,
        "language": "en",
        "basicInfo": {
            "pronunciation": {"uk": "/ˈæp.əl/", "us": "/ˈæp.əl/"},
            "partOfSpeech": ["noun"],
            "image": None,
        },
        "meanings": {"zh": "苹果", "en": "a round fruit"},
        "examples": examples or [make_example("She ate an apple.")],
        "wordFormation": {
            "analysis": "From Old English æppel.",
            "parts": [{"text": "apple", "type": "root", "meaning": "fruit"}],
            "confidence": "high",
        },
        "memoryAids": {"synonyms": [], "antonyms": [], "mnemonic": "an apple a day", "supplement": ""},
        "wordFamily": {"items": [{"word": "apples", "partOfSpeech": ["noun"],
                                 "meanings": {"zh": "苹果（复数）", "en": "plural of apple"}}]},
    }


class WordFormTest(unittest.TestCase):
    """词表屈折不全 → 规则变化 + 内置不规则表。"""

    def test_regular_inflections_missing_from_forms_map(self):
        self.assertTrue(g.looks_like_word("She owns a small bakery.", "own"))
        self.assertTrue(g.looks_like_word("She willed her estate to charity.", "will"))
        self.assertTrue(g.looks_like_word("He was outed as a spy.", "out"))

    def test_irregular_verb_from_builtin_table(self):
        self.assertTrue(g.looks_like_word("He left his umbrella on the train.", "leave"))
        self.assertTrue(g.looks_like_word("The dog bit the postman.", "bite"))
        self.assertTrue(g.looks_like_word("She wound the clock before bed.", "wind"))

    def test_plural_rules(self):
        self.assertTrue(g.looks_like_word("The leaves turn red in autumn.", "leaf"))
        self.assertTrue(g.looks_like_word("Wolves hunt at night.", "wolf"))
        self.assertTrue(g.looks_like_word("The children are playing.", "child"))

    def test_forms_map_still_wins(self):
        # be 的 am/is/are 来自词表，不该只靠规则推导
        self.assertTrue(g.looks_like_word("The weather is nice.", "be", ["am", "is", "are", "was"]))

    def test_boundaries_prevent_false_positives(self):
        self.assertFalse(g.looks_like_word("Thesis done.", "the"))
        # "This" 里的 is 不该命中；下面这句没有独立的 is
        self.assertFalse(g.looks_like_word("This sample lacks it.", "is"))
        self.assertTrue(g.looks_like_word("This is fine.", "is"))
        # 复合词里的词不算命中（会被清洗掉那条例句，而不是误判为通过）
        self.assertFalse(g.looks_like_word("They keep chickens in the backyard.", "yard"))


class ValidateSanitizeTest(unittest.TestCase):
    """模型噪音就地清洗，不整词重试。"""

    def test_clean_record_passes(self):
        record = make_record()
        self.assertEqual(g.validate(record, "apple", "en", 6), [])

    def test_example_without_word_is_dropped_and_popularity_renumbered(self):
        record = make_record(examples=[
            make_example("She ate an apple.", 1),
            make_example("Rain fell all night.", 2),
            make_example("He sliced the apple.", 3),
        ])
        self.assertEqual(g.validate(record, "apple", "en", 6), [])
        texts = [e["text"] for e in record["examples"]]
        self.assertEqual(texts, ["She ate an apple.", "He sliced the apple."])
        self.assertEqual([e["popularity"] for e in record["examples"]], [1, 2])

    def test_duplicate_sentences_are_dropped(self):
        record = make_record(examples=[
            make_example("She ate an apple.", 1),
            make_example("She ate an apple.", 2),
        ])
        self.assertEqual(g.validate(record, "apple", "en", 6), [])
        self.assertEqual(len(record["examples"]), 1)

    def test_all_examples_invalid_is_a_problem(self):
        record = make_record(examples=[make_example("Rain fell all night.")])
        problems = g.validate(record, "apple", "en", 6)
        self.assertTrue(any("no sentence contains" in p for p in problems))

    def test_word_family_self_reference_and_duplicates_are_cleaned(self):
        record = make_record()
        record["wordFamily"]["items"] = [
            {"word": "apple", "partOfSpeech": ["noun"], "meanings": {"zh": "苹果", "en": "apple"}},
            {"word": "apples", "partOfSpeech": ["noun"], "meanings": {"zh": "复数", "en": "plural"}},
            {"word": "apples", "partOfSpeech": ["noun"], "meanings": {"zh": "重复", "en": "dup"}},
            {"word": "applesauce", "partOfSpeech": ["noun"], "meanings": {"zh": "苹果酱", "en": "sauce"}},
        ]
        self.assertEqual(g.validate(record, "apple", "en", 6), [])
        self.assertEqual([i["word"] for i in record["wordFamily"]["items"]], ["apples", "applesauce"])

    def test_incomplete_formation_part_is_dropped(self):
        record = make_record()
        record["wordFormation"]["parts"] = [
            {"text": "apple", "type": "root", "meaning": "fruit"},
            {"text": "ap", "type": "prefix"},
        ]
        self.assertEqual(g.validate(record, "apple", "en", 6), [])
        self.assertEqual(len(record["wordFormation"]["parts"]), 1)

    def test_hard_errors_still_fail(self):
        record = make_record()
        record["meanings"]["en"] = ""
        self.assertIn("meanings.en empty", g.validate(record, "apple", "en", 6))

        record = make_record()
        record["language"] = "es"
        self.assertTrue(any("language mismatch" in p for p in g.validate(record, "apple", "en", 6)))


class ResponseCheckTest(unittest.TestCase):
    """thinking 没关 / 预算不够 → 必须识别成失败，不能当成功写库。"""

    def response(self, finish="stop", content='{"ok": true}', completion=1200,
                 reasoning=0):
        usage = {"prompt_tokens": 900, "completion_tokens": completion}
        if reasoning:
            usage["completion_tokens_details"] = {"reasoning_tokens": reasoning}
        return {"choices": [{"finish_reason": finish, "message": {"content": content}}],
                "usage": usage}

    def test_healthy_response(self):
        problems, _ = g.response_problems(self.response(), 6000)
        self.assertEqual(problems, [])

    def test_length_is_a_problem(self):
        problems, _ = g.response_problems(self.response(finish="length", content=""), 6000)
        self.assertTrue(any("finish_reason=length" in p for p in problems))

    def test_empty_content_is_a_problem(self):
        problems, _ = g.response_problems(self.response(content="   "), 6000)
        self.assertTrue(any("empty content" in p for p in problems))

    def test_reasoning_tokens_are_a_problem(self):
        problems, _ = g.response_problems(
            self.response(content="", completion=4000, reasoning=3600), 6000)
        self.assertTrue(any("thinking not disabled" in p for p in problems))


class FileSafetyTest(unittest.TestCase):
    def test_partial_last_line_is_truncated(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "out.jsonl"
            path.write_text('{"word": "a"}\n{"word": "b", "lang', encoding="utf-8")
            self.assertEqual(g.repair_partial_jsonl(path), 1)
            self.assertEqual(path.read_text(encoding="utf-8"), '{"word": "a"}\n')

    def test_complete_file_untouched(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "out.jsonl"
            path.write_text('{"word": "a"}\n', encoding="utf-8")
            self.assertEqual(g.repair_partial_jsonl(path), 0)

    def test_corrupt_lines_are_skipped_and_counted(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "out.jsonl"
            path.write_text('{"word": "a", "language": "en"}\nnot json\n', encoding="utf-8")
            done, corrupt = g.load_done(path)
            self.assertEqual(done, {("a", "en")})
            self.assertEqual(corrupt, 1)


class PayloadTest(unittest.TestCase):
    def test_opencode_disables_thinking_and_local_uses_template_flag(self):
        payload = g.request_payload("opencode", "apple", "en", "deepseek-v4.1-flash", 6, 6000, 0.2)
        self.assertEqual(payload["thinking"], {"type": "disabled"})
        self.assertEqual(payload["max_tokens"], 6000)
        self.assertEqual(payload["response_format"], {"type": "json_object"})

        payload = g.request_payload("local", "apple", "en", "qwen", 6, 4000, 0.2)
        self.assertEqual(payload["chat_template_kwargs"], {"enable_thinking": False})
        self.assertNotIn("thinking", payload)

    def test_user_prompt_only_requests_zh_translation_for_english(self):
        prompt = g.user_prompt("apple", "en", 6)
        self.assertIn("translations exclude en; use zh)", prompt)
        self.assertNotIn(", es", prompt)


class WordListReportTest(unittest.TestCase):
    def test_non_alpha_noise_is_flagged_but_short_words_are_only_listed(self):
        lines = g.word_list_report(["apple", "be", "et", "3rd", "well-known"])
        text = "\n".join(lines)
        self.assertIn("去重后 5 个", text)
        self.assertIn("短词 2", text)          # be, et
        self.assertIn("非字母噪声 1", text)     # 3rd
        self.assertIn("3rd", text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
