-- 决策 010：教师自定义 AI provider 的显示名称（provider 列继续存 provider ID）。
alter table user_ai_settings add column display_name varchar(100);
