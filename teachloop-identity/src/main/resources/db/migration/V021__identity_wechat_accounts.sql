-- T031：微信身份可先于邮箱存在，因此 email 允许为空（唯一约束在 Postgres 下允许多个 NULL）。
alter table users alter column email drop not null;
alter table users add column wechat_openid varchar(64);
create unique index users_wechat_openid_uk on users (wechat_openid) where wechat_openid is not null;
