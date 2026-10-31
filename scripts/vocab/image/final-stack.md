# 词卡配图：生产定稿（2026-10-31 用户拍板）

> 结论：**主用 Qwen-Image（阿里，20B fp8），后备 HunyuanDiT 1.2（腾讯），OpenAI 只做教材/基准。**
> 文本内容生成：**默认 OpenCode Go（deepseek-v4.1-flash），备用 DeepSeek 官方 API（deepseek-chat）**。

## 1. 主方案：Qwen-Image（ComfyUI）

| 项 | 值 |
| :--- | :--- |
| 权重 | `qwen_image_fp8_e4m3fn.safetensors`（20.4GB）+ `qwen_2.5_vl_7b_fp8_scaled.safetensors`（9.4GB）+ `qwen_image_vae.safetensors` |
| 来源 | `Comfy-Org/Qwen-Image` 的 `split_files/`（公开，无需 token） |
| 节点 | `UNETLoader` + `CLIPLoader(type="qwen_image")` + `VAELoader` + `EmptySD3LatentImage` + `CLIPTextEncode` + `KSampler` + `VAEDecode` |
| 批量参数 | **512² · 12 步 · cfg 4 · euler/simple** |
| 加急参数 | 512² · 8 步（≈ 8 秒/张，实测） |
| 大图参数 | 1024² · 20 步（≈ 99 秒/张，详情页用） |
| 实测吞吐 | **22.5 秒/张**（113 张 42 分钟，含模型反复装载开销；热模型单张最快 12 秒） |
| 显存 | 约 20GB，独占（不能与训练同时跑） |

**提示词模板（固定）**

```text
childrens book illustration, flat colors, thick clean black outlines, detailed faces and clothing,
warm cream background, teal mustard and brown palette, no text: {scene}
```

**负面词**

```text
blurry, low quality, text, photo
```

## 2. 后备：HunyuanDiT 1.2（ComfyUI）

| 项 | 值 |
| :--- | :--- |
| 权重 | `hunyuan_dit_1.2.safetensors`（8.2GB，`comfyanonymous/hunyuan_dit_comfyui`） |
| 节点 | `CheckpointLoaderSimple` + **`CLIPTextEncodeHunyuanDiT`（bert 与 mt5xl 填同一段文本）** + `EmptyLatentImage` + `KSampler` + `VAEDecode` |
| 参数 | **1024² · 28–30 步 · cfg 6–7 · dpmpp_2m/karras** |
| 实测 | **20.5 秒/张**（20 张 411 秒） |
| 定位 | Qwen 某词跑偏时重跑（“第二意见”）；轻量、可与其它任务共存 |

**提示词**：与 Qwen 同一段风格前缀 + 场景。

## 3. OpenAI（只作教材/基准，不进生产）

- 模型 `gpt-image-2`，1024²，quality=medium，≈ **$0.07/张**；
- 100 张（80 训练 + 20 验证）教材已产出，用于：风格基准、LoRA 训练集、人工抽检对照。

## 4. 文本内容生成路由（用户 2026-10-31 确认）

| 优先级 | 提供方 | 模型 | 用途 |
| :--- | :--- | :--- | :--- |
| **默认** | OpenCode Go（`https://opencode.ai/inference/go/openai/v1`） | `deepseek-v4.1-flash` | 词内容生成、场景文案 |
| **备用** | DeepSeek 官方（`https://api.deepseek.com`） | `deepseek-chat` | OpenCode Go 不可用/限流时切换 |

- 两家的 key 都存在本机 `~/.local/share/opencode/auth.json`（`opencode-go` / `deepseek` 两个条目），不进仓库；
- 切到备用时只改 `ai_providers` + `ai_settings.active_provider_id`，代码不用动。

## 5. 已知问题（生产前必须抽检）

1. **抽象词的语义漂移**：113 张里有相当一批画成“学生坐在桌前看书/写字”（如 `dubious`、`elaborate`、`justify`、`plausible`），88px 下无法区分；
2. **偶发文字**：`explicit` 那张出现了 `CAEDS CAFED CLF` 字样，需要负面词 + 抽检；
3. 抽象词适用**代理场景**（见 `prompts/styles.md` 规则），但当前 113 词的 scene 偏弱，建议重写这批 scene。

## 6. 复用命令

```bash
# 主方案：Qwen 批量（ComfyUI 需先起在 8188）
python3 scripts/vocab/image/batch_qwen.py --scenes scenes.json --out ~/ai/vocab-images/qwen113

# 后备：Hunyuan 重跑指定词
python3 scripts/vocab/image/batch_hunyuan.py --scenes scenes.json --only dubious,justify --out ~/ai/vocab-images/hy_fix
```
