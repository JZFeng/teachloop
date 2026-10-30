# 实施计划 — 系统词书（013-word-books）

- 上游：`specs/013-word-books/spec.md`（6 US / 27 AC / 25 FR）、`docs/mock/word-books-2026-10-30/`、`docs/research/2026-10-30-word-books.md`
- 状态：**草案（2026-10-30），待 review**；本文件通过后才拆 Tasks
- 配套：宪法第十四条（模块边界）、第十五条（UI）
- 迁移序号：写作时全库最大 `V075`；011 计划占 `V076–V078`，本计划取 `V079 / V080`；**合并前按 main 现状重排**
- 依赖：011（词单页与背单词模块壳）已上线或同期；012 无耦合

---

## 1. 架构总览

全部落在 `teachloop-vocab` 模块内（书、条目、加入记录都属 vocab），不新建模块：

```
学生端  /vocab/lists ──推荐区──▶ GET /api/vocab/books/recommended
        /vocab/books/{id} ─────▶ GET /api/vocab/books/{id}  (详情 + 覆盖率)
        [加入并开始背] ────────▶ POST /api/vocab/books/{id}/join
                                     │ 事务内：入词库（去重）→ 建/复用副本词单 → 写 joins
                                     ▼
                              返回 listId → POST /api/vocab/review-sessions { listId }

管理端  /api/admin/vocab/books*  ──▶ 书 CRUD / 发布 / 导入条目 / 覆盖率
                                   （导入复用 /vocab/add 的解析与预览组件）
```

- **不新增端口给业务模块间互访**：书与词库同属 vocab，内部直接调用 Repository。
- 唯一新端口是 `Ports.BookRecommendation`（shared 只放 record 与接口，实现留在 vocab），为 v2「按水平推荐」留替换点。
- 不碰 `teachloop-sitting` / `identity` 的任何表；不参与发词与作业（FR-246）。

## 2. 数据模型

### 2.1 表与列

```sql
-- V079__vocab_books.sql
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
  constraint ck_vocab_books_pay_mode check (pay_mode in ('FREE','PAID')),
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

create table vocab_book_joins (
  book_id uuid not null references vocab_books (id) on delete cascade,
  user_id uuid not null,
  joined_at timestamptz not null,
  primary key (book_id, user_id)
);
create index idx_vocab_book_joins_user on vocab_book_joins (user_id);

-- 副本词单标记
alter table vocab_lists add column book_id uuid;
create unique index uk_vocab_lists_user_book
  on vocab_lists (user_id, book_id)
  where book_id is not null and not deleted;

-- V080__vocab_word_sources_book.sql
alter table vocab_word_sources add column book_id uuid;
alter table vocab_word_sources drop constraint vocab_word_sources_kind_check;
alter table vocab_word_sources
  add constraint vocab_word_sources_kind_check
  check (kind in ('ASSIGNMENT','IMPORT','SELF','BOOK'));
```

### 2.2 状态迁移

| 实体 | 前 | 后 | 触发 |
|---|---|---|---|
| `vocab_books.published` | `false` | `true` | 管理员发布 |
| `vocab_books.published` | `true` | `false` | 管理员下架（学生侧立即 404，已加入数据不动） |
| `vocab_lists.book_id` | `null` | 书 id | 首次加入（一本书一份副本，唯一索引兜底） |
| `vocab_book_joins` | 无行 | 一行 | 首次加入（重复加入不新增行） |
| `vocab_word_sources.kind` | 三选一 | 增加 `BOOK` | 加入时逐词追加来源 |

## 3. 端口

```java
// teachloop-shared/.../port/Ports.java
public record BookRef(UUID id, String name, int wordCount, int readyCount) {}
public interface BookRecommendation {
    List<BookRef> recommended(UUID userId, int limit);
}
```

