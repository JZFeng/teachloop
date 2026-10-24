-- T387 / specs/004-vocab US-48 US-57 US-60：异步内容生成、内容反馈、TTS 音频资产
-- 表归属：vocab。

-- 内容生成任务：subject 是词条/词集词条/布置快照三者之一，按 (type,id) 幂等。
create table vocab_generation_jobs (
  id bigserial primary key,
  subject_type varchar(24) not null
    check (subject_type in ('WORD', 'SET_WORD', 'ASSIGNMENT_WORD')),
  subject_id uuid not null,
  state varchar(16) not null default 'QUEUED'
    check (state in ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
  attempts int not null default 0,
  next_run_at timestamptz not null,
  last_error text,
  locked_by varchar(64),
  locked_at timestamptz,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  constraint uk_vocab_generation_jobs unique (subject_type, subject_id)
);

create index idx_vocab_generation_jobs_poll on vocab_generation_jobs (state, next_run_at);

-- 学生反馈：带 word_id 即可（AC-60.4），payload 存提交时的上下文快照。
create table vocab_feedback (
  id bigserial primary key,
  user_id uuid not null,
  word_id uuid not null,
  payload jsonb,
  note text,
  status varchar(16) not null default 'OPEN' check (status in ('OPEN', 'RESOLVED', 'IGNORED')),
  created_at timestamptz not null
);

create index idx_vocab_feedback_status on vocab_feedback (status, created_at);

-- TTS 缓存索引：权威内容是 BlobStore 里的字节，这里只做统计与清理。
create table vocab_audio_assets (
  key varchar(160) primary key,
  form varchar(128) not null,
  voice varchar(64) not null,
  rate numeric(3,2) not null default 1.00,
  size_bytes int not null default 0,
  created_at timestamptz not null
);

create index idx_vocab_audio_assets_form on vocab_audio_assets (form);
