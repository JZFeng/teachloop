# 033 背单词 V3 全量：丢弃 113 词 v2 enrich，按 V3 从零生成

日期：2026-10-31  
状态：完成（2026-10-31 04:15）

## 决定

1. **旧 113 词 V3 数据作废**。它来自 `enrich_v3.py`（v2.5 → v3），用户 2026-10-31 明确
   「现在的这个 v2 的 113 个词不要了，按照 V3 重新生成」。`v3.0` JSONL 不再进入任何 pack。
2. **词族 / 词形拆分**：
   - basewords 1–30：**28,276 个词族**，去重 headword **28,273 个**；forms **80,579 个**；
   - 非 head 的屈折/派生词形 **52,306 个**；
   - 以前答「80,579 个词形」不是 80,579 个独立学习词。
3. **生成路径**：
   - 28,273 个唯一 headword 用 **V3 全量 prompt 一次调用**生成（不再先 v2 再 enrich）；
   - 52,306 个非 head 词形由 headword 的 `morphology.family / derivatives /
     inflections` **确定性派生**，不调 AI；
   - 最终覆盖 80,579 个词形。
4. **V3 全量 prompt**：`teachloop-vocab/src/main/resources/vocab/content-prompt-v3.0.json`。
   - schemaVersion=3；`morphology{modernNote,parts,chain,derivatives,inflections,family}`；
   - `etymology{text}` 独立；
   - `acceptedForms` 来自 basewords 词族，validator 拒绝模型发明的词形；
   - `deepseek-flash` + `thinking:disabled`，maxTokens 8000。
5. **生成器**：`scripts/vocab/generate_v3.py`（沿用 v2.5 的断点续跑、并发、成本上限、
   preflight、失败重试；validator 换成 V3）。旧 `generate_content.py` 不动。
6. **派生器**：`scripts/vocab/derive_v3_forms.py`；**pack**：`scripts/vocab/build_v3_full_pack.py`。
7. **失败策略**：单条失败写 `.failures.jsonl`，全量跑完 `--retry-failures` 再补；不因为
   1 条边界词卡住整批。

## 口径

- 28,273 个 headword 的 V3 内容；52,306 个词形派生；合计 80,579。
- 词形卡的 `example` 只保留 headword 例句中**真正包含该词形**的句子；没有就空，不硬塞。
- 词形卡 `formOf` / `formKind` / `formLabel` 是附加字段；`ContentView` 忽略未知字段。
- 词形卡的 `family` 包含 headword + 词族形式 + 该词形本身，满足「构词与派生带 POS/含义」。

## 验收门槛

- V3 JSONL 必须 28,273 个唯一 headword；`schemaVersion=3`；family 至少含 head；
  family/derivatives/inflections 只允许 accepted forms。
- forms 52,306；合 pack 80,579；缺 head 的 form 为 0。
- pack manifest `promptVersion=vocab-content-v3.0`；dev 重启后任意词（含 `eagerly`/`am`）
  能出 V3 卡面。
- 全量失败重试后，headword 缺口必须为 0 才能宣布完成。

## 已知取舍

- 52,306 个词形卡不是独立 AI 内容，`examples` 多数为空；第一版接受。
- 词形卡的 `meanings.en` 沿用 headword 英文释义，卡面主要展示中文；第一版接受。
- 词形卡 pronunciation 沿用 headword；不规则屈折（am/is）发音不精确；第一版接受。

## 完成记录

- 28,273 unique headword + 52,306 forms = 80,579。
- pack `version=v3.1` / `promptVersion=vocab-content-v3.0` / `count=80579`；dev 已加载。
- 40 条失败经两轮重试全部补齐；5 条 v3.1 重复行由 pack builder 去重。
- 最终 UI/API 验收见 `docs/reports/2026-10-31-vocab-v3-full/README.md`。