- 实现：`teachloop-vocab/…/books/BookRecommendationImpl`。
- v1 规则：`published=true` → `position` 升序 → `vocab_book_joins` 计数降序 → 取 `limit`；不足则返回现有的本数。
- 控制器**不得**出现排序或 SQL（`check_module_isolation` + 代码评审双保险）。
- 拆服务时这个端口就是 HTTP 契约（record 已是 DTO）。

## 4. 关键流程

### 4.1 加入（`POST /api/vocab/books/{id}/join`）

一个事务，按顺序：

1. 校验：书存在且 `published`；`pay_mode=FREE`（否则 409，FR-254）。
2. 取条目词形（`vocab_book_entries`），按 `normalized_form` 去重后分批（≤500）写入 `vocab_words`：
   - 已存在 → 跳过（不动进度）；
   - 不存在 → 新建词条（`state=NEW`、`due_at=now`）。
3. 逐词追加 `vocab_word_sources(kind='BOOK', book_id, word_id)`；同词重复加入不重复插（唯一键 `(word_id,kind,assignment_id,set_id)` 已覆盖，`book_id` 参与去重需在实现时确认：**若唯一键不含 book_id，改用 `on conflict do nothing` 并按（word_id, book_id）判重**）。
4. 建/复用副本词单：按 `(user_id, book_id)` 查；不存在则建 `vocab_lists(book_id, name=书名)`，同名冲突加后缀（AC-102.5）；把本次词条全部 `vocab_list_items`（已存在则忽略）。
5. `vocab_book_joins` upsert（不存在才插，保留首次 `joined_at`）。
6. **不调用** `enqueueNewWords`（FR-239）。
7. 返回 `created / duplicated / total / listId`。

幂等：第二次调用 `created=0`、`duplicated=total`，词单与 joins 都复用。

### 4.2 导入（管理端，复用加词页）

- 前端复用 `/vocab/add` 的粘贴/上传与预览组件，路由加 `?target=book:{id}`。
- 预览接口：`POST /api/admin/vocab/books/{id}/entries/preview`（或复用 `/api/vocab/import/preview` 后在提交时指定目标）。**首选复用现有 preview 接口**，只在 commit 时区分目标——预览的语义与「我的词库」完全一致。
- 提交：`POST /api/admin/vocab/books/{id}/entries` 接收同一份 `text`，内部走 `WordImportService` 解析后写入 `vocab_book_entries`（不写 `vocab_words`）。
- 返回 `created / duplicated / invalid`，与 AC-100.3 一致。

### 4.3 覆盖率

`ready = 命中内容索引的条目数`，`total = 条目数`；**请求时实时计算，不落库**。
实现上按书条目批量查内容索引（内存 Map，`ContentView` / 内容包），避免逐词一次 IO。管理端与学生端共用同一个 `CoverageService`，防止两个口径。

## 5. 界面

与 mock 一致（`docs/mock/word-books-2026-10-30/`）：

| 屏 | 关键约束 |
|---|---|
| 我的词单 + 推荐 | 先词单后推荐；推荐 2 本；空态仍显示推荐 |
| 书详情 | 预览 50/页；底部只放主操作；不设「先不加入」 |
| 副本复习 | 全屏无侧栏；标题带书名与张数 |
| 管理端 | 列表 + 编辑；保存条常驻 |
| 导入词条 | 复用加词页；确认条常驻 |

平板：834×1194 与 1024×1366 下 `scrollWidth <= innerWidth + 2`、首屏外按钮 0（mock 已实测 18 组）。

## 6. 失败与降级

| 环节 | 失败时 | 重试 |
|---|---|---|
| 内容索引不可用 | 覆盖率显示「—」，不显示 0%；加入仍可用 | 下次请求重算 |
| 加入超时（大书 2000 词） | 事务回滚，返回 409/504 与「分批加入」提示 | 用户重试；实现上加 30s 事务超时保护 |
| 推荐端口异常 | 推荐区显示重试，新建词单照常 | 用户点重试 |
| 词单同名冲突 | 自动加后缀，不报错 | — |
| 管理端导入解析失败 | 返回 `invalid` 计数与行号，不写库 | 修改后重试 |

