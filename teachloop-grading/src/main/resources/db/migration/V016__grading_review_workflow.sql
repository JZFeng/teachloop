-- T101 / T103：复核编辑与发布。
-- original_subjective 保存 AI 主观草稿（批改完成时写入），用于「AI 草稿 → 教师最终分」的前后对比；
-- 发布以 reviews.published_at 为准，撤回即清空该列。
alter table reviews add column strengths text;
alter table reviews add column suggestions text;
alter table reviews add column original_subjective numeric(8,2);
alter table reviews add column published_at timestamptz;
