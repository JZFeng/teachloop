-- 决策 010 修订：一个 provider 可以保存多个模型；全局当前选择精确到 provider + model。
alter table ai_providers add column models jsonb;
update ai_providers set models = jsonb_build_array(model) where models is null;
alter table ai_providers alter column models set not null;
alter table ai_settings add column active_model varchar(100);
