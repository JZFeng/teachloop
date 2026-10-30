-- 011-self-study-mode / US-99：学生时区（可空 = 沿回落链）
-- 表归属：identity；按主键取单行，不加索引。
alter table users add column timezone text;