## 7. 迁移与回滚

| 迁移 | 内容 | 回滚 |
|---|---|---|
| `V079__vocab_books.sql` | 3 张表 + `vocab_lists.book_id` + 唯一索引 | 先回退代码，再 `drop index` / `drop column` / `drop table`（顺序：joins → entries → books） |
| `V080__vocab_word_sources_book.sql` | `book_id` 列 + 放宽 check | 先确认无 `BOOK` 行或先改回 `IMPORT`，再删列 |

- 回滚代码后，`BOOK` 来源行会让旧约束报错（旧 check 不含 `BOOK`）→ **回滚前必须先把 `BOOK` 行改成 `IMPORT` 或删除**。这一条写进回滚脚本，不做口头约定。
- `check-boundaries.py` 的 `TABLE_OWNER` 必须同时登记 `vocab_books` / `vocab_book_entries` / `vocab_book_joins`，否则 `make test` 直接红（迁移触碰未登记表）。
- 新表前缀 `vocab_` 与模块前缀一致，`check_migrations` 的命名规则要求文件名为 `V0xx__vocab_*.sql`。

## 8. 测试策略

| 层 | 覆盖 |
|---|---|
| 单测 | 加入幂等（两次加入 created/duplicated）；同名词单后缀；覆盖率批量计算；推荐排序（同 position 时按 joins） |
| 集成 | `POST /join` 事务：中途失败不留下半份副本；`BOOK` 来源写入；`vocab_progress` 前后一致 |
| 前端纯函数 | 推荐区排序展示、预览分页、三态计数展示 |
| E2E | 词单页 → 加入 → 副本出现 → 开始复习（Playwright）；平板两档断言 |
| 守卫 | `make check-deps check-boundaries check-i18n check-ui` |

## 9. Plan review 清单（6 条）

| # | 条目 | 结论 | 证据 |
|---|---|---|---|
| 1 | 每条 AC 能指出表/流程承接 | ✅ | §10 映射表，27 条全覆盖 |
| 2 | 表、字段、索引、状态迁移写全 | ✅ | §2：3 表 + 2 列 + 4 索引 + 状态迁移表 |
| 3 | 外部依赖失败与重试路径 | ✅ | §6；本计划无新增外部服务 |
| 4 | 模块依赖无环、跨模块只走端口 | ✅ | 全部在 vocab 内；唯一跨模块面是 `BookRecommendation`（shared） |
| 5 | 每表一个属主、无跨模块 JOIN | ✅ | 3 张新表均属 vocab；无 join `users`/`classes` |
| 6 | 拆服务路径写得出 | ✅ | `BookRecommendation` 即 HTTP 契约；书与词库同库，拆分时按 `vocab_*` 整体迁出 |

## 10. AC → 承接

| AC | 承接 |
|---|---|
| AC-100.1–100.5 | §4.2 管理端 + `published` 状态机 + 覆盖率 §4.3 |
| AC-100.6 | §4.2 复用加词页（同一 preview 组件，commit 目标不同） |
| AC-101.1–101.4 | §5 学生端书列表/详情；预览分页；平板断言 |
| AC-102.1–102.5 | §4.1 加入事务（幂等、来源、副本、会话） |
| AC-103.1–103.3 | §4.1 第 6 步（不入队）+ 按需生成路径不变 + 覆盖率实时 |
| AC-104.1–104.5 | §5 词单页推荐区（下方）+ 推荐端口 + 区块降级 + 词库只留链接 |
| AC-105.1–105.4 | §2.1 三列 + §4.1 第 1 步校验 + 学生端响应裁剪 |

## 11. 待定

1. 大书加入的事务上限（≤2000 词一次做完，还是拆成两段「先入词库、再建副本」）；倾向一次做完 + 30s 超时。
2. `vocab_word_sources` 的唯一键是否要包含 `book_id`（决定「同一本书重复加入」是否产生多行来源）；实现前需看现有唯一键定义。
