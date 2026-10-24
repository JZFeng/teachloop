-- T387 / specs/004-vocab US-46 US-48：学生词库与复习进度
-- 表归属：vocab；不建跨模块外键（user_id / class_id / set_id 只存 UUID）。

create table vocab_words (
  id uuid primary key,
  user_id uuid not null,
  language varchar(8) not null default 'en',
  form varchar(128) not null,
  normalized_form varchar(128) not null,
  content jsonb,
  content_status varchar(16) not null default 'PENDING'
    check (content_status in ('PENDING', 'READY', 'FAILED')),
  family_id bigint references vocab_lexicon_families (id) on delete set null,
  band smallint,
  removed boolean not null default false,
  removed_at timestamptz,
  deleted boolean not null default false,
  deleted_at timestamptz,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  constraint uk_vocab_words_owner_form unique (user_id, language, normalized_form)
);

create index idx_vocab_words_user_state on vocab_words (user_id, deleted, removed);
create index idx_vocab_words_user_form on vocab_words (user_id, normalized_form);

-- 一个词可能来自多处（班级布置 / 导入 / 自己加），每处一条来源记录。
create table vocab_word_sources (
  id bigserial primary key,
  word_id uuid not null references vocab_words (id) on delete cascade,
  kind varchar(16) not null check (kind in ('ASSIGNMENT', 'IMPORT', 'SELF')),
  set_id uuid,
  assignment_id uuid,
  class_id uuid,
  teacher_id uuid,
  created_at timestamptz not null,
  constraint uk_vocab_word_sources unique nulls not distinct (word_id, kind, assignment_id, set_id)
);

create index idx_vocab_word_sources_word on vocab_word_sources (word_id);

-- 每个 (词) 只有一行进度：状态机 NEW → LEARNING ⇄ REVIEW → GRADUATED。
create table vocab_progress (
  word_id uuid primary key references vocab_words (id) on delete cascade,
  user_id uuid not null,
  state varchar(16) not null default 'NEW'
    check (state in ('NEW', 'LEARNING', 'REVIEW', 'GRADUATED')),
  due_at timestamptz not null,
  interval_days numeric(8,2) not null default 0,
  ease numeric(4,2) not null default 2.50,
  lapses int not null default 0,
  last_rating smallint,
  reviewed_count int not null default 0,
  updated_at timestamptz not null
);

create index idx_vocab_progress_user_due on vocab_progress (user_id, due_at);
create index idx_vocab_progress_user_state on vocab_progress (user_id, state);
