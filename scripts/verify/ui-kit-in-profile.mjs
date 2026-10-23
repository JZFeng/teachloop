#!/usr/bin/env node
/*
 * 验证：界面标准从侧栏导航收进「我的资料 → 偏好设置」，旧 /ui-kit 链接跳到展开态。
 * 用法：node scripts/verify/ui-kit-in-profile.mjs
 * 依赖：本机 Chrome + dev server（5173，代理到有数据的后端）。
 */
import { spawn } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import path from 'node:path'

const ROOT = path.resolve(import.meta.dirname, '../..')
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const BASE = process.env.BASE ?? 'http://localhost:5173'
const CDP = 'http://127.0.0.1:9341'
const OUT = path.join(ROOT, 'docs/reports/t356-ui-kit-in-profile')
const PROFILE = '/tmp/chrome-verify-ui-kit'
const TEACHER = { email: 'teacher@teachloop.local', password: 'teachloop123' }
const STUDENT = { email: 'student02@teachloop.local', password: 'teachloop123' }

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
const consoleErrors = []

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
    } catch { /* 还没起来 */ }
    await sleep(250)
  }
  throw new Error('Chrome 未在 20 秒内启动 remote debugging')
}

async function navigate(route) {
  await send('Page.navigate', { url: `${BASE}${route}` })
  for (let i = 0; i < 60; i += 1) {
    const ready = await evaluate('document.readyState === "complete"').catch(() => false)
    if (ready) break
    await sleep(150)
  }
  await evaluate('document.fonts?.ready ? document.fonts.ready.then(() => true) : true')
  await sleep(900)
}

