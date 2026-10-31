#!/usr/bin/env python3
"""Qwen-Image 批量出图（词卡主方案，2026-10-31 定稿）。

前置：ComfyUI 起在 127.0.0.1:8188，且 models 里有：
  diffusion_models/qwen_image_fp8_e4m3fn.safetensors
  text_encoders/qwen_2.5_vl_7b_fp8_scaled.safetensors
  vae/qwen_image_vae.safetensors

用法：
  python3 scripts/vocab/image/batch_qwen.py --scenes scenes.json --out ~/ai/vocab-images/qwen113
  # 加急：--steps 8；详情页大图：--size 1024 --steps 20

断点续跑（2026-10-31 为 3000 词批量补）：
  --skip-existing       已存在且非空的 <word>.png 直接跳过（默认开）
  --progress FILE       每 N 张追加一行进度（x/total ok/fail avg/eta）
  --failures FILE       失败的词写进 JSON（含原因），跑完用它重试
  --only-file FILE      只跑这文件里的词（一行一个，或 JSON 数组）
  --attempts N          每个词进程内重试次数（默认 2）
"""

from __future__ import annotations
import argparse, json, os, sys, time, urllib.parse, urllib.request, uuid
from datetime import datetime, timezone

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


def generate(word: str, scene: str, out_dir: str, size: int, steps: int, cfg: float,
             timeout: float = 600.0) -> bool:
    payload = {"prompt": workflow(word, scene, size, steps, cfg), "client_id": str(uuid.uuid4())}
    request = urllib.request.Request(API + "/prompt", data=json.dumps(payload).encode(),
                                     headers={"Content-Type": "application/json"})
    body = json.loads(urllib.request.urlopen(request, timeout=30).read())
    if "prompt_id" not in body:
        raise RuntimeError("prompt rejected: " + json.dumps(body)[:120])
    prompt_id = body["prompt_id"]

    deadline = time.time() + timeout
    history, images = {}, []
    while time.time() < deadline:
        history = json.loads(urllib.request.urlopen(f"{API}/history/{prompt_id}", timeout=10).read().decode())
        entry = history.get(prompt_id) or {}
        status = (entry.get("status") or {}).get("status_str")
        if status == "error":
            raise RuntimeError("comfy error: " + json.dumps(entry.get("status"))[:120])
        if entry.get("outputs"):
            images = [item for node in entry["outputs"].values() for item in node.get("images", [])]
            break
        time.sleep(0.35)
    if not images:
        return False

    query = urllib.parse.urlencode({"filename": images[0]["filename"],
                                    "subfolder": images[0]["subfolder"], "type": "output"})
    data = urllib.request.urlopen(f"{API}/view?" + query, timeout=90).read()
    if not data:
        return False
    target = os.path.join(out_dir, word + ".png")
    temp = target + ".part"
    try:
        with open(temp, "wb") as handle:
            handle.write(data)
        os.replace(temp, target)      # 原子落盘：半截文件不会被当成"已完成"
    except Exception:
        if os.path.exists(temp):      # 失败不留 .part 垃圾
            try:
                os.remove(temp)
            except OSError:
                pass
        raise
    return True


def already_done(word: str, out_dir: str) -> bool:
    path = os.path.join(out_dir, word + ".png")
    return os.path.isfile(path) and os.path.getsize(path) > 1024


def load_only(path: str) -> set[str]:
    if not path:
        return set()
    text = open(path, encoding="utf-8").read().strip()
    if text.startswith("["):
        return {str(item).strip().lower() for item in json.loads(text)}
    return {line.strip().lower() for line in text.splitlines() if line.strip()}


