-- T194 / T152：逐题对错落库，反馈详情与报表聚合都读这一列，不在 SQL 里重算匹配策略。
alter table objective_answers add column correct boolean not null default false;
create index objective_answers_item_ix on objective_answers (item_id);
