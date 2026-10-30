-- 013-word-books 回滚脚本（T580 / spec §8、plan §7）
--
-- 顺序不能换：**先把 BOOK 来源行处理掉，再把 check 约束改回旧的三选一**。
-- 旧约束不含 'BOOK'，带着 BOOK 行去建旧约束会直接失败（这就是「回滚前必须处理 BOOK 行」的原因）；
-- 反过来，先回退代码再跑这个脚本时，旧代码的插入路径也不会撞上 BOOK 行。
--
-- 幂等：每一步都是 `if exists` / `not exists` 守卫，重复执行不报错（T580 验收要求）。
-- 数据安全：只删 013 新增的三张表与两个 book_id 列；`vocab_words` / `vocab_progress` /
-- `vocab_list_items` / `vocab_review_sessions` 一行不动（学生已加入的词与进度必须留着）。

-- 1) BOOK 来源行 → IMPORT（保留「这些词从哪来」的信息）；同一 (word, assignment, set, batch)
--    已经有 IMPORT 行时无法再改，交给下一步删除，避免撞 uk_vocab_word_sources。
-- 注意：SET 里不能写 `book_id = null`——第二次执行时该列已经不存在，语句会在解析阶段报错，
-- 脚本就不幂等了。book_id 由第 3 步整列删除，这里只改 kind。
update vocab_word_sources s
   set kind = 'IMPORT'
 where s.kind = 'BOOK'
   and not exists (
       select 1 from vocab_word_sources t
        where t.id <> s.id
          and t.word_id = s.word_id
          and t.kind = 'IMPORT'
          and t.assignment_id is not distinct from s.assignment_id
          and t.set_id is not distinct from s.set_id
          and t.batch_id is not distinct from s.batch_id);

-- 1b) 兜底：走不到上面那步的 BOOK 行（列已删或重复）直接删掉，保证后面能建旧约束
delete from vocab_word_sources where kind = 'BOOK';

-- 2) 来源表的 kind 约束恢复成 ASSIGNMENT / IMPORT / SELF
alter table vocab_word_sources drop constraint if exists vocab_word_sources_kind_check;
alter table vocab_word_sources
  add constraint vocab_word_sources_kind_check
  check (kind in ('ASSIGNMENT', 'IMPORT', 'SELF'));

-- 3) 来源表的 book_id 列
alter table vocab_word_sources drop column if exists book_id;

-- 4) 副本词单标记（先删唯一索引再删列）
drop index if exists uk_vocab_lists_user_book;
alter table vocab_lists drop column if exists book_id;

-- 5) 三张书表：joins → entries → books（外键方向决定顺序）
drop table if exists vocab_book_joins;
drop table if exists vocab_book_entries;
drop table if exists vocab_books;
