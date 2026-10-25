-- D-34（2026-10-25，决策 021）：试卷录入以 Markdown 原文为唯一内容源，题项是它的投影。
-- 四列全部可空：旧卷没有原文（无法凭空造原文），也没有目标总分/命题人/审题人，不做回填。
-- target_total_points 只是发布闸门，不参与 papers.total_points 的计算（那仍由 recalcTotal 算）。
alter table papers add column target_total_points numeric(8,2);
alter table papers add column author_name varchar(80);
alter table papers add column reviewer_name varchar(80);
alter table papers add column content_markdown text;

-- 替换重建（「生成考题」replace=true）前的旧内容快照：部分 + 题项 + 评分项序列化成一份 JSON。
-- 只用于误删恢复（v1 不提供撤销按钮），每卷保留最近 3 条，由应用侧写入后裁剪。
create table paper_content_snapshots (
  id uuid primary key,
  paper_id uuid not null references papers (id) on delete cascade,
  created_at timestamptz not null,
  reason varchar(40) not null,
  payload jsonb not null
);
create index paper_content_snapshots_paper_ix on paper_content_snapshots (paper_id, created_at desc);
