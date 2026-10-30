#!/usr/bin/env python3
"""文案检查：后端不许新增中文硬编码；前端中英必须对齐，组件不许新增中文硬编码。

三件事：
1. **后端**：`api/` 与 `service/` 里禁止新增中文字符串字面量。
   历史代码已有大量中文文案，直接全量失败不可推进，所以用「基线 + 新增即失败」：
   `scripts/i18n-baseline.txt` 记录当前所有中文字面量的指纹（sha256(path|literal) 前 16 位）；
   新增一个 → 退出码 1 并打印文件/行/片段。用 `--update` 重写基线（仅 review 批准后）。
2. **前端键对齐**：`web/src/locales/zh-CN.json` 与 `en.json` 的键集合必须完全相等；
   值不能为空、不能等于键名本身（那样等于没翻译）；组件里每个字面量 `t('xxx')` 都必须能在
   zh-CN.json 里找到——把 key 搬家却忘了改代码，这里会当场失败。
3. **前端组件**：`web/src/**/*.tsx|ts`（locales 与 i18n.ts 除外）里不许新增中文字面量，
   同样用基线 `scripts/i18n-web-baseline.txt` 锁存量。
"""
from __future__ import annotations
import argparse, hashlib, json, re, sys
from pathlib import Path

CJK = re.compile(r"[\u4e00-\u9fff]")
STRING = re.compile(r'"(?:[^"\\]|\\.)*"')
# JSX 注释 {/* ... */}、块注释、行注释、字符串里的中文都算「文字」，注释里的不算
JS_LITERAL = re.compile(r"'((?:[^'\\]|\\.)*)'|\"((?:[^\"\\]|\\.)*)\"|`((?:[^`\\]|\\.)*)`")
JS_COMMENT = re.compile(r"^\s*(//|/\*|\*|\{/\*)")
BLOCK_COMMENT = re.compile(r"/\*.*?\*/")
LINE_COMMENT = re.compile(r"//.*$")
BASELINE = Path(__file__).with_name("i18n-baseline.txt")
WEB_BASELINE = Path(__file__).with_name("i18n-web-baseline.txt")
WEB_LOCALES = Path("web/src/locales")


def targets(root: Path):
    for module in root.glob("teachloop-*"):
        src = module / "src/main/java"
        if not src.exists():
            continue
        for path in src.rglob("*.java"):
            if "/api/" in path.as_posix() or "/service/" in path.as_posix():
                yield path


def literals(path: Path, root: Path):
    text = path.read_text(encoding="utf-8")
    for i, line in enumerate(text.splitlines(), 1):
        for literal in STRING.findall(line):
            if CJK.search(literal):
                yield i, literal, fingerprint(path.relative_to(root), literal)


def web_literals(root: Path):
    src = root / "web/src"
    if not src.exists():
        return
    for path in src.rglob("*"):
        if path.suffix not in (".tsx", ".ts"):
            continue
        if path.name == "i18n.ts" or "locales" in path.parts:
            continue
        for i, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            line = LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", raw))
            if not CJK.search(line) or JS_COMMENT.match(raw):
                continue
            hits = [g for m in JS_LITERAL.finditer(line) for g in m.groups() if g]
            text = " ".join(h for h in hits if CJK.search(h)) or (line.strip() if not hits else "")
            if not text:
                continue
            yield path.relative_to(root), i, text, fingerprint(path.relative_to(root), text)


def fingerprint(path: Path, literal: str) -> str:
    """指纹必须只用**仓库相对路径**：用绝对路径的话，基线只在生成它的那台机/那个目录下有效，
    换 clone 位置（CI workspace、容器里的 /tmp/tl）就全部对不上，闸门必红。
    2026-10-30 实测：ai-ubuntu 容器里跑 check-i18n 报 367 处「新增」，本机同一份代码是 0 处。"""
    raw = f"{path.as_posix()}|{literal}".encode("utf-8")
    return hashlib.sha256(raw).hexdigest()[:16]


def load_baseline(path: Path) -> set[str]:
    if not path.exists():
        return set()
    return {line.strip() for line in path.read_text(encoding="utf-8").splitlines() if line.strip()}


def flat_keys(obj, prefix=""):
    for key, value in obj.items():
        name = f"{prefix}{key}"
        if isinstance(value, dict):
            yield from flat_keys(value, name + ".")
        else:
            yield name, value


T_CALL = re.compile(r"(?<![\w.$])t\(\s*['\"]([A-Za-z0-9_.]+)['\"]")


