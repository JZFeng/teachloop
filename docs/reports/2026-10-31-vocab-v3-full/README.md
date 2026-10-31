# 背单词 V3 全量生成报告（2026-10-31）

状态：**进行中**。全量后台任务已启动；旧 113 词 v2 enrich 数据已不再使用。

## 结论

- basewords 1–30 是 **80,579 个词形**，其中 **28,273 个唯一 headword**、
  **52,306 个非 head 词形**。按用户确认：headword 用 V3 AI 从零生成，词形由 headword 派生。
- 124 词分层 pilot **124/124**（修掉复合词 chain 规则后），0 失败。
- 全量启动时 `[100/28149] ok=100 failed=0`，ETA ≈ 307 分钟；成本按 pilot 外推约 **$24**。
- 全量输出：`fixtures-local/vocab-content/vocab-content-en-v3.1.jsonl`（新文件，未复用旧 113）。
- 旧 `vocab-content-en-v3.0.jsonl`（113 条）不再读取；旧 pack 会在全量完成后覆盖。

## 数据链路

| 阶段 | 产出 |
|---|---|
| headword / form 切分 | `fixtures-local/vocab-content/v3-headwords.txt`（28,273） |
| 词族映射 | `v3-headwords.json`、`v3-forms-map.json`、`v3-form-to-head.json`（80,579） |
| V3 全量生成 | `scripts/vocab/generate_v3.py` + `content-prompt-v3.0.json` |
| 词形派生 | `scripts/vocab/derive_v3_forms.py` |
| 80,579 pack | `scripts/vocab/build_v3_full_pack.py` → `build_content_pack.py` |

- headword 生成：`schemaVersion=3`、`morphology` 6 字段、family 带 POS + 中文含义、
  `etymology.text` 独立中文段落；accepted forms 限制模型不乱造词形。
- 词形派生：`formOf` / `formKind` / `formLabel`；family 包含 head + 词形；
  examples 只保留真正含该词形的 head 例句；不调 AI。
- 旧 v2.5 generator 不改；新脚本只生成 V3。

## 已验证

- `eager` 单词：`parts=[]`、`modernNote` 说明整体词、family 4 项带 POS/含义、
  etymology 中文、6 例句；单请求 5.7s / $0.0009。
- 124 词 pilot：123 成功后修 `tallboy` 复合词（parts 有 roots 但 chain 为空应允许），
  重跑后 **124/124**；0 失败；$0.107；67 词/分（6 并发）。
- 词形派生（用 pilot 189 个 head）：638 个词形；kind 分布 derivative 188 / inflection 182 /
  family 94 / form 174；`eagerly` 带 eager + 词族，`am/is` 带 be 的屈折，`interpreted` 带释义。
- pack 构建（pilot 856 条）通过；manifest `promptVersion=vocab-content-v3.0`。

## 进行中

- 全量 28,149 个待生成；后台日志 `/tmp/v3-full.log`；状态文件
  `fixtures-local/vocab-content/vocab-content-en-v3.1.status.json`。
- 预计完成后：重试 `.failures.jsonl` → `derive_v3_forms.py` → 检查缺 head 为 0 →
  `build_v3_full_pack.py` → 覆盖 `fixtures-local/vocab-content-v3` → dev 重启验证。

## 已知取舍（第一版）

- 52,306 个词形不是独立 AI 卡；多数没有例句（只保留含该词形的 head 例句）。
- 词形卡 pronunciation / `meanings.en` / coreSemantics 沿用 headword；不规则屈折发音不精确。
- 旧 113 中的 `analyze/emphasize/serendipity` 等 6 个不是 headword；这次不单独生成；
  它们的词形是否在 80,579 中由 basewords 词族决定。
- 全量完成前，dev 仍使用旧 113 pack；不要以当前页面判断全量效果。
