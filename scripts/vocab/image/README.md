# 词卡配图：本地 SDXL 批量出图（实验）

在 `ai-ubuntu`（RX 7900 XTX 24GB / ROCm）上跑的批处理，用于给 `coreSemantics.mentalImage` 配一张 1:1 小图。
**注意**：该机器的 `strata.service`（本地 LLM）会占满 24GB 显存，跑图前必须 `systemctl stop strata`，跑完再 `start`。

## 模型与参数

- 底模：`DreamShaperXL_Turbo_v2_1.safetensors`（SDXL Turbo）
- LoRA：`vector-illustration.safetensors`（HF `Muapi/vector-illustration`，触发词 `color icon`，strength 1.0）
- 采样：8 steps / CFG 2.0 / dpmpp_sde + karras / 1024×1024
- 实测：**4.88 秒/张**（单请求串行），113 张 9.2 分钟

## Prompt 结构（顺序很重要）

SDXL 的 CLIP 每个编码器只吃 77 token，**风格词必须放最前面**，否则会被截断：

```text
color icon, flat vector illustration, thick outlines, solid flat colors, minimalist:
{scene_en}, centered, generous negative space, no text
```

负面词：

```text
photo, photorealistic, 3d render, realistic, skin texture, depth of field, blur,
text, letters, typography, watermark, logo, clutter, extra limbs, deformed hands
```

`scene_en` 由文本模型把 `coreSemantics.mentalImage`（中文）转成 12–28 词的可见动作描述：
`python3 scripts/vocab/image/make_scene_prompts.py`（113 词实测成本 $0.005、36 秒）。

## 跑法

```bash
# 1) 释放显存
sudo systemctl stop strata
# 2) 起 ComfyUI（开启模型缓存，别用 --cache-none，否则每张多 3 秒）
cd ~/ai/ComfyUI && ~/ai/comfy/venv/bin/python main.py --listen 127.0.0.1 --port 8188 --reserve-vram 1
# 3) 批量
python3 scripts/vocab/image/batch_images.py --scenes scenes.json --out ~/ai/vocab-images
# 4) 恢复 LLM
sudo systemctl start strata
```
