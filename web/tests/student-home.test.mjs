/*
 * US-72 / AC-72.7 验收：学生首页按「最急的在最上」排序。
 *
 * 之前是截止时间**倒序**（沿用 AC-24.1「列表默认时间倒序」），结果最晚截止的排最前、
 * 最急的沉到最下面。首页是待办不是档案，所以改成升序；这里直接测纯函数，不依赖 React 与 api。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { sortRowsByDue } from '../src/student/rowState.ts'

const row = (dueAt) => ({ offering: { id: dueAt, dueAt } })

test('按截止时间升序：最急的在最上（AC-72.7）', () => {
  const rows = [row('2026-10-30T10:00:00Z'), row('2026-10-24T17:51:00Z'), row('2026-10-26T23:52:00Z')]
  assert.deepEqual(
    sortRowsByDue(rows).map((item) => item.offering.id),
    ['2026-10-24T17:51:00Z', '2026-10-26T23:52:00Z', '2026-10-30T10:00:00Z'],
  )
})

test('不改动入参数组（纯函数）', () => {
  const rows = [row('2026-10-30T10:00:00Z'), row('2026-10-24T17:51:00Z')]
  const before = rows.map((item) => item.offering.id)
  sortRowsByDue(rows)
  assert.deepEqual(rows.map((item) => item.offering.id), before)
})

test('解析不出的截止时间沉到最后，不排在「最急」的位置', () => {
  const rows = [row('not-a-date'), row('2026-10-24T17:51:00Z')]
  assert.deepEqual(
    sortRowsByDue(rows).map((item) => item.offering.id),
    ['2026-10-24T17:51:00Z', 'not-a-date'],
  )
})
