-- 修复：阅读理解的材料标签（单独一行 A–G）曾被当作材料内容拼进题干首行，
-- 学生答题页每道题开头因此多出一个孤立字母，看起来像标准答案。
-- 只处理「首行是单字母 + 剩余题干明显是篇章」的记录，短题干不动。
update objective_items
set stem = regexp_replace(stem, E'^[A-G][[:space:]]*\n', '')
where stem ~ E'^[A-G][[:space:]]*\n'
  and length(stem) > 80;
