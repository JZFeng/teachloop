-- 决策 022：provider 有序 + 故障转移/回切留痕
alter table ai_providers add column priority int not null default 100;

-- 决策 022 D2/D5：成功连续数用于回切判定（失败清零，成功 +1）
alter table ai_circuit_state add column success_streak int not null default 0;

alter table ai_settings
  add column last_failover_at timestamptz,
  add column failover_from_provider_key varchar(80),
  add column failover_reason varchar(40);

-- 决策 023：每日 AI 用量（日期按 Asia/Shanghai 自然日，与应用层一致）
create table ai_usage_daily (
  usage_date date not null,
  provider_key varchar(80) not null,
  model varchar(100) not null,
  purpose varchar(32) not null,
  calls bigint not null default 0,
  input_tokens bigint not null default 0,
  output_tokens bigint not null default 0,
  usage_missing boolean not null default false,
  updated_at timestamptz not null,
  primary key (usage_date, provider_key, model, purpose)
);
