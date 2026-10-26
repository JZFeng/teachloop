/**
 * 批改详情页的「按钮此刻能不能点」与「默认落在哪个页签」（2026-10-26）。
 *
 * 起因：用户打开一个「应交 33 / 已交 0」的作业，页面最显眼的却是「全部交给 AI 批改」——
 * 点了什么也不会发生。这类「动作可用性不随状态收敛」的问题，判定集中在这一个纯函数里，
 * 页面只负责把 reason 显示出来；`web/tests/detail-gates.test.mjs` 直接测它。
 */
export interface DetailGateInput {
  /** 成绩是否已公布（publishScores）。 */
  publishScores: boolean
  /** 已交卷份数（submittedAt 非空；拒收也算交过）。 */
  submitted: number
  /** 已批改份数（含拒收）。 */
  done: number
  /** 还能被派发的份数（未交卷 / 已拒收 / 已批改的都不能再派）。 */
  dispatchable: number
}

export interface DetailGates {
  /** 还没有任何人交卷：整页该走空态，而不是展示 0 的仪表盘。 */
  noSubmission: boolean
  canDispatch: boolean
  canExplain: boolean
  canReview: boolean
  canPublish: boolean
}

export function detailGates({ publishScores, submitted, done, dispatchable }: DetailGateInput): DetailGates {
  return {
    noSubmission: submitted === 0,
    canDispatch: dispatchable > 0,
    // 错题解析依托已批改的题目；没批过就没有解析可生成。
    canExplain: done > 0,
    // 逐份复核的对象是已批改但需要人工判断的提交；一份都没批过时进去只会看到空队列。
    canReview: done > 0,
    // 一分都没批就「公布成绩」，等于把空成绩单发给学生。
    canPublish: done > 0 && !publishScores,
  }
}

/**
 * 默认页签：成绩公布之后老师最关心的是结果，默认落在「成绩汇总」；
 * 公布之前默认「提交明细」（此时汇总只是预览，不该抢主位）。
 */
export function defaultTab(publishScores: boolean): 'submissions' | 'summary' {
  return publishScores ? 'summary' : 'submissions'
}
