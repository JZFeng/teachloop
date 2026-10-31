# T594 — blocks 视图契约：后端输出有序 blocks，配图升为第 9 块（2026-10-30）

## 结论

- 后端 `ContentView` 现在输出 `content.blocks = [{id, payload}]`：顺序 = 用户 `prefs.blocks`，`hidden` 里的块不输出，payload 缺失/为空的块跳过；`basicInfo.image` 是安全 URL 时追加第 9 块 `id="image"`（`payload={url}`），无图就整块不出现。
- 前端 `VocabReviewCard` 只按 `blocks` 渲染，不再按 key 硬编码取 `content.*`；没有 `blocks` 字段的 v1 旧内容回退到旧字段（兼容保留一个版本）。
- 验收全绿：后端 vocab **145/145**（1 个既有 live AI 用例按设计 skip）、app 层 `VocabPrefsTest` **1/1**；前端 **223/223**、`tsc --noEmit` 0；`check-i18n` / `check-ui` 0；iPad 834×1194 与 1024×1366 **无横向溢出**，图片实测 **88×88**。
- 界面实拍（真实页面 + 真实组件，队列接口 mock 成契约形状的**示例数据**）：
  [有图](card-with-image.png) / [无图](card-no-image.png) / [设置页 9 块](prefs-9-blocks.png) / [iPad 834×1194](tablet-834x1194.png)。

## 契约

`GET /api/vocab/review/queue`、`/api/vocab/review-sessions/:id/current` 的 `content`（v2 内容）：

```json
{
  "phonetic": {"uk": "…", "us": "…"},
  "coreSemantics": {"core": "…", "notThis": "…", "mentalImage": "…"},
  "senses": [{"pos": "verb", "zh": "…"}],
  "examples": [{"en": "…", "zh": "…"}],
  "synonyms": [{"word": "…", "pos": "…", "nuance": "…"}],
  "antonyms": [],
  "family": ["struggled", "struggling"],
  "derivation": {"parts": [], "chain": []},
  "analysis": "源自古法语 estrangler…",
  "image": {"url": "https://…/struggle.webp"},
  "blocks": [
    {"id": "core", "payload": {"core": "…", "notThis": "…", "mentalImage": "…"}},
    {"id": "senses", "payload": [{"pos": "verb", "zh": "…"}]},
    {"id": "examples", "payload": [{"en": "…", "zh": "…"}]},
    {"id": "analysis", "payload": "源自古法语 estrangler…"},
    {"id": "image", "payload": {"url": "https://…/struggle.webp"}}
  ]
}
```

- `payload` 是 view 里对应字段的**同一个节点**（`core→coreSemantics`、`derivation→{parts,chain}`、`analysis→字符串`、`image→{url}`），不新造形状。
- 顺序 = `prefs.blocks`；去重；未知 id 忽略；`hidden` 不输出；payload 缺失/空数组不输出该块；一块都没有时输出 `blocks: []`（前端靠「数组在场」区分「后端说没有」和「旧数据没这个字段」）。
- v1 / 非 v2 内容原样返回、**没有 `blocks`** → 前端回退旧字段。
- 块 id 与默认顺序的唯一来源是 `ReviewPrefs.ALL_BLOCKS`（9 项，`image` 末位，前 8 位一字未动）；前端 `wordBlocks.ts` 与之逐项一致。

## 改了哪些文件

