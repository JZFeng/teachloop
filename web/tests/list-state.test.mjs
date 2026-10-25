/*
 * T441 验收：列表状态在 URL 里的读写。
 *
 * 直接测纯函数（ui/listState.ts），不依赖 React 或浏览器：
 *   - 默认每页 10 条（AC-24.3 修订版）；
 *   - 读兼容旧参数 `pageSize`，写只写 `size`（AC-24.4 / AC-68.3）；
 *   - 换排序、换每页条数都回到第 1 页。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  DEFAULT_PAGE_SIZE,
  PAGE_SIZE_OPTIONS,
  readListState,
  withPage,
  withPageSize,
  withSort,
} from '../src/ui/listState.ts'

test('默认每页 10 条，可选 10/20/50/100', () => {
  assert.equal(DEFAULT_PAGE_SIZE, 10)
  assert.deepEqual(PAGE_SIZE_OPTIONS, [10, 20, 50, 100])
  assert.equal(readListState('').pageSize, 10)
})

test('读取排序、页码与每页条数', () => {
  const state = readListState('?sort=dueAt&dir=desc&page=3&size=50')
  assert.deepEqual(state.sort, { key: 'dueAt', dir: 'desc' })
  assert.equal(state.page, 3)
  assert.equal(state.pageSize, 50)
})

test('没有 sort 时用调用方的默认排序', () => {
  assert.deepEqual(readListState('', { key: 'name', dir: 'asc' }).sort, { key: 'name', dir: 'asc' })
  assert.equal(readListState('').sort, undefined)
})

test('dir 只认 desc，其余按 asc', () => {
  assert.equal(readListState('?sort=a&dir=DESC').sort.dir, 'asc')
  assert.equal(readListState('?sort=a&dir=asc').sort.dir, 'asc')
})

test('兼容旧参数 pageSize', () => {
  assert.equal(readListState('?pageSize=20').pageSize, 20)
  // 新参数优先
  assert.equal(readListState('?size=50&pageSize=20').pageSize, 50)
})

test('非法页码与条数回落到默认值', () => {
  assert.equal(readListState('?page=0').page, 1)
  assert.equal(readListState('?page=abc').page, 1)
  assert.equal(readListState('?size=-5').pageSize, 10)
})

test('换排序写 sort/dir 并回到第 1 页', () => {
  const next = new URLSearchParams(withSort('?page=4&size=20', { key: 'submittedAt', dir: 'desc' }))
  assert.equal(next.get('sort'), 'submittedAt')
  assert.equal(next.get('dir'), 'desc')
  assert.equal(next.get('page'), null)
  assert.equal(next.get('size'), '20')
})

test('换每页条数写 size、清掉旧参数 pageSize 与页码', () => {
  const next = new URLSearchParams(withPageSize('?pageSize=20&page=3', 50))
  assert.equal(next.get('size'), '50')
  assert.equal(next.get('pageSize'), null)
  assert.equal(next.get('page'), null)
})

test('换页码不小于 1', () => {
  assert.equal(new URLSearchParams(withPage('', 0)).get('page'), '1')
  assert.equal(new URLSearchParams(withPage('', -3)).get('page'), '1')
  assert.equal(new URLSearchParams(withPage('', 2)).get('page'), '2')
})
