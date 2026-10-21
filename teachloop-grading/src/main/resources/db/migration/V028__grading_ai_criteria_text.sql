-- T253：ai_criteria 只是原样存取 + 应用层解析，用 text 存 JSON 字符串即可。
-- jsonb 会让 Hibernate 的 String 参数类型不匹配（varchar → jsonb 需要显式 cast），这里改回 text。
alter table reviews alter column ai_criteria type text;
