# 词卡配图的 prompt 资产（定稿 2026-10-27）

三样东西决定出图效果：**风格前缀**（这页）、**场景描述**（`scenes-113.json`）、**采样与后处理参数**（见文末）。

## 1. 定稿风格前缀（C1 暖纸底）

```text
warm-toned watercolor illustration on textured paper, smooth soft washes, soft golden side light,
muted ochre and sage palette, soft warm background wash (never white), visible brush edges,
clear silhouette, centered composition, no watermark, no signature, no text:
```

后面直接拼场景描述，例如：

```text
… no signature, no text: a lone climber gripping a steep rock wall, one arm reaching up, body stretched, chalk dust in the air, big sky
```

**为什么这样写**

- 风格词放最前：SDXL 系 CLIP 77 token 截断会吃掉尾部风格词（本机实测会退化成写实照片）；
- `soft warm background wash (never white)`：白底会融进浅色卡片（实测白底占比 59.7% → 0.4%）；
- `no watermark, no signature, no text`：FLUX 会偶发自签名（实测出现过 “Neverwhite”）；
- `clear silhouette, centered composition`：88px 缩略下靠轮廓认图。

## 2. 备选风格（对比用，未定稿）

| 代号 | 前缀 | 观感 | 88px 实测 |
| :--- | :--- | :--- | :--- |
| A | `oil-watercolor hybrid, thick expressive brushstrokes, warm golden backlight, dust motes, deep shadows, painterly edges:` | 厚涂油画，接近 ChatGPT | L≈144、SD≈67，偏暗 |
| B | `loose watercolor wash, wet-on-wet edges, soft paper texture, muted warm palette, faint pencil lines:` | 松风水彩 | L≈225、SD≈41，太淡发灰 |
| **C1（定稿）** | 见上 | 暖纸底绘本 | **L≈196、SD≈55、白底 0.4%** |

## 3. 场景描述规则（达意的关键）

- 一个主体 + 一个**定格姿势** + 一个道具 + 一个环境，12–24 词；
- 只用可画动词：`climb / carry / hold / reach / lift / kneel / sit / walk / look / point / lean / stand`；
- **禁止**状态变化与交互：`push a stuck door / open / close / become / struggle against`（实测 6 次全部画错：门被画成开着、人站着）；
- 抽象词用**代理场景**：`struggle → 攀岩`、`justify → 举着纸张对人说明`、`concede → 双手举起`；
- 场景由 `make_scene_prompts.py` 从 `coreSemantics.mentalImage`（中文）生成，规则写在脚本的 SYSTEM prompt 里。

## 4. 采样参数（FLUX.1-schnell）

| 参数 | 值 |
| :--- | :--- |
| 尺寸 | 512×512 |
| 步数 | 4 |
| guidance | 3.5（FluxGuidance） |
| sampler / scheduler | euler / simple |
| cfg | 1.0 |
| seed | `crc32(word) % 1e9`（可复现） |
| 模型加载 | `--fp8_e4m3fn-unet`（24GB 卡必须） |

实测：2.43 秒/张，113 张 274.9 秒。

## 5. 后处理（`normalize_images.py`）

```text
levels 2%–98% → ×0.92 + 0.06（提暗部）→ ×0.965（高光压到 ~246）
→ R×1.03 / B×0.97（暖色偏移）→ 缩到目标尺寸 → UnsharpMask(0.8, 30, 2)
```

**顺序不能反**：先锐化后缩小会把纸纹放大成颗粒（percent 55 时最明显）。
产物：176px WebP（卡片 2x，≈10KB/张）+ 96px WebP。

## 6. 验收指标（88px）

| 指标 | 目标 | 定稿实测 |
| :--- | :--- | :--- |
| 亮度 | 190–210 | 196.0 |
| 对比度（std） | ≥52 | 54.8 |
| 近白像素（≥250） | <1% | 0.4% |
