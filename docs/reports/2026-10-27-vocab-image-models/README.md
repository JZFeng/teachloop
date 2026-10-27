# 词卡配图：模型选型实测（2026-10-27，branch `exp/vocab-card-image`）

目标：卡片右侧 88px 小图，判据是「一眼看懂这张图在说什么（达意）」，不是大图精细度。
环境：`ai-ubuntu`，RX 7900 XTX 24GB / ROCm，ComfyUI 0.41。

## 结论

**采用 FLUX.1-schnell（官方权重，fp8 载入）+ 水彩风提示词，512×512、4 步、guidance 3.5，单张 2.14 秒。**
113 个词全量 **241 秒（4 分钟）**，0 失败；这是目前唯一同时满足“达意 + 统一画风 + 够快”的组合。

## 四个组合的实测

| 方案 | 尺寸/步数 | 单张 | 113 张 | 达意 | 画风 |
| :--- | :--- | ---: | ---: | :--- | :--- |
| DreamShaperXL_Turbo + vector LoRA | 1024 / 8 | 4.88s | 9m11s | 差（动作乱） | 企业扁平，图库感 |
| SDXL base 1.0 | 1024 / 30 | 8.47s | 16m00s | 中（需改写场景） | 水彩绘本，氛围最好 |
| SDXL base 1.0 | **512 / 20** | **2.55s** | 4m48s | 弱 | 简化水彩 |
| **FLUX.1-schnell + 水彩提示** | **512 / 4** | **2.14s** | **4m01s** | **强** | 统一水彩，接近绘本 |

对比图：`model-comparison.png`（SDXL-512 / FLUX-512 / FLUX+水彩 / SDXL-1024，同 5 词）。
88px 可读性：`flux-88px-strip.png`；卡片版式：`card-mock-flux.png`。

## 为什么 Flux 赢了

1. **动作/空间关系**：`struggle`（攀岩）、`justify`（举着纸张对人说明）、`concede`（双手举起）都一次到位；SDXL 系即使改写场景也会把“推门”画成“门开着”。
2. **速度**：4 步就出；512 下 2.14s，比 SDXL 1024 快 4 倍。
3. **显存**：unet 用 `--fp8_e4m3fn-unet` 载入，峰值 ~17GB，24GB 卡够用。
4. **风格可控**：提示词前缀 `storybook watercolor illustration, painterly, muted warm palette…` 能稳定压出水彩风。

## 环境准备（一次性）

```bash
# 官方 Flux（gated，需在 HF 页面点 Agree）
aria2c -x16 -s16 -H "Authorization: Bearer $HF_TOKEN" \
  -o ~/ai/ComfyUI/models/checkpoints/flux1-schnell.safetensors \
  "https://huggingface.co/black-forest-labs/FLUX.1-schnell/resolve/main/flux1-schnell.safetensors"   # 23.8GB
ln -sf ~/ai/ComfyUI/models/checkpoints/flux1-schnell.safetensors ~/ai/ComfyUI/models/diffusion_models/
curl -L -H "Authorization: Bearer $HF_TOKEN" -o ~/ai/ComfyUI/models/text_encoders/t5xxl_fp8_e4m3fn.safetensors \
  "https://huggingface.co/comfyanonymous/flux_text_encoders/resolve/main/t5xxl_fp8_e4m3fn.safetensors"  # 4.6GB
curl -L -H "Authorization: Bearer $HF_TOKEN" -o ~/ai/ComfyUI/models/text_encoders/clip_l.safetensors \
  "https://huggingface.co/comfyanonymous/flux_text_encoders/resolve/main/clip_l.safetensors"           # 235MB
cp ~/ai/ComfyUI/models/checkpoints/ae.safetensors ~/ai/ComfyUI/models/vae/ae.safetensors

# 启动（fp8 unet 才能塞进 24GB）
~/ai/comfy/venv/bin/python main.py --listen 127.0.0.1 --port 8188 --reserve-vram 1 --fp8_e4m3fn-unet
```

## 工作流（API 版，见 `scripts/vocab/image/batch_images_flux.py`）

```
UNETLoader(flux1-schnell.safetensors)
+ DualCLIPLoader(t5xxl_fp8_e4m3fn, clip_l, type=flux)
+ VAELoader(ae)
+ EmptySD3LatentImage(512)
+ CLIPTextEncode(风格前缀 + scene)
+ FluxGuidance(guidance=3.5)
+ KSampler(steps=4, cfg=1.0, euler/simple)
+ VAEDecode → SaveImage
```

## 场景提示词规则（达意的关键）

