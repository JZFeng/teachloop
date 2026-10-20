-- T037：四类端点限流。属主 identity（与 users/notifications 同模块）。
-- 固定窗口计数：唯一键 = (key, action, window_start)，用
-- insert ... on conflict do update set count = count + 1 returning count 原子累加。
create table rate_limits (
  id uuid primary key,
  key varchar(200) not null,
  action varchar(40) not null,
  window_start timestamptz not null,
  count int not null default 0,
  constraint rate_limits_uk unique (key, action, window_start)
);
create index rate_limits_window_ix on rate_limits (window_start);
