# 规格说明 — 系统词书（013-word-books）

- 特性编号：`013-word-books`
- 状态：**草案（2026-10-30，含 13:53 与 15:0x 两轮澄清），待 review**；未进入 Mock / Plan / Tasks
- 上游：2026-10-30 决策（只有管理员维护 / **加入后要能直接开始背** / 先手工 2–3 本 / **入口在词单页，不在词库** / **v1 免费、v2 upsell，v1 预留字段**）、[`docs/research/2026-10-30-word-books.md`](../../docs/research/2026-10-30-word-books.md)
- 相关规格：`004-vocab`、`011-self-study-mode`
- 编号区间（全局唯一，落笔前已取号）：US-100–105 / AC-100.x–105.x / FR-231–255
- 日期：2026-10-30

---

## 1. 摘要

给背单词加一层**全局只读词书**，并且让「加入」的结果**立刻可背**：

1. 管理员维护词书（词条、发布、覆盖率）；
2. 学生在**我的词单页**看到「推荐词书 / 猜你喜欢」，点进去预览、一键加入；
3. **加入 = 入词库（去重）+ 生成副本词单 + 立刻在这份副本上开始复习**；
4. 推荐规则走端口（v1：已发布 → 排序 → 加入人数；以后换成按水平推荐不改接口）；
5. v1 全部免费，但表上预留 `pay_mode / price_cents / currency`，与 011 的班级收费同一套做法。

| 议题 | 现在 | 本次之后 |
| :--- | :--- | :--- |
| 词书实体 | 没有 | `vocab_books` + `vocab_book_entries` |
| 谁维护 | — | 只有 `ADMIN` |
| 入口位置 | — | **我的词单页**（「新建词单」旁边），**词库页不放** |
| 加入结果 | 只能自己粘贴/上传 | 入词库 + 副本词单 + 直接开复习会话 |
| 推荐规则 | — | `Ports.BookRecommendation`，v1 按「已发布 → position → 加入人数」 |
| 来源记录 | `ASSIGNMENT / IMPORT / SELF` | 新增 `BOOK` |
| 内容 | 按需生成 | 不变；书详情显示覆盖率；加入不触发全量生成 |
| 收费 | — | 三列预留（`FREE / 0 / CNY`），v1 不开付费 |

**核心不变式**：书全局、进度个人；加入只追加不覆盖；推荐规则可替换、不硬编码。

## 2. 角色

| 角色 | 能做 | 不能做 |
|---|---|---|
| **学生** | 在词单页看推荐书、预览、加入、在副本上复习 | 改书；看草稿/下架书 |
| **教师** | 与学生相同 | 建公共词书 |
| **管理员** | 建/改/下架书、导入词条、看覆盖率、看加入人数 | 物理删除书 |

## 3. 用户故事与验收标准

### US-100 管理员维护系统词书

- [ ] AC-100.1 管理端接口全部在 `/api/admin/vocab/books*`，非 `ADMIN` 调用返回 403。
- [ ] AC-100.2 管理员可新建/修改/下架书；下架后学生端列表、详情与推荐都返回 404 或不出现在列表里，不泄露存在性。
- [ ] AC-100.3 词条支持批量导入（复用现有 CSV/粘贴解析器）：同书内 `normalized_form` 唯一；返回 `created / duplicated / invalid`。
- [ ] AC-100.4 管理端书详情显示覆盖率 `就绪 N / 总数 M`，N 与内容索引实际命中一致（抽 10 词人工复核），同时显示「已加入人数」。
- [ ] AC-100.5 书只能下架、不能物理删除；下架后已加入学生的词、副本词单、`BOOK` 来源记录保持不变。
- [ ] AC-100.6 管理端「导入词条」与「加词」页（`/vocab/add?tab=paste`）共用同一套粘贴/上传、格式清洗、拼写检查与跳过提示；两者只有提交目标不同（书 vs 我的词库）。

### US-101 学生浏览词书并预览

