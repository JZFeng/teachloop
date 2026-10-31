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
对照图：`fluxlora-vs-openai.png`（600 步版本的验证集输出，全噪声）。

## D. 国产模型：HunyuanDiT 1.2（可用）

- 权重：`comfyanonymous/hunyuan_dit_comfyui` 单文件 8.2GB（`8240228270` 字节）；
- 节点：`CheckpointLoaderSimple` → `CLIPTextEncodeHunyuanDiT`（bert + mt5xl 两段文本）→ `KSampler`；
- 参数：1024²、28 步、CFG 6、dpmpp_2m/karras；
- 速度：**≈19 秒/张**（20 张 385 秒）；
- 画风：扁平卡通、粗描边、亮色，**比两个 LoRA 都更接近目标**；
- 问题：语义准确度不稳（`wallet` 画成折叠垫、`debt` 跑题），需要更具体的场景 prompt。

对照图：`hunyuan-vs-openai.png`。

未测：Qwen-Image（20B，diffusers 分片 ≈40GB，本机下载+推理成本过高）、Kolors（需转换）。

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
