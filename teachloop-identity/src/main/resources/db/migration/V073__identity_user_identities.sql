-- 2026-10-27（009-registration T519）：一个账号可以挂多条身份（邮箱 / 手机 / 微信 openid 或 unionid）。
-- 现有 users.wechat_openid 单列装不下「公众号 + 小程序」两个应用，所以身份单独成表。
create table user_identities (
  id uuid primary key,
  user_id uuid not null references users (id),
  channel varchar(16) not null,
  external_id varchar(128) not null,
  external_app varchar(32),
  verified_at timestamptz,
  created_at timestamptz not null default now(),
  last_used_at timestamptz
);

-- 同一条外部身份只能属于一个账号。
create unique index user_identities_uk on user_identities (channel, external_id);
create index user_identities_user_ix on user_identities (user_id);

comment on table user_identities is '账号 1:N 身份；channel+external_id 唯一';

-- 回填：把已有微信账号搬进来，标记 legacy（没有 app 维度）。旧列保留一个版本周期，只读不写。
insert into user_identities (id, user_id, channel, external_id, external_app, verified_at, created_at)
select gen_random_uuid(), id, 'wechat', wechat_openid, 'legacy', created_at, created_at
from users
where wechat_openid is not null
on conflict (channel, external_id) do nothing;
