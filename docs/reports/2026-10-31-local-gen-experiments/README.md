# 本地生图实验：SDXL LoRA / FLUX LoRA / 国产 HunyuanDiT（2026-10-31 夜）

目标：在 `ai-ubuntu`（RX 7900 XTX 24GB / ROCm 7.2 / torch 2.14.1）上，用 80 张 OpenAI 教材图训练/复刻同一画风，
供 3000 词批量出图。教材与验证集见 `docs/reports/2026-10-31-word-selection-100/`。

## 结论（一句话）

- **能跑通的只有 SDXL LoRA**（但画风跑偏成“装饰绘本”，不是我们的扁平线描）；
- **FLUX LoRA 训练被 ROCm 的 fp8 路径毁掉**（长训练输出灰色噪声）；
- **国产 HunyuanDiT 1.2 反而最接近目标画风**，且不需要训练，单张 ≈19 秒。

## 关键发现：`expandable_segments` 会让 ROCm 训练崩

所有训练最初都报 `hipErrorIllegalAddress`，崩在 `optimizer.step()`。逐项排查后定位到环境变量：

```bash
PYTORCH_HIP_ALLOC_CONF=expandable_segments:True   # ← 元凶
```

最小复现：两个 2048×2048 参数做 5 次 AdamW step，带该变量必崩，去掉后正常。
**训练脚本里不要设这个变量**（ComfyUI 推理不受影响）。

## B. SDXL LoRA（sd-scripts 失败 → diffusers 脚本成功）

| 步骤 | 结果 |
| :--- | :--- |
| 单文件 `sd_xl_base_1.0.safetensors` 训练 | sd-scripts 误判模型结构，`size mismatch` |
| 转 diffusers 目录后训练 | 成功：80 张 × 10 repeats，800 步，**11 分 17 秒**（≈1.2 it/s） |
| 512 分辨率推理 | 出现明显平铺/错位伪影（训练分辨率 1024 + 错误推理分辨率） |
| **1024 推理 + 风格前缀 + LoRA 1.2–1.5** | 连贯，但画风变成“装饰性绘本”（繁复花边），不是扁平线描 |

产物：`/home/jzfeng/ai/train/out_diff_sdxl/pytorch_lora_weights.safetensors`（89MB），
ComfyUI 里可直接 `LoraLoader` 使用。对照图：`sdxl-lora-style.png`。

## C. FLUX LoRA（训练能跑，产物不可用）

| 配置 | 结果 |
| :--- | :--- |
| `--fp8_base`（全 fp8） | 5 步能存盘，24 步后推理变纯噪声 |
| `--fp8_base_unet` + lr 1e-4 | 10 步推理正常（写实），100 步后噪声 |
| `--fp8_base_unet` + lr 2e-5（150 步） | 有轮廓但整体被噪声覆盖（`lr_test.png`） |
| bf16 + `--blocks_to_swap` | OOM（23.8GB 权重转 bf16 时爆） |

结论：24GB 卡上 FLUX LoRA 只能走 fp8，而 ROCm 的 fp8 训练会产出损坏权重。**本机暂不可用**。

### 第 5 条路径：`ai-toolkit`（ostris）——**成功**

- 环境：独立 venv（torch 2.14.1+rocm7.2、diffusers 0.39、transformers 5.5.3、torchaudio）；
- 模型：直接给 repo id `black-forest-labs/FLUX.1-schnell`，ai-toolkit 自己下载 diffusers 格式
  （`HF_HUB_DISABLE_XET=1` + `HF_TOKEN`，否则 xet 报 `Unable to parse string as hex hash value`）；
- 训练配置：LoRA linear 16/alpha 16、qfloat8 量化、adamw8bit、lr 5e-5、**100 步**、batch 1、gradient checkpointing、
  resolution 512、noise_scheduler flowmatch；
