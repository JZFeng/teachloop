# T429 / T430 / T431 + 例句朗读（决策 019）验收报告

日期：2026-10-25 凌晨
分支：`feat/vocab-global-content-sentence-tts`（基于 `main`，未合并）
范围：只做 T429、T430、T431 与决策 019；**没有动** lab / 008-flags-service / 007-role-nav。

## 1. 结论

四块都完成并在 feature branch 上提交；全量 Java **434 tests / 0 failures / 0 errors / 0 skipped**（101 个类，约 7 分钟），前端 typecheck/build + 52/52，四个守卫全过，QA 环境浏览器实测通过。

关键验收：

| 验收 | 结果 |
| :--- | :--- |
| 两个学生先后遇到同一个词 | 第二个 prepare `ready=1/pending=0`、**0 个新 AI job**，两个用户行 `content_id` 相同 |
| 只读内容包命中 | `struggle` 首次 prepare 命中 3000 词包：`ready=1`、**0 job**、`content_id` 落在包区间（< 1e9），详情页显示 6 条例句 |
| 存量回填 | QA 旧库启动：`total=22 succeeded=22 failed=0`，随后自动 drop `vocab_words_content_legacy`；`vocab_words` 已无 `content` 列 |
| 10 万词包 | 装载 **12ms**、内存索引 **3.9MB**（目标 ≤1s / ≤8MB）；真实 3000 词包装载 10ms |
| 例句喇叭 | 查词页与复习卡背面每条例句各 1 个「朗读例句」；点击发 `GET /api/vocab/audio?text=…&lang=en`，返回 `200 audio/wav` |
| 开关 | `VOCAB_TTS_SENTENCES_ENABLED=false` 起第二实例：`/api/vocab/config` false、`text` 404、`form` 200、页面例句喇叭 **0 个** |
| 包重建安全 | 包重建后旧 `content_id=1411` 指向 `insight`，读路径按词形核对后回退，`struggle` 仍显示 struggle 内容 |

## 2. 提交

| commit | 内容 |
| :--- | :--- |
| `b2c2998` | T429：`ContentStore` / SQLite delta / `content_id` / prepare 命中 / 生成写回 |
| `ecede52` | T430：只读内容包读取器 + JSONL 导入器 |
| `50416b4` | T431：V063 / 存量回填 / 读路径改 `content_id` / `ContentView` |
| `113723e` | T431 性能：队列只对 cap 截断后返回的卡解析内容 |
| `2a5c4a0` / `07ffd56` | 019 前端：句子模式、开关、每句喇叭、按词条语言选音色 |
| `f2cd805` | 019 后端：`text`/`lang`、`/api/vocab/config`、开关、zh/es 音色 |
| `703762c` | 包重建后 content_id 与词形校验（避免旧 id 显示错词） |
| `756e519` | 文档/验收记录/边界登记 |
| `bb94306` | 并行内容生产：3000 词生成脚本容错与实跑报告（不是本任务代码） |

## 3. 实现要点

### T429（P1）
- V062：`vocab_words.content_id bigint` + 部分索引。
- `ContentStore` 端口与 `ContentService`：解析链 **只读包 → SQLite delta → miss**，只返回与 `teachloop.vocab.content.prompt-version` 一致的内容。
- `DeltaContentStore`：WAL、单写者、`(language, normalized_form, prompt_version)` 唯一、id 从 1_000_000_000 起、重启仍命中、失败降级。
- prepare 先查全局内容，命中写 `content_id + READY` 且不入队；AI 成功写 delta；发词快照写 delta。

### T430（P2）
- 包格式：`content-<lang>-<version>.pack`（JSON 顺序拼接）+ `.idx`（28B：hash64+offset+length+contentId，按 unsigned hash 排序）+ `manifest.json`（sha256/count/promptVersion）。
- 启动校验 manifest + 两个 sha256 + count + idx 有序/越界；`pack-required=true` 时缺失或损坏拒绝启动，false 降级为 delta + AI。
- 查询：hash64 二分到同 hash 连续区间，逐条 `FileChannel.read(buf, position)` pread + JSON 解析 + `WordNormalizer` 复核；`getById` 直接按 id 取。
- 导入器：`scripts/vocab/build_content_pack.py`，可复现（同输入两次 sha256 一致）。

### T431（P3）
- V063：CTAS 把存量 `content` 搬进 `vocab_words_content_legacy`，再 drop 原列。
- `ContentBackfillRunner`：启动时逐行写 delta + 回填 content_id，全成功才 drop legacy；失败启动失败并保留表重试。
- 读路径（词库列表/详情/今日队列/自由复习/发词领取）全部按 `content_id` 从 `ContentStore` 解析；写路径不再写内容副本。
- `ContentView`：v2 包内容 → 前端当前 v1 形状（senses/examples/analysis/family/phonetic）过渡适配；T432 切 v2 后可删。
- 包 id 按词形排序分配，重建包会变；读路径按 id 取后核对词形，不一致按 `(language, form)` 回退，避免错词。

