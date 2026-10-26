-- 决策 022 D3：每份提交记录是哪条 provider 判的（同一场可能跨 provider）。
alter table submissions add column graded_provider varchar(64);
