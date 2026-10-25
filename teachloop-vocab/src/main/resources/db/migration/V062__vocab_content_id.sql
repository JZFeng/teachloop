-- T429 / decisions/017 P1：用户词条只存全局内容的引用，不再各自保存唯一副本。
-- content_id 引用只读内容包（1..N）或本地 SQLite delta（>= 1_000_000_000）；
-- 不建跨源外键，由 ContentStore 按 id 区间分派。
alter table vocab_words add column content_id bigint;

-- 回填时找 content_id is null 的存量 READY 词；部分索引比整列索引小。
create index idx_vocab_words_content_id on vocab_words (content_id)
  where content_id is not null;
