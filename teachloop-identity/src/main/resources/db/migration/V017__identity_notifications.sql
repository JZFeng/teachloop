-- T208：站内通知表。属主 identity（与 users 同模块，拆服务时一起搬走）。
-- title/kind 由调用方给出（sitting 发布考试时按 feedback_language 生成），identity 只负责存储与已读。
create table notifications (
  id uuid primary key,
  recipient_id uuid not null references users (id) on delete cascade,
  kind varchar(32) not null,
  target_type varchar(40),
  target_id varchar(64),
  title varchar(200) not null,
  body text,
  read_at timestamptz,
  created_at timestamptz not null
);
create index notifications_recipient_read_ix on notifications (recipient_id, read_at);
create index notifications_created_ix on notifications (created_at desc);
