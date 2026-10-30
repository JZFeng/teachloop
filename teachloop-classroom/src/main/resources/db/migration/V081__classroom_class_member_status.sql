-- 012-class-lifecycle：成员状态化（退班/移出不删行）+ 结课查询索引
-- 表归属：classroom；存量行一律 ACTIVE。
alter table class_members add column status      text        not null default 'ACTIVE';
alter table class_members add column left_at     timestamptz;
alter table class_members add column rejoined_at timestamptz;

alter table class_members add constraint ck_class_members_status
  check (status in ('ACTIVE', 'LEFT'));

create index idx_class_members_class_active   on class_members (class_id)   where status = 'ACTIVE';
create index idx_class_members_student_active on class_members (student_id) where status = 'ACTIVE';
create index idx_classes_teacher_active       on classes (teacher_id)       where archived = false;
