/*
 * US-90 / US-94 的可验收部分：排序、截断、逾期、「N 天后」、日期标题、空词库判定。
 *
 * 这些规则在浏览器里只能靠肉眼看，而它们全是纯函数——放在这里用 node 直接跑，
 * 不用起 React、不用 mock api（AC-90.2 / 90.4 / 90.5 的判定口径）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  ASSIGNMENT_LIMIT,
  EXAM_LIMIT,
  daysOverdue,
  daysUntil,
  emptyReview,
  firstClassName,
  formatMoment,
  formatTodayHeading,
  isOverdue,
  visibleAssignments,
  visibleExams,
} from '../src/student/today.ts'

/** 2026-10-30 09:00 本地时间（周五）——所有断言都以这个「现在」为基准。 */
const NOW = new Date(2026, 9, 30, 9, 0, 0)

const assignment = (id, dueAt) => ({ id, title: id, className: 'Class 3', dueAt, status: 'todo' })
const exam = (id, startsAt) => ({ id, title: id, className: 'Class 3', startsAt })

test('作业卡：逾期在前、按截止时间升序，最多 3 条（AC-90.2 / FR-200）', () => {
  const list = [
    assignment('later', '2026-11-02T10:00:00Z'),
    assignment('overdue-older', '2026-10-28T10:00:00Z'),
    assignment('today', '2026-10-30T18:00:00Z'),
    assignment('overdue-newer', '2026-10-29T10:00:00Z'),
  ]
  assert.equal(ASSIGNMENT_LIMIT, 3)
  assert.deepEqual(
    visibleAssignments(list).map((item) => item.id),
    ['overdue-older', 'overdue-newer', 'today'],
  )
})

test('作业卡：5 条只留 3 条，第 4 条起不显示（页内展开前）', () => {
  const list = [
    assignment('a', '2026-10-30T10:00:00Z'),
    assignment('b', '2026-10-31T10:00:00Z'),
    assignment('c', '2026-11-01T10:00:00Z'),
    assignment('d', '2026-11-02T10:00:00Z'),
    assignment('e', '2026-11-03T10:00:00Z'),
  ]
  const visible = visibleAssignments(list)
  assert.equal(visible.length, 3)
  assert.deepEqual(visible.map((item) => item.id), ['a', 'b', 'c'])
  assert.equal(visibleAssignments(list, 5).length, 5)
})

test('作业卡：不修改入参（纯函数，重试不会把顺序越排越乱）', () => {
  const list = [assignment('b', '2026-11-01T10:00:00Z'), assignment('a', '2026-10-28T10:00:00Z')]
  visibleAssignments(list)
  assert.deepEqual(list.map((item) => item.id), ['b', 'a'])
})

test('作业卡：解析不出的截止时间沉到最后，不占「最急」的位置', () => {
  const list = [assignment('broken', 'not-a-date'), assignment('ok', '2026-10-28T10:00:00Z')]
  assert.deepEqual(visibleAssignments(list).map((item) => item.id), ['ok', 'broken'])
})

test('考试卡：按开始时间升序、最多 2 条（AC-90.4 / FR-201）', () => {
  const list = [
    exam('third', '2026-11-05T09:00:00Z'),
    exam('first', '2026-11-02T09:00:00Z'),
    exam('second', '2026-11-03T09:00:00Z'),
  ]
  assert.equal(EXAM_LIMIT, 2)
  assert.deepEqual(visibleExams(list).map((item) => item.id), ['first', 'second'])
})

test('逾期判定：过去的为真、将来的为假、脏数据为假', () => {
  assert.equal(isOverdue('2026-10-30T08:00:00', NOW), true)
  assert.equal(isOverdue('2026-10-30T10:00:00', NOW), false)
  assert.equal(isOverdue('not-a-date', NOW), false)
})

test('日历天差：今天 0、明天 1、三天后 3、昨天 -1（向上取整，不是 24 小时）', () => {
  assert.equal(daysUntil('2026-10-30T23:00:00', NOW), 0)
  assert.equal(daysUntil('2026-10-31T00:30:00', NOW), 1)
  assert.equal(daysUntil('2026-11-02T09:00:00', NOW), 3)
  assert.equal(daysUntil('2026-10-29T23:59:00', NOW), -1)
})

test('逾期天数：当天早些时候到期也写 1 天，不写 0 天', () => {
  assert.equal(daysOverdue('2026-10-30T08:00:00', NOW), 1)
  assert.equal(daysOverdue('2026-10-27T08:00:00', NOW), 3)
})

test('截止时间缺失（后端 dueAt 可空）：沉到最后、不算逾期、不渲染「截止」', () => {
  const list = [assignment('no-due', null), assignment('soon', '2026-10-29T10:00:00Z')]
  assert.deepEqual(visibleAssignments(list).map((item) => item.id), ['soon', 'no-due'])
  assert.equal(isOverdue(null, NOW), false)
  assert.equal(daysOverdue(null, NOW), 0)
  assert.equal(formatMoment(null, 'zh-CN'), '')
})

test('具体时刻：中文带月日与时分，解析不出来时原样回显', () => {
  const moment = formatMoment('2026-10-30T18:00:00', 'zh-CN')
  assert.match(moment, /^10月30日/)
  assert.equal(formatMoment('2026-10-30T18:00:00', 'en').startsWith('Oct 30'), true)
  assert.equal(formatMoment('not-a-date', 'zh-CN'), 'not-a-date')
})

test('日期标题：中文 10月30日 周五、英文 Oct 30 Fri', () => {
  const date = new Date(2026, 9, 30, 12, 0, 0)
  assert.equal(formatTodayHeading(date, 'zh-CN'), '10月30日 周五')
  assert.equal(formatTodayHeading(date, 'en'), 'Oct 30 Fri')
  assert.equal(formatTodayHeading(new Date('nope'), 'zh-CN'), '')
})

test('空词库：三个计数全为 0 才算空；缺数据不算空', () => {
  assert.equal(emptyReview({ due: 0, reviewedToday: 0, newToday: 0, newLimit: 20 }), true)
  assert.equal(emptyReview({ due: 24, reviewedToday: 8, newToday: 10, newLimit: 20 }), false)
  assert.equal(emptyReview({ due: 0, reviewedToday: 8, newToday: 0, newLimit: 20 }), false)
  assert.equal(emptyReview({ due: 0, reviewedToday: 0, newToday: 3, newLimit: 20 }), false)
  assert.equal(emptyReview(null), false)
  assert.equal(emptyReview(undefined), false)
})

test('副标题班名：先取作业的班名，没有作业再取考试的，都没有返回 null', () => {
  assert.equal(firstClassName([assignment('a', '2026-10-28T10:00:00Z')], []), 'Class 3')
  assert.equal(firstClassName([], [exam('e', '2026-11-02T09:00:00Z')]), 'Class 3')
  assert.equal(firstClassName([], []), null)
  assert.equal(firstClassName(undefined, undefined), null)
})
