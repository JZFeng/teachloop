// 教师端「一份作业当前卡在谁手里」的判定（决策 025，2026-10-25）。
//
// 原则：状态 = 现在谁欠下一个动作；AI 在跑的过程不算状态，它是事实（已批 x/y · 失败 n）。
// 原来的 8 档（有失败/批改中/待处理/无提交/可发布/成绩已公布/待提交/进行中）把
// 「学生交齐没」「AI 批完没」「老师公布没」三个互不排斥的维度压进了一个词，只能按优先级丢信息。
//
// 纯函数、无 React 依赖，`web/tests/status-vocabulary.test.mjs` 直接测这里。
import type { ProgressView } from '../api/types'

/** 3 个待办 + 1 个终态：每一个都对应一个明确的「下一步谁动手」。 */
export type OfferingState = 'uncollected' | 'grading' | 'to_publish' | 'published'

export interface OfferingStateInput {
  progress: ProgressView
  publishScores: boolean
  /** 应交人数（按班成员数；没有班级成员信息时回退 classCount 口径）。 */
  expected: number
  /** 未交人数（应交 - 已交，已由 loadAggregates 算好，负数已归零）。 */
  unsubmitted: number
  /** 已拒收份数：SKIPPED 也算处理完毕，否则「都批完了」永远不成立。 */
  rejected?: number
}

export interface OfferingStateView {
  state: OfferingState
  /** 批改失败份数：独立角标，可与任何主状态并存（它不是阶段，是异常）。 */
  failedCount: number
  /** 已批份数（含拒收）。 */
  gradedCount: number
  /** 交卷行总数（含未交卷草稿与拒收，口径来自 submissions 表）。 */
  total: number
}

export function offeringState(input: OfferingStateInput): OfferingStateView {
  const { progress, publishScores, unsubmitted } = input
  const rejected = input.rejected ?? 0
  const gradedCount = progress.done + rejected
  const total = progress.total

  let state: OfferingState
  if (publishScores) {
    // 老师已经交付，没人欠动作。
    state = 'published'
  } else if (unsubmitted > 0) {
    // 学生欠动作：先催交，再谈公布。
    state = 'uncollected'
  } else if (total > 0 && gradedCount >= total) {
    // 都批完了，只剩老师点「公布成绩」。
    state = 'to_publish'
  } else {
    // 交齐了但还没批完（含没派发、排队、在跑）：老师可以自己推一把。
    state = 'grading'
  }

  return { state, failedCount: progress.failed, gradedCount, total }
}

/**
 * 排序用等级：未交齐 → 待批改 → 待公布 → 已公布。
 * 有失败的插到最前：它是异常，不该因为主状态是「已公布」就被排到后面看不见。
 */
export function offeringStateRank(view: OfferingStateView): number {
  const base: Record<OfferingState, number> = {
    uncollected: 0,
    grading: 1,
    to_publish: 2,
    published: 3,
  }
  return view.failedCount > 0 ? 0 : base[view.state] + 1
}
