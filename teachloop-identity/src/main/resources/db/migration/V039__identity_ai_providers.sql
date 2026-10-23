-- 决策 010 修订：全局 provider 列表 + 一个当前模型（v1 不做按教师/作业覆盖）。
create table ai_providers (
  id uuid primary key,
  provider_key varchar(80) not null unique,
  display_name varchar(100) not null,
  base_url varchar(300) not null,
  protocol varchar(20) not null default 'openai',
  api_key_cipher text,
  model varchar(100) not null,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  updated_by uuid
);

alter table ai_settings
  add column active_provider_id uuid references ai_providers (id) on delete set null;
