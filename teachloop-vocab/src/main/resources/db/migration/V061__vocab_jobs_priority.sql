-- T426：按需准备。复习开始时把前 N 个词的任务提到队首，后台批量任务排在后面。
-- 0 = 后台（导入预热等）；100 = 学生正在等的按需准备。
alter table vocab_generation_jobs add column priority int not null default 0;

create index idx_vocab_generation_jobs_claim
  on vocab_generation_jobs (state, priority desc, next_run_at);
