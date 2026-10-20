-- T198：试卷快照按 plan §5 存 JSONB；T210 要求的 (status, due_at) 索引一并补上。
alter table offerings alter column paper_snapshot type jsonb using paper_snapshot::jsonb;
create index offerings_status_due_ix on offerings (status, due_at);

-- T199：服务端权威计时需要知道「这份卷子已经交过了」，草稿与已交卷据此区分。
alter table submissions add column submitted_at timestamptz;

-- 到点自动交卷只扫「还没交卷且已过期」的行。
create index submissions_deadline_open_ix on submissions (deadline_at) where submitted_at is null;

-- T202：按班汇总要按学生 + 迟交筛选；已有 offering 索引，这里补学生维度。
create index submissions_student_ix on submissions (student_id);
