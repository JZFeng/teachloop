-- 历史数据回填：V012 之前提交的作答 correct 默认 false。
-- 只用与提交时一致的简单策略（单选/填空大小写+空白归一；多选忽略顺序），
-- 复杂匹配策略（normalized/set）无法在 SQL 里还原，回填后仍以新提交为准。
update objective_answers oa
set correct = true
from objective_items oi
where oi.id = oa.item_id
  and oi.question_type in ('SINGLE_CHOICE', 'FILL_BLANK')
  and oi.answer_key is not null
  and lower(btrim(oa.answer)) = lower(btrim(oi.answer_key));

update objective_answers oa
set correct = true
from objective_items oi
where oi.id = oa.item_id
  and oi.question_type = 'MULTIPLE_CHOICE'
  and oi.answer_key is not null
  and (select string_agg(ch, '' order by ch)
       from regexp_split_to_table(upper(oa.answer), '[^A-Z]+') ch
       where ch <> '')
      = (select string_agg(ch, '' order by ch)
         from regexp_split_to_table(upper(oi.answer_key), '[^A-Z]+') ch
         where ch <> '')
  and (select string_agg(ch, '' order by ch)
       from regexp_split_to_table(upper(oa.answer), '[^A-Z]+') ch
       where ch <> '') <> '';
