#!/usr/bin/env python3
"""HunyuanDiT 1.2 批量出图（后备方案：Qwen 跑偏的词用它重跑）。

前置：ComfyUI 起在 127.0.0.1:8188，models/checkpoints/hunyuan_dit_1.2.safetensors 存在。

用法：
  python3 scripts/vocab/image/batch_hunyuan.py --scenes scenes.json --only dubious,justify --out ~/ai/vocab-images/hy_fix
"""

from __future__ import annotations
import argparse, json, os, time, urllib.parse, urllib.request, uuid

API = os.environ.get("COMFY_API", "http://127.0.0.1:8188")
CKPT = os.environ.get("HUNYUAN_CKPT", "hunyuan_dit_1.2.safetensors")

STYLE = ("childrens book illustration, flat colors, thick clean black outlines, warm cream background, "
         "teal mustard and brown palette, single subject, no text: ")
NEG = "blurry, low quality, text, photo"


def workflow(word: str, scene: str, size: int, steps: int, cfg: float) -> dict:
    prompt = STYLE + scene
    return {
        "4": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": CKPT}},
        "6": {"class_type": "CLIPTextEncodeHunyuanDiT", "inputs": {"clip": ["4", 1], "bert": prompt, "mt5xl": prompt}},
        "7": {"class_type": "CLIPTextEncodeHunyuanDiT", "inputs": {"clip": ["4", 1], "bert": NEG, "mt5xl": NEG}},
        "5": {"class_type": "EmptyLatentImage", "inputs": {"width": size, "height": size, "batch_size": 1}},
        "3": {"class_type": "KSampler", "inputs": {
            "seed": 9, "steps": steps, "cfg": cfg, "sampler_name": "dpmpp_2m", "scheduler": "karras",
            "denoise": 1.0, "model": ["4", 0], "positive": ["6", 0], "negative": ["7", 0],
            "latent_image": ["5", 0]}},
        "8": {"class_type": "VAEDecode", "inputs": {"samples": ["3", 0], "vae": ["4", 2]}},
        "9": {"class_type": "SaveImage", "inputs": {"filename_prefix": f"vocab/hy_{word}", "images": ["8", 0]}},
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
    parser.add_argument("--size", type=int, default=1024)
    parser.add_argument("--steps", type=int, default=28)
    parser.add_argument("--cfg", type=float, default=6.5)
    parser.add_argument("--only", default=None)
    args = parser.parse_args()

    scenes = json.load(open(args.scenes, encoding="utf-8"))
    if args.only:
        wanted = {w.strip() for w in args.only.split(",")}
        scenes = {w: s for w, s in scenes.items() if w in wanted}
    os.makedirs(args.out, exist_ok=True)

    started = time.time()
    ok, failed = 0, []
    for i, word in enumerate(sorted(scenes), 1):
        try:
            if generate(word, scenes[word], args.out, args.size, args.steps, args.cfg):
                ok += 1
            else:
                failed.append(word)
        except Exception as error:
            failed.append(f"{word}({str(error)[:60]})")
        print(f"[{i}/{len(scenes)}] {word} ok={ok} fail={len(failed)}", flush=True)
    print(json.dumps({"ok": ok, "fail": failed, "elapsed": round(time.time() - started)}, ensure_ascii=False))
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
