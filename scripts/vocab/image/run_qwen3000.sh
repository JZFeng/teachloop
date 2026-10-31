#!/usr/bin/env bash
# Qwen-Image 3000 词批量出图：主跑 → 失败重试（Qwen 两轮）→ Hunyuan 兜底 → 后处理 176/96 WebP。
#
# 用法（在 ai-ubuntu 上，务必脱离 ssh 会话）：
#   cd ~/ai/vocab-images
#   setsid nohup bash scripts/run_qwen3000.sh >> qwen3000.log 2>&1 < /dev/null & disown
#
# 断点续跑：batch_qwen.py 默认 --skip-existing，直接重跑本脚本即可从中断处继续；
# 已经生成过的词、已经后处理过的图都会被跳过。
set -u

BASE="$HOME/ai/vocab-images"
PY="$HOME/ai/train-venv/bin/python3"
SCENES="$BASE/scenes-3000.json"
OUT="$BASE/qwen3000"
FINAL="$BASE/qwen3000_176"
LOG="$BASE/qwen3000.log"
PROGRESS="$BASE/qwen3000.progress"
FAILURES="$BASE/qwen3000.failures.json"
STATE="$BASE/qwen3000.state.json"
COMFY_URL="http://127.0.0.1:8188"

stamp() { date -Is; }
say() { echo "[wrapper $(stamp)] $*"; }

comfy_up() { curl -s -m 4 -o /dev/null -w '%{http_code}' "$COMFY_URL/system_stats" | grep -q 200; }

ensure_comfy() {
    comfy_up && return 0
    say "ComfyUI 不在线，重启"
    ( cd "$HOME/ai/ComfyUI" && setsid nohup "$HOME/ai/comfy/venv/bin/python" main.py \
        --listen 127.0.0.1 --port 8188 --reserve-vram 1 --fp8_e4m3fn-unet \
        >> "$HOME/ai/logs/comfyui-batch3000.log" 2>&1 < /dev/null & )
    for _ in $(seq 1 120); do
        sleep 2
        comfy_up && { say "ComfyUI 已恢复"; return 0; }
    done
    say "ComfyUI 重启失败，等人工"
    return 1
}

failed_words() {
    "$PY" - "$FAILURES" <<'PY'
import json, sys
try:
    data = json.load(open(sys.argv[1]))
except Exception:
    sys.exit(0)
for word in data.get("words", []):
    print(word)
PY
}

count_png() { find "$OUT" -maxdepth 1 -name '*.png' 2>/dev/null | wc -l; }

mkdir -p "$OUT" "$FINAL"
say "===== 启动：scenes=$(python3 -c 'import json,sys;print(len(json.load(open(sys.argv[1]))))' "$SCENES") 已有=$(count_png) ====="
ensure_comfy || exit 3

# 1) 主跑（已存在的 PNG 跳过；每 100 张写一行进度）
say "主跑开始：512²/12 步，Qwen fp8"
"$PY" "$BASE/scripts/batch_qwen.py" \
    --scenes "$SCENES" --out "$OUT" --size 512 --steps 12 \
    --progress "$PROGRESS" --state "$STATE" --failures "$FAILURES" \
    --skip-existing --attempts 2 >> "$LOG" 2>&1
say "主跑结束：已完成 $(count_png) 张，失败 $(failed_words | wc -l) 个词"

# 2) 失败重试：Qwen 两轮（每次重跑前确认 ComfyUI 还在）
for round in 1 2; do
    words=$(failed_words | paste -sd, -)
    [ -z "$words" ] && break
    ensure_comfy || break
    say "重试第 $round 轮：$(failed_words | wc -l) 个词（Qwen）"
    "$PY" "$BASE/scripts/batch_qwen.py" \
        --scenes "$SCENES" --out "$OUT" --size 512 --steps 12 \
        --only "$words" --progress "$PROGRESS" --state "$STATE" --failures "$FAILURES" \
        --skip-existing --attempts 2 >> "$LOG" 2>&1
    sleep 5
done

# 3) Hunyuan 兜底：Qwen 仍然跑不出来的词（写进同一目录，后处理一并处理）
remaining=$(failed_words | paste -sd, -)
if [ -n "$remaining" ]; then
    say "Hunyuan 兜底：$(failed_words | wc -l) 个词"
    "$PY" "$HOME/ai/vocab-images/scripts/batch_hunyuan.py" \
        --scenes "$SCENES" --only "$remaining" --out "$OUT" --size 768 --steps 24 \
        >> "$LOG" 2>&1 || true
    # 兜底跑完再核对一次：能补上的就从失败清单里剔除
    "$PY" - "$FAILURES" "$OUT" <<'PY'
import json, os, sys
path, out = sys.argv[1], sys.argv[2]
data = json.load(open(path))
left = [w for w in data.get("words", []) if not os.path.isfile(os.path.join(out, w + ".png"))]
data["words"] = left
data["detail"] = [d for d in data.get("detail", []) if d["word"] in left]
data["count"] = len(left)
json.dump(data, open(path, "w"), ensure_ascii=False, indent=2)
print("remaining", len(left))
PY
fi

# 4) 后处理：裁边 3% → levels → 176/96 WebP
say "后处理：$(count_png) 张 → $FINAL"
"$PY" "$BASE/scripts/finalize_176.py" \
    --input "$OUT" --output "$FINAL" --size 176 --also 96 --quality 92 \
    --margin 0.03 --skip-existing --stats "$BASE/qwen3000_176.stats.json" >> "$LOG" 2>&1

say "全部结束：PNG=$(count_png) 张，失败=$(failed_words | wc -l) 个词，176 WebP=$(find "$FINAL" -name '*.webp' | wc -l) 张"
say "体积：$(du -sh "$FINAL" | cut -f1)  统计：$BASE/qwen3000_176.stats.json"
touch "$BASE/qwen3000.DONE"
