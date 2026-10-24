-- T387 / specs/004-vocab：本地词族表（Paul Nation BNC/COCA word family）
-- 由 scripts/vocab/build-wordlist-index.py 生成，按 manifest sha256 幂等 upsert。
-- 归属：vocab（见 scripts/check-boundaries.py TABLE_OWNER）

create table vocab_lexicon_families (
  id bigint primary key,
  head varchar(128) not null,
  band smallint not null,
  family_freq bigint not null,
  created_at timestamptz not null
);

create index idx_vocab_lexicon_families_head on vocab_lexicon_families (head);
create index idx_vocab_lexicon_families_band on vocab_lexicon_families (band);

-- 每个词形一条；normalized_form 是查询入口，folded_form 用于去变音符兜底。
create table vocab_lexicon_forms (
  normalized_form varchar(128) primary key,
  folded_form varchar(160) not null,
  family_id bigint not null references vocab_lexicon_families (id) on delete cascade,
  form_freq bigint not null,
  is_head boolean not null default false,
  created_at timestamptz not null
);

create index idx_vocab_lexicon_forms_folded on vocab_lexicon_forms (folded_form);
create index idx_vocab_lexicon_forms_family on vocab_lexicon_forms (family_id);
