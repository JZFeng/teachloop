#!/usr/bin/env python3
"""FLUX.1-schnell 批量出图（512² / 4 步 / 水彩风），词卡配图实验用。

前置（一次性，见 docs/reports/2026-10-27-vocab-image-models/README.md）：
- 官方 flux1-schnell.safetensors（23.8GB，gated，需 HF 许可）+ symlink 到 models/diffusion_models/
- t5xxl_fp8_e4m3fn.safetensors + clip_l.safetensors 在 models/text_encoders/
- ae.safetensors 在 models/vae/
- ComfyUI 启动必须带 --fp8_e4m3fn-unet，否则 24GB 显存放不下

跑法：
  python3 scripts/vocab/image/batch_images_flux.py --scenes scenes.json --out ~/ai/vocab-images/flux
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
UNET = os.environ.get("FLUX_UNET", "flux1-schnell.safetensors")
T5 = os.environ.get("FLUX_T5", "t5xxl_fp8_e4m3fn.safetensors")
CLIP_L = os.environ.get("FLUX_CLIP_L", "clip_l.safetensors")
VAE = os.environ.get("FLUX_VAE", "ae.safetensors")
SIZE = int(os.environ.get("SIZE", "512"))
STEPS = int(os.environ.get("STEPS", "4"))
GUIDANCE = float(os.environ.get("GUIDANCE", "3.5"))
STYLE = ("warm-toned watercolor illustration on textured paper, soft golden side light, "
         "muted ochre and sage palette, soft warm background wash (never white), visible brush edges, "
         "clear silhouette, centered composition: ")


def workflow(word: str, scene: str) -> dict:
    return {
        "u": {"class_type": "UNETLoader",
              "inputs": {"unet_name": UNET, "weight_dtype": "default"}},
        "c": {"class_type": "DualCLIPLoader",
              "inputs": {"clip_name1": T5, "clip_name2": CLIP_L, "type": "flux"}},
        "v": {"class_type": "VAELoader", "inputs": {"vae_name": VAE}},
        "5": {"class_type": "EmptySD3LatentImage",
              "inputs": {"width": SIZE, "height": SIZE, "batch_size": 1}},
        "6": {"class_type": "CLIPTextEncode", "inputs": {"text": STYLE + scene, "clip": ["c", 0]}},
        "7": {"class_type": "FluxGuidance", "inputs": {"conditioning": ["6", 0], "guidance": GUIDANCE}},
        "3": {"class_type": "KSampler", "inputs": {
            "seed": zlib.crc32(word.encode()) % 10 ** 9, "steps": STEPS, "cfg": 1.0,
            "sampler_name": "euler", "scheduler": "simple", "denoise": 1.0, "model": ["u", 0],
            "positive": ["7", 0], "negative": ["7", 0], "latent_image": ["5", 0]}},
        "8": {"class_type": "VAEDecode", "inputs": {"samples": ["3", 0], "vae": ["v", 0]}},
        "9": {"class_type": "SaveImage",
              "inputs": {"filename_prefix": f"vocab/f_{word}", "images": ["8", 0]}},
    }


def generate(word: str, scene: str, out_dir: str) -> bool:
    payload = {"prompt": workflow(word, scene), "client_id": str(uuid.uuid4())}
    request = urllib.request.Request(
        API + "/prompt", data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"})
    prompt_id = json.loads(urllib.request.urlopen(request, timeout=30).read())["prompt_id"]
    for _ in range(1800):
        history = json.loads(urllib.request.urlopen(f"{API}/history/{prompt_id}", timeout=10).read().decode())
        if prompt_id in history and history[prompt_id].get("outputs"):
            break
        time.sleep(0.4)
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
    parser.add_argument("--scenes", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--only", default=None)
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
        if index % 20 == 0 or index == len(words):
            elapsed = time.time() - started
            print(f"[{index}/{len(words)}] ok={ok} fail={len(failed)} avg={elapsed/index:.2f}s", flush=True)
    print(json.dumps({"ok": ok, "fail": failed, "elapsed": round(time.time() - started, 1)}))
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
