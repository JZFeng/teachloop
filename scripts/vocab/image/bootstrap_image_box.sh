#!/usr/bin/env bash
# 生图机一键恢复（Ubuntu 24.04 + AMD RX 7900 XTX / gfx1100）
# 用法：
#   export HF_TOKEN=hf_xxx        # 只读 token；gated 仓库需先在网页点 Agree
#   bash scripts/vocab/image/bootstrap_image_box.sh
# 说明：可重复执行；已存在的文件会跳过（-C - 续传）。
set -euo pipefail

AI="$HOME/ai"
COMFY="$AI/ComfyUI"
VENV="$AI/comfy/venv"
PY="$VENV/bin/python"
PIP="$VENV/bin/pip"
MODELS="$COMFY/models"
COMFY_COMMIT="c06bf5f"

need() { command -v "$1" >/dev/null || { echo "缺少 $1"; exit 1; }; }

echo "==> 1/7 系统包"
sudo apt-get update -qq
sudo apt-get install -y -qq git curl aria2 python3.12-venv python3-pip build-essential ffmpeg

echo "==> 2/7 venv + PyTorch(ROCm 7.2)"
mkdir -p "$AI" "$AI/logs"
[ -d "$VENV" ] || python3 -m venv "$VENV"
"$PIP" install -q --upgrade pip
"$PIP" install -q torch torchvision --index-url https://download.pytorch.org/whl/rocm7.2
"$PY" - <<'PY'
import torch
print("torch", torch.__version__, "hip", torch.version.hip, "cuda", torch.cuda.is_available())
assert torch.cuda.is_available(), "GPU 不可见：检查 render 组与 /dev/kfd"
print("gpu:", torch.cuda.get_device_name(0))
PY

echo "==> 3/7 ComfyUI @ $COMFY_COMMIT"
[ -d "$COMFY/.git" ] || git clone -q https://github.com/comfyanonymous/ComfyUI "$COMFY"
cd "$COMFY" && git fetch -q origin && git checkout -q "$COMFY_COMMIT"
"$PIP" install -q -r requirements.txt

echo "==> 4/7 目录"
mkdir -p "$MODELS"/{checkpoints,diffusion_models,text_encoders,vae,loras}

echo "==> 5/7 模型（≈55GB，30MB/s 约 30 分钟）"
: "${HF_TOKEN:-}"   # 模型已从备份拷回时可以不提供
dl() { # dl <url> <dest>
  local url="$1" dest="$2"
  if [ -f "$dest" ]; then echo "  跳过 $(basename "$dest")"; return 0; fi
  if [ -z "${HF_TOKEN:-}" ]; then echo "  缺少模型且未提供 HF_TOKEN：$dest"; exit 1; fi
  aria2c -q -x16 -s16 -k 1M --file-allocation=none \
    --header="Authorization: Bearer $HF_TOKEN" -d "$(dirname "$dest")" -o "$(basename "$dest")" "$url"
}
dl "https://huggingface.co/black-forest-labs/FLUX.1-schnell/resolve/main/flux1-schnell.safetensors" "$MODELS/checkpoints/flux1-schnell.safetensors"
dl "https://huggingface.co/black-forest-labs/FLUX.1-schnell/resolve/main/ae.safetensors"                "$MODELS/checkpoints/ae.safetensors"
dl "https://huggingface.co/comfyanonymous/flux_text_encoders/resolve/main/t5xxl_fp8_e4m3fn.safetensors"  "$MODELS/text_encoders/t5xxl_fp8_e4m3fn.safetensors"
dl "https://huggingface.co/comfyanonymous/flux_text_encoders/resolve/main/clip_l.safetensors"            "$MODELS/text_encoders/clip_l.safetensors"
dl "https://huggingface.co/Muapi/vector-illustration/resolve/main/vector-illustration.safetensors"       "$MODELS/loras/vector-illustration.safetensors"

SIZE=$(stat -c%s "$MODELS/checkpoints/flux1-schnell.safetensors")
[ "$SIZE" = "23782506688" ] || { echo "flux 文件不完整：$SIZE"; exit 1; }

ln -sf "$MODELS/checkpoints/flux1-schnell.safetensors" "$MODELS/diffusion_models/flux1-schnell.safetensors"
cp -f "$MODELS/checkpoints/ae.safetensors" "$MODELS/vae/ae.safetensors"

echo "==> 6/7 确认显存空闲（2026-10-27 起不再安装 strata）"
if systemctl list-unit-files 2>/dev/null | grep -q '^strata.service'; then
  echo "  检测到 strata.service（已弃用），停止以释放显存"
  sudo systemctl stop strata 2>/dev/null || true
fi
for f in /sys/class/drm/card*/device/mem_info_vram_used; do
  [ -r "$f" ] && echo "  VRAM used: $(( $(cat "$f")/1024/1024 )) MB"
done

echo "==> 7/7 启动"
pkill -f "main.py --listen 127.0.0.1 --port 8188" 2>/dev/null || true
cd "$COMFY"
nohup "$PY" main.py --listen 127.0.0.1 --port 8188 --reserve-vram 1 --fp8_e4m3fn-unet \
  > "$AI/logs/comfyui-vocab.log" 2>&1 </dev/null &
disown
for _ in $(seq 1 60); do
  curl -fsS http://127.0.0.1:8188/system_stats >/dev/null 2>&1 && break
  sleep 2
done
curl -fsS http://127.0.0.1:8188/system_stats | head -c 160; echo
echo "完成。冒烟测试：python3 scripts/vocab/image/batch_images_flux.py --scenes /tmp/scenes.json --out $AI/vocab-images/raw"
