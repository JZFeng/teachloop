#!/usr/bin/env bash
# T411 / 2026-10-31：本机安装 Piper 本地 TTS（dev 的 127.0.0.1:5002 sidecar 用）。
#
# 为什么需要它：`scripts/dev.sh` 现在会在「piper 可执行 + 模型文件」都在时自动拉起
# `docker/piper-sidecar.py`；两个都不在时只打一条提示，发音静默回落浏览器语音
# （那就是「尾音杂音 / inflect 重音不对」那条链路）。本脚本把这两个东西装到
# `.dsh/tts/` 下：piper-tts 装进独立 venv，模型放进 models/。
#
# 用法：
#   bash scripts/setup-tts.sh              # 装引擎 + 英美两个模型（约 120MB）
#   bash scripts/setup-tts.sh --all        # 额外装中文/西语音色（例句朗读用）
#   bash scripts/setup-tts.sh --serve      # 装好后前台起 sidecar（dev 已在跑时用它，不用重启 dev）
#   bash scripts/setup-tts.sh --print      # 只打印将要做什么
#
# 依赖：python3（3.10+）＋能访问 pypi.org / huggingface.co；有 uv 就用 uv 拿 Python 3.12。
# 不需要 brew install espeak-ng：piper-tts 自带 espeak-ng 数据（site-packages/piper/espeak-ng-data）。
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

TTS_DIR="${PIPER_TTS_DIR:-$ROOT/.dsh/tts}"
VENV="$TTS_DIR/venv"
MODELS="${PIPER_MODEL_DIR:-$TTS_DIR/models}"
PY_VERSION="${PIPER_PYTHON_VERSION:-3.12}"
TTS_PORT="${VOCAB_TTS_PORT:-5002}"
US_VOICE="${VOCAB_TTS_VOICE_US:-en_US-amy-medium}"
UK_VOICE="${VOCAB_TTS_VOICE_UK:-en_GB-alan-medium}"
ZH_VOICE="${VOCAB_TTS_VOICE_ZH:-zh_CN-huayan-medium}"
ES_VOICE="${VOCAB_TTS_VOICE_ES:-es_ES-davefx-medium}"
HF="https://huggingface.co/rhasspy/piper-voices/resolve/main"

ALL=0
SERVE=0
PRINT=0
for arg in "$@"; do
  case "$arg" in
    --all) ALL=1 ;;
    --serve) SERVE=1 ;;
    --print) PRINT=1 ;;
    -h|--help)
      awk 'NR>1 && /^#/ {sub(/^# ?/, ""); print; next} NR>1 {exit}' "$0"
      exit 0 ;;
    *) echo "未知参数: ${arg}（--help 查看用法）" >&2; exit 2 ;;
  esac
done

say() { printf '\033[1;36m[tts]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[tts]\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31m[tts]\033[0m %s\n' "$*" >&2; exit 1; }

command -v python3 >/dev/null || die "未找到 python3"
command -v curl >/dev/null || die "未找到 curl"

# 模型在 HF 上的相对路径（piper-voices 的目录结构：<lang>/<locale>/<name>/<quality>/<file>）
model_path() {
  case "$1" in
    en_US-amy-medium) echo "en/en_US/amy/medium" ;;
    en_GB-alan-medium) echo "en/en_GB/alan/medium" ;;
    zh_CN-huayan-medium) echo "zh/zh_CN/huayan/medium" ;;
    es_ES-davefx-medium) echo "es/es_ES/davefx/medium" ;;
    *) die "不认识的音色：$1（改 VOCAB_TTS_VOICE_* 后用 piper 的模型名）" ;;
  esac
}

voice_files() {
  local voice="$1"
  local dir
  dir="$(model_path "$voice")"
  echo "$HF/$dir/$voice.onnx"
  echo "$HF/$dir/$voice.onnx.json"
}

