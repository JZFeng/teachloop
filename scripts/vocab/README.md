# scripts/vocab（词工具集）

批量生产单词内容（`word_detail_page` v2.3）的工具集：**长期维护的 utility，不是一次性脚本**。

运行时（学生在页面上第一次遇到某个词）不跑这里的代码——那条路径在 Java 侧
（`AiVocabularyContentGenerator`，见 T439）。这里负责**离线批量生产**：把 1 万词打包成内容包，
让线上命中包、不调 AI。

两边共用同一份提示词与请求参数：

```
teachloop-vocab/src/main/resources/vocab/content-prompt-v2.3.json
```

Java 从 classpath 读，Python 从这里读（`scripts/vocab/generate_content.py` 里的
`CONTRACT_DEFAULT`）。改 prompt 或参数只改这一份文件，两边同时生效；`promptVersion`
在最外层，升版必须同时改文件名，旧内容与旧包不参与新版本命中。

## 文件

| 文件 | 用途 |
| :--- | :--- |
| `generate_content.py` | 批量生成：local / OpenCode Go 双后端，可并发、可续跑、preflight、成本上限、心跳 |
| `check_content.py` | 产物校验：结构、覆盖、异常字符、例句分布（复用同一套 `validate`） |
| `test_generate_content.py` | 离线自测 22 条：词形匹配、清洗策略、截断识别、半行修复 |

## 常用命令

```bash
# 词表体检（不发请求）
python3 scripts/vocab/generate_content.py \
  --words fixtures-local/vocab-content/words-top10k.txt \
  --output fixtures-local/vocab-content/vocab-content-en-v2.3.jsonl --check-words

# 批量生成（preflight 自动跑；成本封顶 $8；6 并发）
export LLM_API_KEY="...OpenCode Go 的 key..."
python3 scripts/vocab/generate_content.py \
  --words fixtures-local/vocab-content/words-top10k.txt \
  --output fixtures-local/vocab-content/vocab-content-en-v2.3.jsonl \
  --provider opencode --workers 6 --max-cost-usd 8 \
  --forms-map fixtures-local/vocab-content/forms-top10k.json

# 补失败词
python3 scripts/vocab/generate_content.py ... --retry-failures

# 校验产物
python3 scripts/vocab/check_content.py \
  --input fixtures-local/vocab-content/vocab-content-en-v2.3.jsonl \
  --words fixtures-local/vocab-content/words-top10k.txt

# 自测
python3 scripts/vocab/test_generate_content.py -v
```

## 固化下来的坑（脚本里的控制，不是文档建议）

| 坑 | 控制 |
| :--- | :--- |
| Python urllib 默认 UA 被 Cloudflare 拦成 `403 error 1010` | 显式产品 UA（`teachloop-vocab-content/1.0`）；preflight 遇 403 直接给结论 |
| `400 MissingSessionID` | 每请求随机 `x-opencode-session`（网关路由头，不是对话上下文） |
| 默认 thinking 吐 9k–24k tokens 把 `max_tokens` 吃光 | 契约里 `thinking:{"type":"disabled"}`；`response_problems()` 把 `finish_reason=length`、顶满预算、`reasoning_tokens>200` 一律判失败并重试 |
| 词表屈折不全（willed / outed / left / leaves） | 规则屈折 + 内置不规则表（动词/名词/形容词）+ `usagePattern` 旁证 |
| 模型噪音（同族词重复、parts 缺字段、例句不含目标词） | 就地清洗并重排 `popularity`，不整词重试 |
| 中途被杀留下半行 JSON | 启动截断残行；坏行跳过并计数 |
| 无人值守跑飞成本 | `--max-cost-usd`：到顶停发新词、已完成保留、退出码 3 |
| 批次不可见 | `.status.json` 心跳、`.runs.jsonl` 每轮追加、manifest 带 `promptSha256` |

性能基线（3,000 词实跑，2026-10-25）：6 并发 **1.1 秒/词**吞吐、单请求 p50 6.1s / p90 8.7s、
0 次 429、$0.00068/词。验收标准按 **1.5 秒/词**吞吐定（见 spec AC-60.12）。

## 产物（都在 `fixtures-local/vocab-content/`，gitignored）

`vocab-content-en-v2.3.jsonl`（一行一词）、`.manifest.json`（最近一轮）、`.runs.jsonl`（每轮）、
`.status.json`（心跳）、`.failures.jsonl`（失败明细）。

## 同目录的其它工具

| 文件 | 用途 | 归属 |
| :--- | :--- | :--- |
| `build-wordlist-index.py` + `test_build_wordlist_index.py` | 词表索引（`lexicon-*.tsv` + manifest），词族/词形查询 | T388 |
| `build_content_pack.py` + `test_build_content_pack.py` | 内容包（`.pack/.idx/manifest`）构建，供 `ContentPackReader` 装载 | T430 |
| `generate_content.py` / `check_content.py` / `test_generate_content.py` | 内容批量生成与校验 | T432 / T439 |

## 与内容包的关系

`scripts/vocab/build_content_pack.py` 把 JSONL 打成 `content-en-<version>.pack/.idx/manifest`，
应用启动时装载进内存索引（`ContentPackReader`，T430）。**prompt 契约升版 → 重新生成 → 重新打包**，
三步不能省，否则包里的 `promptVersion` 与运行时不一致。
