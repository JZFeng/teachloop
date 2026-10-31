# 背单词 V3 全量生成报告（2026-10-31）

状态：**完成（2026-10-31 04:15）**。80,579 条 V3 pack 已生成并接入 dev；旧 113 词 v2 enrich 数据已弃用。


## 最终结果（2026-10-31 04:15）

- **headword**：28,273 个唯一词（28,276 词族；3 个重复 head 已合并）。
- **非 head 词形**：52,306 个；`derive_v3_forms.py` 输出 `forms_written=52306 skipped_missing_head=0`。
- **pack**：`fixtures-local/vocab-content-v3`，`version=v3.1`、`promptVersion=vocab-content-v3.0`、**count=80579**；
  pack 216,947,705 B，idx 2,256,212 B；content pack 去重后 80,579，`duplicates=0`。
- **dev**：后端已自动重启并加载 `80,579 条`（`ContentPackBootstrap` 101ms，索引≈3,147KB）；5173/8010 正常。
- **全量生成**：16 并发完成 27,743 成功；40 条失败经两轮重试后全部补齐；最终 `v3.1` 28,278 行（5 条重试造成的重复行），pack builder 按规范化词形去重为 28,273 唯一 head + 52,306 词形。
- **失败修复**：`relate/tallboy` 放宽复合词/前缀 chain；`awful/eastern/ruthless/wholesome/hapless` 丢弃未列入 parts 的 chain 步骤；`sur/sciencefiction` 无目标例句降级为空；4 个法/意借词强制 `language=en`；`baith` malformed JSON 重试通过。
- **最终 API**：`eager/relate/tallboy/eagerly/interpreted/am` 全部 200、`schemaVersion=3`；`tallboy/relate` 的 family 正常；无卡片运行时错误（登录前 401 为未认证探测）。
- **最终截图**：[full head eager](shots/full-head-eager-1440.png) · [full form eagerly](shots/full-form-eagerly-1440.png)。
- **旧 113**：`vocab-content-en-v3.0.discarded.jsonl`，不再读取；dev 已覆盖为 80,579 pack。

### 第一版已知边界

- 52,306 个词形不是独立 AI 卡；31,894 个词形没有例句（只保留含有该词形的 head 例句），词形 pronunciation/`meanings.en`/coreSemantics 沿用 headword。
- head 卡 family 最多 8 个同族词；更长词族由词形卡覆盖。
- 20 个 head 在修复时被降级为空例句（稀有/前缀性 head）。
- `ContentView` 不输出 `formOf/formKind` 扩展字段，卡面用 `morphology.modernNote` 表达词形关系。
- 运行时 AI 生成仍是 v2.5 prompt/validator，启动仍有 prompt 版本不一致 warning；本 pack 是 V3 数据，不受影响。

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

## 2026-10-31 01:47 更新：pilot 已接入 dev

- 全量 16 并发已到 `360/27,783`、0 失败、ETA ≈ 163 分钟；输出仍是同一个
  `vocab-content-en-v3.1.jsonl`（当前 856 条）。
- 用当前 headword + 派生词形构建了 4,775 条 pilot pack，覆盖旧 113 pack，
  dev 后端已加载：`内容包就绪：4775 条 ... version=v3.1-pilot`。
- 真实会话验证：
  - headword `eager`：V3 卡背、派生词/词族 POS+含义、6 例句、词源；
  - 派生词形 `eagerly`：`formOf=eager`、词性 adv.、family 含 eager/eagerly、
    派生词只列 siblings（不再重复自己）、例句只保留含 eagerly 的句子。
- 截图：
  [head-eager](shots/head-eager-1440.png) ·
  [form-eagerly](shots/form-eagerly-1440.png)
- 新增 `/tmp/v3-restart-dev.sh`：全量 pack `count=80579` 后自动重启 dev；
  若 count 不足则不重启，避免把半成品挂上去。
- dev 当前是 pilot 4,775，不是最终 80,579；最终 pack 由 watcher 覆盖后再自动重启。

## 2026-10-31 01:49 平板目标档验收

- 834×1194：`scrollWidth=834`，无横向溢出；`eager` 与 `eagerly` 的「下一个」都在 y=568（50vh），首屏可见。
- 1024×1366：`scrollWidth=1024`，无溢出；`eager` 的「下一个」在 y=654（50vh）。
- 截图：
  [834 eager](shots/tablet-834-eager.png) ·
  [834 eagerly](shots/tablet-834-eagerly.png) ·
  [1024 eager](shots/tablet-1024-eager.png)
- 这是 dev pilot pack（4,775）的浏览器验收；最终 80,579 pack 会自动重启后再复验一次。
