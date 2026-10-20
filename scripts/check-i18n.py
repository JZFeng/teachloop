#!/usr/bin/env python3
"""T007：api/ 与 service/ 里禁止新增中文字符串字面量。

历史代码已有大量中文文案，直接全量失败不可推进。这里用「基线 + 新增即失败」：
- scripts/i18n-baseline.txt 记录当前所有中文字面量指纹（sha256(path|literal) 前 16 位）；
- 新增一个中文字面量 → 退出码 1，并打印文件/行/片段；
- 修改已有中文文案会改变指纹 → 也会被拦下（需要显式更新基线并在 review 中说明）。
- 用 --update 重新生成基线（仅在整个文件迁移到消息 key 或经 review 批准时使用）。
"""
from __future__ import annotations
import argparse, hashlib, re, sys
from pathlib import Path

CJK = re.compile(r"[\u4e00-\u9fff]")
STRING = re.compile(r'"(?:[^"\\]|\\.)*"')
BASELINE = Path(__file__).with_name("i18n-baseline.txt")


def targets(root: Path):
    for module in root.glob("teachloop-*"):
        src = module / "src/main/java"
        if not src.exists():
            continue
        seen = set()
        for path in src.rglob("*.java"):
            if "/api/" in path.as_posix() or "/service/" in path.as_posix():
                if path not in seen:
                    seen.add(path)
                    yield path


def literals(path: Path):
    text = path.read_text(encoding="utf-8")
    for i, line in enumerate(text.splitlines(), 1):
        for literal in STRING.findall(line):
            if CJK.search(literal):
                # 指纹里不含行号，避免改无关代码导致整体基线漂移
                yield i, literal, fingerprint(path, literal)


def fingerprint(path: Path, literal: str) -> str:
    raw = f"{path.as_posix()}|{literal}".encode("utf-8")
    return hashlib.sha256(raw).hexdigest()[:16]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=str(Path(__file__).resolve().parent.parent))
    ap.add_argument("--update", action="store_true", help="用当前代码重写基线")
    args = ap.parse_args()
    root = Path(args.root).resolve()
    found = []
    for path in sorted(targets(root)):
        rel = path.relative_to(root)
        for line, literal, fp in literals(path):
            found.append((rel, line, literal, fp))
    fps = {f[3] for f in found}
    if args.update:
        BASELINE.write_text("\n".join(sorted(fps)) + ("\n" if fps else ""), encoding="utf-8")
        print(f"[check-i18n] 已写入基线：{len(fps)} 个中文字面量")
        return 0
    allowed = set()
    if BASELINE.exists():
        allowed = {line.strip() for line in BASELINE.read_text(encoding="utf-8").splitlines() if line.strip()}
    new = [f for f in found if f[3] not in allowed]
    if new:
        print("[check-i18n] 发现基线外的新中文字面量：")
        for rel, line, literal, _ in new[:50]:
            print(f"  {rel}:{line}: {literal[:80]}")
        print(f"共 {len(new)} 处；业务文案请改走消息 key（T130），或经 review 后 --update 基线。")
        return 1
    print(f"[check-i18n] 通过：api/service 新增中文字面量 0 处（基线 {len(allowed)} 处）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
