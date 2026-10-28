-- 2026-10-28：出站消息队列（注册激活信、登录/重置验证码、管理端测试邮件、将来的短信）。
--
-- 为什么落库而不是 @Async：注册激活信丢了用户就进不来，进程重启不能把在途消息吃掉。
-- 派发由 `OutboundMessageWorker` 定时做（每 2 秒一批，FOR UPDATE SKIP LOCKED 抢锁），
-- 失败按退避重试，3 次仍失败置 FAILED。
--
-- 正文为什么要加密：激活链接里就是可用一次的令牌（`verification_codes` 里只存哈希），
-- 队列行不能成为第二份明文凭据。正文用 SecretBox（AES-GCM）加密存 `body_cipher`，
-- 发出后立刻清空该列，SENT 行只留投递事实，供审计与「刚才那封信发了没」的追问。
create table outbound_messages (
  id uuid primary key,
  channel varchar(16) not null,
  target varchar(255) not null,
  subject varchar(200),
  body_cipher text,
  status varchar(16) not null default 'PENDING',
  attempts int not null default 0,
  next_attempt_at timestamptz not null default now(),
  last_error varchar(500),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  sent_at timestamptz
);

comment on table outbound_messages is '出站消息队列；正文密文存 body_cipher，发出后清空';
comment on column outbound_messages.attempts is '已尝试次数（含正在进行的这一次）';
comment on column outbound_messages.next_attempt_at is '下一次可投递时间；失败退避写未来时间';

-- 派发只查「到期的 PENDING」，其他状态不进索引。
create index outbound_messages_due_ix on outbound_messages (status, next_attempt_at);

-- 管理端按通道查最近一次测试投递
create index outbound_messages_channel_ix on outbound_messages (channel, created_at desc);