### 决策 019
- `GET /api/vocab/audio?text=&lang=`：≤200 code point；空/超长/`form+text`/未知 lang 400；开关关闭 text=404 且不合成；缓存键 `sentence|lang|text|provider|voice|rate` 与单词键分离。
- `GET /api/vocab/config` → `{"sentenceAudio": bool}`。
- 前端 `SpeakerButton` 句子模式、`audioConfig` 开关、复习卡每句喇叭（`stopPropagation` 不翻面），按词条 `language` 选 en/zh/es 音色。

## 4. 验证

### 自动化
| 项 | 结果 |
| :--- | :--- |
| 全量 Java `work/locked-mvn.sh -q test` | **434 / 0 / 0 / 0**，101 类，exit 0 |
| 前端 `pnpm typecheck` / `test` / `build` | 0 / **52/52** / 0 |
| `check-deps` / `check-boundaries` / `check-i18n` / `check-ui`+contrast | 全 0（49 表 / 13 外键 / 8 端口 / 1559 键 / 44 组对比度） |
| `test_build_content_pack.py` | 9/9 |
| `ContentPackReaderTest` | 11/11（含 10 万词 12ms / 3.9MB） |
| `DeltaContentStoreTest` | 4/4 |
| T431 读路径 7 类 | 18/18 |
| `ContentViewTest` | 6/6（含包重建旧 id 回退） |
| `VocabTtsTest` / 关闭开关 | 6/6 + 1/1 |

### 浏览器（QA 5174 → 8011，新构建）
截图在本目录：

- `qa-brave.png`：查词页 `brave` 背面，3 条例句各带「朗读例句」。
- `qa-brave-playing.png`：点击第一条例句后的播放态；网络记录 `?text=She was brave enough…&lang=en`。
- `qa-review.png` / `qa-review-back.png`：今日复习卡正面 / 翻面，翻面 3 条例句喇叭。
- `qa-struggle-final.png`：3000 词包命中 `struggle`，6 条例句 + 6 个喇叭；DB 中 `content_id` 在包区间、0 个 job。
- `qa-disabled-brave.png`：`VOCAB_TTS_SENTENCES_ENABLED=false` 的第二实例，查词页例句喇叭 0 个，英/美单词喇叭仍在。

实测数据：`/api/vocab/audio?text=…` → 200、`audio/wav`、8864 bytes、ETag；`/api/vocab/config` → `{"sentenceAudio":true/false}`；禁用实例 `text=404`、`form=200`。

## 5. 已知取舍与未验证

1. **按释义搜索降级**：V063 删 `vocab_words.content` 后，词库 `q` 只按正面词形搜索；「按中文释义搜索」失效。恢复需另立搜索索引任务（不把内容副本放回用户行）。
2. **真实 Piper 未试听**：自动化/QA 用 `stub` 引擎验证 HTTP 契约与音频字节；`zh_CN-huayan-medium` / `es_ES-davefx-medium` 模型未实机合成。缺模型时端点 404、前端回落浏览器的路径尚未在真机跑过。
3. **内容包产物**：`fixtures-local/vocab-content` 已按最终 3000 条 JSONL 重建（pack 8,731,187B / idx 84,000B）；该目录 gitignored，部署时需 `python3 scripts/vocab/build_content_pack.py …` 重建或挂卷。
4. **T432 / T433 / T434 未做**：v2 生成校验、语言档案、可配置页面模板仍是后续任务；`ContentView` 是过渡适配层。
5. **搜索/性能**：队列 1 万词 p95=27ms；未做应用级 10 万词端到端压测。

## 6. 运行

```bash
# QA（已完成、当前运行）：http://127.0.0.1:5174 → :8011
# 重新构建 3000 词包
python3 scripts/vocab/build_content_pack.py \
  --input fixtures-local/vocab-content/vocab-content-en-v2.3.jsonl \
  --language en --version v2.3 --out-dir fixtures-local/vocab-content

# 部署 QA
VOCAB_CONTENT_STORE_PATH="$PWD/.envs/qa/content-delta.db" \
VOCAB_CONTENT_PACK_DIR="$PWD/fixtures-local/vocab-content" \
VOCAB_TTS_ENGINE=stub TEACHLOOP_GRADING_ENGINE=stub \
./scripts/env.sh deploy qa
```
