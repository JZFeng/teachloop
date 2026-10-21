-- D-16：AI Key 归属。历史数据为空时回退管理员全局 Key；不跨模块加外键。
alter table offerings add column created_by uuid;
create index offerings_created_by_ix on offerings (created_by);
