-- T387 / specs/004-vocab US-49 US-50 US-51：教师词集与发词（发送时快照）
-- 表归属：vocab；class_id / teacher_id 只存 UUID，不建跨模块外键。

create table vocab_sets (
  id uuid primary key,
  teacher_id uuid not null,
  language varchar(8) not null default 'en',
  name varchar(100) not null,
  description text,
  active boolean not null default true,
  deleted boolean not null default false,
  created_at timestamptz not null,
  updated_at timestamptz not null
);

create unique index uk_vocab_sets_teacher_name on vocab_sets (teacher_id, lower(name)) where not deleted;
create index idx_vocab_sets_teacher on vocab_sets (teacher_id, deleted);

create table vocab_set_words (
  id uuid primary key,
  set_id uuid not null references vocab_sets (id) on delete cascade,
  position int not null,
  form varchar(128) not null,
  normalized_form varchar(128) not null,
  content jsonb,
  family_id bigint references vocab_lexicon_families (id) on delete set null,
  band smallint,
  created_at timestamptz not null,
  constraint uk_vocab_set_words unique (set_id, normalized_form)
);

create index idx_vocab_set_words_order on vocab_set_words (set_id, position);

create table vocab_assignments (
  id uuid primary key,
  set_id uuid not null references vocab_sets (id),
  teacher_id uuid not null,
  due_at timestamptz,
  note text,
  created_at timestamptz not null
);

create index idx_vocab_assignments_teacher on vocab_assignments (teacher_id, created_at);

-- 发送时的内容快照：之后改词集不影响已发出的布置（AC-50.2）。
create table vocab_assignment_words (
  id uuid primary key,
  assignment_id uuid not null references vocab_assignments (id) on delete cascade,
  position int not null,
  form varchar(128) not null,
  normalized_form varchar(128) not null,
  content jsonb,
  family_id bigint references vocab_lexicon_families (id) on delete set null,
  band smallint,
  created_at timestamptz not null,
  constraint uk_vocab_assignment_words unique (assignment_id, normalized_form)
);

create index idx_vocab_assignment_words_order on vocab_assignment_words (assignment_id, position);

create table vocab_assignment_classes (
  assignment_id uuid not null references vocab_assignments (id) on delete cascade,
  class_id uuid not null,
  primary key (assignment_id, class_id)
);

-- 收件人也是快照：学生退班不影响已发出的布置（AC-51.4）。
create table vocab_assignment_recipients (
  assignment_id uuid not null references vocab_assignments (id) on delete cascade,
  student_id uuid not null,
  claimed_at timestamptz,
  primary key (assignment_id, student_id)
);

create index idx_vocab_assignment_recipients_student on vocab_assignment_recipients (student_id, claimed_at);
