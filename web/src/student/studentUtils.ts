// 学生端共享的数据拼装：offerings + 每场的 entry / my-submissions。
// 学生只能看到自己参与的场次（后端已按参与班过滤），这里只做组合，不做权限判断。
import { api } from '../api/client'
import type { AttemptView, EntryView, MyAttemptsView, OfferingView } from '../api/types'

export interface StudentRow {
  offering: OfferingView
  entry: EntryView | null
  attempts: MyAttemptsView | null
}

export async function loadStudentRows(): Promise<StudentRow[]> {
  const offerings = await api.get<OfferingView[]>('/api/offerings')
  return Promise.all(offerings.map(async (offering) => {
    const [entry, attempts] = await Promise.all([
      api.get<EntryView>(`/api/offerings/${offering.id}/entry`).catch(() => null),
      api.get<MyAttemptsView>(`/api/offerings/${offering.id}/my-submissions`).catch(() => null),
    ])
    return { offering, entry, attempts }
  }))
}

export function latestOf(attempts: MyAttemptsView | null): AttemptView | null {
  const list = attempts?.attempts ?? []
  if (!list.length) return null
  return [...list].sort((a, b) => a.attemptNo - b.attemptNo)[list.length - 1]
}

export function latestAttempt(row: StudentRow): AttemptView | null {
  return latestOf(row.attempts)
}

export type RowStatusKey = 'not_started' | 'in_progress' | 'submitted' | 'graded' | 'rejected'

export function rowStatus(row: StudentRow): RowStatusKey {
  const attempt = latestAttempt(row)
  if (attempt?.rejectedAt) return 'rejected'
  if (!attempt) return 'not_started'
  if (!attempt.submittedAt) return 'in_progress'
  if (attempt.gradingState === 'DONE') return 'graded'
  return 'submitted'
}

export function rowScore(row: StudentRow): number | null {
  const attempt = latestAttempt(row)
  if (!attempt || !attempt.submittedAt) return null
  return attempt.totalScore
}

export function rowSubmittedAt(row: StudentRow): string | null {
  return latestAttempt(row)?.submittedAt ?? null
}

export function rowHasFeedback(row: StudentRow): boolean {
  const attempt = latestAttempt(row)
  return !!attempt && !!attempt.submittedAt && attempt.gradingState === 'DONE'
}

export function rowIsExam(row: StudentRow): boolean {
  return row.offering.kind === 'EXAM'
}

export function statusTagClass(status: RowStatusKey): string {
  switch (status) {
    case 'in_progress':
      return 'blue'
    case 'graded':
      return 'green'
    case 'rejected':
      return 'red'
    case 'submitted':
      return 'amber'
    default:
      return 'gray'
  }
}

/** 按截止时间倒序（AC-24.1：带时间字段的列表默认时间倒序）。 */
export function sortRowsByDue(rows: StudentRow[]): StudentRow[] {
  return [...rows].sort((a, b) => {
    const at = Date.parse(a.offering.dueAt)
    const bt = Date.parse(b.offering.dueAt)
    return (Number.isNaN(bt) ? 0 : bt) - (Number.isNaN(at) ? 0 : at)
  })
}
