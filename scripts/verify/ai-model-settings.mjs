#!/usr/bin/env node
/*
 * 验证 D-30 修订（2026-10-24 再修订）：管理员 provider 列表 + 多模型勾选 + 管理员切换当前模型；
 * 教师页没有任何 AI 入口且教师切模型 403；学生看不到 AI 设置。
 * 用法：node scripts/verify/ai-model-settings.mjs（QA 5174 已部署）
 * 依赖：本机 Chrome；脚本自带一个假 OpenAI 服务，不需要真实 Ollama。
 */
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { mkdirSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import path from 'node:path'

const ROOT = path.resolve(import.meta.dirname, '../..')
const CHROME = process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const BASE = process.env.BASE ?? 'http://127.0.0.1:5174'
const CDP = process.env.CDP ?? 'http://127.0.0.1:9343'
const OUT = path.join(ROOT, process.env.OUT_DIR ?? 'docs/reports/t386-ai-model-settings')
const PROFILE = path.join(ROOT, '.cache/chrome-ai-model-settings')
const FAKE_PORT = Number(process.env.FAKE_PORT ?? 18089)
const ADMIN = { email: 'qa-admin@teachloop.local', password: 'qa-admin-pass-123' }
const TEACHER = { email: 'teacher@teachloop.local', password: 'teachloop123' }
const STUDENT = { email: 'student@teachloop.local', password: 'teachloop123' }
const MODELS = ['qwen3.8', 'qwen3.8-instruct']

mkdirSync(OUT, { recursive: true })
mkdirSync(PROFILE, { recursive: true })

const fake = createServer((req, res) => {
  const send = (code, body) => {
    res.writeHead(code, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(body))
  }
  if (req.method === 'GET' && req.url?.endsWith('/models')) {
    send(200, { object: 'list', data: MODELS.map((id) => ({ id, object: 'model' })) })
    return
  }
  if (req.method === 'POST' && req.url?.endsWith('/chat/completions')) {
    send(200, {
      id: 'chatcmpl-qa', object: 'chat.completion', model: MODELS[0],
      choices: [{ index: 0, message: { role: 'assistant', content: '{"ok":true}' }, finish_reason: 'stop' }],
      usage: { prompt_tokens: 1, completion_tokens: 1 },
    })
    return
  }
  send(404, { error: 'not found' })
})

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

async function setInput(id, value) {
  await evaluate(`(() => {
    const el = document.querySelector(${JSON.stringify('#' + id)});
    if (!el) return false;
    const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
    setter.call(el, ${JSON.stringify(value)});
    el.dispatchEvent(new Event('input', { bubbles: true }));
    return true;
  })()`)
}

async function clickButton(label) {
  return evaluate(`(() => {
    const btn = [...document.querySelectorAll('button')].find(b => b.textContent.trim() === ${JSON.stringify(label)});
    if (!btn) return false;
    btn.click();
    return true;
  })()`)
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
  if (status !== 200) throw new Error(`QA 已有管理员但不是 qa-admin，无法验证管理员页（HTTP ${status}）`)
}

async function main() {
  await new Promise((resolve, reject) => {
    fake.once('error', reject)
    fake.listen(FAKE_PORT, '127.0.0.1', resolve)
  })
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
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1400, deviceScaleFactor: 1, mobile: false })

  // ---- 管理员：通过 UI 添加 provider，真实点“获取可用模型”并勾选两个模型 ----
  await ensureAdmin()
  if ((await login(ADMIN)) !== 200) throw new Error('管理员登录失败')
  const existing = (await api('/api/admin/ai-providers')).body ?? []
  for (const p of existing.filter((x) => x.providerId === 'qaval')) {
    await api(`/api/admin/ai-providers/${p.id}`, 'DELETE')
  }

  await navigate('/admin/ai')
  await waitUntil(`[...document.querySelectorAll('button')].some(b => b.textContent.includes('添加 provider'))`, { label: '管理员模型页' })
  await evaluate(`[...document.querySelectorAll('button')].find(b => b.textContent.includes('添加 provider'))?.click()`)
  await waitUntil(`!!document.querySelector('#provider-key')`, { label: 'provider 表单' })
  await setInput('provider-key', 'qaval')
  await setInput('provider-name', 'Local Qwen')
  await setInput('provider-base-url', `http://127.0.0.1:${FAKE_PORT}/v1`)
  await setInput('provider-key-secret', '')
  await clickButton('获取可用模型')
  await waitUntil(`document.querySelectorAll('.tl-field input[type=checkbox]').length >= 2`, { label: '模型勾选列表' })
  const loaded = await evaluate(`({
    count: document.querySelectorAll('.tl-field input[type=checkbox]').length,
    labels: [...document.querySelectorAll('.tl-field label')].map(l => l.textContent.trim()),
  })`)
  check('获取可用模型后显示可勾选列表', loaded.count === 2, JSON.stringify(loaded))
  await evaluate(`document.querySelectorAll('.tl-field input[type=checkbox]').forEach(cb => { if (!cb.checked) cb.click() })`)
  await clickButton('保存 provider')
  await waitUntil(`document.body.innerText.includes('Local Qwen') && document.body.innerText.includes('qwen3.8-instruct')`, { label: 'provider 保存后列表' })
  const providers = (await api('/api/admin/ai-providers')).body ?? []
  const saved = providers.find((p) => p.providerId === 'qaval')
  check('保存后 provider 存了两个模型', saved?.models?.length === 2 && saved?.models?.includes('qwen3.8-instruct'), JSON.stringify(saved))
  await shot('admin')

  // ---- 教师：/profile 没有任何 AI 入口；教师切模型 403 ----
  await evaluate(`fetch('/api/auth/logout', { method: 'POST' })`)
  check('教师登录', (await login(TEACHER)) === 200)
  await navigate('/profile')
  await waitUntil(`!!document.querySelector('#profile-name')`, { label: '教师资料页' })
  const teacherView = await evaluate(`({
    hasModelSelect: !!document.querySelector('#teacher-ai-model'),
    hasAiCard: document.body.innerText.includes('AI 模型'),
    hasGrading: !!document.querySelector('#grading-mode'),
    hasPhone: !!document.querySelector('#profile-phone'),
  })`)
  check('教师看到资料与批改时机', teacherView.hasPhone && teacherView.hasGrading, JSON.stringify(teacherView))
  check('教师页没有 AI 模型卡片与下拉', !teacherView.hasModelSelect && !teacherView.hasAiCard, JSON.stringify(teacherView))
  const teacherPut = await api('/api/ai-models/current', 'PUT', { providerId: 'qaval', model: 'qwen3.8-instruct' })
  check('教师切换当前模型返回 403', teacherPut.status === 403, JSON.stringify(teacherPut))
  await shot('teacher')

  // ---- 管理员切换当前模型，全站生效 ----
  await evaluate(`fetch('/api/auth/logout', { method: 'POST' })`)
  check('管理员登录', (await login(ADMIN)) === 200)
  const adminPut = await api('/api/ai-models/current', 'PUT', { providerId: 'qaval', model: 'qwen3.8-instruct' })
  check('管理员切换当前模型成功', adminPut.status === 200 && adminPut.body?.currentModel === 'qwen3.8-instruct', JSON.stringify(adminPut))
  const current = await api('/api/ai-models')
  check('切换后全站当前模型是第二个', current.body?.currentModel === 'qwen3.8-instruct', JSON.stringify(current.body))

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
  try { fake.close() } catch { /* ignore */ }
})
