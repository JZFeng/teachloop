-- 011-self-study-mode / US-95：班级收费字段预留（v1 只允许 FREE，不做支付）
-- 表归属：classroom；全部带默认值，可在有数据的库上直接执行。
alter table classes add column pay_mode    text    not null default 'FREE';
alter table classes add column price_cents integer not null default 0;
alter table classes add column currency    text    not null default 'CNY';

alter table classes add constraint ck_classes_pay_mode check (pay_mode in ('FREE', 'PAID'));
alter table classes add constraint ck_classes_price    check (price_cents >= 0);
