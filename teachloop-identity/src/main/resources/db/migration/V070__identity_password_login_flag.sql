-- 2026-10-27：测试账号密码登录开关（用户要求「先把测试账号打开，让朋友用起来」）。
-- 生产环境密码登录原本只对 ADMIN 开放（T032/T033，`ProdAuthTest` 里有断言）。
-- 本列是**按账号**的显式白名单，不是按 profile 的开关：
--   只有管理员用 CLI `--create-user` 建出来的账号才为 true。
-- 这样「让朋友能登录」与「对所有人放开密码登录」是两件事，后者仍然被禁止。
--
-- 用 if not exists：测试容器与本地库可能被重复初始化（V069 曾被 vocab 分支占用后移除），
-- 幂等写法让这条迁移在两种情况下都能落地，不会把整个上下文带崩。
alter table users add column if not exists password_login_enabled boolean not null default false;

comment on column users.password_login_enabled is
    '生产环境允许邮箱+密码登录；默认 false，仅管理员显式开启的测试/受邀账号为 true';
