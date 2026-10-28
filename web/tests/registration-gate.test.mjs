/*
 * 2026-10-28 Bug（P1，仅生产复现）：注册页恒返回「该注册方式不可用」。
 *
 * 根因不在注册接口，而在生产 `identity_channels.email.enabled=false` 时注册页仍渲染表单：
 * `/api/auth/channels` 已经是 `[]`，用户填完三项才在提交时拿到 404。
 * 这里只测纯判断；注册页「显示哪一屏」由浏览器验收覆盖（dev 环境两态各看一次）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { channelReadiness, emailSignupOpen } from '../src/app/registrationGate.ts'

const configured = {
  enabled: true,
  fromAddress: 'someone@163.com',
  baseUrl: 'smtp.163.com:465',
  hasSecret: true,
  secretUnreadable: false,
}

test('通道列表为空（生产事故现场）→ 注册页不渲染表单', () => {
  assert.equal(emailSignupOpen([]), false)
})

test('只有非邮箱通道可用时也不算能注册', () => {
  assert.equal(emailSignupOpen([{ channel: 'sms' }, { channel: 'wechat' }]), false)
})

test('邮箱通道被下发时渲染表单', () => {
  assert.equal(emailSignupOpen([{ channel: 'email' }]), true)
})

test('配置齐全且已启用 → ready', () => {
  assert.equal(channelReadiness(configured), 'ready')
})

test('配置齐全但开关关着 → disabled（生产当时的实际状态）', () => {
  assert.equal(channelReadiness({ ...configured, enabled: false }), 'disabled')
})

test('发件人不是邮箱 / 缺服务地址 / 缺密钥 → 都算 incomplete', () => {
  for (const patch of [
    { fromAddress: 'teachloop admin' },
    { fromAddress: '   ' },
    { fromAddress: null },
    { baseUrl: '' },
    { hasSecret: false },
  ]) {
    assert.equal(channelReadiness({ ...configured, ...patch, enabled: false }), 'incomplete')
  }
})

test('密钥解不开优先报 unreadable（开不开开关都不生效）', () => {
  assert.equal(channelReadiness({ ...configured, secretUnreadable: true }), 'unreadable')
})