| 层 | 文件 | 改动 |
| :--- | :--- | :--- |
| 后端 | `teachloop-vocab/.../content/ContentView.java` | `toView(raw, blockOrder, hidden)` 重载、`putImage` / `putBlocks`、6 参 `resolve`；`isSafeImageUrl` 只放行 `http(s)://` 与站内 `/` 路径 |
| 后端 | `teachloop-vocab/.../review/ReviewPrefs.java` | `ALL_BLOCKS` 追加 `image`（第 9 位） |
| 后端 | `teachloop-vocab/.../review/ReviewQueueEndpoint.java` | 队列按用户 prefs 排 blocks（`toItem(row, mark, pref)`） |
| 后端 | `teachloop-vocab/.../review/ReviewSessionEndpoint.java` | 注入 `ReviewPrefsStore`，自由复习同一口径 |
| 后端测试 | `ContentViewBlocksTest`（新，9）、`ReviewQueueEndpointTest`（新，2）、`ReviewPrefsTest`（6→9） | 顺序 / hidden / 无图有图 / 缺 payload 跳过 / 未知与重复 id / v1 回退 / 9 项与存量补齐 |
| 后端测试 | `teachloop-app/.../VocabPrefsTest.java` | 断言 8 → `ReviewPrefs.ALL_BLOCKS.size()`（T594 后是 9；原断言写死 8 必红） |
| 前端 | `web/src/vocab/reviewBlocks.ts`（新） | 纯模块 `resolveCardBlocks(content, order?, hidden?)`：blocks 在场按后端顺序、空数组不回退；不在场按 order+hidden 从 `content.*` 回退 |
| 前端 | `web/src/vocab/VocabReviewCard.tsx` | `renderBlock(id, payload)` 只吃 payload；image 块；空提示改为「内容里根本没有块」 |
| 前端 | `web/src/vocab/wordBlocks.ts` | `WORD_BLOCKS` 加 `image`；`BLOCK_LABELS` 指向真实命名空间 |
| 前端 | `web/src/vocab/VocabPrefsCard.tsx`、`prefs.ts`、`vocab.css` | `image` 开关、删「图片暂不提供」、`.review-image-block`（88px 右对齐） |
| i18n | `web/src/locales/{zh-CN,en}.json` | 加 `vocab.blocks.image` / `imageDesc`；删 `vocab.prefs.imageSkipped` |
| 前端测试 | `web/tests/review-blocks.test.mjs`（新，10） | 顺序 / 空数组不回退 / 旧数据回退 / hidden / 未知 id / 缺 payload / image 透传 / analysis 字符串 |

## 验收记录

| 项 | 命令 | 结果 |
| :--- | :--- | :--- |
| 后端 vocab | `./mvnw -q -pl teachloop-vocab -am test` | Tests run 145 / Failures 0 / Errors 0 / Skipped 1，exit 0 |
| 后端 app（跨模块断言） | `./mvnw -q -pl teachloop-app -am -Dtest=VocabPrefsTest -Dsurefire.failIfNoSpecifiedTests=false test` | tests=1 / failures=0 / errors=0，7.0s，exit 0 |
| 前端 | `cd web && pnpm test` | 223/223 pass，exit 0 |
| 前端类型 | `cd web && pnpm typecheck` | exit 0 |
| 文案 / 样式守卫 | `python3 scripts/check-i18n.py` / `scripts/check-ui.py` | 均 exit 0（zh/en 各 1994 键对齐；无新增 UI 违规） |
| 平板 | Playwright 834×1194 与 1024×1366 | `scrollWidth == innerWidth`（834 / 1024），图片 `getBoundingClientRect()` = 88×88 |
| 界面 | 真实 `/vocab/review` 与 `/profile`（队列 mock 示例数据） | 有图 / 无图两态见截图；设置页 9 行顺序 + 「配图」开关，无裸 i18n key |

验证档位：**端到端（真实页面渲染 + 后端单测/集成）**——但内容用的是契约形状的 mock 数据，真实 `basicInfo.image` 目前还没有值（内容包恒为 `null`，决策 018 §151），所以「AI 真有图」这一段尚未端到端跑过。

## 影响、偏差与留白

- **修了一个既存缺陷**：`BLOCK_LABELS` 的 name/desc 指向 `vocab.prefs.blocks.*`，这个命名空间在 locale 里从来不存在（真实存在的是 `vocab.blocks.*`），设置页本来会把裸 key 显示出来。改成真实命名空间后，`vocab.blocks.image` / `imageDesc` 加在同一处，两份 locale 同步。
- **默认位置**：`image` 是第 9 块，默认顺序下渲染在 review-back 底部、右对齐（与 `.core-semantics-art` 同尺寸 88px）；想让它靠上，在设置页「反面顺序」里用 ↑ 上移即可——顺序仍然只由后端一个属主决定。
- **word-details 不对称（有意）**：详情/小测/词库列表继续走单参 `resolve`（默认顺序、不应用 `hidden`），因为「word-details 其它部分不动」；只有今日复习与自由复习按用户偏好排块。
- **留白**：真实图片生成/入库还没做（`basicInfo.image` 恒 `null`），本任务只保证「有 URL 就能渲染、无 URL 不占位、坏 URL 不渲染」；图片落盘/对象存储/透明 WebP 的后处理仍在 `docs/reports/2026-10-27-vocab-image-models`、`2026-10-30-vocab-image-shanbay-style` 的结论里，未接读路径。
