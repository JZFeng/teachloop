-- T019 / T144：可观测的每日汇总表。
-- 明细日志只落 JSONL 文件（90 天后删除）；本表只存报表需要的聚合数字，可重跑覆盖。
create table daily_stats (
  day date not null,
  metric varchar(64) not null,
  value numeric(14,2) not null,
  primary key (day, metric)
);
