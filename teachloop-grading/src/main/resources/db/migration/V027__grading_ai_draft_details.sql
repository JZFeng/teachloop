-- T253：AI 判分的逐项意见与评语要能被老师看到，不能只留一个总分。
-- criteria 是 [{name, points, maxPoints, comment}] 的 JSON 数组；feedback 是「优点/建议」文本。
alter table reviews add column ai_criteria jsonb;
alter table reviews add column ai_feedback text;
