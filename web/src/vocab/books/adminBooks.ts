/**
 * 管理端「系统词书」接口（013 / T570–T571）。
 *
 * 契约：`specs/013-word-books/spec.md` §7.2，全部在 `/api/admin/vocab/books*`，
 * 非 ADMIN 由后端统一回 403（前端不做第二套判权，路由守卫归 S1）。
 *
 * v1 只允许 `FREE`：建书传 `payMode != 'FREE'` 由后端回 400，所以界面**没有**价格输入
 * （AC-105.4 / FR-254）。`payMode / priceCents / currency` 只在这里的定义里出现，
 * 学生端 `books.ts` 里没有这三个字段。
 *
 * 词条导入复用「加词」页的粘贴/上传与预览：预览走 `{id}/entries/preview`，响应形状与
 * `/api/vocab/import/preview` 完全一致（AC-100.6），所以两处的预览面板是同一个组件
 * （`web/src/vocab/import/ImportPastePanel.tsx`）。
 */
import { api } from '../../api/client'

export type PayMode = 'FREE' | 'PAID'

export interface AdminBook {
  id: string
  slug: string
  name: string
  description: string | null
  language: string
  level: string | null
  position: number
  published: boolean
  payMode: PayMode
  priceCents: number
  currency: string
  wordCount: number
  readyCount: number
  /** 加入人数 = `vocab_book_joins` 的行数（FR-252）。 */
  joinCount: number
  updatedAt: string
}

export interface AdminBookCreateBody {
  name: string
  slug?: string | null
  description?: string | null
  language?: string
  level?: string | null
  position?: number
}

/** 修改是字段子集：发布/下架只传 `published`，不动其它字段（FR-235）。 */
export interface AdminBookUpdateBody {
  name?: string
  description?: string | null
  language?: string
  level?: string | null
  position?: number
  published?: boolean
}

export interface AdminBookEntryPage {
  items: Array<{ id: string; form: string; position: number; ready: boolean }>
  total: number
  page: number
  size: number
  totalWords: number
}

export async function fetchAdminBooks(): Promise<AdminBook[]> {
  return (await api.get<AdminBook[] | null>('/api/admin/vocab/books')) ?? []
}

export async function fetchAdminBook(bookId: string): Promise<AdminBook> {
  return api.get<AdminBook>(`/api/admin/vocab/books/${bookId}`)
}

export async function createAdminBook(body: AdminBookCreateBody): Promise<AdminBook> {
  return api.post<AdminBook>('/api/admin/vocab/books', body)
}

/** 元信息与发布状态都走这一个 PUT；`published` 传 false 就是下架（不删任何数据）。 */
export async function updateAdminBook(bookId: string, body: AdminBookUpdateBody): Promise<AdminBook> {
  return api.put<AdminBook>(`/api/admin/vocab/books/${bookId}`, body)
}

/**
 * 词条的读与单条删。
 *
 * 界面上暂时用不到这两个 helper：编辑面板只显示词数/覆盖率，「导入词条」页把预览与提交
 * 直接交给 `ImportPastePanel`（它按 `previewPath / commitPath` 发请求）。契约里有这两个
 * 端点，helper 先留着，排查「某本书第 N 条词条是什么」时不用手写 curl。
 */
export async function fetchAdminBookEntries(
  bookId: string,
  page = 1,
  size = 50,
): Promise<AdminBookEntryPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
  const value = await api.get<Partial<AdminBookEntryPage>>(
    `/api/admin/vocab/books/${bookId}/entries?${query.toString()}`)
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
 * 删单条词条（`DELETE .../entries/{entryId}`）。界面当前**没有**入口：管理端只提供
 * 「下架 + 导入」，单条删除留给排查用——误删一个词条会让这本书的所有学生少看到一个词，
 * 不值得做成列表里的一级动作。
 */
export async function deleteAdminBookEntry(bookId: string, entryId: string): Promise<number> {
  const result = await api.del<{ affected?: number }>(`/api/admin/vocab/books/${bookId}/entries/${entryId}`)
  return result?.affected ?? 0
}
