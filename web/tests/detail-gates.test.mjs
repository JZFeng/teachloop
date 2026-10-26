/**
 * 批改详情页的动作门禁（T496 / 2026-10-26）。
 *
 * 真实场景：应交 33 / 已交 0 的作业，页面上「全部交给 AI 批改」仍是可点的主按钮。
 * 这里把判定钉死，页面只渲染。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { defaultTab, detailGates } from '../src/teacher/detailGates.ts'

test('没人交卷：四个动作全不可用，整页走空态', () => {
  const gates = detailGates({ publishScores: false, submitted: 0, done: 0, dispatchable: 0 })
  assert.equal(gates.noSubmission, true)
  assert.equal(gates.canDispatch, false)
  assert.equal(gates.canExplain, false)
  assert.equal(gates.canReview, false)
  assert.equal(gates.canPublish, false)
})

test('有人交卷但一份都没批：可以派发，不能解析/复核/公布', () => {
  const gates = detailGates({ publishScores: false, submitted: 30, done: 0, dispatchable: 30 })
  assert.equal(gates.noSubmission, false)
  assert.equal(gates.canDispatch, true)
  assert.equal(gates.canExplain, false)
  assert.equal(gates.canReview, false)
  assert.equal(gates.canPublish, false)
})

test('都批完了但没公布：可以解析/复核/公布，不能再派发', () => {
  const gates = detailGates({ publishScores: false, submitted: 30, done: 30, dispatchable: 0 })
  assert.equal(gates.canDispatch, false)
  assert.equal(gates.canExplain, true)
  assert.equal(gates.canReview, true)
  assert.equal(gates.canPublish, true)
})

test('成绩已公布：公布按钮消失（不是禁用），其余不受影响', () => {
  const gates = detailGates({ publishScores: true, submitted: 30, done: 30, dispatchable: 0 })
  assert.equal(gates.canPublish, false)
  assert.equal(gates.canExplain, true)
})

test('还有待派发的份数（部分失败重派）：派发按钮可用', () => {
  const gates = detailGates({ publishScores: false, submitted: 30, done: 20, dispatchable: 10 })
  assert.equal(gates.canDispatch, true)
})

test('默认页签随公布状态切换', () => {
  assert.equal(defaultTab(false), 'submissions')
  assert.equal(defaultTab(true), 'summary')
})
