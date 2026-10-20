-- T083：模型调用留痕（grading 模块拥有，plan §3.3 / §6.4）。
create table ai_calls (
  id uuid primary key,
  model varchar(64) not null,
  prompt_version varchar(64) not null,
  tokens_in integer,
  tokens_out integer,
  latency_ms bigint not null,
  raw_response text,
  created_at timestamptz not null
);
create index ai_calls_created_at_ix on ai_calls (created_at);