async function waitUntil(expression, { timeout = 12000, label = expression } = {}) {
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
  const status = await evaluate(
    `fetch('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: ${JSON.stringify(JSON.stringify(account))} }).then(r => r.status)`,
  )
  return status
}

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`)
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
      return
    }
    if (message.method === 'Runtime.exceptionThrown') {
      consoleErrors.push(message.params?.exceptionDetails?.text ?? 'exception')
    }
    if (message.method === 'Log.entryAdded' && message.params?.entry?.level === 'error') {
      consoleErrors.push(message.params.entry.text)
    }
  })
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve)
    ws.addEventListener('error', reject)
  })
  await send('Page.enable')
  await send('Runtime.enable')
  await send('Log.enable')
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false })

  // ---- 教师 ----
  const teacherStatus = await login(TEACHER)
  check('教师登录', teacherStatus === 200, `HTTP ${teacherStatus}`)
  consoleErrors.length = 0

  await navigate('/')
  await waitUntil(`document.querySelectorAll('#nav a').length > 0`, { label: '侧栏导航渲染' })
  const nav = await evaluate(`({
    labels: [...document.querySelectorAll('#nav a')].map(a => a.textContent.trim()),
    hrefs: [...document.querySelectorAll('#nav a')].map(a => a.getAttribute('href')),
  })`)
  check('侧栏不再有组件标准入口',
    !nav.hrefs.includes('/ui-kit') && !nav.labels.some((line) => line.includes('组件标准') || line.includes('界面标准')),
    JSON.stringify(nav))
  check('教师导航回到 7 项', nav.hrefs.length === 7, `${nav.hrefs.length} 项`)

  await navigate('/profile')
  await waitUntil(`document.body.textContent.includes('界面标准')`, { label: '偏好设置里的界面标准行' })
  const closed = await evaluate(`({
    panel: !!document.getElementById('ui-kit-panel'),
    expanded: document.querySelector('[aria-controls="ui-kit-panel"]')?.getAttribute('aria-expanded'),
  })`)
  check('默认收起', closed.panel === false && closed.expanded === 'false', JSON.stringify(closed))

  await evaluate(`(() => {
    const button = [...document.querySelectorAll('button')].find(b => b.textContent.trim() === '展开界面标准')
    if (!button) return false
    button.click()
    return true
  })()`)
  await waitUntil(`!!document.getElementById('ui-kit-panel')`, { label: '展开面板' })
  await sleep(700)
  const opened = await evaluate(`(() => {
    const panel = document.getElementById('ui-kit-panel')
    return {
      sections: panel.querySelectorAll('.tl-kit-section').length,
      hasButtonSection: panel.textContent.includes('按钮'),
      hasTableSection: panel.textContent.includes('表格'),
      hasBrandSection: panel.textContent.includes('品牌色板与功能位'),
      expanded: document.querySelector('[aria-controls="ui-kit-panel"]')?.getAttribute('aria-expanded'),
      url: location.pathname + location.search,
    }
  })()`)
  check('展开后面板完整', opened.sections >= 10 && opened.hasButtonSection && opened.hasTableSection && opened.hasBrandSection,
    JSON.stringify(opened))
  check('展开状态同步到 URL', opened.url === '/profile?panel=ui-kit', opened.url)
  await shot('profile-ui-kit-light')

  // 旧链接 /ui-kit 跳到展开态
  await navigate('/ui-kit')
  await waitUntil(`location.pathname === '/profile' && !!document.getElementById('ui-kit-panel')`, { label: '旧链接跳转' })
  const redirected = await evaluate(`location.pathname + location.search`)
  check('/ui-kit 跳到资料页展开态', redirected === '/profile?panel=ui-kit', redirected)

  // 收起
  await evaluate(`(() => {
    const button = [...document.querySelectorAll('button')].find(b => b.textContent.trim() === '收起界面标准')
    if (!button) return false
    button.click()
    return true
  })()`)
  await waitUntil(`!document.getElementById('ui-kit-panel')`, { label: '收起面板' })
  const collapsed = await evaluate(`({ url: location.pathname + location.search, hasButton: document.body.textContent.includes('展开界面标准') })`)
  check('收起后 URL 干净', collapsed.url === '/profile' && collapsed.hasButton, JSON.stringify(collapsed))
  await evaluate('window.scrollTo(0, 0); true')
  await sleep(300)
  await shot('profile-preferences-collapsed')

  // 暗色截图
  await evaluate(`localStorage.setItem('teachloop.theme', 'dark'); true`)
  await navigate('/profile?panel=ui-kit')
  await waitUntil(`!!document.getElementById('ui-kit-panel')`, { label: '暗色下展开' })
  await sleep(900)
  await shot('profile-ui-kit-dark')
  await evaluate(`localStorage.setItem('teachloop.theme', 'light'); true`)

  const teacherErrors = [...consoleErrors]
  check('教师侧无控制台异常', teacherErrors.length === 0, teacherErrors.join(' | '))

  // ---- 学生：看不到界面标准行 ----
  const studentStatus = await login(STUDENT)
  check('学生登录', studentStatus === 200, `HTTP ${studentStatus}`)
  await navigate('/profile')
  await waitUntil(`document.body.textContent.includes('偏好设置')`, { label: '学生资料页' })
  const studentView = await evaluate(`({
    hasDesignRow: document.body.textContent.includes('界面标准'),
    hasPanel: !!document.getElementById('ui-kit-panel'),
    language: document.body.textContent.includes('界面语言'),
  })`)
  check('学生看不到界面标准（偏好设置仍在）', studentView.hasDesignRow === false && studentView.hasPanel === false && studentView.language,
    JSON.stringify(studentView))

  const failed = results.filter((item) => !item.ok)
  console.log(`\n${failed.length === 0 ? '全部通过' : `${failed.length} 项失败`}（${results.length} 项）`)
  process.exitCode = failed.length === 0 ? 0 : 1
}

main()
  .catch((error) => {
    console.error('验证脚本异常：', error)
    process.exitCode = 1
  })
  .finally(() => {
    try { ws?.close() } catch { /* ignore */ }
    chrome.kill('SIGKILL')
  })
