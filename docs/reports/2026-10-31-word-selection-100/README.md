# 100 词：训练 + 验证集，以及 OpenAI 出图结果（2026-10-31）

## 这份清单怎么来的

- 词表：`words-top10k.txt`（按词频排序），排除功能词、数字、颜色、星期月份；
- 分层（按“可画性”）：**A 具体名词 30 / B 动作动词 35 / C 形容词 20 / D 抽象（代理场景）15**；
- 划分：每层留 20% 做验证 → **训练 80 / 验证 20**（验证词不进训练，只用于“学得像不像”的考试）；
- 每个词都有一句英文场景（`scene` 列 / `captions.json`），出图与训练共用。

文件：
- `words-100.csv` / `.json`：word, stratum, pos, rank_top10k, split, scene
- `words-100.txt`：一行一词（直接喂脚本）
- `captions.json`：`word → "tlbook, {scene}"`（LoRA 训练用 caption，`tlbook` 是触发词）
- `contact-sheet-openai.png`：100 张成图总览

验证集（20）：castle, harbour, lantern, oven, umbrella, wallet, climb, dig, fold, peel, sprint, squeeze, stir, brittle, empty, jagged, slippery, debt, rivalry, serendipity

## OpenAI 出图（已完成 100/100）

- 模型：`gpt-image-2`，1024²，quality=medium，提示词前缀见下；
- 用量：**175,600 图像 token**，按 $40/1M 估算 **≈$7.0**；
- 统计：0 失败（80 训练 + 20 验证）。

风格前缀（与本地 S2/S3 对齐）：

```text
hand-drawn editorial line illustration for a children's book, thick clean black outlines,
flat full-color fills, detailed hair and clothing, expressive faces, warm cream background,
teal, mustard and warm brown palette, single subject filling about 70% of the frame,
no text, no numbers, no watermark, no signature: {scene}
```

## Flux LoRA 训练：本机受阻塞（重要）

在 `ai-ubuntu`（7900 XTX / ROCm 7.2 / torch 2.14.1）上用 `kohya-ss/sd-scripts` 训练 Flux LoRA，**三种配置都在 optimizer step 崩溃**：

| 尝试 | 关键参数 | 结果 |
| :--- | :--- | :--- |
| 1 | `--fp8_base`，无 block swap | `hipErrorIllegalAddress` |
| 2 | `--fp8_base --blocks_to_swap=20` | `hipErrorIllegalAddress` |
| 3 | bf16 + `--blocks_to_swap=20` | `hipErrorIllegalAddress`（VRAM 11.7GB 时崩） |
| 4 | bf16 + `--blocks_to_swap=8` | SIGABRT，同样 HIP 非法地址 |

错误原文：`torch.AcceleratorError: CUDA error: an illegal memory access was encountered / hipErrorIllegalAddress`，
出现在 `torch/optim/adam.py` 的 step 阶段。结论：**当前 ROCm + sd-scripts 的 Flux 训练路径不可用**（不是显存不足：OOM 是另一种报错）。

已准备好的训练资产（随时可在 CUDA 机器或修好 ROCm 后继续）：
- `~/ai/train/images/`：80 张图 + 80 个 `.txt` caption（`tlbook, ...`）
- `~/ai/train/dataset.toml`（512、num_repeats=10 → 800 steps）
- `~/ai/train/run_train.sh` / `run_train_bf16.sh`
- 训练环境：`~/ai/train-venv`（独立 venv，不影响 ComfyUI）

## 下一步选项

| 方案 | 说明 | 成本 |
| :--- | :--- | :--- |
| **A（推荐）** | 这 100 张直接作为第一批生产图；LoRA 等有 CUDA 机器再训 | $0 增量 |
| B | 改训 SDXL LoRA（ROCm 支持成熟），基座换成 SDXL 验证风格 | 训练 20–30 分钟，需重测质量 |
| C | 继续在 ROCm 上调 Flux 训练（换 torch 版本 / 关 sdpa / 换 ai-toolkit） | 未知，今晚不划算 |


## 113 词最终产物（2026-10-31 定稿）

- 抽检：**84 通过 / 24 模糊 / 5 失败**（`qa-113.csv`），29 个问题词已**重写场景并重跑**（`fix-29-before-after.png`）；
- 后处理：四周裁 3% → levels 2–98% → ×1.08−0.02 → 缩到 176/96 → UnsharpMask(0.8/30/2)；
- 产物：`final-176/<word>.webp`（113 张，共 976KB，约 8.6KB/张）；
- 88px 指标：**亮度 177.3 / 对比度 81.3 / 边缘 58.4**（对比度高于目标 55）；
- 卡片实拍：`card-mock-final-qwen.png`。
