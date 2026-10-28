/*
 * 2026-10-28：管理端「邮件服务配置（SMTP）」表单 ↔ identity_channels 的映射。
 *
 * 页面上是七个字段，库里只有 from_address / base_url / extra 三列，
 * 任何一处写漏都会变成「保存成功但发信还是用旧值」。这里锁住映射本身；
 * 表单长什么样、测试邮件能否真发出去，由 dev 浏览器验收覆盖。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildEmailUpdate,
  parseHostPort,
  toEmailForm,
  validateEmailForm,
} from '../src/app/emailChannelForm.ts'

const row = {
  channel: 'email',
  enabled: true,
  driver: 'smtp',
  fromAddress: '19725156124@163.com',
  baseUrl: 'smtp.163.com:465',
  hasSecret: true,
  secretMask: '••••••••bt',
  secretUnreadable: false,
  dailyQuota: null,
  extra: { name: '系统管理员', encryption: 'ssl' },
}

const form = {
  enabled: true,
  fromName: '系统管理员',
  fromAddress: '19725156124@163.com',
  host: 'smtp.163.com',
  port: '465',
  security: 'ssl',
  username: '',
  secret: '',
}

test('base_url 拆成 host:port；缺端口按 465', () => {
  assert.deepEqual(parseHostPort('smtp.163.com:465'), { host: 'smtp.163.com', port: '465' })
  assert.deepEqual(parseHostPort('smtp.163.com'), { host: 'smtp.163.com', port: '465' })
  assert.deepEqual(parseHostPort(''), { host: '', port: '465' })
  assert.deepEqual(parseHostPort(null), { host: '', port: '465' })
  assert.deepEqual(parseHostPort('smtp.163.com:587'), { host: 'smtp.163.com', port: '587' })
})

test('行数据 → 表单：extra 里的名称与加密方式落到对应字段', () => {
  const view = toEmailForm(row)
  assert.equal(view.fromName, '系统管理员')
  assert.equal(view.fromAddress, '19725156124@163.com')
  assert.equal(view.host, 'smtp.163.com')
  assert.equal(view.port, '465')
  assert.equal(view.security, 'ssl')
  assert.equal(view.enabled, true)
})

test('授权码永远不回填：表单里的 secret 一律是空串', () => {
  assert.equal(toEmailForm(row).secret, '')
})

test('没有 extra 的老数据：名称空、加密方式按 ssl、认证账号留空走默认', () => {
  const view = toEmailForm({ ...row, extra: null })
  assert.equal(view.fromName, '')
  assert.equal(view.security, 'ssl')
  assert.equal(view.username, '')
})

test('extra 里写 starttls / none 时原样还原', () => {
  assert.equal(toEmailForm({ ...row, extra: { encryption: 'starttls' } }).security, 'starttls')
  assert.equal(toEmailForm({ ...row, extra: { encryption: 'none' } }).security, 'none')
})

test('表单 → 请求体：host 与 port 拼回 base_url，extra 带加密方式', () => {
  const body = buildEmailUpdate(form, null)
  assert.equal(body.baseUrl, 'smtp.163.com:465')
  assert.equal(body.fromAddress, '19725156124@163.com')
  assert.equal(body.driver, 'smtp')
  assert.deepEqual(body.extra, { encryption: 'ssl', name: '系统管理员' })
})

test('授权码留空表示不修改（secret=null），填了就带上', () => {
  assert.equal(buildEmailUpdate(form, null).secret, null)
  assert.equal(buildEmailUpdate({ ...form, secret: 'abcd1234' }, null).secret, 'abcd1234')
})

test('认证账号填了才写进 extra；不填由后端回落到发件邮箱', () => {
  assert.equal('user' in buildEmailUpdate(form, null).extra, false)
  assert.equal(buildEmailUpdate({ ...form, username: 'sender@163.com' }, null).extra.user, 'sender@163.com')
})

test('dailyQuota 原样带回，保存不会把它清空', () => {
  assert.equal(buildEmailUpdate(form, 200).dailyQuota, 200)
})

test('校验：服务器 / 端口 / 发件邮箱不合法都拦住', () => {
  assert.equal(validateEmailForm(form, true), null)
  assert.equal(validateEmailForm({ ...form, host: '  ' }, true), 'host')
  assert.equal(validateEmailForm({ ...form, port: '0' }, true), 'port')
  assert.equal(validateEmailForm({ ...form, port: '465abc' }, true), 'port')
  assert.equal(validateEmailForm({ ...form, fromAddress: 'teachloop admin' }, true), 'fromAddress')
  assert.equal(validateEmailForm({ ...form, fromAddress: '' }, true), 'fromAddress')
})

test('首次配置必须填授权码；已有授权码时留空合法', () => {
  assert.equal(validateEmailForm(form, false), 'secret')
  assert.equal(validateEmailForm(form, true), null)
  assert.equal(validateEmailForm({ ...form, secret: 'new-code' }, false), null)
})
