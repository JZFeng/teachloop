-- 2026-10-27：改密码后踢掉其他会话。
-- 会话里只存 uid（SessionAuthFilter），所以用「用户级失效时间 + 会话登录时间」比较：
--   会话的 loginAt 早于 session_invalid_before → 该会话作废。
-- 改密码时把 session_invalid_before 置为 now()，并刷新**当前**会话的 loginAt，改密码的人不掉线。
alter table users add column if not exists session_invalid_before timestamptz;

comment on column users.session_invalid_before is
    '改密码/管理员重置后置为 now()；早于该时间登录的会话一律作废';
