# T432 · 3,000 词内容批量生成报告（2026-10-25 凌晨）

用 OpenCode Go（deepseek-v4.1-flash）离线批量生成 `word_detail_page` v2.3 内容的实跑记录。
本报告对应的不是业务代码改动，而是**内容素材生产**与配套的一次性脚本。

## 1. 结论

| 指标 | 结果 |
| :--- | :--- |
| 目标 / 完成 | 3,000 / **3,000**（100%，无重复词） |
| 总墙钟时间 | **56.9 分钟**（含 200 词验证段与 2 次补跑） |
| 总成本（按套餐单价估算） | **$2.05**，约 **$0.00068/词** |
| 首轮直通率 | 200 词验证段 93%，全量段 **99.8%**（2641/2647），不足 1% 由校验器清洗或补跑补齐 |
| 限流 | 全程 **0 次 429**，只有 2 次 HTTP 503（自动重试成功） |
| 截断 | **0 条**（`max_tokens 6000`，实际输出 1,000 条均 ~1,100 tokens，全部 `finish=stop`） |
| 结构校验 | 3,000 条 **0 问题**（schema/语言/例句条数/popularity/含词/去重） |
| 产物 | `vocab-content-en-v2.3.jsonl`，8.73 MB，一行一词 |

## 2. 调用配置（最终生效值）

| 项 | 值 |
| :--- | :--- |
| 端点 | `https://opencode.ai/inference/go/openai/v1/chat/completions` |
| Key | OpenCode Go 的 `oc_sk_…`（本地 LLM 的 key 未使用） |
| 模型 | `deepseek-v4.1-flash` |
| 并发 | 6 workers（验证段 4，全量段 6） |
| 关思考 | `"thinking": {"type": "disabled"}` |
| 预算 | `max_tokens 6000`、`temperature 0.2`（重试 0.1） |
| 结构化 | `response_format: {"type": "json_object"}` |
| 会话头 | `x-opencode-session: <每请求随机 UUID>`（网关路由头，非对话上下文） |
| User-Agent | `python-requests/2.31`（必须非默认，否则 Cloudflare 403） |

## 3. 分段实跑

| 段 | 词数 | 成功 | 失败 | 墙钟 | tokens in/out | 成本 |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| 冒烟（apple/struggle/the） | 3 | 3 | 0 | 5.7s | 2,310 / 2,569 | $0.0019 |
| 验证段（前 200 高频词） | 200 | 186 | 14 | 7.0 min | 167,789 / 223,567 | $0.159 |
| 全量续跑 | 2,647 | 2,641 | 6 | 49.3 min | 2,031,750 / 2,629,349 | $1.882 |
| 补跑 A | 6 | 3 | 3 | 24.9s | 7,654 / 10,491 | $0.0074 |
| 补跑 B（清洗规则修好后） | 3 | 3 | 0 | 6.2s | 2,310 / 3,165 | $0.0022 |
| **合计** | **3,000** | **3,000** | — | **56.9 min** | **2,211,813 / 2,869,141** | **$2.05** |

吞吐：全量段 **53.6 词/分钟**（1.1 秒/词，6 并发）；单请求 p50 6.1s、p90 8.7s。

## 4. 质量

结构校验（`scripts/vocab/check_content.py`，复用生成端同一套 `validate`）：

```
words=3000 records=3000 bad_json_lines=0
missing=0 completeness=100.0%
examples_histogram={1: 1, 4: 10, 5: 216, 6: 2773}   # 平均 5.92 条
validation_issues={}                                  # 零问题
artifact_chars=0                                      # 无 <|endoftext|>、无乱码
zh_missing=0                                          # 每条例句都有中文
```

字段覆盖：

| 字段 | 覆盖 |
| :--- | :--- |
| `basicInfo.pronunciation` UK/US | 3,000 / 3,000 |
| `meanings.zh` / `meanings.en` | 3,000 / 3,000（中文释义平均 24 字） |
| `wordFormation` | 3,000 / 3,000 |
| `memoryAids.mnemonic` | 3,000 / 3,000 |
| `wordFamily.items` | 平均 4.13 条；44 个虚词为空数组 |

抽检（`clock` / `benefit` / `ice` 等随机记录）：音标 UK/US 分列、释义先 n. 后 v.、
例句按词性与常用度排序（verb 在前、noun 在后）、`popularity` 连续 1..N、
同族词带中文释义、构词分析给出真实词源（如 benefit ← bene + facere）。

已知瑕疵（不阻塞）：

- 词表本身带 `et`（来自 "et al."）这类非英语高频噪声，只有 1 条例句（`Et tu, Brute?`），是词表问题不是生成问题。
- 5 条记录里有一条例句用了派生词而非目标词（如 `scholar` 的 "scholarship"），因为 `usagePattern` 里含目标词所以被保留。

