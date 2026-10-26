/*
 * 决策 025 验收：作业状态按「角色责任」划分。
 *
 * 原则：状态 = 现在谁欠下一个动作；AI 在跑的过程不是状态，是事实。
 * 直接测纯函数，不依赖 React 或浏览器：
 *   - 老师端 4 个状态（未交齐 / 待批改 / 待公布 / 已公布）+ 失败角标；
 *   - 学生端 3 个状态（未完成 / 已交卷 / 已公布）+ 拒收角标；
 *   - 排序把「有失败」提前，避免异常被主状态盖住。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { offeringState, offeringStateRank } from '../src/teacher/offeringState.ts'
import { studentRowRejected, studentRowState } from '../src/student/rowState.ts'

const progress = (over = {}) => ({ total: 30, done: 0, running: 0, pending: 0, failed: 0, ...over })
/** 造一份 MyAttemptsView（studentRowState 直接吃这个视图，不要再套一层）。 */
const student = (list) => (list ? {
  offeringId: 'o',
  attemptsUsed: list.length,
  maxAttempts: 2,
  canRetry: false,
  activeAttemptNo: null,
  attempts: list,
} : null)
const attempt = (over = {}) => ({
  attemptNo: 1,
  submittedAt: '2026-10-25T10:00:00Z',
  autoSubmitted: false,
  lateBySeconds: 0,
  rejectedAt: null,
  rejectReason: null,
  gradingState: 'DONE',
  objectiveScore: null,
  subjectiveScore: null,
  totalScore: null,
  active: false,
  submissionId: 's1',
  attachmentName: null,
  contentHtml: null,
  contentText: null,
  ...over,
})

// ---------- 老师端：谁欠下一个动作 ----------

test('还有学生没交 → 未交齐（学生欠动作，优先于其他）', () => {
  const view = offeringState({
    progress: progress({ done: 28, total: 30 }),
    publishScores: false,
    expected: 30,
    unsubmitted: 2,
  })
  assert.equal(view.state, 'uncollected')
  assert.equal(view.gradedCount, 28)
})

test('交齐且都批完、还没公布 → 待公布（老师欠动作）', () => {
  const view = offeringState({
    progress: progress({ done: 30 }),
    publishScores: false,
    expected: 30,
    unsubmitted: 0,
  })
  assert.equal(view.state, 'to_publish')
})

test('交齐但 AI 还没批完 → 待批改（老师可以自己推一把，但这不是「批改中」工具状态）', () => {
  const view = offeringState({
    progress: progress({ done: 12, running: 3, pending: 15 }),
    publishScores: false,
    expected: 30,
    unsubmitted: 0,
  })
  assert.equal(view.state, 'grading')
  assert.equal(view.gradedCount, 12)
})

test('已公布成绩 → 已公布，没人欠动作', () => {
  const view = offeringState({
    progress: progress({ done: 30 }),
    publishScores: true,
    expected: 30,
    unsubmitted: 0,
  })
  assert.equal(view.state, 'published')
})

test('已拒收也算处理完毕：done 28 + rejected 2 = total 30 → 待公布', () => {
  const view = offeringState({
    progress: progress({ done: 28 }),
    publishScores: false,
    expected: 30,
    unsubmitted: 0,
    rejected: 2,
  })
  assert.equal(view.state, 'to_publish')
  assert.equal(view.gradedCount, 30)
})

test('失败是角标不是状态：已公布 + 1 份失败，主状态仍是已公布', () => {
  const view = offeringState({
    progress: progress({ done: 29, failed: 1 }),
    publishScores: true,
    expected: 30,
    unsubmitted: 0,
  })
  assert.equal(view.state, 'published')
  assert.equal(view.failedCount, 1, '旧的 8 档这里会显示「有失败」，把「已公布」盖掉')
})

test('排序：有失败的排到最前，其余按 未交齐 → 待批改 → 待公布 → 已公布', () => {
  const rank = (state, failed = 0) => offeringStateRank({ state, failedCount: failed, gradedCount: 0, total: 0 })
  assert.ok(rank('published', 1) < rank('uncollected', 0), '有失败的已公布要排在未交齐之前')
  assert.ok(rank('uncollected') < rank('grading'))
  assert.ok(rank('grading') < rank('to_publish'))
  assert.ok(rank('to_publish') < rank('published'))
})

test('没人交卷（total=0）且名单里有学生 → 未交齐，不是「无提交」这个独立状态', () => {
  const view = offeringState({
    progress: progress({ total: 0 }),
    publishScores: false,
    expected: 30,
    unsubmitted: 30,
  })
  assert.equal(view.state, 'uncollected')
})

// ---------- 学生端：我做了没 / 批了没 ----------

test('没有作答记录 → 未完成', () => {
  assert.equal(studentRowState(student(null)), 'todo')
  assert.equal(studentRowState(student([])), 'todo')
})

test('开了卷但没交 → 未完成（不再单独区分「作答中」）', () => {
  assert.equal(studentRowState(student([attempt({ submittedAt: null })])), 'todo')
})

test('交了但老师没公布（分数为 null）→ 已交卷', () => {
  assert.equal(studentRowState(student([attempt({ gradingState: 'DONE', totalScore: null })])), 'submitted')
})

test('老师公布了（分数可见）→ 已公布；0 分也算已公布', () => {
  assert.equal(studentRowState(student([attempt({ totalScore: 88 })])), 'published')
  assert.equal(studentRowState(student([attempt({ totalScore: 0 })])), 'published')
})

test('被拒收 → 主状态回到未完成，并亮拒收角标', () => {
  const rows = student([attempt({ rejectedAt: '2026-10-25T11:00:00Z', totalScore: null })])
  assert.equal(studentRowState(rows), 'todo')
  assert.equal(studentRowRejected(rows), true)
})

test('多次尝试取最后一次：第二次交了、第一次没交 → 已交卷', () => {
  const rows = student([
    attempt({ attemptNo: 1, submittedAt: null }),
    attempt({ attemptNo: 2, submittedAt: '2026-10-25T12:00:00Z', totalScore: null }),
  ])
  assert.equal(studentRowState(rows), 'submitted')
})
