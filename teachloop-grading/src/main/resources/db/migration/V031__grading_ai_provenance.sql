-- T255/T263：AI 草稿的来源与预填字段；失败原因与「AI 未参与」区分。
alter table reviews add column ai_overall text;
alter table reviews add column ai_strength text;
alter table reviews add column ai_suggestion text;
alter table reviews add column ai_model varchar(64);
alter table reviews add column ai_prompt_version varchar(64);
alter table reviews add column ai_key_source varchar(16);
alter table reviews add column ai_failure varchar(32);
alter table reviews add column feedback_language varchar(16);
alter table ai_calls add column key_source varchar(16);
