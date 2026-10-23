#!/usr/bin/env node
/*
 * 验证 D-30 修订：管理员 provider 列表 + 教师全局模型下拉；学生看不到 AI 设置。
 * 用法：node scripts/verify/ai-model-settings.mjs（QA 5174 已部署）
 */
import { spawn } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import path from 'node:path'

const ROOT = path.resolve(import.meta.dirname, '../..')
const CHROME = process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const BASE = process.env.BASE ?? 'http://127.0.0.1:5174'
const CDP = process.env.CDP ?? 'http://127.0.0.1:9343'
const OUT = path.join(ROOT, process.env.OUT_DIR ?? 'docs/reports/t386-ai-model-settings')
const PROFILE = path.join(ROOT, '.cache/chrome-ai-model-settings')
const ADMIN = { email: 'qa-admin@teachloop.local', password: 'qa-admin-pass-123' }
const TEACHER = { email: 'teacher@teachloop.local', password: 'teachloop123' }
const STUDENT = { email: 'student@teachloop.local', password: 'teachloop123' }

mkdirSync(OUT, { recursive: true })
mkdirSync(PROFILE, { recursive: true })

const chrome = spawn(CHROME, [
  '--headless=new', '--no-sandbox', '--disable-gpu', '--disable-crash-reporter',
  '--disable-breakpad', '--no-first-run', `--user-data-dir=${PROFILE}`,
  `--remote-debugging-port=${new URL(CDP).port}`, '--hide-scrollbars', 'about:blank',
], { stdio: 'ignore' })

let ws
let nextId = 0
const pending = new Map()

function send(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = (nextId += 1)
    pending.set(id, { resolve, reject })
    ws.send(JSON.stringify({ id, method, params }))
  })
}

async function evaluate(expression) {
  const result = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
  if (result?.exceptionDetails) throw new Error(result.exceptionDetails.text ?? 'Runtime.evaluate 失败')
  return result?.result?.value
}

async function waitForChrome() {
  for (let i = 0; i < 80; i += 1) {
    try {
      if ((await fetch(`${CDP}/json/version`)).ok) return
    } catch { /* not ready */ }
    await sleep(250)
  }
  throw new Error('Chrome 未在 20 秒内启动 remote debugging')
}

async function navigate(route) {
  await send('Page.navigate', { url: `${BASE}${route}` })
  for (let i = 0; i < 60; i += 1) {
    if (await evaluate('document.readyState === "complete"').catch(() => false)) break
    await sleep(150)
  }
  await evaluate('document.fonts?.ready ? document.fonts.ready.then(() => true) : true')
  await sleep(900)
}

async function waitUntil(expression, { timeout = 15000, label = expression } = {}) {
  const started = Date.now()
  while (Date.now() - started < timeout) {
    if (await evaluate(expression).catch(() => false)) return true
    await sleep(200)
  }
  throw new Error(`等待超时：${label}`)
}

async function shot(name) {
  const { data } = await send('Page.captureScreenshot', { format: 'png' })
  const file = path.join(OUT, `${name}.png`)
  writeFileSync(file, Buffer.from(data, 'base64'))
  return file
}

async function login(account) {
  await navigate('/login')
  return evaluate(
    `fetch('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: ${JSON.stringify(JSON.stringify(account))} }).then(r => r.status)`,
  )
}

