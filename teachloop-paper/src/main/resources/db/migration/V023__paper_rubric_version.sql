-- T054：发布锁定与评分标准版本；每次发布递增。
alter table papers add column rubric_version int not null default 1;
