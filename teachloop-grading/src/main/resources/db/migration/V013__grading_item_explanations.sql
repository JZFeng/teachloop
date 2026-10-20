-- T087 / §6.4：错题解析按题目共享，同一道题只存一份（语言 + prompt 版本参与唯一键）。
-- 迁移只写 grading 模块的表；item_id 是指向 paper 的跨模块外键（plan §3.5 已登记）。
create table item_explanations (
  id uuid primary key,
  item_id uuid not null references objective_items (id) on delete cascade,
  language varchar(16) not null,
  prompt_version varchar(64) not null,
  text text,
  state varchar(16) not null default 'done',
  created_at timestamptz not null,
  constraint item_explanations_uk unique (item_id, language, prompt_version)
);
create index item_explanations_item_ix on item_explanations (item_id);
