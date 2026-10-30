/*
 * 资料补全的 URL 工具（2026-10-27 引入，011 / T561 之后收窄）。
 *
 * 011（US-93）把「资料未补全就强制改道 /profile」删掉了：普通读取请求不再被拦，
 * 首次登录走可跳过的 /welcome（见 tests/welcome-gate.test.mjs），之后只留顶部提示条。
 * 这里只锁住仍然在用的部分：补全后「回哪里」的地址校验（登录回跳、资料页保存都靠它）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  PROFILE_PATH,
  nextAfterProfile,
  profilePathWithNext,
} from '../src/app/profileGate.ts'

test('资料页地址常量不变（登录回跳、顶部提示条都在用）', () => {
  assert.equal(PROFILE_PATH, '/profile')
})

test('改道时把原地址带进 next：补全后能回到本来要去的页面', () => {
  assert.equal(profilePathWithNext('/classes'), '/profile?next=%2Fclasses')
  assert.equal(
    profilePathWithNext('/vocab/review/session/abc?x=1'),
    '/profile?next=%2Fvocab%2Freview%2Fsession%2Fabc%3Fx%3D1',
  )
  // 首页不值得带 next（与登录回跳同一约定），没有 next 时就是干净的 /profile。
  assert.equal(profilePathWithNext(null), '/profile')
  assert.equal(profilePathWithNext(undefined), '/profile')
})

test('保存后只回站内路径，站外地址一律丢掉', () => {
  assert.equal(nextAfterProfile('/classes'), '/classes')
  assert.equal(nextAfterProfile('https://evil.example/x'), null)
  assert.equal(nextAfterProfile('//evil.example/x'), null)
  assert.equal(nextAfterProfile('/\\evil.example'), null)
  assert.equal(nextAfterProfile(null), null)
})

test('学生深链带 next：补全保存后回到原地址（不是首页）', () => {
  const next = profilePathWithNext('/assignments/abc?tab=submit')
  assert.equal(nextAfterProfile(new URL(next, 'http://x').searchParams.get('next')),
    '/assignments/abc?tab=submit')
})
