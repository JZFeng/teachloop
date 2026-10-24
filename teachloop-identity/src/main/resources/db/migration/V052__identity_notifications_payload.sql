-- A1（2026-10-24）：站内通知只存事实（kind + payload），文案在读取时按请求的 Accept-Language 渲染。
-- 这样学生切界面语言后，历史通知也会跟着变；也顺带修掉「中文界面收到英文通知」。
--
-- title / body 降级为「历史行 + 未知 kind」的回退原文，新写入的行可以为空。
alter table notifications add column payload jsonb;
alter table notifications alter column title drop not null;
