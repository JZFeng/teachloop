-- 决策 013（D-31）：AI 调用熔断状态。按 provider_key 隔离，app 与 worker 两个进程共享。
create table ai_circuit_state (
  provider_key varchar(80) primary key,
  state varchar(16) not null default 'CLOSED',
  consecutive_failures int not null default 0,
  streak_started_at timestamptz,
  opened_at timestamptz,
  half_open_started_at timestamptz,
  last_error_kind varchar(32),
  last_failure_at timestamptz,
  updated_at timestamptz not null
);
