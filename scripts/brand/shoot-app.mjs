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
 *
 * 教师账号：teacher@teachloop.local / teachloop123
 * 学生账号：student02@teachloop.local / teachloop123（学生页单独登出再登入）
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

const TEACHER = { email: 'teacher@teachloop.local', password: 'teachloop123' }
const STUDENT = { email: 'student02@teachloop.local', password: 'teachloop123' }

/** [文件名, 路由, 主题, 视口高度] */
const TEACHER_ROUTES = [
  ['home-light', '/', 'light'],
  ['home-dark', '/', 'dark'],
  ['classes-light', '/classes', 'light'],
  ['classes-dark', '/classes', 'dark'],
  ['assignments-light', '/assignments', 'light'],
  ['assignments-dark', '/assignments', 'dark'],
  ['papers-light', '/papers', 'light'],
  ['papers-dark', '/papers', 'dark'],
  ['new-assignment-light', '/assignments/new', 'light', 1500],
  ['new-assignment-dark', '/assignments/new', 'dark', 1500],
  ['grading-light', '/grading', 'light'],
  ['grading-dark', '/grading', 'dark'],
  ['review-light', '/review', 'light'],
  ['review-dark', '/review', 'dark'],
  ['reports-light', '/reports', 'light'],
  ['reports-dark', '/reports', 'dark'],
  ['profile-light', '/profile', 'light'],
  ['notifications-light', '/notifications', 'light'],
  ['ui-kit-light', '/ui-kit', 'light', 2600],
  ['ui-kit-dark', '/ui-kit', 'dark', 2600],
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

async function setViewport(height) {
  await send('Emulation.setDeviceMetricsOverride', {
    width: 1440,
    height,
    deviceScaleFactor: 2,
    mobile: false,
  })
}

async function navigate(route) {
  await send('Page.navigate', { url: `${BASE}${route}` })
  await sleep(1600)
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

async function login(user) {
  const status = await evaluate(
    `fetch('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: ${JSON.stringify(
      JSON.stringify(user),
    )} }).then(r => r.status)`,
  )
  return status
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

async function capture(routes) {
  for (const [name, route, theme, height = 900] of routes) {
    await setViewport(height)
    await setTheme(theme)
    await navigate(route)
    await shot(name)
  }
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

  // 登录页：先登出，再分别截亮/暗（主题从 localStorage 读，导航时生效）。
  await setViewport(900)
  await navigate('/login')
  await evaluate(`fetch('/api/auth/logout', { method: 'POST' }).then(r => r.status)`)
  for (const theme of ['light', 'dark']) {
    await setTheme(theme)
    await navigate('/login')
    await shot(`login-${theme}`)
  }

  if ((await login(TEACHER)) !== 200) throw new Error('教师登录失败')
  const offerings = await evaluate(`fetch('/api/offerings').then(r => r.json())`)
  await capture(TEACHER_ROUTES)

  const assignmentId = offerings?.find((item) => item.kind === 'ASSIGNMENT')?.id
  if (assignmentId) {
    await capture([
      ['grading-detail-light', `/grading/${assignmentId}`, 'light', 1600],
      ['class-summary-light', `/offerings/${assignmentId}/summary`, 'light', 1400],
      ['paper-wizard-light', '/papers/new', 'light', 1600],
      ['paper-import-light', '/papers/import', 'light', 1400],
    ])
  }

  // 学生页：登出 → 用学生账号登录 → 截首页 / 班级 / 反馈 / 作业 / 考试入口。
  await evaluate(`fetch('/api/auth/logout', { method: 'POST' }).then(r => r.status)`)
  const studentStatus = await login(STUDENT)
  if (studentStatus === 200) {
    const studentOfferings = await evaluate(`fetch('/api/offerings').then(r => r.json())`)
    const studentAssignmentId = studentOfferings?.find((item) => item.kind === 'ASSIGNMENT')?.id
    const studentExamId = studentOfferings?.find((item) => item.kind === 'EXAM')?.id
    const studentRoutes = [
      ['student-home-light', '/', 'light'],
      ['student-home-dark', '/', 'dark'],
      ['student-classes-light', '/classes', 'light'],
      ['student-feedback-light', '/feedback', 'light'],
    ]
    if (studentAssignmentId) {
      studentRoutes.push(['student-assignment-light', `/assignments/${studentAssignmentId}`, 'light', 1500])
    }
    if (studentExamId) {
      studentRoutes.push(['student-exam-entry-light', `/exams/${studentExamId}`, 'light'])
    }
    await capture(studentRoutes)

    const feedback = await evaluate(`fetch('/api/feedback').then(r => r.json())`)
    const feedbackOfferingId = feedback?.[0]?.offeringId
    if (feedbackOfferingId) {
      await capture([['student-feedback-detail-light', `/feedback/${feedbackOfferingId}`, 'light', 1500]])
    }
  } else {
    console.warn(`[shoot-app] 学生登录失败（HTTP ${studentStatus}），跳过学生页`)
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
