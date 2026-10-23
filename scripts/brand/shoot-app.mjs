#!/usr/bin/env node
/**
 * 给真实前端截图（登录态 + 亮暗主题 + 指定路由）。
 *
 * 为什么不用 `chrome --screenshot`：那只能截公开页。登录态在 cookie 里，
 * 主题在 localStorage 里，两者都要先用 CDP 写进去，再导航。
 *
 * 用法：
 *   BASE=http://localhost:5173 node scripts/brand/shoot-app.mjs
 * 依赖：本机 Chrome + 一个跑着的 dev server（`pnpm dev`，代理到有数据的后端）。
 */
import { spawn } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const CHROME = process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const BASE = process.env.BASE ?? 'http://localhost:5173'
const OUT = path.join(ROOT, 'docs/reports/brand-app-2026-10-22')
const PROFILE = path.join(ROOT, '.cache/chrome-app')
const CDP = 'http://127.0.0.1:9333'
const EMAIL = 'teacher@teachloop.local'
const PASSWORD = 'teachloop123'

/** 截哪些页：文件名、路由、主题。登录页单独处理（要先登出）。 */
const ROUTES = [
  ['home-light', '/', 'light'],
  ['home-dark', '/', 'dark'],
  ['papers-light', '/papers', 'light'],
  ['papers-dark', '/papers', 'dark'],
  ['new-assignment-light', '/assignments/new', 'light'],
  ['new-assignment-dark', '/assignments/new', 'dark'],
  ['review-light', '/review', 'light'],
  ['review-dark', '/review', 'dark'],
  ['reports-light', '/reports', 'light'],
  ['grading-light', '/grading', 'light'],
  ['classes-light', '/classes', 'light'],
  ['assignments-light', '/assignments', 'light'],
  ['ui-kit-light', '/ui-kit', 'light'],
  ['profile-light', '/profile', 'light'],
  ['notifications-light', '/notifications', 'light'],
]

mkdirSync(OUT, { recursive: true })
mkdirSync(PROFILE, { recursive: true })

const chrome = spawn(
  CHROME,
  [
    '--headless=new',
    '--no-sandbox',
    '--disable-gpu',
    '--disable-crash-reporter',
    '--disable-breakpad',
    '--no-first-run',
    `--user-data-dir=${PROFILE}`,
    '--remote-debugging-port=9333',
    '--hide-scrollbars',
    'about:blank',
  ],
  { stdio: 'ignore' },
)

let ws
let nextId = 0
const pending = new Map()

function send(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++nextId
    pending.set(id, { resolve, reject })
    ws.send(JSON.stringify({ id, method, params }))
  })
}

async function evaluate(expression) {
  const result = await send('Runtime.evaluate', {
    expression,
    awaitPromise: true,
    returnByValue: true,
  })
  return result?.result?.value
}

async function navigate(route) {
  await send('Page.navigate', { url: `${BASE}${route}` })
  await sleep(1600)
  // 等 React 切换到目标屏，避免截到上一页的骨架。
  await evaluate('document.fonts?.ready ? document.fonts.ready.then(() => true) : true')
  await sleep(400)
}

async function shot(name) {
  const { data } = await send('Page.captureScreenshot', { format: 'png' })
  const file = path.join(OUT, `${name}.png`)
  writeFileSync(file, Buffer.from(data, 'base64'))
  console.log(`  ${name}.png`)
}

async function setTheme(theme) {
  await evaluate(`localStorage.setItem('teachloop.theme', '${theme}')`)
}

async function waitForChrome() {
  for (let i = 0; i < 60; i += 1) {
    try {
      const res = await fetch(`${CDP}/json/version`)
      if (res.ok) return
    } catch {
      /* 还没起来 */
    }
    await sleep(250)
  }
  throw new Error('Chrome 未在 15 秒内启动 remote debugging')
}

async function main() {
  await waitForChrome()
  const target = await (
    await fetch(`${CDP}/json/new?${encodeURIComponent('about:blank')}`, { method: 'PUT' })
  ).json()
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
  await send('Emulation.setDeviceMetricsOverride', {
    width: 1440,
    height: 900,
    deviceScaleFactor: 2,
    mobile: false,
  })

  // 登录页：先登出，再分别截亮/暗（主题从 localStorage 读，导航时生效）。
  await navigate('/login')
  await evaluate(`fetch('/api/auth/logout', { method: 'POST' }).then(r => r.status)`)
  for (const theme of ['light', 'dark']) {
    await setTheme(theme)
    await navigate('/login')
    await shot(`login-${theme}`)
  }

  // 登录：在页面里发请求，cookie 会落在 localhost:5173（Vite 代理到后端）。
  const loginStatus = await evaluate(
    `fetch('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: ${JSON.stringify(
      JSON.stringify({ email: EMAIL, password: PASSWORD }),
    )} }).then(r => r.status)`,
  )
  if (loginStatus !== 200) throw new Error(`登录失败：HTTP ${loginStatus}`)

  for (const [name, route, theme] of ROUTES) {
    await setTheme(theme)
    await navigate(route)
    await shot(name)
  }

  console.log(`[shoot-app] 输出到 ${path.relative(ROOT, OUT)}/`)
}

try {
  await main()
} finally {
  try {
    ws?.close()
  } catch {
    /* 忽略 */
  }
  chrome.kill('SIGTERM')
}
