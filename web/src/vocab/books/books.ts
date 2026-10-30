/**
 * 学生端「系统词书」接口（013 / T574–T576）。
 *
 * 契约（`specs/013-word-books/spec.md` §7.2，字段名以契约为准，不要在前端猜）：
 *   GET  /api/vocab/books                    已发布书列表
 *   GET  /api/vocab/books/recommended?limit= 推荐（走推荐端口，v1 最多 2 本，可能 0..2）
 *   GET  /api/vocab/books/{id}               详情 + 覆盖率 + 我的加入进度
 *   GET  /api/vocab/books/{id}/entries?...   预览（每页 50）
 *   POST /api/vocab/books/{id}/join          加入：入词库 + 建副本词单
 *   POST /api/vocab/review-sessions {listId} 在副本词单上开一轮复习（沿用既有会话）
 *
 * 学生端响应里**没有** `payMode / priceCents`（AC-105.4 / FR-255）：后端不返回，
 * 这里也不定义这两个字段——前端拿不到价格，就不会有人误做一个购买按钮。
 *
 * 管理端接口在 `adminBooks.ts`，两个文件分开是因为调用方与判权完全不同。
 */
import { api } from '../../api/client'

/** 书详情预览的每页词数（AC-101.3 固定 50）。 */
export const BOOK_ENTRIES_PAGE = 50

/** 推荐区最多显示的本书（AC-104.1）。 */
export const RECO_LIMIT = 2

export interface BookSummary {
  id: string
  name: string
  description: string | null
  language: string
  level: string | null
  wordCount: number
  readyCount: number
  joined: boolean
}

export interface BookDetail extends BookSummary {
  /** 书里已经在我词库里的词条数（部分加入时 < wordCount）。 */
  joinedEntries: number
  /** 已加入时是副本词单 id；未加入是 null。 */
  listId: string | null
}

export interface BookEntry {
  id: string
  form: string
  position: number
  /** 内容索引里已命中：true = 内容就绪，false = 生成中。 */
  ready: boolean
}

export interface BookEntryPage {
  items: BookEntry[]
  total: number
  page: number
  size: number
  /** 这本书的词条总数（与分页无关，末页「共 N 词」用它）。 */
  totalWords: number
}

export interface BookJoinResult {
  created: number
  duplicated: number
  total: number
  listId: string
}

/** 覆盖率百分比（取整；0 词时按 0，不返回 NaN）。 */
export function coveragePercent(ready: number, total: number): number {
  if (!Number.isFinite(ready) || !Number.isFinite(total) || total <= 0) return 0
  return Math.round((Math.max(0, Math.min(ready, total)) / total) * 100)
}

export async function fetchBooks(): Promise<BookSummary[]> {
  return (await api.get<BookSummary[] | null>('/api/vocab/books')) ?? []
}

/** 推荐区：后端最多给 `limit` 本，0 本是正常结果（新库/全下架），不是错误。 */
export async function fetchRecommendedBooks(limit: number = RECO_LIMIT): Promise<BookSummary[]> {
  const query = new URLSearchParams({ limit: String(limit) })
  return (await api.get<BookSummary[] | null>(`/api/vocab/books/recommended?${query.toString()}`)) ?? []
}

export async function fetchBook(bookId: string): Promise<BookDetail> {
  return api.get<BookDetail>(`/api/vocab/books/${bookId}`)
}

export async function fetchBookEntries(
  bookId: string,
  page = 1,
  size: number = BOOK_ENTRIES_PAGE,
): Promise<BookEntryPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
  const value = await api.get<Partial<BookEntryPage>>(`/api/vocab/books/${bookId}/entries?${query.toString()}`)
  const items = Array.isArray(value?.items) ? value.items : []
  const total = typeof value?.total === 'number' ? value.total : items.length
  return {
    items,
    total,
    page: typeof value?.page === 'number' ? value.page : page,
    size: typeof value?.size === 'number' ? value.size : size,
    totalWords: typeof value?.totalWords === 'number' ? value.totalWords : total,
  }
}

/**
 * 加入一本书：入词库（去重）+ 建副本词单，返回四元组（AC-102.1）。
 * 幂等（AC-102.2）：第二次调用 `created = 0`、`duplicated = total`，不新建第二份副本。
 * 失败：404（书不存在/已下架）、409（付费书或词单数达上限）——都直接抛 `ApiError`。
 */
export async function joinBook(bookId: string): Promise<BookJoinResult> {
  return api.post<BookJoinResult>(`/api/vocab/books/${bookId}/join`)
}

/** 在副本词单上开一轮复习：服务端按 `listId` 展开词条，沿用 1000 词上限（T576）。 */
export async function createBookReviewSession(listId: string): Promise<string> {
  const session = await api.post<{ id: string }>('/api/vocab/review-sessions', { listId })
  return session.id
}

export interface BookReviewStart {
  sessionId: string
  listId: string
  /** 这次点击触发了一次加入（已加入时是 undefined）。 */
  join: BookJoinResult | null
}

/**
 * 「加入并开始背 / 继续背」的完整链路：详情 → 未加入则加入 → 按 listId 开会话。
 *
 * 三个入口（推荐卡、书详情、副本复习页）都走这一个函数，避免「加入」与「开会话」
 * 在三个页面各写一遍、各有各的失败处理。
 */
export async function startBookReview(bookId: string): Promise<BookReviewStart> {
  const detail = await fetchBook(bookId)
  let listId = detail.listId
  let join: BookJoinResult | null = null
  if (!listId) {
    join = await joinBook(bookId)
    listId = join.listId
  }
  const sessionId = await createBookReviewSession(listId)
  return { sessionId, listId, join }
}
