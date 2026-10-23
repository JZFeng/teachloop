#!/usr/bin/env node
/*
 * T358 验证：首页/班级是中性导航（不是漏做），UI Kit 里能看到口径说明。
 *
 * 用法：BASE=http://127.0.0.1:5174 node scripts/verify/nav-tone-note.mjs
 * 依赖：本机 Chrome + 跑着的站点（QA 5174 或 dev 5173，需代理到后端）。
 * 结果：控制台输出每一项 PASS/FAIL；截图写到 docs/reports/t358-nav-tone-note/。
 *
 * 为什么用真浏览器：导航着色是 CSS + Shell 配置共同决定的，单测只能覆盖配置对象；
 * 「首页/班级没有彩色胶囊、其余三项有」与「UI Kit 说明在中英下都可见」要按渲染结果核对。
 */
import { spawn } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import path from 'node:path'

const ROOT = path.resolve(import.meta.dirname, '../..')
const CHROME = process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const BASE = process.env.BASE ?? 'http://127.0.0.1:5174'
const CDP = process.env.CDP ?? 'http://127.0.0.1:9338'
const OUT = path.join(ROOT, process.env.OUT_DIR ?? 'docs/reports/t358-nav-tone-note')
const PROFILE = path.join(ROOT, '.cache/chrome-nav-tone-note')
const TEACHER = { email: process.env.EMAIL ?? 'teacher@teachloop.local', password: process.env.PASSWORD ?? 'teachloop123' }

const ZH_NOTE = '导航只给已上线的三个功能位上色'
const EN_NOTE = 'Only the three live feature slots carry nav colors'

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

async function shot(name, clip) {
  const params = { format: 'png' }
  if (clip) params.clip = { ...clip, scale: 1 }
  const { data } = await send('Page.captureScreenshot', params)
  const file = path.join(OUT, `${name}.png`)
  writeFileSync(file, Buffer.from(data, 'base64'))
  return file
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
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false })

  // ---- 0. 登录 ----
  await navigate('/login')
  const loginStatus = await evaluate(
    `fetch('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: ${JSON.stringify(JSON.stringify(TEACHER))} }).then(r => r.status)`,
  )
  check('教师登录', loginStatus === 200, `HTTP ${loginStatus}`)
  consoleErrors.length = 0
  // 复用同一个 Chrome profile 时语言会留在上一次的值；本轮固定从中文开始核对。
  await evaluate(`localStorage.setItem('teachloop.lang', 'zh-CN')`)

  // ---- 1. 导航：中性项无胶囊，三个功能位有胶囊 ----
  await navigate('/')
  await waitUntil(`document.querySelectorAll('#nav .nav-link').length >= 7`, { label: '侧栏导航出现' })
  const nav = await evaluate(`(() => [...document.querySelectorAll('#nav .nav-link')].map((a) => {
    const ico = a.querySelector('.nav-ico')
    const style = ico ? getComputedStyle(ico) : null
    return {
      href: a.getAttribute('href'),
      label: a.textContent.trim(),
      tone: a.dataset.tone ?? null,
      bg: style ? style.backgroundColor : null,
      color: style ? style.color : null,
    }
  }))()`)
  const byHref = new Map(nav.map((item) => [item.href, item]))
  const transparent = (value) => value === 'rgba(0, 0, 0, 0)' || value === 'transparent'
  check('首页没有功能色胶囊', byHref.get('/')?.tone === null && transparent(byHref.get('/')?.bg ?? ''),
    JSON.stringify(byHref.get('/')))
  check('班级没有功能色胶囊', byHref.get('/classes')?.tone === null && transparent(byHref.get('/classes')?.bg ?? ''),
    JSON.stringify(byHref.get('/classes')))
  check('作业/试卷 = create 蓝', byHref.get('/assignments')?.tone === 'create' && byHref.get('/papers')?.tone === 'create'
    && !transparent(byHref.get('/assignments')?.bg ?? '') && !transparent(byHref.get('/papers')?.bg ?? ''))
  check('批改/复核 = assess 青', byHref.get('/grading')?.tone === 'assess' && byHref.get('/review')?.tone === 'assess')
  check('报表 = insights 玫红', byHref.get('/reports')?.tone === 'insights')
  await shot('home-nav', { x: 0, y: 0, width: 260, height: 720 })

  await navigate('/classes')
  await waitUntil(`document.querySelectorAll('#nav .nav-link').length >= 7`, { label: '班级页导航出现' })
  await shot('classes-nav', { x: 0, y: 0, width: 260, height: 720 })

  // ---- 2. UI Kit：中文说明可见 ----
  await navigate('/profile?panel=ui-kit')
  await waitUntil(`!!document.querySelector('#ui-kit-panel')`, { label: 'UI Kit 面板展开' })
  await evaluate(`[...document.querySelectorAll('#ui-kit-panel p')]
    .find((p) => p.textContent.includes(${JSON.stringify(ZH_NOTE)}))?.scrollIntoView({ block: 'center' })`)
  await sleep(500)
  const zhPanel = await evaluate(`document.querySelector('#ui-kit-panel')?.innerText ?? ''`)
  check('UI Kit 中文口径说明可见', zhPanel.includes(ZH_NOTE))
  await shot('ui-kit-zh')

  // ---- 3. UI Kit：英文说明可见 ----
  await evaluate(`(() => {
    const sel = document.querySelector('#profile-language')
    const setter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value').set
    setter.call(sel, 'en')
    sel.dispatchEvent(new Event('change', { bubbles: true }))
    return true
  })()`)
  await waitUntil(`(document.querySelector('#ui-kit-panel')?.innerText ?? '').includes(${JSON.stringify(EN_NOTE)})`,
    { label: 'UI Kit 英文说明出现' })
  const enPanel = await evaluate(`document.querySelector('#ui-kit-panel')?.innerText ?? ''`)
  check('UI Kit 英文口径说明可见', enPanel.includes(EN_NOTE))
  const enNav = await evaluate(`(() => [...document.querySelectorAll('#nav .nav-link')]
    .map((a) => ({ href: a.getAttribute('href'), text: a.textContent.trim(), tone: a.dataset.tone ?? null })))()`)
  const enByHref = new Map(enNav.map((item) => [item.href, item]))
  check('英文下首页/班级仍是中性导航',
    enByHref.get('/')?.text === 'Home' && enByHref.get('/')?.tone === null
      && enByHref.get('/classes')?.text === 'Classes' && enByHref.get('/classes')?.tone === null,
    JSON.stringify([enByHref.get('/'), enByHref.get('/classes')]))
  await evaluate(`[...document.querySelectorAll('#ui-kit-panel p')]
    .find((p) => p.textContent.includes(${JSON.stringify(EN_NOTE)}))?.scrollIntoView({ block: 'center' })`)
  await sleep(400)
  await shot('ui-kit-en')

  check('全程无未捕获异常', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '))

  const failed = results.filter((item) => !item.ok)
  console.log(`\n${results.length - failed.length}/${results.length} 通过；截图 ${OUT}`)
  process.exitCode = failed.length ? 1 : 0
}

main()
  .catch((error) => {
    console.error('验证失败：', error.message)
    process.exitCode = 1
  })
  .finally(() => {
    ws?.close?.()
    chrome.kill('SIGTERM')
  })
