-- T268：教师确认、匿名化的参考范文（锚点）。默认空；只有非空时才进入 prompt。
-- 本期先用一个 text 字段承载 0–3 份范文 + 分数 + 评语，避免为一个可选功能增加关联表。
alter table offerings add column anchor_examples text;