def human(seconds: float) -> str:
    if seconds != seconds or seconds in (float("inf"),):
        return "?"
    hours, rest = divmod(int(seconds), 3600)
    minutes, sec = divmod(rest, 60)
    return f"{hours}h{minutes:02d}m" if hours else f"{minutes}m{sec:02d}s"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--scenes", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--size", type=int, default=512)
    parser.add_argument("--steps", type=int, default=12)
    parser.add_argument("--cfg", type=float, default=4.0)
    parser.add_argument("--only", default=None, help="只跑这些词，逗号分隔")
    parser.add_argument("--only-file", default=None, help="只跑这文件里的词（一行一个或 JSON 数组）")
    parser.add_argument("--skip-existing", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--progress", default=None, help="进度文件（每 progress-every 张追加一行）")
    parser.add_argument("--progress-every", type=int, default=100)
    parser.add_argument("--state", default=None, help="机器可读状态 JSON（每次写进度时覆盖）")
    parser.add_argument("--failures", default=None, help="失败清单 JSON")
    parser.add_argument("--attempts", type=int, default=2)
    parser.add_argument("--timeout", type=float, default=600.0, help="单张等待上限（秒）")
    args = parser.parse_args()

    scenes = json.load(open(args.scenes, encoding="utf-8"))
    if args.only:
        wanted = {w.strip().lower() for w in args.only.split(",")}
        scenes = {w: s for w, s in scenes.items() if w in wanted}
    only_file = load_only(args.only_file)
    if only_file:
        scenes = {w: s for w, s in scenes.items() if w.lower() in only_file}
    os.makedirs(args.out, exist_ok=True)

    words = sorted(scenes)
    todo = [w for w in words if not (args.skip_existing and already_done(w, args.out))]
    skipped = len(words) - len(todo)

    def log(message: str) -> None:
        print(message, flush=True)
        if args.progress:
            with open(args.progress, "a", encoding="utf-8") as handle:
                handle.write(message + "\n")

    log(f"# start {datetime.now(timezone.utc).isoformat(timespec='seconds')} "
        f"total={len(words)} skip_existing={skipped} todo={len(todo)} "
        f"size={args.size} steps={args.steps} cfg={args.cfg}")

    started = time.time()
    ok, failed = 0, []
    if not todo:
        log("[done] nothing to do: all images already exist")
    for i, word in enumerate(todo, 1):
        reason = ""
        for attempt in range(1, args.attempts + 1):
            try:
                if generate(word, scenes[word], args.out, args.size, args.steps, args.cfg, args.timeout):
                    ok += 1
                    reason = ""
                    break
                reason = "no-image"
            except Exception as error:
                reason = f"{type(error).__name__}: {str(error)[:80]}"
            if attempt < args.attempts:
                time.sleep(2 * attempt)
        if reason:
            failed.append({"word": word, "reason": reason})

        elapsed = time.time() - started
        done = i
        if done % args.progress_every == 0 or done == len(todo):
            avg = elapsed / done
            remaining = avg * (len(todo) - done)
            log(f"[{done}/{len(todo)}] ok={ok} fail={len(failed)} avg={avg:.1f}s "
                f"elapsed={human(elapsed)} eta={human(remaining)} last={word}")
            if args.state:
                json.dump({"total": len(words), "todo": len(todo), "done": done,
                           "ok": ok, "failed": len(failed), "skipped": skipped,
                           "avg_seconds": round(avg, 2), "elapsed_seconds": round(elapsed),
                           "eta_seconds": round(remaining), "updated": datetime.now(timezone.utc).isoformat()},
                          open(args.state, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
            if args.failures:
                json.dump({"generated_at": datetime.now(timezone.utc).isoformat(),
                           "scenes": args.scenes, "out": args.out,
                           "count": len(failed), "ok": ok, "todo": len(todo),
                           "words": [entry["word"] for entry in failed],
                           "detail": failed},
                          open(args.failures, "w", encoding="utf-8"), ensure_ascii=False, indent=2)

    total_elapsed = time.time() - started
    log(f"# done {datetime.now(timezone.utc).isoformat(timespec='seconds')} ok={ok} fail={len(failed)} "
        f"skipped={skipped} elapsed={human(total_elapsed)} rate={total_elapsed/max(1,len(todo)):.1f}s/word")
    print(json.dumps({"ok": ok, "fail": [entry["word"] for entry in failed],
                      "skipped": skipped, "elapsed": round(total_elapsed)}, ensure_ascii=False))
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
