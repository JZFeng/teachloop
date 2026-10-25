/*
 * 通知跳转（FR-053 / T250）——2026-10-25 补 vocab 发词通知。
 *
 * 直接测纯函数（app/notificationRoute.ts），不依赖 React 或浏览器。
 * 重点守两件事：
 *   1) vocab 发词通知（后端写的是大写 `VOCAB_ASSIGNMENT`）能跳到学生预习/领取页；
 *   2) 大小写不敏感——历史行与别的发射方都是小写，不能因为大小写挂掉既有跳转。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { notificationRoute } from '../src/app/notificationRoute.ts'

const assignmentId = '9f1c2c7e-1a2b-4c3d-8e9f-0a1b2c3d4e5f'

test('vocab 发词通知：大写 targetType 也跳到学生预习页', () => {
  const route = notificationRoute({
    kind: 'VOCAB_ASSIGNMENT',
    targetType: 'VOCAB_ASSIGNMENT',
    targetId: assignmentId,
  })
  assert.equal(route, `/vocab/assignments/${assignmentId}`)
})

test('vocab 发词通知：小写（后端将来统一大小写）同样命中', () => {
  const route = notificationRoute({
    kind: 'vocab_assignment',
    targetType: 'vocab_assignment',
    targetId: assignmentId,
  })
  assert.equal(route, `/vocab/assignments/${assignmentId}`)
})

test('考试与作业仍然按 targetType 分页', () => {
  assert.equal(
    notificationRoute({ kind: 'offering_published', targetType: 'exam', targetId: 'e1' }),
    '/exams/e1')
  assert.equal(
    notificationRoute({ kind: 'offering_published', targetType: 'assignment', targetId: 'a1' }),
    '/assignments/a1')
  // 改造前的历史 targetType
  assert.equal(
    notificationRoute({ kind: 'offering_published', targetType: 'offering', targetId: 'o1' }),
    '/assignments/o1')
})

test('反馈通知指向布置的反馈页；历史 submission 行退到反馈列表', () => {
  assert.equal(
    notificationRoute({ kind: 'feedback_published', targetType: 'offering', targetId: 'of1' }),
    '/feedback/of1')
  assert.equal(
    notificationRoute({ kind: 'scores_published', targetType: 'offering', targetId: 'of2' }),
    '/feedback/of2')
  assert.equal(
    notificationRoute({ kind: 'feedback_published', targetType: 'submission', targetId: 's1' }),
    '/feedback')
})

test('没有目标 id、或 targetType 不认识时不给跳转（只标已读）', () => {
  assert.equal(notificationRoute({ kind: 'VOCAB_ASSIGNMENT', targetType: 'VOCAB_ASSIGNMENT', targetId: null }), null)
  assert.equal(notificationRoute({ kind: 'notice', targetType: null, targetId: 'x' }), null)
  assert.equal(notificationRoute({ kind: 'offering_published', targetType: 'class', targetId: 'c1' }), null)
})
