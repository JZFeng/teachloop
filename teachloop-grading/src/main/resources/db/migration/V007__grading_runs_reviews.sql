create table grading_runs (
  id uuid primary key,
  submission_id uuid not null references submissions (id) on delete cascade,
  engine varchar(64) not null,
  started_at timestamptz not null,
  finished_at timestamptz,
  state varchar(16) not null default 'running',
  detail text
);
create index grading_runs_submission_ix on grading_runs (submission_id);

create table reviews (
  id uuid primary key,
  submission_id uuid not null references submissions (id) on delete cascade,
  status varchar(16) not null default 'PENDING',
  teacher_id uuid references users (id),
  comment text,
  created_at timestamptz not null,
  constraint reviews_submission_uk unique (submission_id)
);
