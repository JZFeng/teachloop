-- D-24 / D-25（T361）：写作题按题项存评分标准与参考范文。
-- rubric_markdown：只有 question_type = 'ESSAY' 有意义；判分优先用它，空则回退试卷级 rubric。
-- reference_markdown：只有 ESSAY 有意义；只给教师端看，不进 AI prompt、不进学生端。
-- 两列都不参与 papers.total_points（总分公式仍是 Σ item.points + Σ section_criteria.max_points）。
alter table objective_items add column rubric_markdown text;
alter table objective_items add column reference_markdown text;
