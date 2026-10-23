#!/usr/bin/env node
/*
 * T354 验证：弹层按 Esc 关闭（CDP 真键盘事件，不是模拟 click）。
 *
 * 用法：node scripts/verify/escape-dismiss.mjs
 * 依赖：本机 Chrome + 跑着的 dev server（默认 http://localhost:5173，代理到有数据的后端）。
 * 结果：控制台输出每一项 PASS/FAIL；截图写到 docs/reports/t354-esc-dismiss/。
 */
import { spawn } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import path from 'node:path'

const ROOT = path.resolve(import.meta.dirname, '../..')
const CHROME = process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const BASE = process.env.BASE ?? 'http://localhost:5173'
const CDP = 'http://127.0.0.1:9336'
const OUT = path.join(ROOT, 'docs/reports/t354-esc-dismiss')
const PROFILE = path.join(ROOT, '.cache/chrome-esc-dismiss')
const OFFERING = process.env.OFFERING ?? 'bfd90d97-4e47-484a-9ef7-12e9fc31b9e2'
const TEACHER = { email: 'teacher@teachloop.local', password: 'teachloop123' }

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
    const id = ++nextId
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
  await sleep(700)
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

async function pressEscape() {
  const base = { key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27, nativeVirtualKeyCode: 27 }
  await send('Input.dispatchKeyEvent', { type: 'rawKeyDown', ...base })
  await send('Input.dispatchKeyEvent', { type: 'keyUp', ...base })
  await sleep(450)
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
  await send('Emulation.setDeviceMetricsOverride', {
    width: 1440, height: 900, deviceScaleFactor: 1, mobile: false,
  })

  // 登录（与 scripts/brand/shoot-app.mjs 同一套：先开页面拿到 CSRF，再 POST）。
  await navigate('/login')
  const loginStatus = await evaluate(
    `fetch('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: ${JSON.stringify(JSON.stringify(TEACHER))} }).then(r => r.status)`,
  )
  check('教师登录', loginStatus === 200, `HTTP ${loginStatus}`)
  // 登录前 RequireAuth 会打一次 /api/me 拿 401，那是设计内噪音；从这里开始算异常。
  consoleErrors.length = 0
  const offerings = await evaluate(`fetch('/api/offerings').then(r => r.json())`)
  const ids = (offerings ?? []).map((item) => item.id)
  check('拿到作业场次', Array.isArray(offerings) && offerings.length > 0, `${ids.length} 场`)

  // ---- 1. 审计抽屉（用户报的那个弹层）----
  await navigate(`/grading/${OFFERING}`)
  await waitUntil(
    `[...document.querySelectorAll('button')].some(b => b.textContent.trim() === '审计')`,
    { label: '审计按钮出现' },
  )
  const clicked = await evaluate(`(() => {
    const button = [...document.querySelectorAll('button')].find(b => b.textContent.trim() === '审计')
    if (!button) return false
    button.click()
    return true
  })()`)
  check('点开审计抽屉', clicked === true)
  await waitUntil(`!!document.querySelector('aside[role="dialog"]')`, { label: '审计抽屉出现' })
  await waitUntil(`!!document.querySelector('aside[role="dialog"] .audit-timeline li')`, { label: '审计记录加载完成' })

  const drawer = await evaluate(`(() => {
    const node = document.querySelector('aside[role="dialog"]')
    return {
      exists: !!node,
      ariaModal: node?.getAttribute('aria-modal'),
      label: node?.getAttribute('aria-label'),
      heading: node?.querySelector('h2')?.textContent ?? null,
      events: node?.querySelectorAll('.audit-timeline li').length ?? 0,
      bodyLocked: getComputedStyle(document.body).overflow,
    }
  })()`)
  check('审计抽屉是无障碍 dialog', drawer.ariaModal === 'true' && drawer.label === '审计时间线', JSON.stringify(drawer))
  const openShot = await shot('audit-open')

  await pressEscape()
  const afterEsc = await evaluate(`({
    drawer: !!document.querySelector('aside[role="dialog"]'),
    path: location.pathname,
    tableRows: document.querySelectorAll('tbody tr').length,
  })`)
  check('Esc 关闭审计抽屉', afterEsc.drawer === false, JSON.stringify(afterEsc))
  check('关闭后页面与路由不动', afterEsc.path === `/grading/${OFFERING}` && afterEsc.tableRows > 0)
  const closedShot = await shot('audit-closed')

  // 关闭后再按一次 Esc 不应有副作用。
  await pressEscape()
  check('无弹层时 Esc 无副作用', await evaluate(`location.pathname === '/grading/${OFFERING}'`))

  // ---- 2. 放大编辑器遮罩（多层弹层的另一层）----
  await navigate('/assignments/new')
  await waitUntil(`!!document.querySelector('button[aria-label="放大编辑"]')`, { timeout: 20000, label: '放大按钮出现' })
  await evaluate(`document.querySelector('button[aria-label="放大编辑"]').click()`)
  await waitUntil(`!!document.querySelector('.md-overlay')`, { label: '编辑遮罩出现' })
  await waitUntil(`!!document.querySelector('.md-overlay .vditor')`, { timeout: 20000, label: 'Vditor 初始化完成' })
  await sleep(800)
  const editorOpen = await evaluate(`(() => {
    const node = document.querySelector('.md-overlay')
    return { role: node?.getAttribute('role'), ariaModal: node?.getAttribute('aria-modal'), locked: getComputedStyle(document.body).overflow }
  })()`)
  check('放大编辑器遮罩打开且锁滚动', editorOpen.locked === 'hidden', JSON.stringify(editorOpen))
  await shot('editor-open')
  await pressEscape()
  const editorClosed = await evaluate(`({ overlay: !!document.querySelector('.md-overlay'), overflow: getComputedStyle(document.body).overflow })`)
  check('Esc 收起放大编辑器并恢复滚动', editorClosed.overlay === false && editorClosed.overflow !== 'hidden', JSON.stringify(editorClosed))
  await shot('editor-closed')

  check('无未捕获异常 / 控制台 error', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '))

  console.log(`\n截图：${OUT}`)
  console.log(`弹层内事件数：${drawer.events}；审计打开截图 ${path.basename(openShot)} / 关闭截图 ${path.basename(closedShot)}`)
  const failed = results.filter((item) => !item.ok)
  if (failed.length > 0) {
    console.error(`\n${failed.length} 项失败`)
    process.exitCode = 1
  } else {
    console.log(`\n全部 ${results.length} 项通过`)
  }
}

main()
  .catch((error) => {
    console.error('FAIL  验证脚本异常：', error)
    process.exitCode = 1
  })
  .finally(() => {
    try { ws?.close() } catch { /* ignore */ }
    chrome.kill('SIGKILL')
  })
