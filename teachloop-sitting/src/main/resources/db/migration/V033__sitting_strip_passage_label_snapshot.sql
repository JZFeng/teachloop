-- 修复：已发布场次的试卷快照里，阅读理解材料标签（单独一行 A–G）被拼进题干首行。
-- 只删除题干第一个字母行；答案、选项、分值、题项顺序与快照其它键一律不动。
update offerings o
set paper_snapshot = jsonb_set(o.paper_snapshot, '{items}', (
    select jsonb_agg(
               case
                   when (e ->> 'stem') ~ E'^[A-G][[:space:]]*\n'
                       and length(e ->> 'stem') > 80
                       then jsonb_set(e, '{stem}',
                               to_jsonb(regexp_replace(e ->> 'stem', E'^[A-G][[:space:]]*\n', '')))
                   else e
               end
               order by ord)
    from jsonb_array_elements(o.paper_snapshot -> 'items') with ordinality as t(e, ord)
))
where exists (
    select 1
    from jsonb_array_elements(o.paper_snapshot -> 'items') e
    where (e ->> 'stem') ~ E'^[A-G][[:space:]]*\n'
      and length(e ->> 'stem') > 80
);
