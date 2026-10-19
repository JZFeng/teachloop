create table classes (
  id uuid primary key,
  name varchar(120) not null,
  teacher_id uuid not null references users (id),
  invite_code varchar(32) not null unique,
  archived boolean not null default false,
  created_at timestamptz not null
);
create index classes_teacher_ix on classes (teacher_id);

create table class_members (
  id uuid primary key,
  class_id uuid not null references classes (id) on delete cascade,
  student_id uuid not null references users (id),
  joined_at timestamptz not null,
  constraint class_members_uk unique (class_id, student_id)
);
create index class_members_student_ix on class_members (student_id);
