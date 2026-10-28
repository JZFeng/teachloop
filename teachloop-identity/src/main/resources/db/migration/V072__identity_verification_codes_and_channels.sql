-- 2026-10-27（009-registration T517/T518）：验证码与身份通道配置。属主：teachloop-identity。
-- 两类「证明邮箱/手机是你的」凭据共用一张表：
--   注册激活令牌（24 小时、点确认页才消费）与 6 位验证码（10 分钟、错 5 次作废）。
-- 只存哈希，不存明文；target 存明文是为了频控与唯一性查询，不是密钥。
create table verification_codes (
  id uuid primary key,
  purpose varchar(24) not null,
  channel varchar(16) not null,
  target varchar(255) not null,
  code_hash varchar(64) not null,
  expires_at timestamptz not null,
  consumed_at timestamptz,
  attempts int not null default 0,
  created_at timestamptz not null default now()
);

create index verification_codes_target_ix on verification_codes (target, purpose, created_at desc);
create index verification_codes_expires_ix on verification_codes (expires_at);

comment on table verification_codes is '验证码/激活令牌；已消费的靠 consumed_at 判定，不物理删除';

-- 通道配置：3 行固定，管理员在设置页改；密钥用 SecretBox 加密后存 secret_cipher。
create table identity_channels (
  channel varchar(16) primary key,
  enabled boolean not null default false,
  driver varchar(32) not null,
  from_address varchar(255),
  base_url varchar(300),
  secret_cipher text,
  extra jsonb,
  daily_quota int,
  updated_at timestamptz not null default now(),
  updated_by uuid references users (id)
);

comment on table identity_channels is '身份通道开关与驱动配置；未配置好的通道视为不存在';

-- v1 默认全关：连 email 也要管理员填好发件人再打开，避免部署完就往外发信。
insert into identity_channels (channel, enabled, driver)
values ('email', false, 'smtp'),
       ('sms', false, 'aliyun-sms'),
       ('wechat', false, 'wechat_web')
on conflict (channel) do nothing;
