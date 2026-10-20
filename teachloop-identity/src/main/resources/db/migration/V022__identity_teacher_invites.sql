-- T036：教师邀请码（3 天过期、一次性、可停用）。
create table teacher_invites (
  id uuid primary key,
  code varchar(16) not null unique,
  created_by uuid not null references users (id),
  expires_at timestamptz not null,
  used_by uuid references users (id),
  used_at timestamptz,
  disabled boolean not null default false,
  created_at timestamptz not null
);
create index teacher_invites_code_ix on teacher_invites (code);
