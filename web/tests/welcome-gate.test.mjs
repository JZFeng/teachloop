/*
 * US-93 / AC-93.2：`/welcome` 首次引导的闸门。
 *
 * 五种分支都要有断言（资料缺/不缺、已问过/没问过、豁免地址），
 * 外加「没有 localStorage 时不抛异常」——Safari 无痕模式就是这个形状。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  PROFILE_COLLECTION_PATHS,
  PROMPTED_KEY,
  WELCOME_PATH,
  clearPrompted,
  isProfileCollectionPage,
  isPrompted,
  markPrompted,
  shouldPrompt,
  shouldShowProfileBanner,
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

/*
 * 2026-10-30 Bug（用户截图 `/welcome`）：顶部「补全手机号」提示条在人已经站到它要去的那一页上时
 * 仍然显示。四组断言：目标页不显示 / 其他页照旧显示 / 关过就都不显示 / 资料完整（或未知）不显示。
 */
test('提示条：`/welcome` 与 `/profile` 自己不显示（AC-93.3 的目标页）', () => {
  assert.deepEqual([...PROFILE_COLLECTION_PATHS], ['/welcome', '/profile'])
  for (const pathname of ['/welcome', '/welcome/', '/profile', '/profile/', '/profile?next=/classes']) {
    assert.equal(isProfileCollectionPage(pathname), true, pathname)
    assert.equal(
      shouldShowProfileBanner({ profileComplete: false, pathname, dismissed: false }),
      false,
      pathname,
    )
  }
})

test('提示条：别的页面照旧显示（AC-93.3 跳过之后仍要提醒）', () => {
  for (const pathname of ['/', '/classes', '/vocab/review', '/notifications', '/classroom']) {
    assert.equal(isProfileCollectionPage(pathname), false, pathname)
    assert.equal(
      shouldShowProfileBanner({ profileComplete: false, pathname, dismissed: false }),
      true,
      pathname,
    )
  }
})

test('提示条：本会话关过就不再显示（含目标页）', () => {
  for (const pathname of ['/', '/welcome', '/profile']) {
    assert.equal(
      shouldShowProfileBanner({ profileComplete: false, pathname, dismissed: true }),
      false,
      pathname,
    )
  }
})

test('提示条：资料完整或未知 → 不显示（未知不等于缺失）', () => {
  for (const profileComplete of [true, null]) {
    assert.equal(
      shouldShowProfileBanner({ profileComplete, pathname: '/', dismissed: false }),
      false,
      String(profileComplete),
    )
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
