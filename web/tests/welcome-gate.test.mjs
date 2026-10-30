/*
 * US-93 / AC-93.2：`/welcome` 首次引导的闸门。
 *
 * 五种分支都要有断言（资料缺/不缺、已问过/没问过、豁免地址），
 * 外加「没有 localStorage 时不抛异常」——Safari 无痕模式就是这个形状。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  PROMPTED_KEY,
  WELCOME_PATH,
  clearPrompted,
  isPrompted,
  markPrompted,
  shouldPrompt,
} from '../src/app/welcomeGate.ts'

const base = { profileIncomplete: true, pathname: '/', prompted: false }

/** 假 localStorage：node 默认没有这个全局，页面里是真浏览器给的。 */
function withFakeStorage(run) {
  const store = new Map()
  const fake = {
    getItem: (key) => (store.has(key) ? store.get(key) : null),
    setItem: (key, value) => { store.set(key, String(value)) },
    removeItem: (key) => { store.delete(key) },
    clear: () => store.clear(),
    key: (index) => [...store.keys()][index] ?? null,
    get length() { return store.size },
  }
  Object.defineProperty(globalThis, 'localStorage', { value: fake, configurable: true, writable: true })
  try {
    run(store)
  } finally {
    delete globalThis.localStorage
  }
}

test('闸门：资料缺手机号 + 首页 + 没问过 → 需要引导', () => {
  assert.equal(shouldPrompt(base), true)
  assert.equal(WELCOME_PATH, '/welcome')
})

test('闸门：深链同样要引导（不是只认首页）', () => {
  assert.equal(shouldPrompt({ ...base, pathname: '/vocab/review' }), true)
})

test('闸门：已经问过 → 不再引导（AC-93.2 本设备只问一次）', () => {
  assert.equal(shouldPrompt({ ...base, prompted: true }), false)
})

test('闸门：资料不缺（或未知）→ 不引导', () => {
  assert.equal(shouldPrompt({ ...base, profileIncomplete: false }), false)
})

test('闸门：四个豁免地址都不拦，带尾斜杠也一样', () => {
  for (const pathname of ['/welcome', '/welcome/', '/profile', '/login', '/setup']) {
    assert.equal(shouldPrompt({ ...base, pathname }), false, pathname)
  }
})

test('记录：写入后 isPrompted 为真，清掉后回到假', () => {
  withFakeStorage((store) => {
    assert.equal(isPrompted(), false)
    markPrompted()
    assert.equal(store.get(PROMPTED_KEY), '1')
    assert.equal(isPrompted(), true)
    clearPrompted()
    assert.equal(isPrompted(), false)
  })
})

test('记录：没有 localStorage 时安全返回 false，写入不抛异常', () => {
  assert.equal(isPrompted(), false)
  assert.doesNotThrow(() => markPrompted())
  assert.doesNotThrow(() => clearPrompted())
})
