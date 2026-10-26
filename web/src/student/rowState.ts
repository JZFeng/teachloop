// 学生端「这件事我做到哪一步」的判定（决策 025，2026-10-25）。
//
// 学生只关心三件事：作业发了没（能看到的都是已发布的，所以不占状态位）、
// 我做了没、批了没。于是状态只有三个：未完成 / 已交卷 / 已公布。
// 被拒收不是阶段，是「要重交」，所以做成角标，落在「未完成」上。
//
// 纯函数、无 React 与 api 依赖，`web/tests/status-vocabulary.test.mjs` 直接测这里。
import type { AttemptView, MyAttemptsView } from '../api/types'

export type StudentRowState = 'todo' | 'submitted' | 'published'

export function latestAttemptOf(attempts: MyAttemptsView | null): AttemptView | null {
  const list = attempts?.attempts ?? []
  if (!list.length) return null
  return [...list].sort((a, b) => a.attemptNo - b.attemptNo)[list.length - 1]
}

/**
 * 只按「这份答卷现在处于哪一步」判定，不掺 AI 的批改状态：
 * - 没开始 / 有未交草稿 / 被拒收（要重交）→ `todo`
 * - 交卷了、分数还看不到（老师没公布）→ `submitted`
 * - 分数能看到了 → `published`
 */
export function studentRowState(attempts: MyAttemptsView | null): StudentRowState {
  const attempt = latestAttemptOf(attempts)
  if (!attempt || !attempt.submittedAt) return 'todo'
  if (attempt.rejectedAt) return 'todo'
  if (attempt.totalScore != null) return 'published'
  return 'submitted'
}

/** 被拒收要做成角标：它不改变「未完成」这个主状态，但必须让学生知道要重交。 */
export function studentRowRejected(attempts: MyAttemptsView | null): boolean {
  return Boolean(latestAttemptOf(attempts)?.rejectedAt)
}
