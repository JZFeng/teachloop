/*
 * 2026-10-29（Bug）：学生把地址栏改成 /admin/ai 就能整页看到「系统设置」（模型列表、通道配置、
 * 保存按钮），只有接口被后端 403 挡住。根因是这条路由当时没挂 `RequireFeature`。
 *
 * 这里锁的是守卫的判定本身：没有 nav.ai 的账号拿到的是 deny（路由层退回落地页，
 * 页面壳与内容都不渲染）；开关没回来之前是 loading，不能先判 deny。
 * 真实跳转仍由浏览器验收覆盖。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { featureGate } from '../src/app/featureGate.ts'

const STUDENT = { 'nav.home': true, 'nav.classes': true, 'nav.vocab.library': true }
const TEACHER = { 'nav.home': true, 'nav.classes': true, 'nav.papers': true }
const ADMIN = { 'nav.ai': true }

test('学生/教师没有 nav.ai：/admin/ai 的守卫必须是 deny，不是渲染页面', () => {
  for (const features of [STUDENT, TEACHER]) {
    assert.equal(featureGate({ loaded: true, features, feature: 'nav.ai' }), 'deny')
  }
})

test('管理员是唯一放行 nav.ai 的角色', () => {
  assert.equal(featureGate({ loaded: true, features: ADMIN, feature: 'nav.ai' }), 'allow')
})

test('开关还没回来时是 loading：不能先 deny，否则深链会被先弹回落地页', () => {
  assert.equal(featureGate({ loaded: false, features: {}, feature: 'nav.ai' }), 'loading')
})

test('key 写成字符串 "true" 之类不放行：只有布尔 true 才算有权限', () => {
  assert.equal(featureGate({ loaded: true, features: { 'nav.ai': 'true' }, feature: 'nav.ai' }), 'deny')
})

test('不相关的 key 不影响本页判定（学生进自己的词库照旧放行）', () => {
  assert.equal(featureGate({ loaded: true, features: STUDENT, feature: 'nav.vocab.library' }), 'allow')
  assert.equal(featureGate({ loaded: true, features: STUDENT, feature: 'nav.papers' }), 'deny')
})
