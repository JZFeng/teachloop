#!/usr/bin/env python3
"""用本地 ComfyUI 给每个词出一张 1:1 扁平插画（实验脚本）。

前置：
- 机器上 ComfyUI 在 127.0.0.1:8188；
- checkpoints 里有 DreamShaperXL_Turbo_v2_1.safetensors；
- loras 里有 vector-illustration.safetensors（HF Muapi/vector-illustration，触发词 "color icon"）；
- 若同机跑着占满显存的 LLM（如 strata.service），先 `systemctl stop strata`，跑完再 start。

用法：
  python3 scripts/vocab/image/batch_images.py --scenes scenes.json --out ~/ai/vocab-images
"""

from __future__ import annotations

import argparse
import json
import os
import time
import urllib.parse
import urllib.request
import uuid
import zlib

API = os.environ.get("COMFY_API", "http://127.0.0.1:8188")
CHECKPOINT = os.environ.get("CHECKPOINT", "DreamShaperXL_Turbo_v2_1.safetensors")
LORA = os.environ.get("LORA", "vector-illustration.safetensors")
LORA_STRENGTH = float(os.environ.get("LORA_STRENGTH", "1.0"))
STEPS = int(os.environ.get("STEPS", "8"))
CFG = float(os.environ.get("CFG", "2.0"))
SIZE = int(os.environ.get("SIZE", "1024"))

# SDXL 的 CLIP 每个编码器 77 token：风格词必须放最前面，否则会被截断成写实照片
STYLE = "color icon, flat vector illustration, thick outlines, solid flat colors, minimalist: "
NEGATIVE = (
    "photo, photorealistic, 3d render, realistic, skin texture, depth of field, blur, "
    "text, letters, typography, watermark, logo, clutter, extra limbs, deformed hands"
)


def workflow(word: str, scene: str, seed: int) -> dict:
    positive = f"{STYLE}{scene}, centered, generous negative space, no text"
    return {
        "4": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": CHECKPOINT}},
        "10": {"class_type": "LoraLoader", "inputs": {
            "lora_name": LORA, "strength_model": LORA_STRENGTH, "strength_clip": LORA_STRENGTH,
            "model": ["4", 0], "clip": ["4", 1]}},
        "5": {"class_type": "EmptyLatentImage",
              "inputs": {"width": SIZE, "height": SIZE, "batch_size": 1}},
        "6": {"class_type": "CLIPTextEncode", "inputs": {"text": positive, "clip": ["10", 1]}},
        "7": {"class_type": "CLIPTextEncode", "inputs": {"text": NEGATIVE, "clip": ["10", 1]}},
        "3": {"class_type": "KSampler", "inputs": {
            "seed": seed, "steps": STEPS, "cfg": CFG, "sampler_name": "dpmpp_sde",
            "scheduler": "karras", "denoise": 1.0, "model": ["10", 0],
            "positive": ["6", 0], "negative": ["7", 0], "latent_image": ["5", 0]}},
        "8": {"class_type": "VAEDecode", "inputs": {"samples": ["3", 0], "vae": ["4", 2]}},
        "9": {"class_type": "SaveImage", "inputs": {"filename_prefix": f"vocab/{word}", "images": ["8", 0]}},
    }


def generate(word: str, scene: str, out_dir: str) -> bool:
    payload = {"prompt": workflow(word, scene, zlib.crc32(word.encode()) % 10 ** 9),
               "client_id": str(uuid.uuid4())}
    request = urllib.request.Request(
        API + "/prompt", data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"})
    prompt_id = json.loads(urllib.request.urlopen(request, timeout=30).read())["prompt_id"]
    for _ in range(600):
        history = json.loads(urllib.request.urlopen(f"{API}/history/{prompt_id}", timeout=10).read().decode())
        if prompt_id in history and history[prompt_id].get("outputs"):
            break
        time.sleep(0.25)
    images = [item for node in history[prompt_id]["outputs"].values() for item in node.get("images", [])]
    if not images:
        return False
    query = urllib.parse.urlencode({"filename": images[0]["filename"],
                                    "subfolder": images[0]["subfolder"], "type": "output"})
    data = urllib.request.urlopen(f"{API}/view?" + query, timeout=60).read()
    open(os.path.join(out_dir, word + ".png"), "wb").write(data)
    return True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--scenes", required=True, help="word -> scene_en 的 JSON")
    parser.add_argument("--out", required=True)
    parser.add_argument("--only", default=None, help="只跑这些词，逗号分隔")
    args = parser.parse_args()

    scenes = json.load(open(args.scenes, encoding="utf-8"))
    if args.only:
        wanted = {word.strip() for word in args.only.split(",")}
        scenes = {word: scene for word, scene in scenes.items() if word in wanted}
    os.makedirs(args.out, exist_ok=True)

    started = time.time()
    ok, failed = 0, []
    words = sorted(scenes)
    for index, word in enumerate(words, 1):
        try:
            if generate(word, scenes[word], args.out):
                ok += 1
            else:
                failed.append(word)
        except Exception as error:
            failed.append(f"{word}({error})")
        if index % 10 == 0 or index == len(words):
            elapsed = time.time() - started
            print(f"[{index}/{len(words)}] ok={ok} fail={len(failed)} avg={elapsed/index:.2f}s", flush=True)
    print(json.dumps({"ok": ok, "fail": failed, "elapsed": round(time.time() - started, 1)}))
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
