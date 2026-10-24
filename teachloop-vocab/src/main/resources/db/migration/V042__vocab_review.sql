-- T387 / specs/004-vocab US-47 US-52 US-53 US-61：复习日志、卡组视图、自由复习会话
-- 表归属：vocab。

-- 纯流水表：词被软删除后日志仍保留（AC-55.1），所以刻意不建到 vocab_words 的外键。
create table vocab_review_logs (
  id bigserial primary key,
  user_id uuid not null,
  word_id uuid not null,
  rating smallint not null check (rating in (1, 2)),
  reviewed_at timestamptz not null,
  elapsed_ms int,
  session_id uuid
);

create index idx_vocab_review_logs_user_time on vocab_review_logs (user_id, reviewed_at);
create index idx_vocab_review_logs_word on vocab_review_logs (word_id);

-- 卡组 = 保存的筛选条件（不是容器），criteria 存来源/标签/状态等组合。
create table vocab_decks (
  id uuid primary key,
  user_id uuid not null,
  name varchar(80) not null,
  criteria jsonb not null default '{}'::jsonb,
  position int not null default 0,
  deleted boolean not null default false,
  created_at timestamptz not null,
  updated_at timestamptz not null
);

create unique index uk_vocab_decks_user_name on vocab_decks (user_id, lower(name)) where not deleted;
create index idx_vocab_decks_user on vocab_decks (user_id, deleted);

-- 自由复习会话：scope 是筛选条件快照，cursor 支持「上次看到第 N 个」续位。
create table vocab_review_sessions (
  id uuid primary key,
  user_id uuid not null,
  scope jsonb not null,
  total int not null,
  cursor int not null default 0,
  known_count int not null default 0,
  status varchar(16) not null default 'ACTIVE' check (status in ('ACTIVE', 'ENDED')),
  started_at timestamptz not null,
  updated_at timestamptz not null
);

create index idx_vocab_review_sessions_user on vocab_review_sessions (user_id, status);
