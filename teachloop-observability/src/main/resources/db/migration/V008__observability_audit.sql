create table audit_logs (
  id uuid primary key,
  actor_id uuid references users (id),
  action varchar(80) not null,
  target_type varchar(40),
  target_id varchar(64),
  detail text,
  created_at timestamptz not null
);
create index audit_logs_target_ix on audit_logs (target_type, target_id, created_at desc);
create index audit_logs_created_ix on audit_logs (created_at desc);
