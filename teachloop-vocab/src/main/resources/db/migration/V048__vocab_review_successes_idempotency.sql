-- T405 / specs/004-vocab：评分日志与幂等、毕业所需的连续认识次数。
-- 原 vocab_progress 没有 successes；review_logs 也没有幂等键，重放会双写。
-- 表归属：vocab。

alter table vocab_progress add column successes int not null default 0;

create table vocab_review_requests (
  user_id uuid not null,
  idempotency_key varchar(120) not null,
  word_id uuid not null,
  created_at timestamptz not null,
  primary key (user_id, idempotency_key)
);
