-- D-14（2026-10-20）：作业不再引用试卷。存量作业与原卷脱钩，内容仍在 paper_snapshot 里。
-- 顺序不能反：先允许 NULL，才能把存量作业的 paper_id 置空；回填完成后再上加约束。
alter table offerings alter column paper_id drop not null;

update offerings set paper_id = null, paper_version = 0
 where kind = 'ASSIGNMENT' and paper_id is not null;

alter table offerings add constraint offerings_kind_content_ck
  check ((kind = 'ASSIGNMENT') = (paper_id is null));