async function api(pathname, method = 'GET', body = null) {
  return evaluate(`fetch(${JSON.stringify(pathname)}, {
    method: ${JSON.stringify(method)},
    headers: { 'Content-Type': 'application/json' },
    body: ${body === null ? 'null' : JSON.stringify(JSON.stringify(body))},
  }).then(async r => ({ status: r.status, body: await r.json().catch(() => null) }))`)
}

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`)
}

async function ensureAdmin() {
  await navigate('/login')
  const setup = await api('/api/setup/status')
  if (setup.body?.needsAdmin) {
    const created = await api('/api/setup/admin', 'POST', {
      email: ADMIN.email, password: ADMIN.password, displayName: 'QA Admin',
    })
    if (created.status !== 200) throw new Error(`创建管理员失败：${created.status} ${JSON.stringify(created.body)}`)
    return
  }
  const status = await login(ADMIN)
  if (status !== 200) throw new Error(`QA 已有管理员但没有 qa-admin 账号，无法验证管理员页（HTTP ${status}）`)
}

async function main() {
  await waitForChrome()
  const target = await (await fetch(`${CDP}/json/new?${encodeURIComponent('about:blank')}`, { method: 'PUT' })).json()
  ws = new WebSocket(target.webSocketDebuggerUrl)
  ws.addEventListener('message', (event) => {
    const message = JSON.parse(event.data)
    if (message.id && pending.has(message.id)) {
      const { resolve, reject } = pending.get(message.id)
      pending.delete(message.id)
      if (message.error) reject(new Error(message.error.message))
      else resolve(message.result)
    }
  })
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve)
    ws.addEventListener('error', reject)
  })
  await send('Page.enable')
  await send('Runtime.enable')
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1300, deviceScaleFactor: 1, mobile: false })

  // ---- 管理员：创建/登录，添加一个测试 provider（第一个自动成为当前模型） ----
  await ensureAdmin()
  if ((await login(ADMIN)) !== 200) throw new Error('管理员登录失败')
  let providers = (await api('/api/admin/ai-providers')).body ?? []
  let provider = providers.find((p) => p.providerId === 'qaval')
  if (!provider) {
    const created = await api('/api/admin/ai-providers', 'POST', {
      providerId: 'qaval', displayName: 'Local Qwen', protocol: 'openai',
      baseUrl: 'http://127.0.0.1:11434/v1', apiKey: null, model: 'qwen3.8',
    })
    if (created.status !== 200) throw new Error(`添加 provider 失败：${created.status} ${JSON.stringify(created.body)}`)
    provider = created.body
  }
  check('管理员创建 provider 并自动成为当前模型', provider.active === true, JSON.stringify(provider))

  await navigate('/admin/ai')
  await waitUntil(`document.body.innerText.includes('已配置的 provider')`, { label: '管理员模型页' })
  const adminView = await evaluate(`({
    hasLocal: document.body.innerText.includes('Local Qwen'),
    hasAdd: [...document.querySelectorAll('button')].some(b => b.textContent.includes('添加 provider')),
    hasActive: document.body.innerText.includes('当前使用'),
    hasProviderKeyField: !!document.querySelector('#provider-key'),
  })`)
  check('管理员页显示 provider 列表与当前标记', adminView.hasLocal && adminView.hasActive, JSON.stringify(adminView))
  check('管理员页有添加入口', adminView.hasAdd)
  await evaluate(`[...document.querySelectorAll('button')].find(b => b.textContent.includes('添加 provider'))?.click()`)
  await waitUntil(`!!document.querySelector('#provider-key')`, { label: 'provider 表单' })
  check('provider 表单有 ID/地址/协议/Key/模型字段', await evaluate(`!!document.querySelector('#provider-key') && !!document.querySelector('#provider-base-url') && !!document.querySelector('#provider-protocol') && !!document.querySelector('#provider-key-secret') && !!document.querySelector('#provider-model')`))
  await shot('admin')

  // ---- 教师：只看到下拉；切换全局模型 ----
  await evaluate(`fetch('/api/auth/logout', { method: 'POST' })`)
  check('教师登录', (await login(TEACHER)) === 200)
  await navigate('/profile')
  await waitUntil(`!!document.querySelector('#teacher-ai-model')`, { label: '教师模型下拉' })
  const teacherView = await evaluate(`({
    options: [...document.querySelector('#teacher-ai-model').options].map(o => o.textContent),
    hasOldProviderFields: !!document.querySelector('#ai-provider') || !!document.querySelector('#ai-base-url') || !!document.querySelector('#ai-key'),
    hasGrading: !!document.querySelector('#grading-mode'),
    current: document.body.innerText.match(/当前全站模型：([^\\n]+)/)?.[1] ?? null,
  })`)
  check('教师页只看到模型下拉', teacherView.options.some(t => t.includes('Local Qwen')) && !teacherView.hasOldProviderFields, JSON.stringify(teacherView))
  check('教师页仍有批改时机', teacherView.hasGrading)
  await evaluate(`document.querySelector('#teacher-ai-model').value = 'qaval'; document.querySelector('#teacher-ai-model').dispatchEvent(new Event('change', { bubbles: true }))`)
  await evaluate(`[...document.querySelectorAll('button')].find(b => b.textContent.trim() === '保存选择')?.click()`)
  await sleep(700)
  const teacherCurrent = await api('/api/ai-models')
  check('教师保存后全站当前模型为 qaval', teacherCurrent.body?.currentProviderId === 'qaval', JSON.stringify(teacherCurrent.body))
  await shot('teacher')

  // ---- 学生：看不到 AI ----
  await evaluate(`fetch('/api/auth/logout', { method: 'POST' })`)
  check('学生登录', (await login(STUDENT)) === 200)
  await navigate('/profile')
  await waitUntil(`!!document.querySelector('#profile-language')`, { label: '学生资料页' })
  const studentView = await evaluate(`({
    hasModelSelect: !!document.querySelector('#teacher-ai-model'),
    hasAiCard: document.body.innerText.includes('AI 模型'),
    hasLanguage: !!document.querySelector('#profile-language'),
    hasPhone: !!document.querySelector('#profile-phone'),
  })`)
  check('学生看到账号与语言', studentView.hasPhone && studentView.hasLanguage)
  check('学生看不到 AI 模型', !studentView.hasModelSelect && !studentView.hasAiCard)
  await shot('student')

  // ---- 清理测试 provider，恢复 QA 的旧全局配置 ----
  await evaluate(`fetch('/api/auth/logout', { method: 'POST' })`)
  await login(ADMIN)
  const all = (await api('/api/admin/ai-providers')).body ?? []
  for (const p of all.filter((x) => x.providerId === 'qaval')) {
    await api(`/api/admin/ai-providers/${p.id}`, 'DELETE')
  }
  const after = await api('/api/admin/ai-providers')
  check('清理测试 provider', (after.body ?? []).every((p) => p.providerId !== 'qaval'), JSON.stringify(after.body))

  const failed = results.filter(r => !r.ok)
  writeFileSync(path.join(OUT, 'results.json'), JSON.stringify(results, null, 2))
  console.log(`\n${results.length - failed.length}/${results.length} PASS`)
  if (failed.length) process.exitCode = 1
}

main().catch((err) => {
  console.error(err)
  process.exitCode = 1
}).finally(() => {
  try { chrome.kill('SIGTERM') } catch { /* ignore */ }
})