- [ ] AC-101.1 学生端只列 `published=true` 的书，按 `position` 升序；草稿书不出现在列表、搜索与推荐里。
- [ ] AC-101.2 书详情显示名称、简介、词数、覆盖率、我是否已加入（部分加入显示「已加入 N / M」）。
- [ ] AC-101.3 预览按每页 50 词加载，每行显示词形与「内容就绪 / 生成中」；末页显示「共 N 词」。
- [ ] AC-101.4 平板：834×1194 与 1024×1366 下 `scrollWidth <= innerWidth + 2`，且「加入并开始背」在首屏可见。

### US-102 学生加入词书，并立刻在副本上开始背

- [ ] AC-102.1 点「加入并开始背」发起一次请求，返回 `created / duplicated / total / listId`；完成后按钮变「继续背」。
- [ ] AC-102.2 幂等：第二次点击 `created = 0`、`duplicated = total`，且**不新建第二份副本词单**（同一本书同一学生只有一份）。
- [ ] AC-102.3 已在库的词只追加 `kind='BOOK'` 来源，`vocab_progress` 的 `due_at` / `state` / `interval_days` 在加入前后完全一致。
- [ ] AC-102.4 副本可直接开始复习：会话包含书中**全部词条**（含加入前已在库的），`总张数 = 书词数`，不含书外词。
- [ ] AC-102.5 副本词单名默认等于书名；同名已存在时自动加后缀，不覆盖已有词单。

### US-103 加入不触发全量内容生成

- [ ] AC-103.1 加入一本 500 词的词书后，`vocab_generation_jobs` 不新增 500 条。
- [ ] AC-103.2 学生打开未生成内容的词时走现有按需生成路径（FRONT 优先级，不受日预算限制）。
- [ ] AC-103.3 加入前后书详情页覆盖率不变。

### US-104 我的词单页推荐词书

作为新用户，我打开「我的词单」想开始背点什么，不想看到空页面。

- [ ] AC-104.1 「我的词单」页在**词单列表下方**显示推荐区：2 本书（名称、词数、覆盖率）+「加入并开始背」（2026-10-30 调整：先看自己的东西，再看推荐，与电商「猜你喜欢」同位）。
- [ ] AC-104.2 我的词单为空时推荐区仍然显示（不是只在空态出现），点任意一本等价于在书详情页加入。
- [ ] AC-104.3 推荐由 `Ports.BookRecommendation` 提供；v1 规则为「已发布 → `position` 升序 → 加入人数降序」取前 2，规则变化不得改变接口形状（返回 `List<BookRef>`）。
- [ ] AC-104.4 推荐区加载失败时只影响该区块（显示重试），新建词单与词单列表照常可用。
- [ ] AC-104.5 **我的词库页不放推荐与词书入口**；空词库时只保留原有「加词 / 导入词表」两个动作，另加一行文字链接「还没有词？去词单页挑一本词书」。

### US-105 词书预留收费字段（v1 不开付费）

- [ ] AC-105.1 `vocab_books` 增加 `pay_mode`（默认 `FREE`）、`price_cents`（默认 0）、`currency`（默认 `CNY`）；存量书全部为 `FREE/0/CNY`。
- [ ] AC-105.2 建书传 `pay_mode != FREE` 返回 400（v1 不支持创建付费书）。
- [ ] AC-105.3 加入 `pay_mode != FREE` 的书返回 409「付费词书暂未开放」。
- [ ] AC-105.4 前端不出现价格、购买或升级入口；`pay_mode` 不出现在学生端响应里（只在管理端可见）。

## 4. 功能需求