- 结果：**第 50 步的 checkpoint 可用**（`tlbook_aitk_000000050.safetensors`，76MB），
  在 ComfyUI 里 `LoraLoaderModelOnly` 直接加载，512²/4 步推理 **2.1 秒/张**；
  但**第 100 步（最终权重）又是噪声**——说明训练到某个步数后仍会发散。

### C 结论（可用但有边界）

| 项 | 结果 |
| :--- | :--- |
| 可用 LoRA | ✅ `~/ai/train/aitk_out/tlbook_aitk/tlbook_aitk_000000050.safetensors` |
| 安全步数 | **≤50 步**（100 步发散；原因仍是 ROCm fp8 反传不稳定） |
| 推理速度 | **2.1 秒/张**（512² / 4 步） |
| 20 词验证（88px） | 原始：L=218.9 / SD=39.6（偏淡）→ **后处理：L=212.0 / SD=62.1**（可读） |
| 画风 | 细线 + 米色底，方向正确；后处理后偏“扁平贴纸”，不如 Qwen/OpenAI 细腻 |

对照图：`fluxlora-aitk-vs-openai.png`（原始）、`fluxlora-aitk-postprocessed.png`（后处理后）。
对照图：`fluxlora-vs-openai.png`（600 步版本的验证集输出，全噪声）。

## D. 国产模型：HunyuanDiT 1.2（可用）

- 权重：`comfyanonymous/hunyuan_dit_comfyui` 单文件 8.2GB（`8240228270` 字节）；
- 节点：`CheckpointLoaderSimple` → `CLIPTextEncodeHunyuanDiT`（bert + mt5xl 两段文本）→ `KSampler`；
- 参数：1024²、28 步、CFG 6、dpmpp_2m/karras；
- 速度：**≈19 秒/张**（20 张 385 秒）；
- 画风：扁平卡通、粗描边、亮色，**比两个 LoRA 都更接近目标**；
- 问题：语义准确度不稳（`wallet` 画成折叠垫、`debt` 跑题），需要更具体的场景 prompt。

对照图：`hunyuan-vs-openai.png`。

## E. Kolors（快手，SDXL 系）——可用，偏绘画感

- 模型：`Kwai-Kolors/Kolors-diffusers`（17GB，含 ChatGLM 文本编码器）；
- 加载：diffusers `KolorsPipeline(variant="fp16")` + `enable_model_cpu_offload()`（直接上卡会 OOM：TE 12GB + UNet 5GB）；
- 速度：CPU offload 下约 **2–3 分钟/张**（1024²，30 步）；
- 画风：水彩/绘画感儿童绘本，**不像线描**，但与 OpenAI 的“插画感”接近；
- 结论：可作为备选，但要“扁平线描”仍需提示词或 LoRA。

对照图：`kolors-test.png`。

## F. 88px 达意实测

| 来源 | 亮度 | 对比度 | 边缘密度 | 88px 观感 |
| :--- | ---: | ---: | ---: | :--- |
| OpenAI | 147.0 | 71.8 | 43.6 | 厚描边、层次清楚 |
| HunyuanDiT | 185.6 | 59.6 | 51.7 | 更亮、更卡通，细节偏多 |
| 对照条带 | `eval-88px-strip.png` | | | 两者在 88px 都能认出主体 |

## G. Qwen-Image（阿里，20B，fp8）——本地最强风格匹配

- 模型：`Comfy-Org/Qwen-Image` 的 `qwen_image_fp8_e4m3fn`（20.4GB）+ `qwen_2.5_vl_7b_fp8_scaled`（9.4GB）+ VAE（254MB）；
- 加载：ComfyUI 原生（UNETLoader + CLIPLoader type=qwen_image + VAELoader + CLIPTextEncode）；
- 参数：1024²、20 步、cfg 4、euler/simple；
- 速度：**75–90 秒/张**（顺序加载，24GB 卡吃紧）；
- 画风：**扁平线描 + 粗描边 + 米色底 + teal/mustard**，三者里最接近 OpenAI 教材；
- 88px：对比度 **81.7**（三者最高），小图辨识度最好。

