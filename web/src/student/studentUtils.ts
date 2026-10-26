// 学生端共享的数据拼装：offerings + 每场的 entry / my-submissions。
// 学生只能看到自己参与的场次（后端已按参与班过滤），这里只做组合，不做权限判断。
import { api } from '../api/client'
import type { AttemptView, EntryView, MyAttemptsView, OfferingView } from '../api/types'
import {
  latestAttemptOf,
  studentRowRejected,
  studentRowState,
  type StudentRowState,
} from './rowState'

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
  return latestAttemptOf(attempts)
}

export function latestAttempt(row: StudentRow): AttemptView | null {
  return latestOf(row.attempts)
}

/**
 * 决策 025：学生只看三个词——未完成 / 已交卷 / 已公布；
 * 被拒收是「要重交」，做成角标（`rowRejected`），不占状态位。
 */
export type RowStatusKey = StudentRowState

export function rowStatus(row: StudentRow): RowStatusKey {
  return studentRowState(row.attempts)
}

export function rowRejected(row: StudentRow): boolean {
  return studentRowRejected(row.attempts)
}

export function rowScore(row: StudentRow): number | null {
  const attempt = latestAttempt(row)
  if (!attempt || !attempt.submittedAt) return null
  return attempt.totalScore
}

export function rowSubmittedAt(row: StudentRow): string | null {
  return latestAttempt(row)?.submittedAt ?? null
}

/**
 * 「有反馈」只在成绩可见之后亮：决策 025 把交付门放在老师公布上，
 * 判分完成但没公布时既没有分数也没有错题，亮这个角标会骗人。
 */
export function rowHasFeedback(row: StudentRow): boolean {
  const attempt = latestAttempt(row)
  return !!attempt && !!attempt.submittedAt && attempt.totalScore != null
}

export function rowIsExam(row: StudentRow): boolean {
  return row.offering.kind === 'EXAM'
}

export function statusTagClass(status: RowStatusKey): string {
  switch (status) {
    case 'published':
      return 'green'
    case 'submitted':
      return 'amber'
    default:
      return 'gray'
  }
}

/** 首页排序（AC-72.7）：实现搬去 `rowState.ts`（纯函数，能被 node 测试直接引），这里只转发。 */
export { sortRowsByDue } from './rowState'