| 编号 | 需求 |
|---|---|
| FR-231 | 新增 `vocab_books`（`id/slug/name/description/language/level/position/published/时间戳`），属主 vocab |
| FR-232 | 新增 `vocab_book_entries`（`book_id/form/normalized_form/position/note`），同书 `normalized_form` 唯一 |
| FR-233 | 扩展 `vocab_word_sources.kind` 加入 `BOOK`，新增可空 `book_id` 列 |
| FR-234 | 管理端接口 `/api/admin/vocab/books*`，统一 `ADMIN` 判权 |
| FR-235 | 只能下架、不提供物理删除；下架不清学生数据 |
| FR-236 | 学生端只返回 `published=true`，按 `position` 排序；草稿与下架一律 404 |
| FR-237 | 加入按 `(user_id, language, normalized_form)` 去重，单事务、写库分批（每批 ≤500 行） |
| FR-238 | 加入只追加来源，不写不改 `vocab_progress` |
| FR-239 | 加入不触发全量内容生成（不调用 `enqueueNewWords`） |
| FR-240 | 覆盖率由内容索引实时计算，不落库 |
| FR-241 | 词条导入复用 `WordImportService` 的解析路径，**界面复用 `/vocab/add` 的粘贴/上传与预览组件**，不写第二套解析与第二套预览 |
| FR-242 | 加入返回 `created/duplicated/total/listId` |
| FR-243 | 文案进 `vocab.books.*`，中英同步 |
| FR-244 | 新增页面在 834×1194 与 1024×1366 无横向溢出，主操作在首屏 |
| FR-245 | 发布、下架、词条增删写审计日志（`AuditPort`） |
| FR-246 | 词书不参与发词与作业：`vocab_sets` / `vocab_assignments` 行为不变 |
| FR-247 | `vocab_lists` 增加可空 `book_id` + 唯一索引 `(user_id, book_id) where book_id is not null and not deleted` |
| FR-248 | 复习会话支持按 `listId` 创建（服务端展开），沿用单会话上限 1000 词，超出分批 |
| FR-249 | 加入复用 `POST /api/vocab/lists/{id}/words { forms }` 的入库路径，只把来源标为 `BOOK` |
| FR-250 | 推荐走 `Ports.BookRecommendation`（shared 只放 record 与接口），v1 实现放 vocab；不得在控制器里写排序规则 |
| FR-251 | 新增 `vocab_book_joins(book_id, user_id, joined_at)`，唯一 `(book_id, user_id)`；作为「加入人数」与 v2 买断/订阅的挂载点 |
| FR-252 | 「加入人数」= `count(*) from vocab_book_joins`，不新增计数表、不在请求里扫词库 |
| FR-253 | `vocab_books` 增加 `pay_mode/price_cents/currency`（默认 `FREE/0/CNY`），迁移可在有数据的库执行 |
| FR-254 | v1 只允许 `FREE`：建书 `PAID` → 400；加入 `PAID` → 409；判定在服务端，不靠前端隐藏 |
| FR-255 | 学生端响应不含 `pay_mode/price_cents`；管理端可见（v2 上架时再改这条） |

## 5. 界面与交互

- **入口在「我的词单」页**（`/vocab/lists`）：先「我的词单」列表，**下面是推荐区**（2 本词书卡片）。词库页**不放**词书入口，词库保持「总览 + 筛选 + 删除」的单一职责（AC-104.5）。
- **书详情**：头部件（名称、简介、词数、覆盖率）+ 预览列表（50/页）+ 底部固定**只放主操作**「加入并开始背 / 继续背」；不设「先不加入」——它与页头「← 我的词单」重复，多一个按钮只会让主操作变弱。
- **副本词单**：在词单列表里展示，带「词书副本」标签；点「继续背」直接进复习会话。
- **管理端**：书列表（草稿标记、加入人数）+ 编辑页（元信息 / 覆盖率 / 发布开关 / 「导入词条」按钮）；**导入复用「加词」页**（`/vocab/add?tab=paste`）的粘贴/上传与预览，只把提交目标换成书。
- **空态**：词单页空态仍显示推荐区与新建入口；词库空态只留「加词 / 导入」+ 一行指向词单页的文字链接。
- 平板两档断点（`1100` / `1023`）；预览列表在 834 宽可横向滚动，不得整页溢出。
- Mock：**待画**（4 屏：词单页含推荐、书详情、副本复习、管理端编辑）。

## 6. 非目标

- **v1 不做支付**：没有订单、没有购买按钮、没有退款；只预留三列与 `vocab_book_joins` 挂载点。
- 课程与词书的绑定关系（v2 与班级收费一起设计，届时另开规格）。
- 教师自建公共词书；教师词集语义不变。
- 词书版本历史与差异合并。
- 外部词表自动抓取与版权合规。
- 按水平智能推荐（v1 固定规则；端口形状保证以后替换）。
- 加入时按难度/频段筛选子集。

## 7. 数据与接口

### 7.1 表