## 5. 踩过的坑

1. **Cloudflare 403 `error code: 1010`**：urllib 默认 User-Agent 直接被拦。固定 `python-requests/2.31` 后正常。
2. **`x-opencode-session` 必填**：缺了报 400 `MissingSessionID`。它只是网关路由头，每个请求随机 UUID 即可，
   与「对话上下文」无关——词与词之间没有任何共享 session。
3. **`max_tokens` 被 reasoning 吃光**：模型默认先吐 9k–24k reasoning tokens，正文被截断或为空。
   加 `"thinking": {"type": "disabled"}` 后正文 1,000–1,300 tokens，`finish=stop`。这是唯一必须关的开关。
4. **词表屈折不全**：`forms-top10k.json` 缺规则变化（will→willed、out→outed、own→owns）与不规则变化
   （leave→left、wind→wound、bite→bit、leaf→leaves）。先在匹配器里补规则变化 + f/fe→ves，
   再用 `usagePattern` 作旁证兜不规则变化。
5. **模型噪音可直接清洗而非重试**：同族词重复/重复词头、`wordFormation.parts` 缺字段、
   单条例句不含目标词——这三类当场清洗（丢弃该条/该切分项、重排 `popularity`），只有「清洗后仍不合格」才重试整词。
   这一条把失败率从 7% 压到 0.2%。

## 6. 产物与路径

| 文件 | 说明 |
| :--- | :--- |
| `fixtures-local/vocab-content/vocab-content-en-v2.3.jsonl` | 3,000 条内容（8.73 MB，gitignored） |
| `fixtures-local/vocab-content/vocab-content-en-v2.3.manifest.json` | 最后一次运行的 manifest（模型/token/成本/分布） |
| `fixtures-local/vocab-content/vocab-content-en-v2.3.failures.jsonl` | 过程失败明细（含已被补跑覆盖的词，仅作诊断） |
| `fixtures-local/vocab-content/words-top3000.txt` | 词表（按词族频次降序） |
| [words-top3000.txt](words-top3000.txt) | 词表副本（随报告入库，3,000 行） |
| `scripts/vocab/generate_content.py` | 批量脚本（local / opencode 双后端，可并发、可续跑） |
| `scripts/vocab/check_content.py` | 产物校验器（结构/覆盖/异常字符/分布） |
| [prompt v2.3](../../prompts/vocab-content-v2.md) | zh/en 双语 prompt（v2.2 的三语言产物作废） |

词表前 20：the, be, of, and, to, a, in, have, it, he, that, i, for, they, you, not, on, she, with, as。

## 7. 与本地 LLM 对比

| 方案 | 3,000 词耗时 | 成本 | 备注 |
| :--- | ---: | ---: | :--- |
| 本地 qwen3.8-flash（单槽） | ≈10 h | $0 | 12.4s/词，且会占住应用自己的生成 worker |
| **OpenCode Go / deepseek-v4.1-flash（6 并发）** | **57 min** | **$2.05** | 实测采用 |
| OpenCode Go / MiMo V2.6 Flash | ≈55 min | ≈$1.25 | 试跑 6 词有 2 条非法 JSON，未采用 |

## 8. 下一步（待你确认）

1. **T430 内容包导入器**：把这 3,000 条 JSON 打成 `content-en-<version>.pack/.idx/manifest`（hash64 + offset + length），
   启动装载进内存索引，`ContentStore` 先查包、再查 delta、最后 AI。
2. **T432 落库侧**：`AiVocabularyContentGenerator` 的 prompt 同步到 v2.3（zh/en、examples 1–6、wordFormation 可选），
   写库前跑同一套结构校验；失败重试一次后置 FAILED。
3. **扩量**：同样的管线跑 3,001–10,000（约 3.4 小时、$6），或用增量批次。
4. 词表清洗：剔除 `et` 这类非词条噪声，补一份更完整的屈折表（不规则动词/名词复数）。

## 9. 复现命令

```bash
export LLM_API_KEY="$(cat /tmp/oc_key.txt)"   # OpenCode Go key，未提交

python3 scripts/vocab/generate_content.py \
  --words fixtures-local/vocab-content/words-top3000.txt \
  --output fixtures-local/vocab-content/vocab-content-en-v2.3.jsonl \
  --provider opencode --model deepseek-v4.1-flash \
  --workers 6 --forms-map fixtures-local/vocab-content/forms-top10k.json

python3 scripts/vocab/check_content.py \
  --input fixtures-local/vocab-content/vocab-content-en-v2.3.jsonl \
  --words fixtures-local/vocab-content/words-top3000.txt
```

脚本支持断点续跑：已成功的 (word, language) 自动跳过，中断后重跑同一条命令即可。
