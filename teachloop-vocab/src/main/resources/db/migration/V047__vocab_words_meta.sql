-- T396/T397/T398 / specs/004-vocab：收藏、标签、学生列配置。
-- 原 V041 的 vocab_words 没有 favorite / tags；AC-59.9 的「已收藏」筛选、FR-092 的多标签、
-- AC-59.5 的「列配置按学生保存」都需要落点。表归属：vocab。

alter table vocab_words add column favorite boolean not null default false;
alter table vocab_words add column tags jsonb not null default '[]'::jsonb;

create index idx_vocab_words_user_favorite on vocab_words (user_id, favorite) where not deleted;
create index idx_vocab_words_tags on vocab_words using gin (tags);

-- 学生表格偏好：显示哪些列、列顺序（jsonb 由前端定义形状，服务端只存整份配置）。
create table vocab_user_prefs (
  user_id uuid primary key,
  table_columns jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null
);
