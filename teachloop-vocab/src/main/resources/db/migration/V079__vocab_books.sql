-- 013-word-books：系统词书（全局只读）+ 加入记录 + 副本词单标记
-- 表归属：vocab；user_id / book_id 只存 UUID，不建跨模块外键。
create table vocab_books (
  id uuid primary key,
  slug varchar(64) not null unique,
  name varchar(80) not null,
  description varchar(300),
  language varchar(8) not null default 'en',
  level varchar(24),
  position int not null default 0,
  published boolean not null default false,
  pay_mode text not null default 'FREE',
  price_cents integer not null default 0,
  currency text not null default 'CNY',
  created_at timestamptz not null,
  updated_at timestamptz not null,
  constraint ck_vocab_books_pay_mode check (pay_mode in ('FREE', 'PAID')),
  constraint ck_vocab_books_price check (price_cents >= 0)
);
create index idx_vocab_books_published on vocab_books (position) where published;

create table vocab_book_entries (
  id bigserial primary key,
  book_id uuid not null references vocab_books (id) on delete cascade,
  form varchar(80) not null,
  normalized_form varchar(80) not null,
  position int not null default 0,
  note varchar(200),
  constraint uk_vocab_book_entries unique (book_id, normalized_form)
);
create index idx_vocab_book_entries_book on vocab_book_entries (book_id, position);

-- 加入记录：既是「加入人数」的来源，也是 v2 买断/订阅的挂载点（不进 v1 逻辑）
create table vocab_book_joins (
  book_id uuid not null references vocab_books (id) on delete cascade,
  user_id uuid not null,
  joined_at timestamptz not null,
  primary key (book_id, user_id)
);
create index idx_vocab_book_joins_user on vocab_book_joins (user_id);

-- 副本词单：一本书一个人只有一份（软删后名字可复用，故条件里带 not deleted）
alter table vocab_lists add column book_id uuid;
create unique index uk_vocab_lists_user_book
  on vocab_lists (user_id, book_id)
  where book_id is not null and not deleted;
