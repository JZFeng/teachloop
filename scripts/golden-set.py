#!/usr/bin/env python3
"""T175：golden set 质量回归。用同一 rubric 让 DeepSeek 给 10 份 v1 样本评分，
与人工总分对比，输出平均/最大偏差与偏差方向。

用法：
  DEEPSEEK_API_KEY=... python3 scripts/golden-set.py
  # 可选 --model deepseek-flash --base https://api.deepseek.com
无 Key 时直接报错，不写假数字。
"""
import argparse, json, os, re, sys, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SAMPLES = ROOT / "docs/rubrics/samples/submissions"
REVIEWS = ROOT / "docs/rubrics/samples/reviews"
RUBRIC = (ROOT / "docs/rubrics/Rubric.md").read_text(encoding="utf-8")

def human_score(path: Path) -> int:
    m = re.search(r"Total Score:\s*(\d+)\s*/\s*(\d+)", path.read_text(encoding="utf-8"))
    if not m:
        raise SystemExit(f"无法读取人工总分: {path}")
    return int(m.group(1))

def grade(text: str, model: str, base: str, key: str) -> dict:
    prompt = f"""You are grading one English assignment against this rubric.

RUBRIC:
{RUBRIC}

Return ONLY JSON: {{"total": <0-10 integer>, "length": <0-2>, "position": <0-4>, "details": <0-4>, "reason": "<short>"}}.
Do not quote the rubric."""
    body = json.dumps({
        "model": model,
        "temperature": 0.2,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": prompt},
            {"role": "user", "content": text},
        ],
    }).encode()
    req = urllib.request.Request(base.rstrip("/") + "/chat/completions", data=body,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
    with urllib.request.urlopen(req, timeout=120) as r:
        payload = json.loads(r.read())
    content = payload["choices"][0]["message"]["content"]
    return json.loads(content)

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=os.environ.get("TEACHLOOP_AI_MODEL", "deepseek-flash"))
    ap.add_argument("--base", default=os.environ.get("TEACHLOOP_AI_BASE_URL", "https://api.deepseek.com"))
    args = ap.parse_args()
    key = os.environ.get("DEEPSEEK_API_KEY")
    if not key:
        print("缺少 DEEPSEEK_API_KEY；T175 不跑，避免写假偏差。", file=sys.stderr)
        return 2

    rows = []
    for sample in sorted(SAMPLES.glob("*.txt")):
        name = sample.stem
        review = REVIEWS / f"{name}_review.txt"
        human = human_score(review)
        result = grade(sample.read_text(encoding="utf-8"), args.model, args.base, key)
        ai = int(result["total"])
        rows.append({"student": name, "human": human, "ai": ai, "deviation": ai - human,
                     "reason": result.get("reason", "")})
        print(f"{name:12s} human={human:2d} ai={ai:2d} dev={ai-human:+d}")

    devs = [r["deviation"] for r in rows]
    mean = sum(devs) / len(devs)
    max_abs = max(abs(d) for d in devs)
    direction = "偏高" if mean > 0 else ("偏低" if mean < 0 else "持平")
    print("\nSUMMARY")
    print(json.dumps({"n": len(rows), "mean_deviation": round(mean, 2),
                      "max_abs_deviation": max_abs, "direction": direction, "rows": rows},
                     ensure_ascii=False, indent=2))
    return 0

if __name__ == "__main__":
    sys.exit(main())
