/*
 * SPEC 012（班级生命周期）前端验收：US-96/97/98 的两条展示规则。
 *
 * 为什么测类型文件里的函数：学生班级页要「进行中在上、已结束在下，已结束只读」，
 * 这条判断一旦写错，学生会在结课的班里看到提交入口（AC-98.2 直接破）。
 * ClassesPage.tsx 是 JSX，node --test 不转 JSX，所以规则本体放在
 * `src/api/types.ts`，页面与这里共用同一份实现。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  assignableClasses,
  isAssignableClass,
  offeringsForClass,
  splitClassesByLifecycle,
} from '../src/api/types.ts'

const cls = (id, archived, memberCount = 3) => ({
  id,
  name: `班 ${id}`,
  inviteCode: `CODE${id}`,
  memberCount,
  archived,
})

const offering = (id, dueAt, classIds, kind = 'ASSIGNMENT') => ({
  id,
  paperTitle: `作业 ${id}`,
  dueAt,
  kind,
  classes: classIds.map((classId) => ({
    classId,
    dueAtOverride: null,
    durationOverride: null,
    memberCount: 3,
  })),
})

test('进行中 / 已结束按 archived 分组，组内保持后端顺序（AC-98.2）', () => {
  const { active, archived } = splitClassesByLifecycle([
    cls('a', false),
    cls('b', true),
    cls('c', false),
    cls('d', true),
  ])
  assert.deepEqual(active.map((row) => row.id), ['a', 'c'])
  assert.deepEqual(archived.map((row) => row.id), ['b', 'd'])
})

test('结课的班仍然出现（只读可见），不是被过滤掉', () => {
  const { active, archived } = splitClassesByLifecycle([cls('only-archived', true)])
  assert.deepEqual(active, [])
  assert.equal(archived.length, 1)
  assert.equal(archived[0].archived, true)
})

test('分组不改动入参（纯函数）', () => {
  const rows = [cls('a', false), cls('b', true)]
  const before = rows.map((row) => row.id)
  splitClassesByLifecycle(rows)
  assert.deepEqual(rows.map((row) => row.id), before)
})

test('作业按 classId 过滤：一个作业挂两个班时两边都能看到', () => {
  const rows = [
    offering('o1', '2026-10-30T10:00:00Z', ['a', 'b']),
    offering('o2', '2026-10-28T10:00:00Z', ['b']),
    offering('o3', '2026-10-29T10:00:00Z', ['c']),
  ]
  assert.deepEqual(offeringsForClass(rows, 'a').map((row) => row.id), ['o1'])
  assert.deepEqual(offeringsForClass(rows, 'b').map((row) => row.id), ['o2', 'o1'])
  assert.deepEqual(offeringsForClass(rows, 'c').map((row) => row.id), ['o3'])
  assert.deepEqual(offeringsForClass(rows, 'nobody'), [])
})

test('作业按截止时间升序：最急的在最上（与 AC-72.7 同口径）', () => {
  const rows = [
    offering('late', '2026-11-05T10:00:00Z', ['a']),
    offering('early', '2026-10-24T17:51:00Z', ['a']),
    offering('middle', '2026-10-26T23:52:00Z', ['a']),
  ]
  assert.deepEqual(
    offeringsForClass(rows, 'a').map((row) => row.id),
    ['early', 'middle', 'late'],
  )
})

test('解析不出的截止时间沉到最后，不占「最急」的位置', () => {
  const rows = [
    offering('broken', 'not-a-date', ['a']),
    offering('real', '2026-10-24T17:51:00Z', ['a']),
    offering('none', null, ['a']),
  ]
  assert.deepEqual(
    offeringsForClass(rows, 'a').map((row) => row.id),
    ['real', 'broken', 'none'],
  )
})

test('筛作业不改动入参数组（纯函数）', () => {
  const rows = [
    offering('o1', '2026-11-05T10:00:00Z', ['a']),
    offering('o2', '2026-10-24T17:51:00Z', ['a']),
  ]
  const before = rows.map((row) => row.id)
  offeringsForClass(rows, 'a')
  assert.deepEqual(rows.map((row) => row.id), before)
})

test('已结课的班不能当布置目标：assignableClasses 只留进行中的班（AC-98.7）', () => {
  const rows = [cls('a', false), cls('b', true), cls('c', false)]
  assert.deepEqual(assignableClasses(rows).map((row) => row.id), ['a', 'c'])
})

test('名单不删班级：已结课的班仍在原数组里，只是被判为不可选', () => {
  const rows = [cls('a', false), cls('b', true)]
  const before = rows.map((row) => row.id)
  assignableClasses(rows)
  assert.deepEqual(rows.map((row) => row.id), before)
  assert.equal(rows.length, 2)
  assert.equal(isAssignableClass(rows[1]), false)
  assert.equal(isAssignableClass(rows[0]), true)
})

test('全部结课时没有可选项，但名单仍非空（页面要能说明原因）', () => {
  const rows = [cls('a', true), cls('b', true)]
  assert.deepEqual(assignableClasses(rows), [])
  assert.equal(rows.length, 2)
})
