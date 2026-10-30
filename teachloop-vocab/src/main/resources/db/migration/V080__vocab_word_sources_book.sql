-- 013-word-books / FR-233：来源类型增加 BOOK（复制/加入词书）
-- 表归属：vocab；check 是放宽，不阻塞存量行。
alter table vocab_word_sources add column book_id uuid;
alter table vocab_word_sources drop constraint vocab_word_sources_kind_check;
alter table vocab_word_sources
  add constraint vocab_word_sources_kind_check
  check (kind in ('ASSIGNMENT', 'IMPORT', 'SELF', 'BOOK'));
