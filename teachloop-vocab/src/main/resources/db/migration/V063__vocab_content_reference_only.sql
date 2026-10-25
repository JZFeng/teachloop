-- T431 / decisions/017 P3：用户词条只保留全局内容引用，删除各自的内容副本。
--
-- 先把存量副本搬进 legacy 表（只搬有内容的行），应用启动时 ContentBackfillRunner
-- 逐行写入全局 delta 并回填 content_id，全部成功后才 drop legacy 表；回填失败则
-- 启动失败、legacy 表保留供下次重试。因此这里**不能**在同一条迁移里删 legacy 表。
create table vocab_words_content_legacy as
  select id, language, normalized_form, content
    from vocab_words
   where content is not null;

alter table vocab_words drop column content;
