create table users (
  id uuid primary key,
  role varchar(16) not null,
  email varchar(255) not null unique,
  password_hash varchar(100),
  phone varchar(32),
  display_name varchar(100) not null,
  locale varchar(16) not null default 'zh-CN',
  status varchar(16) not null default 'ACTIVE',
  created_at timestamptz not null
);
