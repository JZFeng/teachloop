#!/usr/bin/env python3
"""批量生成 v2.3 词内容（长期维护的 utility；运行时生成在 Java 侧，见 T439）。

prompt 与请求参数来自共享契约 teachloop-vocab/src/main/resources/vocab/content-prompt-v3.0.json
（Java 运行时读同一份），逐词生成 word_detail_page v2 JSON 写 JSONL，可断点续跑、可并发、带结构校验。

「踩过的坑」都固化成了脚本里的控制，不是文档建议：

| 坑 | 脚本里的控制 |
| :--- | :--- |
| Cloudflare 403 error 1010（默认 urllib UA） | 固定 DEFAULT_USER_AGENT；preflight 遇到 403 直接给结论 |
| 400 MissingSessionID | 每个请求随机 x-opencode-session（网关路由头，非对话上下文） |
| thinking 吃掉 max_tokens 导致正文截断 | thinking:disabled / enable_thinking:false；check 检 finish_reason=length 与 reasoning tokens，命中即重试；preflight 断言 |
| 词表屈折不全（will→willed、leave→left、leaf→leaves） | 规则屈折 + 内置不规则表（动词/名词/形容词），usagePattern 作旁证 |
| 模型噪音（同族词重复、parts 缺字段、例句不含目标词） | 就地清洗并重排 popularity，不整词重试 |
| 中途被杀留下半行 JSON | 启动时截断末尾残行；读到坏行跳过并计数 |
| 无人值守跑飞成本 | --max-cost-usd 达到上限即停止提交新词，已完成保留，退出码 3 |
| 批次边界不可见 | 每轮追加 .runs.jsonl、.status.json 心跳、manifest 带 prompt sha256 |

用法：
  LLM_API_KEY=... python3 scripts/vocab/generate_content.py \
      --words fixtures-local/vocab-content/words-top10k.txt --language en \
      --output fixtures-local/vocab-content/vocab-content-en-v2.3.jsonl \
      --provider opencode --model deepseek-v4.1-flash --workers 6 --max-cost-usd 8

注意：
- 输出是 JSONL，可重复执行；已成功的词（word+language）自动跳过。
- 并发只在单一进程内（线程池），不要同时起多个进程写同一个文件。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
from pathlib import Path

PROMPT_VERSION = "vocab-content-v3.0"
# prompt 与请求参数只有一份：Java 运行时（AiVocabularyContentGenerator）读同一个资源文件，
# 批量工具也从这里读，避免两边各维护一份 prompt 慢慢漂移。
CONTRACT_DEFAULT = (Path(__file__).resolve().parents[2]
                    / "teachloop-vocab/src/main/resources/vocab/content-prompt-v3.0.json")
RETRYABLE_STATUS = {408, 409, 425, 429, 500, 502, 503, 504}
# 超过这个 reasoning token 数，说明 thinking 没关掉（正文预算会被吃掉）
MAX_REASONING_TOKENS = 200

# 词表（basewords 成员表）不全：不规则屈折这里兜底
IRREGULAR_VERBS: dict[str, list[str]] = {
    "be": ["am", "is", "are", "was", "were", "been", "being"],
    "have": ["has", "had", "having"],
    "do": ["does", "did", "done", "doing"],
    "say": ["says", "said", "saying"],
    "go": ["goes", "went", "gone", "going"],
    "get": ["gets", "got", "gotten", "getting"],
    "make": ["makes", "made", "making"],
    "know": ["knows", "knew", "known", "knowing"],
    "think": ["thinks", "thought", "thinking"],
    "take": ["takes", "took", "taken", "taking"],
    "see": ["sees", "saw", "seen", "seeing"],
    "come": ["comes", "came", "coming"],
    "find": ["finds", "found", "finding"],
    "give": ["gives", "gave", "given", "giving"],
    "tell": ["tells", "told", "telling"],
    "become": ["becomes", "became", "becoming"],
    "leave": ["leaves", "left", "leaving"],
    "feel": ["feels", "felt", "feeling"],
    "put": ["puts", "putting"],
    "bring": ["brings", "brought", "bringing"],
    "begin": ["begins", "began", "begun", "beginning"],
    "keep": ["keeps", "kept", "keeping"],
    "hold": ["holds", "held", "holding"],
    "write": ["writes", "wrote", "written", "writing"],
    "stand": ["stands", "stood", "standing"],
    "hear": ["hears", "heard", "hearing"],
    "mean": ["means", "meant", "meaning"],
    "meet": ["meets", "met", "meeting"],
    "run": ["runs", "ran", "running"],
    "pay": ["pays", "paid", "paying"],
    "sit": ["sits", "sat", "sitting"],
    "speak": ["speaks", "spoke", "spoken", "speaking"],
    "lead": ["leads", "led", "leading"],
    "read": ["reads", "reading"],
    "grow": ["grows", "grew", "grown", "growing"],
    "lose": ["loses", "lost", "losing"],
    "fall": ["falls", "fell", "fallen", "falling"],
    "send": ["sends", "sent", "sending"],
    "build": ["builds", "built", "building"],
    "understand": ["understands", "understood", "understanding"],
    "draw": ["draws", "drew", "drawn", "drawing"],
    "break": ["breaks", "broke", "broken", "breaking"],
    "spend": ["spends", "spent", "spending"],
    "rise": ["rises", "rose", "risen", "rising"],
    "drive": ["drives", "drove", "driven", "driving"],
    "buy": ["buys", "bought", "buying"],
    "wear": ["wears", "wore", "worn", "wearing"],
    "choose": ["chooses", "chose", "chosen", "choosing"],
    "seek": ["seeks", "sought", "seeking"],
    "throw": ["throws", "threw", "thrown", "throwing"],
    "catch": ["catches", "caught", "catching"],
    "deal": ["deals", "dealt", "dealing"],
    "win": ["wins", "won", "winning"],
    "forget": ["forgets", "forgot", "forgotten", "forgetting"],
    "eat": ["eats", "ate", "eaten", "eating"],
    "drink": ["drinks", "drank", "drunk", "drinking"],
    "sing": ["sings", "sang", "sung", "singing"],
    "swim": ["swims", "swam", "swum", "swimming"],
    "fly": ["flies", "flew", "flown", "flying"],
    "ride": ["rides", "rode", "ridden", "riding"],
    "hide": ["hides", "hid", "hidden", "hiding"],
    "bite": ["bites", "bit", "bitten", "biting"],
    "wind": ["winds", "wound", "winding"],
    "bear": ["bears", "bore", "borne", "bearing"],
    "beat": ["beats", "beaten", "beating"],
    "bend": ["bends", "bent", "bending"],
    "bind": ["binds", "bound", "binding"],
    "blow": ["blows", "blew", "blown", "blowing"],
    "burn": ["burns", "burnt", "burning"],
    "dig": ["digs", "dug", "digging"],
    "feed": ["feeds", "fed", "feeding"],
    "fight": ["fights", "fought", "fighting"],
    "flee": ["flees", "fled", "fleeing"],
    "freeze": ["freezes", "froze", "frozen", "freezing"],
    "hang": ["hangs", "hung", "hanging"],
    "hurt": ["hurts", "hurting"],
    "lend": ["lends", "lent", "lending"],
    "light": ["lights", "lit", "lighting"],
    "shake": ["shakes", "shook", "shaken", "shaking"],
    "shine": ["shines", "shone", "shining"],
    "shoot": ["shoots", "shot", "shooting"],
    "sleep": ["sleeps", "slept", "sleeping"],
    "slide": ["slides", "slid", "sliding"],
    "smell": ["smells", "smelt", "smelling"],
    "spill": ["spills", "spilt", "spilling"],
    "split": ["splits", "splitting"],
    "spread": ["spreads", "spreading"],
    "steal": ["steals", "stole", "stolen", "stealing"],
    "stick": ["sticks", "stuck", "sticking"],
    "sting": ["stings", "stung", "stinging"],
    "strike": ["strikes", "struck", "striking"],
    "sweep": ["sweeps", "swept", "sweeping"],
    "swing": ["swings", "swung", "swinging"],
    "teach": ["teaches", "taught", "teaching"],
    "tear": ["tears", "tore", "torn", "tearing"],
    "wake": ["wakes", "woke", "woken", "waking"],
    "weep": ["weeps", "wept", "weeping"],
    "sell": ["sells", "sold", "selling"],
    "shut": ["shuts", "shutting"],
    "lie": ["lies", "lay", "lain", "lying"],
    "lay": ["lays", "laid", "laying"],
}

IRREGULAR_NOUNS: dict[str, list[str]] = {
    "man": ["men"], "woman": ["women"], "child": ["children"],
    "foot": ["feet"], "tooth": ["teeth"], "mouse": ["mice"], "goose": ["geese"],
    "person": ["people"], "ox": ["oxen"], "life": ["lives"], "wife": ["wives"],
    "knife": ["knives"], "half": ["halves"], "shelf": ["shelves"], "wolf": ["wolves"],
    "thief": ["thieves"], "crisis": ["crises"], "analysis": ["analyses"],
    "basis": ["bases"], "thesis": ["theses"], "phenomenon": ["phenomena"],
    "criterion": ["criteria"], "datum": ["data"], "medium": ["media"],
    "index": ["indices", "indexes"], "appendix": ["appendices"],
}

IRREGULAR_ADJECTIVES: dict[str, list[str]] = {
    "good": ["better", "best"], "bad": ["worse", "worst"],
    "far": ["farther", "further", "farthest", "furthest"],
    "little": ["less", "least"], "many": ["more", "most"], "much": ["more", "most"],
}

SUFFIXES = ("", "s", "es", "ed", "d", "ing", "ly", "er", "est", "n", "en",
            "ies", "ied", "ier", "iest", "ings", "ers")

# --------------------------------------------------------------------------- #
# 共享契约（prompt + 请求参数）
# --------------------------------------------------------------------------- #

CONTRACT: dict = {}
SYSTEM: str = ""
LANGUAGES: tuple[str, ...] = ()
REQUEST: dict = {}
DEFAULT_USER_AGENT: str = ""
OPENCODE_BASE_URL: str = ""
FORMS_MAP: dict[str, list[str]] = {}


def apply_contract(path: Path) -> None:
    """加载 Java/Python 共用的 prompt 契约；版本不一致直接拒绝启动。"""
    global CONTRACT, SYSTEM, LANGUAGES, REQUEST, DEFAULT_USER_AGENT, OPENCODE_BASE_URL
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    version = str(data.get("promptVersion", ""))
    if version != PROMPT_VERSION:
        raise SystemExit(f"prompt 契约版本不一致：{path} 是 {version}，脚本是 {PROMPT_VERSION}")
    CONTRACT = data
    SYSTEM = data["system"]
    LANGUAGES = tuple(data["languages"])
    REQUEST = data["request"]
    DEFAULT_USER_AGENT = REQUEST.get("userAgent") or "teachloop-vocab-content/1.0"
    OPENCODE_BASE_URL = data["providers"]["opencode"]["baseUrl"]


apply_contract(Path(os.environ.get("VOCAB_PROMPT_FILE", CONTRACT_DEFAULT)))


# --------------------------------------------------------------------------- #
# 请求
# --------------------------------------------------------------------------- #

def user_prompt(word: str, language: str, example_count: int, extra: str = "") -> str:
    """用户提示词模板来自契约；acceptedForms 限制 morphology 里可出现的词形。"""
    others = ", ".join(lang for lang in LANGUAGES if lang != language)
    accepted = FORMS_MAP.get(word.lower()) or [word]
    prompt = CONTRACT["userPrompt"].format(
        word=word, language=language, otherLanguages=others, exampleCount=example_count,
        acceptedForms=", ".join(accepted))
    return prompt + ("\n" + extra if extra else "")


def request_payload(provider: str, word: str, language: str, model: str,
                    example_count: int, max_tokens: int, temperature: float,
                    extra: str = "") -> dict:
    payload = {
        "model": model,
        "temperature": temperature,
        "response_format": REQUEST.get("responseFormat", {"type": "json_object"}),
        "max_tokens": max_tokens,
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": user_prompt(word, language, example_count, extra)},
        ],
    }
    # 关思考：默认会先吐 9k–24k reasoning tokens，把 max_tokens 吃光导致正文截断
    style = CONTRACT["providers"][provider].get("disableThinking")
    if style == "thinking":
        payload["thinking"] = {"type": "disabled"}
    elif style == "chat_template_kwargs":
        payload["chat_template_kwargs"] = {"enable_thinking": False}
    return payload


def call_model(url: str, key: str, payload: dict, timeout: int, provider: str) -> dict:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {key}",
        "User-Agent": DEFAULT_USER_AGENT,
    }
    if provider == "opencode":
        # 网关要求该头；只是路由，不是对话上下文，每个请求随机即可
        headers[REQUEST.get("sessionHeader", "x-opencode-session")] = str(uuid.uuid4())
    request = urllib.request.Request(url, data=body, headers=headers, method="POST")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def response_problems(response: dict, max_tokens: int) -> tuple[list[str], dict]:
    """返回（问题列表, usage）。finish_reason=length / reasoning tokens 都算失败。"""
    problems: list[str] = []
    usage = response.get("usage") or {}
    choices = response.get("choices") or []
    if not choices:
        return ["no choices in response"], usage
    choice = choices[0]
    finish = choice.get("finish_reason")
    content = (choice.get("message") or {}).get("content") or ""
    if finish == "length":
        problems.append(f"finish_reason=length (max_tokens={max_tokens} 不够)")
    elif finish not in (None, "stop"):
        problems.append(f"finish_reason={finish}")
    if not content.strip():
        problems.append("empty content")
    if int(usage.get("completion_tokens") or 0) >= max_tokens:
        problems.append("completion_tokens hit the max_tokens budget")
    details = usage.get("completion_tokens_details") or {}
    reasoning = int(details.get("reasoning_tokens") or 0)
    if reasoning > MAX_REASONING_TOKENS:
        problems.append(f"thinking not disabled (reasoning_tokens={reasoning})")
    return problems, usage


# --------------------------------------------------------------------------- #
# 词形匹配
# --------------------------------------------------------------------------- #

def derived_forms(word: str) -> set[str]:
    """词表里没收录的屈折：先规则变化，再查内置不规则表。"""
    w = word.lower().strip()
    if not w:
        return set()
    forms = {w}
    for suffix in SUFFIXES:
        forms.add(w + suffix)
    if w.endswith("e"):
        forms.update({w + "d", w + "s", w[:-1] + "ing"})
    if w.endswith("f"):
        forms.update({w[:-1] + "ves", w + "s"})
    if w.endswith("fe"):
        forms.add(w[:-2] + "ves")
    if w.endswith("y") and len(w) > 2:
        forms.update({w[:-1] + "ies", w[:-1] + "ied", w[:-1] + "ier", w[:-1] + "iest"})
    if len(w) > 2 and w[-1] not in "aeiou" and w[-2] in "aeiou":
        # stop → stopped / stopping
        forms.update({w + w[-1] + "ed", w + w[-1] + "ing", w + w[-1] + "er", w + w[-1] + "est"})
    for table in (IRREGULAR_VERBS, IRREGULAR_NOUNS, IRREGULAR_ADJECTIVES):
        forms.update(form.lower() for form in table.get(w, ()))
    return forms


def looks_like_word(text: str, word: str, forms: list[str] | None = None) -> bool:
    """目标词或它的标准屈折形式出现在句子里。

    forms 来自词表（basewords 的成员表），但词表并不全，所以再补一层规则屈折与不规则表。
    """
    lowered = text.lower()
    candidates = {c.lower().strip() for c in (forms or []) if c and c.strip()}
    candidates |= derived_forms(word)
    for form in candidates:
        # 用非字母边界，避免 "is" 命中 "this"
        if re.search(rf"(?<![a-z]){re.escape(form)}(?![a-z])", lowered):
            return True
    return False


# --------------------------------------------------------------------------- #
# 结构校验（含清洗）
# --------------------------------------------------------------------------- #


INFLECTION_SUFFIXES = ("s", "es", "ed", "d", "ing", "ies", "ied", "'s")


def is_inflection_of(base: str, form: str) -> bool:
    """屈折变化（复数/过去式/分词）不要求在 chain 里出现。"""
    b, w = base.lower(), form.lower()
    if w == b:
        return True
    if w in {b + suffix for suffix in INFLECTION_SUFFIXES}:
        return True
    return len(b) >= 3 and w.startswith(b[:-1]) and w.endswith(("s", "ed", "ing"))


def coverage_gaps(obj: dict, word: str, chain: list[dict]) -> list[str]:
    reached = {str(step.get("to", "")).lower() for step in chain}
    return [item for item in derivational_family(obj, word) if item.lower() not in reached]


def derivational_family(obj: dict, word: str) -> list[str]:
    items = (obj.get("wordFamily") or {}).get("items") or []
    return [str(item.get("word", "")).strip() for item in items
            if isinstance(item, dict) and str(item.get("word", "")).strip()
            and not is_inflection_of(word, str(item.get("word", "")).strip())]

def validate(obj: dict, word: str, language: str, example_count: int,
             forms: list[str] | None = None) -> list[str]:
    problems: list[str] = []
    if obj.get("schemaVersion") != 2:
        problems.append("schemaVersion != 2")
    if str(obj.get("word", "")).strip().lower() != word.lower():
        problems.append(f"word mismatch: {obj.get('word')!r}")
    if obj.get("language") != language:
        problems.append(f"language mismatch: {obj.get('language')!r}")

    basic = obj.get("basicInfo") or {}
    pronunciation = basic.get("pronunciation") or {}
    if not (pronunciation.get("uk") and pronunciation.get("us")):
        problems.append("basicInfo.pronunciation missing uk/us")
    if not basic.get("partOfSpeech"):
        problems.append("basicInfo.partOfSpeech empty")

    meanings = obj.get("meanings") or {}
    for lang in LANGUAGES:
        if not str(meanings.get(lang, "")).strip():
            problems.append(f"meanings.{lang} empty")

    core = obj.get("coreSemantics")
    if not isinstance(core, dict):
        problems.append("coreSemantics missing")
    else:
        if not str(core.get("core", "")).strip():
            problems.append("coreSemantics.core empty")
        elif len(str(core.get("core")).strip()) > 30:
            problems.append("coreSemantics.core 超过 30 字")
        image = str(core.get("mentalImage", "")).strip()
        if not image:
            problems.append("coreSemantics.mentalImage empty")
        elif len(image) > 60:
            problems.append("coreSemantics.mentalImage 超过 60 字")

    examples = obj.get("examples")
    if not isinstance(examples, list) or not examples:
        problems.append(f"examples count must be 1..{example_count}")
    else:
        # 模型偶尔塞一条不含目标词的例句（leaf → "leaves turn red"、yard → "backyard" 这类边界），
        # 丢掉这一条比整词重试划算；保留下来的句子重新编号 popularity = 1..N
        kept: list[dict] = []
        seen_sentences: set[str] = set()
        for example in examples:
            if not isinstance(example, dict):
                continue
            text = str(example.get("text", "")).strip()
            if not text or text.lower() in seen_sentences:
                continue
            if not looks_like_word(text, word, forms) and not looks_like_word(
                    str(example.get("usagePattern", "")), word, forms):
                continue
            seen_sentences.add(text.lower())
            kept.append(example)
        kept = kept[:example_count]
        if not kept:
            problems.append("examples: no sentence contains the target word")
        else:
            for order, example in enumerate(kept, start=1):
                example["popularity"] = order
            obj["examples"] = kept
            for index, example in enumerate(kept):
                if not str(example.get("partOfSpeech", "")).strip():
                    problems.append(f"examples[{index}].partOfSpeech empty")
                if not str(example.get("usagePattern", "")).strip():
                    problems.append(f"examples[{index}].usagePattern empty")
                translations = example.get("translations") or {}
                for lang in LANGUAGES:
                    if lang == language:
                        continue
                    if not str(translations.get(lang, "")).strip():
                        problems.append(f"examples[{index}].translations.{lang} empty")

    formation = obj.get("wordFormation")
    if formation is not None and not isinstance(formation, dict):
        problems.append("wordFormation must be an object")
    elif isinstance(formation, dict):
        if not str(formation.get("analysis", "")).strip():
            problems.append("wordFormation.analysis empty")
        if formation.get("confidence") not in ("high", "medium", "low"):
            problems.append("wordFormation.confidence invalid")
        parts = formation.get("parts", [])
        if not isinstance(parts, list):
            problems.append("wordFormation.parts must be a list")
        else:
            kept = [part for part in parts
                    if isinstance(part, dict)
                    and all(str(part.get(key, "")).strip() for key in ("text", "type", "meaning"))]
            formation["parts"] = kept
            if not kept:
                problems.append("wordFormation.parts empty（只有真正的单根词允许单条 root）")
        # chain：丢空步，要求能够解释派生词
        chain = formation.get("chain")
        if chain is None:
            chain = []
        if not isinstance(chain, list):
            problems.append("wordFormation.chain must be a list")
            chain = []
        cleaned_chain = []
        for step in chain:
            if not isinstance(step, dict):
                continue
            sources = [str(x).strip() for x in (step.get("from") or []) if str(x).strip()]
            target = str(step.get("to", "")).strip()
            if not sources or not target:
                continue
            if len(sources) == 1 and sources[0].lower() == target.lower():
                continue  # 空步（struggle → struggle）
            if not str(step.get("partOfSpeech", "")).strip() or not str(step.get("meaning", "")).strip():
                continue
            cleaned_chain.append({"from": sources, "to": target,
                                  "partOfSpeech": str(step["partOfSpeech"]).strip(),
                                  "meaning": str(step["meaning"]).strip()})
        part_texts = {str(part.get("text", "")).strip().lower() for part in formation.get("parts") or []}
        part_texts.add(word.lower())  # 词本身可以作为派生基础
        reached: set[str] = set()
        for step in cleaned_chain:
            outsiders = [x for x in step["from"]
                         if x.lower() not in part_texts and x.lower() not in reached]
            if outsiders:
                problems.append("chain 引入了 parts 之外的元素: " + ", ".join(outsiders))
            reached.add(step["to"].lower())
        formation["chain"] = cleaned_chain
        monomorphemic = len(formation.get("parts") or []) == 1
        if not cleaned_chain and not monomorphemic:
            problems.append("wordFormation.chain empty（词缀词必须给出派生阶梯）")
        aids = obj.get("memoryAids")
        mnemonic = str((aids or {}).get("mnemonic", "")).strip()
        if mnemonic and isinstance(aids, dict):
            # mnemonic 是可选字段：可拆词或疑似伪词源时直接清空，不为它重试整词
            if len(formation.get("parts") or []) >= 2 or re.search(
                    r"[+＋]|词根|词缀|前缀|后缀|root|prefix|suffix", mnemonic, re.I):
                aids["mnemonic"] = ""
        # chain 覆盖不全只算告警（family 里常有不由该词头直接派生的词），不因此丢弃整条内容

    family = (obj.get("wordFamily") or {}).get("items")
    if not isinstance(family, list):
        problems.append("wordFamily.items missing")
    else:
        # 同族词里重复词头、或同一条重复出现，属于可直接清洗的噪音，不算硬错误
        cleaned: list[dict] = []
        seen = {word.lower()}
        for item in family:
            if not isinstance(item, dict):
                problems.append("wordFamily item is not an object")
                continue
            form = str(item.get("word", "")).strip().lower()
            if not form:
                problems.append("wordFamily item word empty")
                continue
            if form in seen:
                continue
            seen.add(form)
            cleaned.append(item)
        obj["wordFamily"] = {"items": cleaned[:6]}
        for index, item in enumerate(cleaned[:6]):
            if not item.get("partOfSpeech"):
                problems.append(f"wordFamily[{index}].partOfSpeech empty")
            for lang in LANGUAGES:
                if not str((item.get("meanings") or {}).get(lang, "")).strip():
                    problems.append(f"wordFamily[{index}].meanings.{lang} empty")

    return problems



def _v3_text(value) -> str:
    return str(value).strip() if value is not None else ""


def _v3_pos(value) -> str:
    if isinstance(value, list):
        return ", ".join(_v3_text(x) for x in value if _v3_text(x))
    return _v3_text(value)


def validate_v3(obj: dict, word: str, language: str, example_count: int,
                forms: list[str] | None = None) -> list[str]:
    problems: list[str] = []
    if obj.get("schemaVersion") != 3:
        problems.append("schemaVersion != 3")
    if str(obj.get("word", "")).strip().lower() != word.lower():
        problems.append(f"word mismatch: {obj.get('word')!r}")
    if obj.get("language") != language:
        problems.append(f"language mismatch: {obj.get('language')!r}")

    basic = obj.get("basicInfo") or {}
    pronunciation = basic.get("pronunciation") or {}
    if not (_v3_text(pronunciation.get("uk")) and _v3_text(pronunciation.get("us"))):
        problems.append("basicInfo.pronunciation missing uk/us")
    if not basic.get("partOfSpeech"):
        problems.append("basicInfo.partOfSpeech empty")

    meanings = obj.get("meanings") or {}
    for lang in LANGUAGES:
        if not _v3_text(meanings.get(lang)):
            problems.append(f"meanings.{lang} empty")

    core = obj.get("coreSemantics")
    if not isinstance(core, dict):
        problems.append("coreSemantics missing")
    else:
        core_text = _v3_text(core.get("core"))
        if not core_text:
            problems.append("coreSemantics.core empty")
        elif len(core_text) > 30:
            problems.append("coreSemantics.core 超过 30 字")
        image = _v3_text(core.get("mentalImage"))
        if not image:
            problems.append("coreSemantics.mentalImage empty")
        elif len(image) > 60:
            problems.append("coreSemantics.mentalImage 超过 60 字")

    accepted = {_v3_text(x).lower() for x in (forms or [word]) if _v3_text(x)}
    accepted.add(word.lower())

    examples = obj.get("examples")
    if not isinstance(examples, list) or not examples:
        problems.append(f"examples count must be 1..{example_count}")
    else:
        kept: list[dict] = []
        seen_sentences: set[str] = set()
        for example in examples:
            if not isinstance(example, dict):
                continue
            sentence = _v3_text(example.get("text"))
            if not sentence or sentence.lower() in seen_sentences:
                continue
            if not looks_like_word(sentence, word, forms) and not looks_like_word(
                    _v3_text(example.get("usagePattern")), word, forms):
                continue
            seen_sentences.add(sentence.lower())
            kept.append(example)
        kept = kept[:example_count]
        if not kept:
            problems.append("examples: no sentence contains the target word")
        else:
            for order, example in enumerate(kept, start=1):
                example["popularity"] = order
            obj["examples"] = kept
            for index, example in enumerate(kept):
                if not _v3_text(example.get("partOfSpeech")):
                    problems.append(f"examples[{index}].partOfSpeech empty")
                if not _v3_text(example.get("usagePattern")):
                    problems.append(f"examples[{index}].usagePattern empty")
                translations = example.get("translations") or {}
                for lang in LANGUAGES:
                    if lang == language:
                        continue
                    if not _v3_text(translations.get(lang)):
                        problems.append(f"examples[{index}].translations.{lang} empty")

    morphology = obj.get("morphology")
    if not isinstance(morphology, dict):
        problems.append("morphology missing")
        morphology = {}
    modern = _v3_text(morphology.get("modernNote"))
    if not modern:
        problems.append("morphology.modernNote empty")
    elif len(modern) > 260:
        problems.append("morphology.modernNote 过长（>260 字）")

    parts: list[dict] = []
    for item in (morphology.get("parts") or []):
        if not isinstance(item, dict):
            continue
        text = _v3_text(item.get("text"))
        if not text:
            continue
        parts.append({
            "text": text,
            "type": _v3_text(item.get("type")) or "morpheme",
            "meaning": _v3_text(item.get("meaning")),
            "origin": _v3_text(item.get("origin")),
        })
    # 单条 part 等于整个词不是拆分；直接清空 parts/chain
    if len(parts) == 1 and parts[0]["text"].lower().strip("-") == word.lower():
        parts = []
    if any(not item["meaning"] for item in parts):
        problems.append("morphology.parts 有 part 缺 meaning")
    morphology["parts"] = parts

    chain: list[dict] = []
    for step in (morphology.get("chain") or []):
        if not isinstance(step, dict):
            continue
        sources = [_v3_text(x) for x in (step.get("from") or []) if _v3_text(x)]
        target = _v3_text(step.get("to"))
        if not sources or not target:
            continue
        if len(sources) == 1 and sources[0].lower() == target.lower():
            continue
        pos = _v3_pos(step.get("partOfSpeech"))
        meaning = _v3_text(step.get("meaning"))
        if not pos or not meaning:
            continue
        chain.append({"from": sources, "to": target, "partOfSpeech": pos, "meaning": meaning})
    part_texts = {item["text"].lower() for item in parts}
    part_texts.add(word.lower())
    reached: set[str] = set()
    for step in chain:
        outsiders = [x for x in step["from"]
                     if x.lower() not in part_texts and x.lower() not in reached]
        if outsiders:
            problems.append("morphology.chain 引入了 parts 之外的元素: " + ", ".join(outsiders))
        reached.add(step["to"].lower())
    # chain 是可选派生阶梯：prefix/root 简单组合（relate）或复合词（tallboy）
    # 可以只有 parts、没有 chain；卡面仍能展示结构。只有 parts 为空却有 chain 才矛盾。
    if not parts and chain:
        problems.append("morphology.chain 有内容但 parts 为空")
    morphology["chain"] = chain

    family: list[dict] = []
    seen_family: set[str] = set()
    for item in (morphology.get("family") or []):
        if not isinstance(item, dict):
            continue
        form = _v3_text(item.get("word"))
        if not form:
            continue
        key = form.lower()
        if key in seen_family or key not in accepted:
            continue
        pos = _v3_pos(item.get("partOfSpeech"))
        meaning = _v3_text(item.get("meaning"))
        if not pos or not meaning:
            continue
        seen_family.add(key)
        family.append({"word": form, "partOfSpeech": pos, "meaning": meaning})
    if word.lower() not in seen_family:
        head_meaning = _v3_text(meanings.get("zh")) or _v3_text(meanings.get("en"))
        head_pos = _v3_pos(basic.get("partOfSpeech"))
        if head_meaning and head_pos:
            family.insert(0, {"word": word, "partOfSpeech": head_pos, "meaning": head_meaning})
            seen_family.add(word.lower())
        else:
            problems.append("morphology.family missing head word")
    if not family:
        problems.append("morphology.family empty")
    family = family[:8]
    morphology["family"] = family

    derivatives: list[dict] = []
    seen_deriv: set[str] = set()
    for item in (morphology.get("derivatives") or []):
        if not isinstance(item, dict):
            continue
        form = _v3_text(item.get("word"))
        key = form.lower()
        if not form or key == word.lower() or key in seen_deriv or key not in accepted:
            continue
        pos = _v3_pos(item.get("partOfSpeech"))
        meaning = _v3_text(item.get("meaning"))
        if not pos or not meaning:
            continue
        seen_deriv.add(key)
        derivatives.append({"word": form, "partOfSpeech": pos, "meaning": meaning})
    morphology["derivatives"] = derivatives[:6]

    inflections: list[dict] = []
    seen_infl: set[str] = set()
    for item in (morphology.get("inflections") or []):
        if not isinstance(item, dict):
            continue
        form = _v3_text(item.get("word"))
        key = form.lower()
        if not form or key == word.lower() or key in seen_infl or key not in accepted:
            continue
        label = _v3_text(item.get("label"))
        if not label:
            continue
        seen_infl.add(key)
        inflections.append({"word": form, "label": label})
    morphology["inflections"] = inflections[:6]

    # family 必须包含所有 derivatives/inflections；如果还有位置，补进去
    for item in derivatives + [{"word": x["word"], "partOfSpeech": "", "meaning": x["label"]} for x in inflections]:
        key = _v3_text(item.get("word")).lower()
        if not key or key in seen_family:
            continue
        if len(family) >= 8:
            break
        pos = _v3_pos(item.get("partOfSpeech"))
        meaning = _v3_text(item.get("meaning"))
        if not pos or not meaning:
            continue
        family.append({"word": _v3_text(item.get("word")), "partOfSpeech": pos, "meaning": meaning})
        seen_family.add(key)
    morphology["family"] = family[:8]
    obj["morphology"] = morphology

    etymology = obj.get("etymology")
    if not isinstance(etymology, dict):
        problems.append("etymology missing")
    else:
        text = _v3_text(etymology.get("text"))
        if not text:
            problems.append("etymology.text empty")
        elif len(text) < 8 or len(text) > 500:
            problems.append("etymology.text 长度异常（8..500）")
        etymology["text"] = text

    aids = obj.get("memoryAids")
    if not isinstance(aids, dict):
        problems.append("memoryAids missing")
    else:
        for key in ("synonyms", "antonyms"):
            kept_aids = []
            for item in (aids.get(key) or []):
                if not isinstance(item, dict):
                    continue
                form = _v3_text(item.get("word"))
                if not form:
                    continue
                kept_aids.append({
                    "word": form,
                    "partOfSpeech": _v3_pos(item.get("partOfSpeech")),
                    "nuance": _v3_text(item.get("nuance")),
                })
            aids[key] = kept_aids
        mnemonic = _v3_text(aids.get("mnemonic"))
        if mnemonic and (len(parts) >= 2 or re.search(
                r"[+＋]|词根|词缀|前缀|后缀|root|prefix|suffix", mnemonic, re.I)):
            aids["mnemonic"] = ""
        # family/derivatives 的词形必须来自 accepted；examples 已单独校验
    return problems


# --------------------------------------------------------------------------- #
# 词表 / 文件
# --------------------------------------------------------------------------- #

def load_words(path: Path) -> list[str]:
    words: list[str] = []
    text = path.read_text(encoding="utf-8")
    stripped = text.strip()
    if stripped.startswith("["):
        data = json.loads(stripped)
        for item in data:
            if isinstance(item, str):
                words.append(item)
            elif isinstance(item, dict) and item.get("word"):
                words.append(str(item["word"]))
    else:
        for line in text.splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            words.append(line)
    seen: set[str] = set()
    unique: list[str] = []
    for word in words:
        key = word.strip().lower()
        if key and key not in seen:
            seen.add(key)
            unique.append(word.strip())
    return unique


def word_list_report(unique: list[str]) -> list[str]:
    """词表体检：只告警、不阻断（词表噪音不该让它跑不起来）。

    - 噪声：含非字母字符（数字、标点、空格），多半是解析残留，必须人看一眼；
    - 短词：长度 ≤2，既有 a/i/of 这类真词，也有 et 这类拉丁残留，只做提示。
    """
    noise = [w for w in unique if not re.fullmatch(r"[A-Za-z][A-Za-z'\-]*", w)]
    short = [w for w in unique if len(w) <= 2]
    lines = [f"词表：去重后 {len(unique)} 个，短词 {len(short)}，非字母噪声 {len(noise)}"]
    if noise:
        lines.append(f"  非字母噪声（前 15）：{', '.join(noise[:15])}")
    if short:
        lines.append(f"  短词（前 15，可能是虚词）：{', '.join(short[:15])}")
    return lines


def repair_partial_jsonl(path: Path) -> int:
    """上次被 kill 时可能留下半行 JSON：截到最后一个换行，避免污染续跑与校验。"""
    if not path.exists():
        return 0
    data = path.read_bytes()
    if not data or data.endswith(b"\n"):
        return 0
    cut = data.rfind(b"\n")
    path.write_bytes(data[: cut + 1] if cut >= 0 else b"")
    return 1


def load_done(path: Path) -> tuple[set[tuple[str, str]], int]:
    done: set[tuple[str, str]] = set()
    corrupt = 0
    if not path.exists():
        return done, corrupt
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        try:
            record = json.loads(line)
            done.add((str(record.get("word", "")).lower(), str(record.get("language", ""))))
        except Exception:
            corrupt += 1
    return done, corrupt


# --------------------------------------------------------------------------- #
# 统计
# --------------------------------------------------------------------------- #

class Stats:
    def __init__(self, price_in: float, price_out: float) -> None:
        self.lock = threading.Lock()
        self.completed = 0
        self.failed = 0
        self.aborted = 0
        self.tokens_in = 0
        self.tokens_out = 0
        self.attempts = 0
        self.http_errors: dict[str, int] = {}
        self.finish_reasons: dict[str, int] = {}
        self.latencies: list[float] = []
        self.example_hist: dict[int, int] = {}
        self.dropped_examples = 0
        self.chain_gap_words = 0
        self.price_in = price_in
        self.price_out = price_out
        self.started_at = time.time()

    def cost(self) -> float:
        with self.lock:
            return (self.tokens_in / 1_000_000 * self.price_in
                    + self.tokens_out / 1_000_000 * self.price_out)

    def record_attempt(self, status: int | None = None) -> None:
        with self.lock:
            self.attempts += 1
            if status is not None:
                key = f"HTTP {status}"
                self.http_errors[key] = self.http_errors.get(key, 0) + 1

    def record_finish(self, finish: str | None) -> None:
        if finish is None:
            return
        with self.lock:
            self.finish_reasons[finish] = self.finish_reasons.get(finish, 0) + 1

    def record_usage(self, usage: dict) -> None:
        with self.lock:
            self.tokens_in += int(usage.get("prompt_tokens") or 0)
            self.tokens_out += int(usage.get("completion_tokens") or 0)

    def record_chain_gaps(self, gaps) -> None:
        if not gaps:
            return
        with self.lock:
            self.chain_gap_words += 1

    def record_success(self, latency: float, examples: int, dropped: int) -> None:
        with self.lock:
            self.completed += 1
            self.latencies.append(latency)
            self.example_hist[examples] = self.example_hist.get(examples, 0) + 1
            self.dropped_examples += dropped

    def record_failure(self) -> None:
        with self.lock:
            self.failed += 1


# --------------------------------------------------------------------------- #
# preflight
# --------------------------------------------------------------------------- #

def preflight(url: str, key: str, args, model: str) -> bool:
    """花一次请求把「环境类坑」全查一遍，避免跑 3 分钟才发现 key/UA/参数不对。"""
    print("preflight: 发 1 次探测请求（约 $0.001）", flush=True)
    payload = request_payload(args.provider, "test", args.language, model, 2, 2000, 0.2)
    started = time.time()
    try:
        response = call_model(url, key, payload, args.timeout, args.provider)
    except urllib.error.HTTPError as error:
        detail = ""
        try:
            detail = error.read().decode("utf-8", "ignore")[:200]
        except Exception:
            pass
        hint = {
            401: "key 无效或过期",
            403: "多半是 User-Agent 被 Cloudflare 拦（error 1010）或 key 无权限",
            400: "检查 x-opencode-session（MissingSessionID）、thinking 参数是否被网关拒",
            404: "模型名或 base-url 不对",
            429: "限流：降低 --workers",
        }.get(error.code, "见 detail")
        print(f"  ✗ HTTP {error.code}: {detail}", flush=True)
        print(f"  → {hint}", flush=True)
        print("preflight: FAIL", flush=True)
        return False
    except Exception as error:
        print(f"  ✗ 请求失败：{error}", flush=True)
        print("preflight: FAIL", flush=True)
        return False
    elapsed = time.time() - started

    problems, usage = response_problems(response, 2000)
    content = ""
    obj = {}
    parse_error = ""
    try:
        content = response["choices"][0]["message"]["content"]
        obj = json.loads(content)
    except Exception as error:
        parse_error = str(error)
    checks: list[tuple[str, bool, str]] = [
        ("HTTP 200", True, ""),
        ("finish_reason=stop", not any("finish_reason" in p for p in problems),
         "; ".join(p for p in problems if "finish_reason" in p)),
        ("thinking 已关闭", not any("reasoning" in p for p in problems),
         "; ".join(p for p in problems if "reasoning" in p)),
        ("输出是合法 JSON", bool(obj), parse_error),
        ("语言指令生效（zh+en 都有）",
         bool(str((obj.get("meanings") or {}).get("zh", "")).strip())
         and bool(str((obj.get("meanings") or {}).get("en", "")).strip()),
         "meanings 缺 zh 或 en"),
    ]
    ok = all(passed for _, passed, _ in checks)
    completion = int(usage.get("completion_tokens") or 0)
    print(f"  耗时 {elapsed:.1f}s，completion_tokens={completion}", flush=True)
    for name, passed, detail in checks:
        print(f"  {'✓' if passed else '✗'} {name}" + (f" —— {detail}" if detail and not passed else ""),
              flush=True)
    print(f"preflight: {'PASS' if ok else 'FAIL'}", flush=True)
    return ok


# --------------------------------------------------------------------------- #
# main
# --------------------------------------------------------------------------- #

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--words", required=True, help="word list: one per line, or JSON array")
    parser.add_argument("--language", default="en", help="target language code (en)")
    parser.add_argument("--output", required=True, help="JSONL output path")
    parser.add_argument("--provider", choices=("local", "opencode", "deepseek"), default="local")
    parser.add_argument("--base-url", default=None)
    parser.add_argument("--model", default=None)
    parser.add_argument("--example-count", type=int, default=None,
                        help="每词例句上限（默认取契约 examples.maxPerWord）")
    parser.add_argument("--max-tokens", type=int, default=None,
                        help="输出 token 预算（默认取契约 request.maxTokens）")
    parser.add_argument("--timeout", type=int, default=None,
                        help="单请求超时秒数（默认取契约 request.timeoutSeconds）")
    parser.add_argument("--workers", type=int, default=1, help="parallel requests (opencode: 4-6)")
    parser.add_argument("--limit", type=int, default=0, help="only run the first N pending words (0 = all)")
    parser.add_argument("--forms-map", default=None,
                        help="JSON map head word -> accepted inflected forms (from basewords)")
    parser.add_argument("--price-in", type=float, default=0.15, help="USD per 1M input tokens (estimate)")
    parser.add_argument("--price-out", type=float, default=0.60, help="USD per 1M output tokens (estimate)")
    parser.add_argument("--max-cost-usd", type=float, default=None,
                        help="成本上限（估算）：超过后不再提交新词，已完成保留，退出码 3")
    parser.add_argument("--retry-failures", action="store_true",
                        help="把 <output>.failures.jsonl 里的词排到最前面重试")
    parser.add_argument("--skip-preflight", action="store_true", help="跳过 1 次探测请求")
    parser.add_argument("--preflight-only", action="store_true", help="只跑 preflight")
    parser.add_argument("--check-words", action="store_true", help="只打印词表体检结果，不请求模型")
    parser.add_argument("--prompt-file", default=None,
                        help="覆盖共享契约路径（默认 teachloop-vocab/src/main/resources/vocab/content-prompt-v3.0.json）")
    args = parser.parse_args()

    if args.prompt_file:
        apply_contract(Path(args.prompt_file))
    args.example_count = args.example_count or int(CONTRACT["examples"]["maxPerWord"])
    args.timeout = args.timeout or int(REQUEST.get("timeoutSeconds", 150))

    if not 1 <= args.example_count <= 6:
        print("--example-count 必须在 1..6", file=sys.stderr)
        return 2

    # 优先级：命令行 > 环境变量 > 共享契约
    provider_defaults = CONTRACT["providers"][args.provider]
    base_url = (args.base_url
                or os.environ.get("LLM_BASE_URL")
                or provider_defaults["baseUrl"])
    model = (args.model
             or os.environ.get("LLM_MODEL")
             or provider_defaults["model"])
    if args.max_tokens is not None:
        max_tokens = args.max_tokens
    elif args.provider in ("opencode", "deepseek"):
        max_tokens = max(int(REQUEST.get("maxTokens", 4000)), 6000)
    else:
        max_tokens = int(REQUEST.get("maxTokensLocal", REQUEST.get("maxTokens", 4000)))

    forms_map: dict[str, list[str]] = {}
    if args.forms_map:
        forms_map = json.loads(Path(args.forms_map).read_text(encoding="utf-8"))
    global FORMS_MAP
    FORMS_MAP = forms_map

    words = load_words(Path(args.words))
    for line in word_list_report(words):
        print(line, flush=True)
    if args.check_words:
        return 0

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    failures = output.with_suffix(".failures.jsonl")
    repaired = repair_partial_jsonl(output)
    done, corrupt = load_done(output)
    if repaired:
        print("修复：截掉上次中断留下的半行 JSON（1 行）", flush=True)
    if corrupt:
        print(f"警告：{corrupt} 行无法解析，已跳过（不参与去重）", flush=True)

    retry_words: list[str] = []
    if args.retry_failures and failures.exists():
        for line in failures.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            try:
                retry_words.append(str(json.loads(line).get("word", "")).strip())
            except Exception:
                continue
    pending = [w for w in retry_words if w and (w.lower(), args.language) not in done]
    seen_pending = {w.lower() for w in pending}
    pending += [w for w in words
                if (w.lower(), args.language) not in done and w.lower() not in seen_pending]
    if args.limit > 0:
        pending = pending[: args.limit]

    print(f"provider={args.provider} model={model} workers={args.workers} "
          f"max_cost={'$' + str(args.max_cost_usd) if args.max_cost_usd else 'off'} "
          f"done={len(done)} pending={len(pending)}", flush=True)

    if not pending:
        print("没有待生成的词（已全部完成）", flush=True)
        return 0

    key = os.environ.get("LLM_API_KEY")
    if not key:
        print("缺少 LLM_API_KEY 环境变量", file=sys.stderr)
        return 2
    url = base_url.rstrip("/") + "/chat/completions"

    if not args.skip_preflight or args.preflight_only:
        if not preflight(url, key, args, model):
            return 2
        if args.preflight_only:
            return 0

    stats = Stats(args.price_in, args.price_out)
    prompt_sha = hashlib.sha256(SYSTEM.encode("utf-8")).hexdigest()[:16]
    out_lock = threading.Lock()
    failures_lock = threading.Lock()
    abort = threading.Event()

    def work(word: str) -> bool:
        attempts = [(max_tokens, 0.2, ""), (max(max_tokens, 6000), 0.1, "")]
        record = None
        reasons: list[str] = []
        for max_tokens_try, temperature, extra in attempts:
            if abort.is_set():
                return False
            payload = request_payload(args.provider, word, args.language, model,
                                      args.example_count, max_tokens_try, temperature, extra)
            response = None
            call_started = time.time()
            for backoff in range(4):
                try:
                    stats.record_attempt()
                    call_started = time.time()
                    response = call_model(url, key, payload, args.timeout, args.provider)
                    break
                except urllib.error.HTTPError as error:
                    stats.record_attempt(status=error.code)
                    reasons.append(f"HTTP {error.code}")
                    if error.code in RETRYABLE_STATUS and backoff < 3:
                        time.sleep(min(2 ** backoff * 2, 20))
                        continue
                    break
                except Exception as error:  # timeout / connection
                    stats.record_attempt()
                    reasons.append(f"request failed: {error}")
                    if backoff < 3:
                        time.sleep(min(2 ** backoff * 2, 20))
                        continue
                    break
            if response is None:
                continue
            if "error" in response:
                reasons.append(str(response["error"].get("message", response["error"])))
                continue
            problems, usage = response_problems(response, max_tokens_try)
            stats.record_usage(usage)
            stats.record_finish(str(response["choices"][0].get("finish_reason")))
            if problems:
                reasons.extend(problems)
                continue
            try:
                obj = json.loads(response["choices"][0]["message"]["content"])
            except Exception as error:
                reasons.append(f"not json: {error}")
                continue
            before = len(obj.get("examples") or [])
            problems = validate_v3(obj, word, args.language, args.example_count,
                                   forms_map.get(word.lower()))
            after = len(obj.get("examples") or [])
            if problems:
                reasons.append("; ".join(problems))
                continue
            stats.record_chain_gaps([])
            stats.record_success(time.time() - call_started, after, max(before - after, 0))
            record = obj
            break

        if record is None:
            stats.record_failure()
            with failures_lock:
                fail_out.write(json.dumps({"word": word, "language": args.language,
                                           "reasons": reasons}, ensure_ascii=False) + "\n")
                fail_out.flush()
            return False
        with out_lock:
            out.write(json.dumps(record, ensure_ascii=False) + "\n")
            out.flush()
        return True

    def write_status() -> None:
        with stats.lock:
            elapsed = time.time() - stats.started_at
            done_n = stats.completed + stats.failed
            rate = elapsed / max(done_n, 1)
            payload = {
                "updatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
                "completed": stats.completed, "failed": stats.failed,
                "pending": len(pending), "aborted": stats.aborted,
                "wordsPerMinute": round(done_n / max(elapsed, 1) * 60, 1),
                "etaMinutes": round(rate * (len(pending) - done_n) / 60, 1),
                "tokensIn": stats.tokens_in, "tokensOut": stats.tokens_out,
                "estimatedCostUsd": round(
                    stats.tokens_in / 1e6 * stats.price_in
                    + stats.tokens_out / 1e6 * stats.price_out, 4),
                "finishReasons": stats.finish_reasons,
                "httpErrors": stats.http_errors,
            }
        output.with_suffix(".status.json").write_text(
            json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    def report(done_n: int) -> None:
        with stats.lock:
            elapsed = time.time() - stats.started_at
            rate = elapsed / max(done_n, 1)
            print(f"[{done_n}/{len(pending)}] ok={stats.completed} failed={stats.failed} "
                  f"avg={rate:.1f}s eta={rate * (len(pending) - done_n) / 60:.1f}min "
                  f"tok_in={stats.tokens_in} tok_out={stats.tokens_out}", flush=True)

    with output.open("a", encoding="utf-8") as out, failures.open("a", encoding="utf-8") as fail_out:
        in_flight = {}
        submitted = 0
        finished = 0
        cap_warned = False
        with ThreadPoolExecutor(max_workers=max(args.workers, 1)) as pool:
            while submitted < len(pending) or in_flight:
                while (submitted < len(pending) and len(in_flight) < max(args.workers, 1) * 2
                       and not abort.is_set()):
                    future = pool.submit(work, pending[submitted])
                    in_flight[future] = pending[submitted]
                    submitted += 1
                if not in_flight:
                    break
                done_futures, _ = wait(list(in_flight), timeout=30, return_when=FIRST_COMPLETED)
                if not done_futures:
                    write_status()
                    continue
                for future in done_futures:
                    word = in_flight.pop(future, None)
                    try:
                        future.result()
                    except Exception as error:  # 单条炸了不能拖垮整批
                        stats.record_failure()
                        with failures_lock:
                            fail_out.write(json.dumps(
                                {"word": word, "language": args.language,
                                 "reasons": [f"worker crashed: {error}"]},
                                ensure_ascii=False) + "\n")
                            fail_out.flush()
                    finished += 1
                if finished % 10 == 0 or finished == len(pending):
                    report(finished)
                    write_status()
                if (args.max_cost_usd is not None and not abort.is_set()
                        and stats.cost() > args.max_cost_usd):
                    abort.set()
                    cap_warned = True
                    print(f"成本上限 ${args.max_cost_usd} 已达（估算 ${stats.cost():.3f}），停止提交新词"
                          f"（在途请求仍会完成，可能超出约 {max(args.workers, 1) * 2} 个词的量）",
                          flush=True)

    stats.aborted = len(pending) - stats.completed - stats.failed
    write_status()

    elapsed = time.time() - stats.started_at
    cost = stats.cost()
    latencies = sorted(stats.latencies)
    manifest = {
        "promptVersion": PROMPT_VERSION,
        "promptSha256": prompt_sha,
        "provider": args.provider,
        "model": model,
        "baseUrl": base_url,
        "language": args.language,
        "workers": args.workers,
        "exampleCount": args.example_count,
        "completed": stats.completed,
        "failed": stats.failed,
        "aborted": stats.aborted,
        "attempts": stats.attempts,
        "httpErrors": stats.http_errors,
        "finishReasons": stats.finish_reasons,
        "completionTokens": stats.tokens_out,
        "promptTokens": stats.tokens_in,
        "estimatedCostUsd": round(cost, 4),
        "elapsedSeconds": round(elapsed, 1),
        "wordsPerMinute": round(stats.completed / max(elapsed, 1) * 60, 2),
        "latencyP50Seconds": round(latencies[len(latencies) // 2], 1) if latencies else None,
        "latencyP90Seconds": round(latencies[int(len(latencies) * 0.9)], 1) if latencies else None,
        "examplesHistogram": dict(sorted(stats.example_hist.items())),
        "droppedExamplesTotal": stats.dropped_examples,
        "wordsWithChainGaps": stats.chain_gap_words,
        "finishedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    }
    output.with_suffix(".manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    with output.with_suffix(".runs.jsonl").open("a", encoding="utf-8") as ledger:
        ledger.write(json.dumps(manifest, ensure_ascii=False) + "\n")
    print(json.dumps(manifest, ensure_ascii=False), flush=True)
    if abort.is_set():
        return 3
    return 0 if stats.failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