ensure_engine() {
  if [ -x "$VENV/bin/piper" ]; then
    say "piper 已安装：$VENV/bin/piper"
    return
  fi
  say "安装 piper-tts 到 $VENV"
  mkdir -p "$TTS_DIR"
  if command -v uv >/dev/null 2>&1; then
    # uv 顺带解决「系统 python 太新、piper-tts/onnxruntime 没有对应 wheel」的问题。
    UV_CACHE_DIR="$TTS_DIR/uv-cache" UV_PYTHON_INSTALL_DIR="$TTS_DIR/python" \
      uv venv --quiet --python "$PY_VERSION" "$VENV"
    UV_CACHE_DIR="$TTS_DIR/uv-cache" UV_PYTHON_INSTALL_DIR="$TTS_DIR/python" \
      uv pip install --quiet --python "$VENV/bin/python" piper-tts
  else
    python3 -m venv "$VENV"
    "$VENV/bin/python" -m pip install --quiet --upgrade pip
    "$VENV/bin/python" -m pip install --quiet piper-tts \
      || die "pip install piper-tts 失败；装个 uv（brew install uv）后重试更稳"
  fi
  [ -x "$VENV/bin/piper" ] || die "安装后仍找不到 $VENV/bin/piper"
  say "piper 安装完成"
}

download() {
  local url="$1" dest="$2"
  # 模型 60MB 左右、配置 5KB 左右：分开判「已存在」，可重复执行不再重下
  local min_bytes=1000000
  case "$dest" in *.json) min_bytes=100 ;; esac
  if [ -f "$dest" ] && [ "$(wc -c < "$dest")" -gt "$min_bytes" ]; then
    say "已存在：$(basename "$dest")"
    return
  fi
  say "下载 $(basename "$dest")"
  curl -fsSL --retry 3 --retry-delay 2 -o "$dest.part" "$url" \
    || { rm -f "$dest.part"; die "下载失败：$url"; }
  mv "$dest.part" "$dest"
}

ensure_models() {
  mkdir -p "$MODELS"
  local voices=("$US_VOICE" "$UK_VOICE")
  [ "$ALL" = "1" ] && voices+=("$ZH_VOICE" "$ES_VOICE")
  local voice file
  for voice in "${voices[@]}"; do
    while IFS= read -r file; do
      download "$file" "$MODELS/$(basename "$file")"
    done < <(voice_files "$voice")
  done
}

verify() {
  say "校验音素化（espeak-ng 数据随 piper 一起装好，不需要 brew）"
  "$VENV/bin/python" - "$US_VOICE" <<'PY'
import sys
from piper.phonemize_espeak import EspeakPhonemizer

voice = sys.argv[1]
phonemizer = EspeakPhonemizer()
for word in ("inflect", "reflect"):
    print(f"  {word}: {' '.join(phonemizer.phonemize('en-us', word)[0])}")
print("  期望 inflect = ɪ n f l ˈ ɛ k t（重音在第二音节）")
PY
  say "模型目录：$MODELS"
  ls -lh "$MODELS" | sed 's/^/  /'
}

print_plan() {
  cat <<PLAN
[tts] 将要执行：
  1) 引擎：${VENV}（piper-tts，Python ${PY_VERSION}）
  2) 模型：${MODELS}（$US_VOICE / $UK_VOICE$([ "$ALL" = "1" ] && echo " / $ZH_VOICE / $ES_VOICE")）
  3) 校验：espeak 音素化 + 模型文件列表
来源：pypi.org（piper-tts）、huggingface.co（rhasspy/piper-voices）
PLAN
}

if [ "$PRINT" = "1" ]; then
  print_plan
  exit 0
fi

ensure_engine
ensure_models
verify

if [ "$SERVE" = "1" ]; then
  [ -f "$MODELS/$US_VOICE.onnx" ] || die "缺模型：$MODELS/$US_VOICE.onnx"
  say "前台启动 sidecar :${TTS_PORT}（Ctrl-C 停止；日志直接打在终端）"
  PIPER_BIN="$VENV/bin/piper" PIPER_MODEL="$MODELS/$US_VOICE.onnx" \
    PIPER_MODEL_DIR="$MODELS" PIPER_DEFAULT_VOICE="$US_VOICE" PIPER_PORT="$TTS_PORT" \
    exec python3 "$ROOT/docker/piper-sidecar.py"
fi

cat <<NEXT

  装好了。两种启动方式：
    make dev                         # dev.sh 会在检测到引擎+模型后自动拉起 sidecar
    bash scripts/setup-tts.sh --serve  # dev 已经在跑时，单独前台起 sidecar 即可（不用重启）

  验一条音频（无需登录）：
    curl -s -o /tmp/tts.wav -X POST http://127.0.0.1:$TTS_PORT/synthesize \\
      -H 'Content-Type: application/json' -d '{"text":"inflect","voice":"$US_VOICE","rate":0.9}'
    file /tmp/tts.wav    # 应该是 RIFF (little-endian) data, WAVE audio, 16 bit, mono 22050 Hz

NEXT
