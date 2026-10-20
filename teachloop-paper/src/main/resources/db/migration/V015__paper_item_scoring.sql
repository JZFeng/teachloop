-- T196：多选题计分策略。单选/填空/写作忽略该列。
alter table objective_items add column scoring_strategy varchar(24) not null default 'ALL_OR_NOTHING';
