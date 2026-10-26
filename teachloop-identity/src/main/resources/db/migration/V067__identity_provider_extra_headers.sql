-- 决策 024：provider 级额外请求头（例如 opencode go 必需的 x-opencode-session）。
-- 值为 $uuid 时由运行时每次生成一个 UUID，避免把会话标识写死进配置。
alter table ai_providers add column extra_headers jsonb;
