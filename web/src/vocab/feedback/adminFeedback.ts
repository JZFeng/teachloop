/**
 * 管理端「单词反馈」接口（2026-10-31）。
 *
 * 契约：学生提交的反馈落在 `vocab_feedback`（写入口 `POST /api/vocab/words/{id}/feedback`，
 * `VocabularyWordDetailEndpoint:188`），读与处理都在 `/api/admin/vocab/feedback*`：
 * 整条前缀由后端 `SecurityConfig` 收口成 `hasRole("ADMIN")`，教师/学生打进去是 403，
 * 前端不做第二套判权（路由守卫归 `nav.vocab.feedback`）。
 *
 * `submitterId` 只有 user id：`users` 表属 identity 模块，后端不跨模块 JOIN，
 * 界面展示短 id（前 8 位）而不是编一个邮箱出来。
 */
import { api } from '../../api/client'

export type FeedbackStatus = 'OPEN' | 'RESOLVED' | 'IGNORED'
/** 列表筛选多一个 `ALL`（= 不过滤）；后端缺省按 `OPEN`。 */
export type FeedbackFilter = FeedbackStatus | 'ALL'

export interface AdminFeedbackItem {
  id: number
  /** 词条被删过时后端回 null（`left join vocab_words`）。 */
  wordForm: string | null
  wordLanguage: string | null
  /** 提交时写的备注；`vocab_feedback.note` 与 `payload.note` 两条路径后端已合并。 */
  note: string | null
  status: FeedbackStatus
  submitterId: string
  createdAt: string
}

export interface AdminFeedbackPage {
  items: AdminFeedbackItem[]
  total: number
  page: number
  size: number
}

/**
 * 列表：`status` 缺省 OPEN，`page` 从 1 开始。
 *
 * 返回体做一次防御性归一：字段缺省时按空数组/0 处理，避免表格因为 `undefined` 崩掉
 * （与 `adminBooks.ts` 的 `fetchAdminBookEntries` 同一套写法）。
 */
export async function fetchAdminFeedback(
  filter: FeedbackFilter,
  page = 1,
  size = 20,
): Promise<AdminFeedbackPage> {
  const query = new URLSearchParams({ status: filter, page: String(page), size: String(size) })
  const value = await api.get<Partial<AdminFeedbackPage> | null>(
    `/api/admin/vocab/feedback?${query.toString()}`)
  const items = Array.isArray(value?.items) ? value.items : []
  return {
    items,
    total: typeof value?.total === 'number' ? value.total : items.length,
    page: typeof value?.page === 'number' ? value.page : page,
    size: typeof value?.size === 'number' ? value.size : size,
  }
}

/** 处理一条反馈：`OPEN` 重开 / `RESOLVED` 已处理 / `IGNORED` 不处理。非法值后端 400。 */
export async function updateFeedbackStatus(
  id: number,
  status: FeedbackStatus,
): Promise<AdminFeedbackItem> {
  return api.post<AdminFeedbackItem>(`/api/admin/vocab/feedback/${id}/status`, { status })
}

/** 提交人展示用：UUID 前 8 位（完整值放 `title`，需要时能核对）。 */
export function shortSubmitterId(submitterId: string): string {
  return submitterId.slice(0, 8)
}
