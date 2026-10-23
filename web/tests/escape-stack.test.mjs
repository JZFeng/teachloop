/*
 * 弹层 Esc 关闭的栈逻辑（T354）。
 *
 * 这里只测纯逻辑 `escapeStack`（不碰 DOM）：它决定一次 Esc 归哪一层、
 * 什么时候不该处理。真实按键链路由 CDP 在浏览器里另行验证。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { createEscapeStack } from '../src/ui/escapeStack.ts'

const escape = { key: 'Escape' }

test('空栈时 Esc 不归任何人处理', () => {
  const stack = createEscapeStack()
  assert.equal(stack.handle(escape), false)
})

test('非 Escape 键不处理', () => {
  const stack = createEscapeStack()
  let closed = 0
  stack.push(() => { closed += 1 })
  assert.equal(stack.handle({ key: 'Enter' }), false)
  assert.equal(stack.handle({ key: 'Esc' }), false)
  assert.equal(closed, 0)
})

test('只关最上层：两层各关一次，顺序是后开的先关', () => {
  const stack = createEscapeStack()
  const order = []
  stack.push(() => order.push('page'))
  // 弹层关闭后会卸载并注销，真实页面里由 hook 的 cleanup 完成。
  const removeDialog = stack.push(() => order.push('dialog'))

  assert.equal(stack.handle(escape), true)
  assert.deepEqual(order, ['dialog'])
  removeDialog()
  assert.equal(stack.handle(escape), true)
  assert.deepEqual(order, ['dialog', 'page'])
})

test('顶层拒绝关闭时 Esc 被吃掉，不穿透到下层', () => {
  const stack = createEscapeStack()
  let pageClosed = 0
  stack.push(() => { pageClosed += 1 })
  // 模拟「提交中不许关」：顶层的处理函数什么都不做。
  stack.push(() => {})

  assert.equal(stack.handle(escape), true)
  assert.equal(pageClosed, 0)
  assert.equal(stack.size, 2)
})

test('注销后不再触发，重复注销不报错', () => {
  const stack = createEscapeStack()
  let closed = 0
  const remove = stack.push(() => { closed += 1 })

  remove()
  remove()
  assert.equal(stack.size, 0)
  assert.equal(stack.handle(escape), false)
  assert.equal(closed, 0)
})

test('乱序注销只移除自己那一层', () => {
  const stack = createEscapeStack()
  const order = []
  const removePage = stack.push(() => order.push('page'))
  stack.push(() => order.push('dialog'))

  removePage()
  assert.equal(stack.size, 1)
  assert.equal(stack.handle(escape), true)
  assert.deepEqual(order, ['dialog'])
})

test('输入法组合中的 Esc 不关弹层', () => {
  const stack = createEscapeStack()
  let closed = 0
  stack.push(() => { closed += 1 })

  assert.equal(stack.handle({ key: 'Escape', isComposing: true }), false)
  assert.equal(stack.handle({ key: 'Escape', keyCode: 229 }), false)
  assert.equal(closed, 0)
  // 组合结束后同一层仍然能正常关。
  assert.equal(stack.handle(escape), true)
  assert.equal(closed, 1)
})

test('已被别处 preventDefault 的 Esc 不重复处理', () => {
  const stack = createEscapeStack()
  let closed = 0
  stack.push(() => { closed += 1 })

  assert.equal(stack.handle({ key: 'Escape', defaultPrevented: true }), false)
  assert.equal(closed, 0)
})
