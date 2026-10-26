-- T515 / AC-60.13：查词即生成（2026-10-26 用户 Bug）——不在库的词也要能直接触发 AI，
-- 生成完自动落到查词页的卡片反面，不需要先「＋ 加入词库」。
--
-- 为什么加 subject_type='FORM' 而不是给用户建一条影子词条：
--   词内容本来就是全局共享的（AC-60.6，同一 (language, normalized_form) 只生成一次），
--   查词只是「请求这份内容」，跟某个学生的词库没有关系；建影子词条会把查过的词
--   全部塞进词库，复习队列和词库统计都会被污染。
--
-- subject_id 由 (language, normalized_form) 稳定派生（UUID.nameUUIDFromBytes），
-- 所以同一词形的并发查词只会有一条 job（uk_vocab_generation_jobs 唯一键兜底）；
-- subject_form / subject_language 让 worker 不必回头查 vocab_words。
alter table vocab_generation_jobs
  drop constraint vocab_generation_jobs_subject_type_check;

alter table vocab_generation_jobs
  add constraint vocab_generation_jobs_subject_type_check
    check (subject_type in ('WORD', 'SET_WORD', 'ASSIGNMENT_WORD', 'FORM'));

alter table vocab_generation_jobs
  add column subject_form varchar(128),
  add column subject_language varchar(8);

-- 查词轮询按 (语言, 规范化词形) 找 job 状态；normalized_form 已在写入前小写去变音符。
create index idx_vocab_generation_jobs_form
  on vocab_generation_jobs (subject_language, subject_form)
  where subject_type = 'FORM';
