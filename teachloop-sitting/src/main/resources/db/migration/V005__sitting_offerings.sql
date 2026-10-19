create table offerings (
  id uuid primary key,
  paper_id uuid not null references papers (id),
  kind varchar(16) not null,
  start_at timestamptz,
  due_at timestamptz not null,
  duration_minutes int,
  timing_mode varchar(16) not null default 'INDIVIDUAL',
  grace_hours int not null default 3,
  max_attempts int not null default 3,
  feedback_language varchar(16) not null default 'zh-CN',
  show_peer_overview boolean not null default true,
  publish_scores boolean not null default false,
  status varchar(16) not null default 'DRAFT',
  paper_snapshot text not null,
  paper_version int not null,
  rubric_markdown text,
  grading_prompt_template text,
  grading_dispatched_at timestamptz,
  created_at timestamptz not null
);
create index offerings_paper_ix on offerings (paper_id);
create index offerings_status_ix on offerings (status);

create table offering_classes (
  id uuid primary key,
  offering_id uuid not null references offerings (id) on delete cascade,
  class_id uuid not null references classes (id),
  due_at_override timestamptz,
  duration_override int,
  constraint offering_classes_uk unique (offering_id, class_id)
);

create table submissions (
  id uuid primary key,
  offering_id uuid not null references offerings (id) on delete cascade,
  student_id uuid not null references users (id),
  attempt_no int not null default 1,
  input_kind varchar(16) not null default 'TEXT',
  active boolean not null default true,
  content_text text,
  objective_total numeric(8,2) not null default 0,
  subjective_total numeric(8,2) not null default 0,
  started_at timestamptz,
  deadline_at timestamptz,
  auto_submitted boolean not null default false,
  late_by_seconds bigint not null default 0,
  rejected_at timestamptz,
  reject_reason text,
  grading_state varchar(16) not null default 'DONE',
  grading_attempts int not null default 0,
  grading_error text,
  created_at timestamptz not null
);
create unique index submissions_active_uk on submissions (offering_id, student_id) where active;
create index submissions_offering_ix on submissions (offering_id);
create index submissions_state_ix on submissions (grading_state);

create table objective_answers (
  id uuid primary key,
  submission_id uuid not null references submissions (id) on delete cascade,
  item_id uuid not null references objective_items (id),
  number int not null,
  answer text not null,
  constraint objective_answers_uk unique (submission_id, item_id)
);
create index objective_answers_submission_ix on objective_answers (submission_id);
