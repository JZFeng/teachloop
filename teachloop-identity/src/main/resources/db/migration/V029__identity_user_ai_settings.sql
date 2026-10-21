-- D-16：教师/管理员个人 AI Key。全局 Key 仍在 ai_settings(id=1)；
-- 判分时优先用作业创建者的 Key，缺失回退全局。只存密文，接口只回掩码。
create table user_ai_settings (
  user_id uuid primary key references users (id) on delete cascade,
  provider varchar(40) not null default 'deepseek',
  base_url varchar(300) not null default 'https://api.deepseek.com',
  model varchar(100) not null default 'deepseek-flash',
  api_key_cipher text,
  temperature numeric(4,2) not null default 0.20,
  updated_at timestamptz not null,
  updated_by uuid references users (id)
);
