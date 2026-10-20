-- T060/T061/T067：富文本与文件提交。
-- content_html 存消毒后的原文（回显用），content_text 始终是从中提取的纯文本（AI 用）；
-- 附件只存 StorageBackend 的 key/名称/类型，不把二进制塞进数据库。
-- unreadable：文件解析失败或无文本层（AC-6.9 / T066），标记后不参与评分，绝不产生 0 分。
alter table submissions add column content_html text;
alter table submissions add column attachment_path text;
alter table submissions add column attachment_name varchar(255);
alter table submissions add column attachment_mime varchar(100);
alter table submissions add column unreadable boolean not null default false;
