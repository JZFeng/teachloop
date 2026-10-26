# 文生图 prompt：5 个词（2026-10-26）

来源：v2.5 内容的 `coreSemantics`（语义核心 + 一句话画面）。统一风格，便于同一张卡片上横向比较。

## 通用设置

| 引擎 | 参数 |
| :--- | :--- |
| SDXL（4060Ti / 7900XTX） | 1024×1024，30 steps，CFG 5.5，DPM++ 2M Karras，负面提示词生效 |
| Flux.1-schnell | 1024×1024，4 steps，guidance 1.0（负面提示词基本无效） |
| Flux.1-dev | 1024×1024，20 steps，guidance 3.5 |
| Gemini / ChatGPT | 直接贴 Positive，要求「1:1，方形构图，不要文字」 |

统一风格尾巴（已并入每条 Positive）：
`flat editorial illustration, clean vector-like shapes, centered composition, generous negative space, muted desaturated palette with a single warm accent, soft diffused light, subtle paper grain, no text, no letters, no watermark`

## struggle（verb, noun）

- 语义核心：困难 + 努力，在阻力中持续用力
- 画面：一个人使劲推一扇卡住的门，门几乎纹丝不动
- 生成要点：keep the body weight visibly forward and the door clearly resisting

**Positive**
```text
a lone figure leaning with both palms against a jammed heavy wooden door, feet braced, shoulders low, the door barely open, tiny dust motes in the air, flat editorial illustration, clean vector-like shapes, centered composition, generous negative space, muted desaturated palette with a single warm accent, soft diffused light, subtle paper grain, no text, no letters, no watermark
```

**Negative**
```text
text, letters, typography, watermark, signature, photo, photorealistic, 3d render, busy background, clutter, extra fingers, distorted hands, deformed face, low contrast, oversaturated
```

## abandon（verb）

- 语义核心：彻底离开或放弃，不再负责
- 画面：（原画面是留下婴儿车；为避免图像模型敏感内容，改为留下行李箱）
- 生成要点：the suitcase must be isolated in frame and the person clearly leaving it behind

**Positive**
```text
a traveler walking away down an empty street, back turned, a suitcase left standing alone on the curb behind them, long shadow stretching forward, nobody looking back, flat editorial illustration, clean vector-like shapes, centered composition, generous negative space, muted desaturated palette with a single warm accent, soft diffused light, subtle paper grain, no text, no letters, no watermark
```

**Negative**
```text
text, letters, typography, watermark, signature, photo, photorealistic, 3d render, busy background, clutter, extra fingers, distorted hands, deformed face, low contrast, oversaturated
```

## hierarchy（noun）

- 语义核心：按等级排列的体系
- 画面：一座金字塔，每层站着不同的人，越往上人越少
- 生成要点：tiers clearly separated, figures identical in style, no faces needed

**Positive**
```text
a cross-section of a stepped pyramid, small identical figures standing on each tier, many crowded on the wide base, two in the middle, one alone at the pointed top, flat editorial illustration, clean vector-like shapes, centered composition, generous negative space, muted desaturated palette with a single warm accent, soft diffused light, subtle paper grain, no text, no letters, no watermark
```

**Negative**
```text
text, letters, typography, watermark, signature, photo, photorealistic, 3d render, busy background, clutter, extra fingers, distorted hands, deformed face, low contrast, oversaturated
```

## quixotic（adjective）

- 语义核心：理想化到不切实际
- 画面：一个人骑着瘦马冲向风车，以为那是巨人
- 生成要点：windmill should read as both machine and giant silhouette

**Positive**
```text
a skinny rider on a bony horse charging across dry grass toward a windmill, lance lowered, windmill sails angled like a looming giant, dramatic sky, flat editorial illustration, clean vector-like shapes, centered composition, generous negative space, muted desaturated palette with a single warm accent, soft diffused light, subtle paper grain, no text, no letters, no watermark
```

**Negative**
```text
text, letters, typography, watermark, signature, photo, photorealistic, 3d render, busy background, clutter, extra fingers, distorted hands, deformed face, low contrast, oversaturated
```

## serendipity（noun）

- 语义核心：意外发现美好事物的运气
- 画面：在旧书里偶然翻到一张百元钞票
- 生成要点：the falling banknote is the focal point, one clear gesture of surprise

**Positive**
```text
a person standing in a dusty second-hand bookshop, an old book open in their hands, a folded banknote slipping out from between the pages, warm window light, shelves blurred behind, flat editorial illustration, clean vector-like shapes, centered composition, generous negative space, muted desaturated palette with a single warm accent, soft diffused light, subtle paper grain, no text, no letters, no watermark
```

**Negative**
```text
text, letters, typography, watermark, signature, photo, photorealistic, 3d render, busy background, clutter, extra fingers, distorted hands, deformed face, low contrast, oversaturated
```


## 实测回填（2026-10-26，struggle 两张）

| 来源 | 尺寸 | 1:1 裁切后 88px 观感 | 备注 |
| :--- | :--- | :--- | :--- |
| 本地（Pushing Against the Light） | 1024×1536（2:3） | ✅ 主体大、门占满画面、光缝清楚 | 裁 1:1 后仍可用 |
| Gemini | 2741×1530（≈16:9） | ⚠️ 主体偏小、两侧空白多 | 缩到 88px 细节丢失 |

结论与调整：

1. **直接按 1:1 生成**（SDXL 1024×1024；Gemini 要「方形 1:1」），不要生成 2:3/16:9 再裁。
2. 宽画幅模型要在 prompt 里加 `close-up, subject fills 60-70% of the frame`，否则主体太小。
3. 卡片右侧是 88px，图必须“大主体 + 高对比 + 无文字”，细节在图上看不见。

对比图：`struggle-crop-compare.png`（300px / 88px / 48px 三档）。