- 一个主体 + 一个**定格姿势** + 一个道具 + 一个环境；12–24 词；
- 只用可画动词：climb / carry / hold / reach / lift / kneel / sit / walk / look / point / lean；
- 禁止状态变化与交互：push a stuck door / open / close / become；
- 抽象词用代理场景：struggle → 攀岩；justify → 举着纸张说明；concede → 双手举起。

`scenes113_drawable.json` 是这套规则重写后的 113 条场景。

## 收尾状态

- `strata`、`comfyui` 两个 systemd 服务都已停止（按用户要求，自己启动）；显存 26MB。
- 无效的半截模型（hunyuan 887MB、flux-fp8 365MB）已删除；保留官方 Flux 23.8GB + SDXL 两个。
- 未测：HunyuanDiT 1.2（下载中断，8.2GB 单文件可用）、Qwen-Image（20B，diffusers 分片，需拼装）。

## 定稿（2026-10-27 用户拍板）

**卡片配图 = FLUX.1-schnell 水彩提示 + 确定性后处理（levels 拉伸 + 轻微提亮 + 锐化）。**

- 目标区间：88px 亮度 **≈210**、对比度 **≥55**；实测 113 张：**L=206.1 / SD=65.0**（处理前 215.3 / 56.3）。
- 后处理脚本：[`scripts/vocab/image/normalize_images.py`](../../../scripts/vocab/image/normalize_images.py)（1%–99% levels → ×1.06+0.02 → UnsharpMask 1.2/60/2，纯确定性）。
- 卡片给图片加 1px `var(--line)` 边框，避免近白图融进浅色卡片。
- 为什么不是“只提亮”：B 亮底只提亮会把对比度从 40.9 降到 35.2，88px 更糊；提对比后 B 到 49.4，仍低于目标，故采用现水彩底 + 提对比。
- 产物：`contact-sheet-final.png`（113 张）、`card-mock-final.png`（卡片 88px 实拍）、`brightness-contrast-variants.png`（亮度/对比度对照）。
- 对比图统一用 `Hiragino Sans GB` 字体（Arial 无 CJK 字形，会出方框）。

## 修订（2026-10-27 下午）：白底太亮 → 暖纸底

用户反馈「card-mock-final 背景全白、太亮，看不清内容」。测量确认：上一版 **59.7% 的像素 ≥250**（近白），卡片是浅色，图与底融在一起。

修正两处：

1. **提示词**：去掉 `generous negative space`，改为
   `warm-toned watercolor illustration on textured paper, soft golden side light, muted ochre and sage palette, soft warm background wash (never white), visible brush edges, clear silhouette, centered composition`
2. **后处理**（`normalize_images.py` 的 v2 曲线）：levels 2%–98% → 轻提暗部 `×0.92+0.06` → **高光压到 ~246**（不再推到 255）→ 暖色偏移（R×1.03 / B×0.97）→ UnsharpMask(1.2, 55, 2)。

结果（113 张，88px）：

| 版本 | 亮度 | 对比度 | 近白像素(≥250) |
| :--- | ---: | ---: | ---: |
| 上一版（白底） | 213.3 | 65.2 | **59.7%** |
| **C1 暖底（当前）** | **196.0** | **54.8** | **0.4%** |
| A + 提亮（参照） | 175.8 | 55.0 | 0.5% |
| ChatGPT（参照） | 128.7 | 56.9 | 0.1% |

产物：`contact-sheet-c1.png`（113 张）、`card-mock-c1.png`（卡片实拍）、`warm-background-compare.png`（白底 / C1 / C2 / A+提亮 / ChatGPT 五列对比）。

## 最终定稿 v2（2026-10-27）

- 生成：FLUX.1-schnell，512×512，4 步，guidance 3.5，暖纸底水彩提示词（含 `no watermark, no signature, no text`）。
- 后处理：levels 2%–98% → 暗部 ×0.92+0.06 → 高光压 246 → 暖色 R×1.03/B×0.97 → **缩到目标尺寸后**轻锐化（0.8/30/2）。
- 产物：176px WebP（卡片 2x，113 张共 1.1MB，≈10KB/张）、96px WebP（520KB）。
- 实测：113 张 274.9 秒（2.43s/张）；88px 亮度 196.0、对比度 54.8、近白像素 0.4%。
- 分辨率结论：512 足够；768/1024 无可见收益，1024 还会改构图并偶发水印文字。
- 环境恢复：见 [`docs/runbooks/2026-10-27-image-gen-box-restore.md`](../../runbooks/2026-10-27-image-gen-box-restore.md) 与 `scripts/vocab/image/bootstrap_image_box.sh`。
