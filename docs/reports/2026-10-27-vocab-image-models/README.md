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
