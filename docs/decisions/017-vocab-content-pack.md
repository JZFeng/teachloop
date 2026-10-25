# 决策 017 — 词内容全局共享：只读内容包 + 内存索引 + SQLite delta

日期：2026-10-24　状态：生效（实现分阶段）　相关：AC-48.14、T429、T430
背景调研：[2026-10-24-vocab-content-storage.md](../research/2026-10-24-vocab-content-storage.md)

## 问题

同一词形（如 `apple`）在学生 A 与学生 B 那里各有一行 `vocab_words`，各自 `content` 为 null，
于是各自入队、各自调一次 AI。内容没有任何全局副本，AI 成本与等待都按人数重复。

## 决定（用户 2026-10-24 拍板）

1. **内容不放业务数据库**。全局内容以「只读内容包 + 内存索引」为主，AI 现场生成的新内容写
   单文件 SQLite delta（本地文件、WAL、单写者、ACID），不再写回用户行做唯一副本。
2. **只读内容包**：`content-<lang>-<version>.pack`（JSON 记录顺序拼接，可 64KB 块压缩随机读）
   + `content-<lang>-<version>.idx`（`hash64 + offset + length`，按 hash 排序）
   + `manifest.json`（语言、条数、prompt 版本、创建时间、pack/idx 的 sha256）。
   启动时校验 manifest，把 idx 装入内存；查找 = hash → 内存索引 → 一次 `pread` → JSON。
3. **Bloom filter 暂不做**：100k 词索引仅约 1.6MB、常驻内存，Bloom 只省一次内存查找；
   等词条到百万级、索引需要落盘时再加（约 10 bits/词）。
4. **delta**：`content-delta.db`，表 `(language, normalized_form, content, prompt_version, created_at)`
   主键 `(language, normalized_form)`；AI 成功后写 delta + 更新内存索引。
5. **用户词条只存引用**：`vocab_words` 增加 `content_id bigint`（包内词条 id 或 delta 行 id）；
   读取时由 `ContentStore` 解析，用户行不再保留内容副本（分阶段迁移，先加列再删 `content`）。
6. **接口**：vocab 模块内 `ContentStore` 端口：`get(language, form)`、`put(...)`、`stats()`；
   `prepare` 先查 ContentStore（命中直接 READY），未命中才入队 AI；`GenerationJobStore.succeed`
   写 delta 与内存索引。

## 分阶段

| 阶段 | 内容 | 解决什么 |
| :--- | :--- | :--- |
| P1 | SQLite delta + `content_id` 列 + `ContentStore` 接入 prepare/生成写回 | 同一词只生成一次（学生 B 直接命中） |
| P2 | 内容包 `.pack/.idx/manifest` + 导入器（从预生成 JSON 生成） | 常见词 0 次 AI、毫秒级命中 |
| P3 | 读路径改用 `content_id`，删除用户行 `content` 副本 | 单一内容源，省库空间 |

## 代价与边界

- 只读包与 prompt 版本绑定：改提示词必须出新包并在 manifest 升版本，否则新旧内容混用。
- 包损坏（复制不完整）会让大量词 miss：启动校验 sha256 与条数，失败拒绝启动或降级 AI。
- delta 是单实例本地文件；多实例部署时需共享存储（或改回集中存储），v1 单机不涉及。
- 性能指标待用户后续确认（本决策只定结构，不定数字）。

## 实现注记（2026-10-25，T429/T430/T431）

- **idx 实际布局**：在 `hash64(8) + offset(8) + length(4)` 之后追加 `contentId(8)`，每条 28 字节，
  仍按 unsigned hash64 升序、同 hash 按 contentId 升序。原因：P3 读路径要按 `vocab_words.content_id`
  取内容，`getById` 需要 O(1) 的 id→offset；内存每条 40B，实测 10 万词索引 3.9MB（目标 ≤8MB）。
- **导入器**：`scripts/vocab/build_content_pack.py`（JSONL → pack/idx/manifest，原子写、可复现 sha256）；
  读取器启动校验 manifest + sha256 + count + idx 有序，`pack-required=true` 时缺失/损坏拒绝启动。
- **P3 迁移**：V063 先把存量 `vocab_words.content` 复制到 `vocab_words_content_legacy`，再删列；
  启动时 `ContentBackfillRunner` 逐行写 delta、回填 `content_id`，全部成功才 drop legacy 表，
  失败则启动失败并保留表重试。
- **已知取舍**：内容移出 PG 后，词库「按释义搜索」无法在 SQL 里做，T431 起降级为只按正面词形搜索；
  如需恢复，应另立「搜索索引」任务，而不是把内容副本放回 `vocab_words`。
- **内容结构**：包内记录是 v2.3 JSON；前端当前按 v1 渲染，读路径经 `ContentView` 适配（T432 切 v2 后可删）。

## 修订（2026-10-25）：老词回填暂不切包

QA 实测（`d3ccac3` / `e79081e`）：包内词由包供给（`struggle` → `content_id=2587`、`prepare` 7.6ms、0 个生成任务），
包外词走 AI 现场生成（`serendipity`、`quixotic` → 9s 端到端、`content_id=1_000_000_020/21`）。
存量 20 条 `BACKFILL` 行仍让老演示词读旧内容。

用户 2026-10-25 决定：**先不动，等 10K 一起切**。切换时按顺序做：
① 重新生成并打包 `content-en-v2.3`（count=10,000，sha256 变化）；
② 清空 `word_content_delta` 的 `BACKFILL` 行（保留 `AI` 行）；
③ `vocab_words.content_id` 置空；
④ 重启以装载新包；
⑤ 抽查 `prepare` 命中（`ready=1 / pending=0`、`content_id` 落在包区间）。
