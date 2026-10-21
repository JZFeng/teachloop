-- T234：Spring Session JDBC 的会话属性写入不是幂等的。
-- 同一 session 被两个并发请求同时写「新」属性时，两边都走 INSERT，第二条撞
-- spring_session_attributes_pk 抛 500（症状：偶发「出错了」，日志里
-- duplicate key value violates unique constraint "spring_session_attributes_pk"）。
--
-- 在库这一层把 INSERT 变成 upsert：行已存在就覆盖 attribute_bytes，不再插入。
-- 放在触发器里而不是改 Spring Session 的 SQL，是因为这两个表由 V002 建、框架自己写，
-- 我们只需要让「同一 (session, 属性名) 写两次」变成无害。
create or replace function spring_session_attributes_upsert() returns trigger as $$
begin
    if exists (select 1 from spring_session_attributes
                where session_primary_id = new.session_primary_id
                  and attribute_name = new.attribute_name) then
        update spring_session_attributes
           set attribute_bytes = new.attribute_bytes
         where session_primary_id = new.session_primary_id
           and attribute_name = new.attribute_name;
        return null; -- 已经写过，吞掉这次 INSERT
    end if;
    return new;
end;
$$ language plpgsql;

create trigger spring_session_attributes_upsert
    before insert on spring_session_attributes
    for each row execute function spring_session_attributes_upsert();
