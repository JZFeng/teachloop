-- T448 / specs/004-vocab US-69：学生词单（只引用词条 id 的容器）
-- 表归属：vocab；user_id 只存 UUID，不建跨模块外键。
-- 词单不复制任何排期：词条仍按 (user_id, language, normalized_form) 一份，进度仍只有
-- vocab_progress 一行；同一词进多份词单，复习后仍只有一份 due_at（AC-69.1）。

create table vocab_lists (
  id uuid primary key,
  user_id uuid not null,
  name varchar(80) not null,
  description varchar(200),
  position int not null default 0,
  deleted boolean not null default false,
  created_at timestamptz not null,
  updated_at timestamptz not null
);

-- 同一学生未删除词单内不重名（大小写不敏感，AC-69.1）；软删后名字可复用。
create unique index uk_vocab_lists_user_name on vocab_lists (user_id, lower(name)) where not deleted;
create index idx_vocab_lists_user on vocab_lists (user_id, deleted, position);

create table vocab_list_items (
  id bigserial primary key,
  list_id uuid not null references vocab_lists (id) on delete cascade,
  -- 刻意不建外键：与 vocab_review_logs 同口径——词软删（AC-55）后引用要保留，
  -- 恢复后它仍在原来所属的词单里（AC-69.3）；归属校验在应用层用 belongsTo 做。
  -- 建外键会把「软删词」与「词单引用」耦合，删除/恢复词条都要连带改引用行。
  word_id uuid not null,
  position int not null default 0,
  added_at timestamptz not null,
  constraint uk_vocab_list_items unique (list_id, word_id)
);

create index idx_vocab_list_items_list on vocab_list_items (list_id, position);
create index idx_vocab_list_items_word on vocab_list_items (word_id);
