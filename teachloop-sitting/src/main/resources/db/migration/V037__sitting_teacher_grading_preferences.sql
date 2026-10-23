-- 决策 010：教师级批改时机。作业默认每晚固定时刻批量派发；教师可改成 due+grace。
-- 不建外键：sitting 不跨模块引用 users（与 offerings.created_by 同口径）。
create table teacher_grading_preferences (
  teacher_id uuid primary key,
  trigger_mode varchar(20) not null default 'FIXED_TIME',
  fixed_time time not null default '02:30',
  time_zone varchar(60) not null default 'Asia/Shanghai',
  catch_up boolean not null default true,
  last_fixed_run_at timestamptz,
  updated_at timestamptz not null,
  updated_by uuid
);
