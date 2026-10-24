-- T388 / specs/004-vocab §13：词表索引装载指纹（单行表）。
--
-- 用途：plan §3.1 要求「应用启动时对比 manifest 的 sha256，发现词表变了才 upsert」，
-- 因此需要记住上次写进 vocab_lexicon_* 的是哪一份产物。
-- 指纹 = manifest.content_sha256（源文件 sha256 + 两个产物 sha256 + 计数，
-- 不含构建时间，见 scripts/vocab/build-wordlist-index.py）。
-- 表归属：vocab（见 scripts/check-boundaries.py TABLE_OWNER）。

create table vocab_lexicon_meta (
  id smallint primary key,
  content_sha256 varchar(64) not null,
  family_count int not null,
  form_count int not null,
  applied_at timestamptz not null,
  constraint ck_vocab_lexicon_meta_single_row check (id = 1)
);