```sql
-- V0xx__vocab_books.sql（序号合并前重排）
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
create index idx_vocab_books_published on vocab_books (position) where published;

-- 加入记录：既是「加入人数」的来源，也是 v2 买断/订阅的挂载点
create table vocab_book_joins (
  book_id uuid not null references vocab_books (id) on delete cascade,
  user_id uuid not null,
  joined_at timestamptz not null,
  primary key (book_id, user_id)
);
create index idx_vocab_book_joins_user on vocab_book_joins (user_id);

-- 来源类型扩展（V041:31 现在是 ASSIGNMENT / IMPORT / SELF）
alter table vocab_word_sources add column book_id uuid;
alter table vocab_word_sources drop constraint vocab_word_sources_kind_check;
alter table vocab_word_sources
  add constraint vocab_word_sources_kind_check
  check (kind in ('ASSIGNMENT', 'IMPORT', 'SELF', 'BOOK'));

-- 副本词单（FR-247）
alter table vocab_lists add column book_id uuid;
create unique index uk_vocab_lists_user_book
  on vocab_lists (user_id, book_id)
  where book_id is not null and not deleted;
```

### 7.2 接口

| 方法 | 路径 | 权限 | 语义 |
|---|---|---|---|
| `GET` | `/api/admin/vocab/books` | ADMIN | 书列表 + 覆盖率 + 加入人数 |
| `POST` | `/api/admin/vocab/books` | ADMIN | 建书 |
| `PUT` | `/api/admin/vocab/books/{id}` | ADMIN | 改元信息 / 发布 / 下架 |
| `POST` | `/api/admin/vocab/books/{id}/entries` | ADMIN | 批量导入词条 |
| `DELETE` | `/api/admin/vocab/books/{id}/entries/{entryId}` | ADMIN | 删单条词条 |
| `GET` | `/api/vocab/books` | 登录用户 | 已发布书列表 |
| `GET` | `/api/vocab/books/recommended` | 登录用户 | 走推荐端口，返回 2 本 |
| `GET` | `/api/vocab/books/{id}` | 登录用户 | 详情 + 覆盖率 + 我是否加入 |
| `GET` | `/api/vocab/books/{id}/entries?page=` | 登录用户 | 预览（50/页） |
| `POST` | `/api/vocab/books/{id}/join` | 登录用户 | 加入：入词库 + 副本 + 返回 `listId` |
| `POST` | `/api/vocab/review-sessions` | 登录用户 | 增加可选 `listId` |

### 7.3 推荐端口

```java
// shared
public record BookRef(UUID id, String name, int wordCount, int readyCount) {}
public interface BookRecommendation {
    List<BookRef> recommended(UUID userId, int limit);
}
```

- v1 实现：`published=true` → `position` → `vocab_book_joins` 计数降序，取 2；不足 2 本时有几本给几本。
- v2 换规则（按水平、按班级、按课程）只换实现，控制器与前端不动。

## 8. 迁移与兼容

- 三张新表 + 两列（`vocab_lists.book_id`、`vocab_word_sources.book_id`）+ 一条 check 放宽；全部可空或带默认值，存量不变。
- 回滚：先回退代码，再删表/列；`BOOK` 来源行在回滚前保留（删了会丢失来源信息）。
- 与 012 无耦合；与 011 只在「词单页」有交集（011 不新增词单页元素，013 加推荐区）。

## 9. 待定

1. 推荐区显示 2 本还是 3 本（当前 2 本，来自 011 原型里的两卡位置）。
2. 单书规模上限：暂定 ≤2000 词（会话上限 1000，必然分批）。
3. 覆盖率低于阈值时是否禁止加入：暂不禁止，只提示。
4. v2 付费的形态（单本买断 / 订阅 / 与课程绑定）——本期只留 `pay_mode` 与 `vocab_book_joins` 两个挂载点。

## 10. 自检

```bash
grep -c '^### US-' specs/013-word-books/spec.md        # 期望 6
grep -cE '^- \[ \] AC-' specs/013-word-books/spec.md   # 期望 27
grep -c '^| FR-' specs/013-word-books/spec.md          # 期望 25
grep -ho '^- \[ \] AC-[0-9]*\.[0-9]*' specs/*/spec.md | sort | uniq -d   # 期望只剩存量 AC-27.x / 28.x
```
