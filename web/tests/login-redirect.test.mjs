/*
 * 登录回跳（2026-10-24 学生反馈）：老师把预习链接发到班群，学生没登录时点开，
 * 登录后必须回到那次布置，而不是被丢回首页。
 *
 * 这里只测纯逻辑：`safeNextPath` / `loginNextFrom` / `loginPath` 不碰 DOM，
 * 真实跳转链路由浏览器验收覆盖（本轮未做浏览器验收）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { loginNextFrom, loginPath, safeNextPath } from '../src/app/redirect.ts'

test('站内路径原样保留：学生点开的预习链接登录后还能回去', () => {
  assert.equal(safeNextPath('/vocab/assignments/abc'), '/vocab/assignments/abc')
  assert.equal(safeNextPath('/vocab/assignments/abc?x=1#y'), '/vocab/assignments/abc?x=1#y')
})

test('拒绝站外与协议相对地址，避免开放重定向', () => {
  assert.equal(safeNextPath('https://evil.example/vocab'), null)
  assert.equal(safeNextPath('//evil.example/vocab'), null)
  assert.equal(safeNextPath('/\\evil.example'), null)
  assert.equal(safeNextPath('javascript:alert(1)'), null)
  assert.equal(safeNextPath('/vocab\n/../x'), null)
  assert.equal(safeNextPath(''), null)
  assert.equal(safeNextPath(null), null)
  assert.equal(safeNextPath(undefined), null)
})

test('首页不写 next，其余页面带上查询与 hash', () => {
  assert.equal(loginNextFrom('/'), null)
  assert.equal(loginNextFrom('/vocab/assignments/abc'), '/vocab/assignments/abc')
  assert.equal(loginNextFrom('/vocab/library', '?sort=createdAt'), '/vocab/library?sort=createdAt')
  assert.equal(loginNextFrom('/vocab/library', '', '#top'), '/vocab/library#top')
})

test('登录地址对 next 做编码，没有 next 时就是 /login', () => {
  assert.equal(loginPath(null), '/login')
  assert.equal(loginPath('/vocab/assignments/abc'), '/login?next=%2Fvocab%2Fassignments%2Fabc')
})
