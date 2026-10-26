# 本地批量出图：113 个词的卡片配图（2026-10-26，branch `exp/vocab-card-image`）

在 `ai-ubuntu`（RX 7900 XTX 24GB，ROCm）上用 ComfyUI 给 113 个词的 `coreSemantics.mentalImage` 各出一张 1:1 扁平插画。

## 结论

| 项 | 结果 |
| :--- | :--- |
| 出图 | **113/113**，0 失败，共 **9 分 11 秒** |
| 单张耗时 | **4.88 秒**（串行，1024×1024） |
| 显存 | 峰值 ~16GB / 24GB |
| 提示词生成 | 113 条英文场景，DeepSeek flash，**$0.005**、36 秒 |
| 额外成本 | 0（本地 GPU，电费忽略） |
| 风格 | 扁平矢量（SDXL Turbo + `vector-illustration` LoRA，strength 1.0） |

## 环境（本机已有，未新装框架）

- `~/ai/ComfyUI` 0.41.0 + `~/ai/comfy/venv`（PyTorch 2.14.1+rocm7.2，`torch.cuda.is_available()=True`）
- 底模 `DreamShaperXL_Turbo_v2_1.safetensors`（已有）
- 新增 LoRA `vector-illustration.safetensors`（HF `Muapi/vector-illustration`，218MB，触发词 `color icon`）

## 唯一的阻塞点：显存被本地 LLM 占满

`strata.service`（Qwen3.8-Flash-Next IQ2_XS）常驻 **24.2GB / 24.5GB** 显存，ComfyUI 一跑就 `Got an OOM`。
处理：`sudo systemctl stop strata` → 出图 → `sudo systemctl start strata`（已恢复，`/v1/*` 正常）。**这一步必须人工确认**，因为它也是 QA 的词内容生成后端。

## 参数（关键：风格词放最前）

```text
color icon, flat vector illustration, thick outlines, solid flat colors, minimalist:
{scene_en}, centered, generous negative space, no text
```

- 8 steps / CFG 2.0 / dpmpp_sde + karras / 1024×1024 / seed = crc32(word)
- 负面：`photo, photorealistic, 3d render, realistic skin, depth of field, blur, text, letters, watermark, clutter, extra limbs`
- **风格词必须排在最前**：SDXL 的 CLIP 每个编码器只有 77 token，风格尾巴放最后会被截掉（实测会退化成写实照片）。

## 观感评估

| 好的（可直接用） | 差的（建议重跑或换提示） |
| :--- | :--- |
| hierarchy（金字塔分层）、quixotic（风车骑士）、serendipity（旧书里掉钞票）、abandon（留下行李箱走开） | acclaim / profound（整张深色背景，与卡片浅色不搭）、assess / hypothesis / explicit（道具是文档，模型在纸上写了字） |

三类常见问题：

1. **深色背景**：模型把“戏剧感”理解成暗背景；需要在负面词里加 `dark background, night scene`，或正向加 `white background`。
2. **文档类道具会带字**：`document / form / chart` 这类词几乎必出文字，负面词压不住 → 场景描述里避开“表格/文件”。
3. **过度字面**：`abstract` 被画成颜料管、`beverage` 类词容易画成静物；这类抽象词更适合用“动作”表达。

## 产物

- `contact-sheet.png`：113 张 10×12 拼图（150px 缩略）
- `card-mock.png`：6 个词在真实卡片版式里的效果（右侧 88px）
- `samples/`：6 张 256px 样例（struggle / hierarchy / serendipity / quixotic / abandon / trivial）

脚本：[`scripts/vocab/image/`](../../../scripts/vocab/image/)（`make_scene_prompts.py` 生成场景、`batch_images.py` 出图）。

## 下一步建议

1. 先修 3 类问题再重跑：白底 + 禁止文档道具 + 深色图自动重跑一次（seed 变化）。
2. 卡片只需要 88px：生成 1024 后落 256（详情页）与 96（卡片）两档，PNG→WebP 可压到 20KB。
3. 如果要上主线：图片按 `content_id` 存对象存储/内容包，加 `image_url` 字段；生成走离线批处理，不要让线上请求现画。
