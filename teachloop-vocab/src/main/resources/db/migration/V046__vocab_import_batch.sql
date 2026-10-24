-- T393 / specs/004-vocab AC-48.11 AC-46.4：导入来源标记与批次。
-- 原表只有 kind + set/assignment/class/teacher，无法表达「Unit 5 生词」这类来源标记，
-- 也无法区分「某次导入」。加 label（展示/筛选）与 batch_id（一次提交一个）。
-- 唯一键加入 batch_id：同一个词在不同批次各记一条来源；同一批次内同一来源只记一次。
-- 表归属：vocab（见 scripts/check-boundaries.py TABLE_OWNER）。

alter table vocab_word_sources add column label varchar(80);
alter table vocab_word_sources add column batch_id uuid;

alter table vocab_word_sources drop constraint uk_vocab_word_sources;
alter table vocab_word_sources
  add constraint uk_vocab_word_sources
  unique nulls not distinct (word_id, kind, assignment_id, set_id, batch_id);

create index idx_vocab_word_sources_batch on vocab_word_sources (batch_id);
create index idx_vocab_word_sources_label on vocab_word_sources (label);
