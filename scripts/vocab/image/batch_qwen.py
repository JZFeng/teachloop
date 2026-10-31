#!/usr/bin/env python3
"""Qwen-Image 批量出图（词卡主方案，2026-10-31 定稿）。

前置：ComfyUI 起在 127.0.0.1:8188，且 models 里有：
  diffusion_models/qwen_image_fp8_e4m3fn.safetensors
  text_encoders/qwen_2.5_vl_7b_fp8_scaled.safetensors
  vae/qwen_image_vae.safetensors

用法：
  python3 scripts/vocab/image/batch_qwen.py --scenes scenes.json --out ~/ai/vocab-images/qwen113
  # 加急：--steps 8；详情页大图：--size 1024 --steps 20
"""

from __future__ import annotations
import argparse, json, os, time, urllib.parse, urllib.request, uuid

API = os.environ.get("COMFY_API", "http://127.0.0.1:8188")
UNET = os.environ.get("QWEN_UNET", "qwen_image_fp8_e4m3fn.safetensors")
TE = os.environ.get("QWEN_TE", "qwen_2.5_vl_7b_fp8_scaled.safetensors")
VAE = os.environ.get("QWEN_VAE", "qwen_image_vae.safetensors")

STYLE = ("childrens book illustration, flat colors, thick clean black outlines, detailed faces and clothing, "
         "warm cream background, teal mustard and brown palette, no text: ")
NEG = "blurry, low quality, text, photo"


def workflow(word: str, scene: str, size: int, steps: int, cfg: float) -> dict:
    return {
        "u": {"class_type": "UNETLoader", "inputs": {"unet_name": UNET, "weight_dtype": "default"}},
        "c": {"class_type": "CLIPLoader", "inputs": {"clip_name": TE, "type": "qwen_image"}},
        "v": {"class_type": "VAELoader", "inputs": {"vae_name": VAE}},
        "5": {"class_type": "EmptySD3LatentImage", "inputs": {"width": size, "height": size, "batch_size": 1}},
        "6": {"class_type": "CLIPTextEncode", "inputs": {"text": STYLE + scene, "clip": ["c", 0]}},
        "7": {"class_type": "CLIPTextEncode", "inputs": {"text": NEG, "clip": ["c", 0]}},
        "3": {"class_type": "KSampler", "inputs": {
            "seed": 9, "steps": steps, "cfg": cfg, "sampler_name": "euler", "scheduler": "simple",
            "denoise": 1.0, "model": ["u", 0], "positive": ["6", 0], "negative": ["7", 0],
            "latent_image": ["5", 0]}},
        "8": {"class_type": "VAEDecode", "inputs": {"samples": ["3", 0], "vae": ["v", 0]}},
        "9": {"class_type": "SaveImage", "inputs": {"filename_prefix": f"vocab/q_{word}", "images": ["8", 0]}},
    }


def generate(word: str, scene: str, out_dir: str, size: int, steps: int, cfg: float) -> bool:
    payload = {"prompt": workflow(word, scene, size, steps, cfg), "client_id": str(uuid.uuid4())}
    request = urllib.request.Request(API + "/prompt", data=json.dumps(payload).encode(),
                                     headers={"Content-Type": "application/json"})
    prompt_id = json.loads(urllib.request.urlopen(request, timeout=30).read())["prompt_id"]
    for _ in range(1800):
        history = json.loads(urllib.request.urlopen(f"{API}/history/{prompt_id}", timeout=10).read().decode())
        if prompt_id in history and history[prompt_id].get("outputs"):
            break
        time.sleep(0.5)
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
    parser.add_argument("--size", type=int, default=512)
    parser.add_argument("--steps", type=int, default=12)
    parser.add_argument("--cfg", type=float, default=4.0)
    parser.add_argument("--only", default=None, help="只跑这些词，逗号分隔")
    args = parser.parse_args()

    scenes = json.load(open(args.scenes, encoding="utf-8"))
    if args.only:
        wanted = {w.strip() for w in args.only.split(",")}
        scenes = {w: s for w, s in scenes.items() if w in wanted}
    os.makedirs(args.out, exist_ok=True)

    started = time.time()
    ok, failed = 0, []
    words = sorted(scenes)
    for i, word in enumerate(words, 1):
        try:
            if generate(word, scenes[word], args.out, args.size, args.steps, args.cfg):
                ok += 1
            else:
                failed.append(word)
        except Exception as error:
            failed.append(f"{word}({str(error)[:60]})")
        if i % 20 == 0 or i == len(words):
            print(f"[{i}/{len(words)}] ok={ok} fail={len(failed)} avg={(time.time()-started)/i:.1f}s", flush=True)
    print(json.dumps({"ok": ok, "fail": failed, "elapsed": round(time.time() - started)}, ensure_ascii=False))
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
