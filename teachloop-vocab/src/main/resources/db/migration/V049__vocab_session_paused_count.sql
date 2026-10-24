-- T404 / specs/004-vocab US-61：自由复习小结需要「暂停多少」。known_count 已有，补 paused_count。
alter table vocab_review_sessions add column paused_count int not null default 0;
