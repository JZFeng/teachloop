-- T092：一个 offering 只产生一条 grading_run（幂等键 = offering_id）。
-- 迁移只写 grading 模块的表；offering_id 是指向 sitting 的跨模块外键（拆服务时按清单删除）。
alter table grading_runs add column offering_id uuid references offerings (id) on delete cascade;
alter table grading_runs alter column submission_id drop not null;
create unique index grading_runs_offering_uk on grading_runs (offering_id) where offering_id is not null;
