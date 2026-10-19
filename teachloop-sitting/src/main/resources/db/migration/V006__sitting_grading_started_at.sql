alter table submissions add column grading_started_at timestamptz;
create index submissions_grading_started_ix on submissions (grading_started_at);
