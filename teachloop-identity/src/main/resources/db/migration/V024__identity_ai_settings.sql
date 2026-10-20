-- 管理员初始设置：AI 模型配置（单行）。API Key 只存 AES-GCM 密文。
create table ai_settings (
  id int primary key check (id = 1),
  provider varchar(40) not null,
  base_url varchar(300) not null,
  model varchar(100) not null,
  api_key_cipher text,
  temperature numeric(4,2) not null default 0.20,
  updated_at timestamptz not null,
  updated_by uuid references users (id)
);
insert into ai_settings (id, provider, base_url, model, temperature, updated_at)
values (1, 'deepseek', 'https://api.deepseek.com', 'deepseek-flash', 0.20, now());
