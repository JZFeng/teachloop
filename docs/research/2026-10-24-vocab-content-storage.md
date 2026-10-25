# 词内容存储：行业做法与文件包方案（2026-10-24）

问题：同一个 `apple`，学生 A 生成过，学生 B 还要再调一次 AI。需要一份**全局共享**的词内容，
并回答「100k 词放数据库查一次 SQL 会不会太慢、用文件 + 内存索引是不是更好」。

## 1. 同行怎么存

| 系统 | 存储形态 | 查找方式 | 对我们的启发 |
| :--- | :--- | :--- | :--- |
| **Anki** | 一个 SQLite 文件 `collection.anki2` 存 notes/cards/revlog；媒体是独立文件；导出 `.apkg` 是 zip（内含 SQLite + media） | SQLite B-tree 索引；`.apkg` 导入时解包写入本地 collection | 不是「10 万个散文件」，而是**单文件容器 + 索引**；导入是整包导入，不是逐词请求 |
| **StarDict** | `.ifo`（元数据）+ `.idx`（排序词表：词 + 偏移 + 长度）+ `.dict`（内容包，可 dictzip 分块压缩） | `.idx` 全量装入内存，按词二分；按偏移从 `.dict` 读一段 | 正是「内容包 + 偏移索引」；索引小、内容按需 pread |
| **MDict（.mdx/.mdd）** | 词条数据 + 资源包；解析库启动时 `BuildIndex()` 建内存精确索引，也可把索引导出到 Redis/SQL | 内存索引 → 记录块 → 解压读取 | 索引与内容分离；索引可换存储后端 |
| **墨墨背单词**（二手资料） | MySQL 存用户/词书/词条；Redis 缓存当日队列与高频释义；离线词库分块二进制打包、按需增量下载 | 服务端 DB + Redis；客户端本地包 | 用户态数据在 DB，**词内容**在缓存/离线包 |
| **不背单词**（二手资料） | 例句音频放 OSS；Elasticsearch 建「词—例句—台词」检索 | ES 召回 | 内容体量大时用专门的检索/对象存储，不是业务库 |
| **扇贝**（二手资料） | MySQL 主库存进度，数据仓库汇总，LLM 生成词义/例句 | 服务端 + 模型 | 用户数据与内容生成分离 |

结论：**词典类系统普遍是「不可变内容包 + 内存索引」，Anki 用单文件 SQLite 也是同一思路的变体**；
没有谁把 10 万个词拆成 10 万个 HTML 散文件（inode 开销、部署和备份都难受）。

## 2. 方案：内容包 + 内存索引（不引入内容库）

### 2.1 只读内容包（预生成）

- `content-<lang>-<version>.pack`：按词条顺序拼接的 JSON（可选 64KB 块压缩，像 dictzip 一样支持随机读）。
  100k 词 × 平均 0.85KB ≈ **85MB 原始**，gzip 后约 25–35MB。
- `content-<lang>-<version>.idx`：每条 `hash(64bit) + offset(64bit) + length(32bit)`，按 hash 排序。
  100k 条 ≈ **1.6MB**；启动时整个读进内存（或 mmap）。
- `manifest.json`：语言、词条数、prompt 版本、创建时间、pack/idx 的 sha256。
- 查找：`hash(normalized_form)` → 内存里二分/开放寻址 → 一次 `pread`（页缓存内约 5–20µs）→ 解析 JSON（10–30µs）。

### 2.2 Bloom filter 要不要

- 只有当**索引不在内存**、每次都要读磁盘时，Bloom 才能省一次 IO。
- 100k 条的索引只有 ~1.6MB，直接常驻内存即可；Bloom（1% 误判约 120KB）省下的是「一次内存查找」，
  收益可以忽略。**v1 不做**，等词条到百万级、索引需要落盘时再加（10 bits/词即可）。

### 2.3 AI 新生成的内容（可变部分）

预生成包是只读的，AI 现场生成的词要另存一份，并且同样全局共享：

- **推荐：单文件 SQLite `content-delta.db`**（Anki 同款，本地文件、WAL、单写者、ACID），
  表 `(language, normalized_form, content, prompt_version, created_at)`，一次主键查询 ~0.05–0.2ms。
- 备选：追加式 JSONL（`content-delta.jsonl`），启动时重建内存索引；实现更简单，但写入崩溃要处理半行、定期 compaction。
- 不选：把 delta 继续写在每个用户的 `vocab_words.content` 里（回到今天的问题）。

### 2.4 用户词条只存引用

`vocab_words` 删除 `content` 大字段，改存 `content_id`（或 `(language, normalized_form)` 键）：

- 命中预生成包：`content_id` = 包内词条 id；
- 命中 delta：`content_id` = delta 行 id；
- 未命中：`content_id` 为空、状态 PENDING，prepare 时入队 AI；生成后写 delta 并回填 id。

队列/详情/词库读取时由 `ContentStore`（内存索引 + pread / SQLite delta）解析内容，**不再复制一份到用户行**。

### 2.5 与现有系统的接缝

- `ContentStore` 作为 vocab 模块内的端口：`get(language, form)`、`put(language, form, content, source)`、`stats()`。
- `ReviewPreparationEndpoint` 先批量 `get`：命中直接返回 READY；未命中入队（priority 100）。
- `GenerationJobStore.succeed` 同时写 delta + 更新内存索引。
- 多实例：只读包可共享（挂载/OSS 下载）；delta 在 v1 单实例本地即可，未来换共享 SQLite/对象存储。

## 3. 性能（待用户确认后写进 spec）

- 索引查找 ~1µs，pack 读取 + JSON 解析 ~30–50µs/词；20 词窗口 < 2ms（不含网络）。
- 启动装载 100k 索引 ~10–30ms（mmap 不复制内容）。
- 内存：索引 1.6MB + 可选 LRU 内容缓存。
- 对比 Postgres：主键查询 0.2–1ms + JSON 解析，100k 行 jsonb 占库 ~100MB；文件方案没有 DB 依赖，也避免内容库被用户事务拖慢。

## 4. 风险

- 包/索引版本必须与 prompt 版本绑定，否则改 prompt 后新旧内容混用；manifest 里记录版本，启动校验。
- 包损坏（复制不完整）会让大量词 miss；启动校验 sha256 与条数，失败则拒绝启动或降级 AI。
- AI delta 的并发写：单实例用 SQLite WAL 串行化；未来多实例需要共享存储或改回集中存储。

## 参考

- Anki 数据库结构（SQLite schema v11、`.apkg` 即 zip）：https://github.com/ankidroid/Anki-Android/wiki/Database%E2%80%90Structure%E2%80%902026
- Anki 文件管理：https://docs.ankiweb.net/files
- StarDict 格式说明（`.ifo/.idx/.dict`）：https://download.salixos.org/x86_64/extra-15.0/source/misc/stardict-tools/README.StarDict
- MDict 解析库（启动建内存索引、可外置索引）：https://pkg.go.dev/github.com/lib-x/mdx
- 背单词产品技术拆解（二手，仅作行业侧写）：https://gitcode.csdn.net/6a2ba59510ee7a33f27b9af1.html
