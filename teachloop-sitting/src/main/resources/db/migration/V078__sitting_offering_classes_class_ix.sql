-- 011-self-study-mode / plan §4.1：今日页从「学生的班」出发查待交作业。
-- offering_classes 只有 unique (offering_id, class_id)（V005:31），前导列是 offering_id，
-- 按 class_id 过滤用不上 → 补一条 class_id 索引。
create index offering_classes_class_ix on offering_classes (class_id);