def check_referenced_keys(root: Path, zh_keys: set[str]) -> list[str]:
    """组件里字面量 t('key') 必须存在；动态拼接的 key（t(`...`)）不在此列。"""
    src = root / "web/src"
    if not src.exists():
        return []
    missing = {}
    for path in src.rglob("*"):
        if path.suffix not in (".tsx", ".ts"):
            continue
        if path.name == "i18n.ts" or "locales" in path.parts:
            continue
        for m in T_CALL.finditer(path.read_text(encoding="utf-8")):
            key = m.group(1)
            if key not in zh_keys:
                missing.setdefault(key, path.relative_to(root))
    return [f"组件引用了不存在的 key：{k}（{v}）" for k, v in sorted(missing.items())]


def check_web_locales(root: Path) -> list[str]:
    zh_path, en_path = root / WEB_LOCALES / "zh-CN.json", root / WEB_LOCALES / "en.json"
    if not zh_path.exists() or not en_path.exists():
        return [f"缺少文案文件：{zh_path.relative_to(root)} 或 {en_path.relative_to(root)}"]
    zh = dict(flat_keys(json.loads(zh_path.read_text(encoding="utf-8"))))
    en = dict(flat_keys(json.loads(en_path.read_text(encoding="utf-8"))))
    problems = [f"en.json 缺少 key：{k}" for k in zh.keys() - en.keys()]
    problems += [f"zh-CN.json 缺少 key：{k}" for k in en.keys() - zh.keys()]
    for name, table in (("zh-CN.json", zh), ("en.json", en)):
        problems += [f"{name} 的 {k} 是空值" for k, v in table.items() if not str(v).strip()]
        problems += [f"{name} 的 {k} 与键名相同（等于没翻译）" for k, v in table.items() if str(v) == k]
    problems += check_referenced_keys(root, set(zh))
    return problems


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=str(Path(__file__).resolve().parent.parent))
    ap.add_argument("--update", action="store_true", help="用当前代码重写基线")
    args = ap.parse_args()
    root = Path(args.root).resolve()

    # ---- 前端键对齐 ----
    locale_problems = check_web_locales(root)
    if locale_problems:
        print("[check-i18n] 前端文案不一致：")
        for problem in locale_problems[:50]:
            print("  " + problem)
        print(f"共 {len(locale_problems)} 处；改 web/src/locales/zh-CN.json 与 en.json 对齐。")
        return 1

    # ---- 后端中文字面量 ----
    found = []
    for path in sorted(targets(root)):
        rel = path.relative_to(root)
        for line, literal, fp in literals(path, root):
            found.append((rel, line, literal, fp))
    backend_fps = {f[3] for f in found}

    # ---- 前端中文字面量 ----
    web_found = list(web_literals(root))
    web_fps = {f[3] for f in web_found}

    if args.update:
        BASELINE.write_text("\n".join(sorted(backend_fps)) + ("\n" if backend_fps else ""), encoding="utf-8")
        WEB_BASELINE.write_text("\n".join(sorted(web_fps)) + ("\n" if web_fps else ""), encoding="utf-8")
        print(f"[check-i18n] 已写入基线：后端 {len(backend_fps)} 处、前端 {len(web_fps)} 处")
        return 0

    backend_allowed = load_baseline(BASELINE)
    backend_new = [f for f in found if f[3] not in backend_allowed]
    web_allowed = load_baseline(WEB_BASELINE)
    web_new = [f for f in web_found if f[3] not in web_allowed]

    if backend_new or web_new:
        print("[check-i18n] 发现基线外的新中文字面量：")
        for rel, line, literal, _ in backend_new[:50]:
            print(f"  后端 {rel}:{line}: {literal[:80]}")
        for rel, line, literal, _ in web_new[:50]:
            print(f"  前端 {rel}:{line}: {literal[:80]}")
        print(f"共 {len(backend_new) + len(web_new)} 处；界面文案请走消息 key / locales JSON，"
              f"或经 review 后 --update 基线。")
        return 1

    zh_count = len(list(flat_keys(json.loads((root / WEB_LOCALES / 'zh-CN.json').read_text(encoding='utf-8')))))
    print(f"[check-i18n] 通过：前端 zh/en 各 {zh_count} 键且完全对齐；"
          f"新增中文字面量后端 0 处（基线 {len(backend_allowed)}）、前端 0 处（基线 {len(web_allowed)}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