对照图：`three-way-compare.png`（大图）、`three-way-88px.png`（88px 行）。

## 88px 最终指标（20 个验证词）

| 来源 | 亮度 | 对比度 | 边缘密度 | 视觉判断 |
| :--- | ---: | ---: | ---: | :--- |
| OpenAI（教材） | 147.0 | 71.8 | 43.6 | 厚描边、层次最丰富 |
| HunyuanDiT 1.2 | 185.6 | 59.6 | 51.7 | 更亮更卡通，细节偏多 |
| **Qwen-Image fp8** | 169.6 | **81.7** | 51.1 | **线描最明显，88px 最清楚** |

## 成本 / 速度 / 3000 词可行性

| 方案 | 单张耗时 | 113 词 | 3000 词 | 成本 | 风格符合度 |
| :--- | ---: | ---: | ---: | ---: | --- |
| OpenAI gpt-image-2 | ~20 s | ~40 min | ~17 h | **$0.07/张 ≈ $210** | 100%（基准） |
| **Qwen-Image fp8** | 75–90 s | ~2.5–3 h | ~65–75 h | 0 | **90%** |
| HunyuanDiT 1.2 | 20.5 s | ~40 min | **~17 h** | 0 | 80% |
| SDXL LoRA | 8 s | ~15 min | ~7 h | 0 | 50%（跑偏） |
| FLUX LoRA | — | — | — | — | ❌ 产物损坏 |

宁可慢也要像：**113 词用 Qwen（2.5–3 小时）**；要铺 3000 词用 **Hunyuan（17 小时）**，或对 Qwen 降分辨率/步数（512² + 12 步 ≈ 25–30 s，3000 词约 20–25 小时）。

## D2. HunyuanDiT 精炼提示（20 张验证集，最终版）

把提示词换成与 OpenAI 教材同款的描述（editorial line illustration + cream 背景 + teal/mustard 配色），
20 个验证词全部重出：**411 秒 / 20 张 ≈ 20.5 秒/张**，风格一致性明显好于 LoRA 方案。

- 画风：扁平卡通、粗描边、暖米色底，**最接近 OpenAI 教材**；
- 语义：castle / umbrella / sprint / stir / serendipity 等准确；`wallet`、`jagged`、`brittle` 有漂移；
- 对照图：`hunyuan2-vs-openai.png`。

## SDXL 二次训练（把风格词写进 caption）

caption 改为 `tlbook, flat vector childrens book illustration, thick clean black outlines, flat colors, warm cream background…, {scene}`，
lr 5e-5、600 步。结果：风格开始像了，但**不一致**（有的扁平、有的灰阶、有的写实）。对照图：`sdxl-lora-restyle.png`。
结论：SDXL LoRA 需要更长训练/更低 lr 才可能稳定，今晚不再投入。

## 推荐

| 方案 | 适用 | 成本 |
| :--- | :--- | :--- |
| 首批上线用 OpenAI 100 张 | 立即可用、画风一致 | 已完成（≈$7） |
| 本地 HunyuanDiT 补量 | 3000 词兜底，扁平卡通风 | 0 元，19s/张 |
| FLUX/SDXL LoRA | **暂不推荐**（前者损坏、后者跑偏） | — |
| 后续 | 有 CUDA 机器再训 FLUX LoRA；或改训 Qwen-Image | — |

## 复现命令

```bash
# SDXL LoRA（diffusers 脚本，需先去掉 expandable_segments）
bash ~/ai/train/run_diff_sdxl.sh 800

# FLUX LoRA（fp8_base_unet，仅供实验，产物不可用）
bash ~/ai/train/run_train.sh 150

# HunyuanDiT（ComfyUI）
python3 /tmp/hy_eval.py
```
