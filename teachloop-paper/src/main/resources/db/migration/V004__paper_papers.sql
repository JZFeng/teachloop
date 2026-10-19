create table papers (
  id uuid primary key,
  title varchar(200) not null,
  default_duration_minutes int,
  total_points numeric(8,2) not null default 0,
  version int not null default 1,
  status varchar(16) not null default 'DRAFT',
  author_id uuid not null references users (id),
  rubric_markdown text,
  grading_prompt_template text,
  created_at timestamptz not null
);
create index papers_author_ix on papers (author_id);

create table paper_sections (
  id uuid primary key,
  paper_id uuid not null references papers (id) on delete cascade,
  title varchar(200) not null,
  position int not null,
  points_per_item numeric(8,2)
);
create index paper_sections_paper_ix on paper_sections (paper_id);

create table objective_items (
  id uuid primary key,
  paper_id uuid not null references papers (id) on delete cascade,
  paper_section_id uuid not null references paper_sections (id) on delete cascade,
  number int not null,
  position int not null,
  question_type varchar(24) not null,
  stem text,
  options text,
  answer_key text,
  points numeric(8,2) not null,
  explanation_state varchar(16) not null default 'none',
  constraint objective_items_uk unique (paper_id, number)
);

create table section_criteria (
  id uuid primary key,
  paper_id uuid not null references papers (id) on delete cascade,
  paper_section_id uuid not null references paper_sections (id) on delete cascade,
  name varchar(200) not null,
  max_points numeric(8,2) not null,
  description text,
  position int not null
);
create index section_criteria_paper_ix on section_criteria (paper_id);
