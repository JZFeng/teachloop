#!/usr/bin/env node
/*
 * T355 验证：同一篇材料只渲染一次（完形/七选五/语法填空），作答控件落在原文空位上。
 *
 * 用法：node scripts/verify/cloze-grouping.mjs
 * 依赖：本机 Chrome + 跑着的 dev server（默认 http://localhost:5173，代理到有数据的后端）
 *       + 一场未交卷、窗口仍开着的考试（OFFERING 环境变量）。
 * 结果：控制台输出每一项 PASS/FAIL；截图写到 docs/reports/t355-cloze-grouping/。
 *
 * 为什么用真浏览器：分组元数据由后端算（groupKey/material/blankIndex），落位与去重是
 * React 渲染的结果，单测只能覆盖纯函数，覆盖不到「同一段话在屏幕上是几次」。
 */
import { spawn } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import path from 'node:path'

const ROOT = path.resolve(import.meta.dirname, '../..')
const CHROME = process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const BASE = process.env.BASE ?? 'http://localhost:5173'
const CDP = process.env.CDP ?? 'http://127.0.0.1:9337'
const OUT = path.join(ROOT, process.env.OUT_DIR ?? 'docs/reports/t355-cloze-grouping')
const PROFILE = path.join(ROOT, '.cache/chrome-cloze-grouping')
const OFFERING = process.env.OFFERING ?? '819141a4-33eb-4a85-a5c9-3274f4ca7ed7'
const STUDENT = { email: process.env.EMAIL ?? 'student@teachloop.local', password: process.env.PASSWORD ?? 'teachloop123' }

const PASSAGE_7 = 'How can English be learned well?'
const PASSAGE_9 = 'I walked into the room.'
const PASSAGE_11 = 'Entering senior high school is an exciting but'

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


async function scrollTo(selector) {
  await evaluate(`document.querySelector(${JSON.stringify(selector)})
    ?.scrollIntoView({ block: 'center' })`)
  await sleep(500)
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

  // ---- 0. 登录 + 开考 ----
  await navigate('/login')
  const loginStatus = await evaluate(
    `fetch('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: ${JSON.stringify(JSON.stringify(STUDENT))} }).then(r => r.status)`,
  )
  check('学生登录', loginStatus === 200, `HTTP ${loginStatus}`)
  consoleErrors.length = 0

  let session = await evaluate(`fetch('/api/offerings/${OFFERING}/session').then(r => r.json())`)
  if (session?.submitted) {
    // 已经交过卷：开新一次作答（旧 attempt 保留），否则只会看到结果页。
    const next = await evaluate(`fetch('/api/offerings/${OFFERING}/attempts', { method: 'POST' }).then(r => r.status)`)
    check('开新一次作答', next === 200, `HTTP ${next}`)
    session = await evaluate(`fetch('/api/offerings/${OFFERING}/session').then(r => r.json())`)
  }
  if (!session?.started) {
    const started = await evaluate(
      `fetch('/api/offerings/${OFFERING}/start', { method: 'POST' }).then(r => r.status)`,
    )
    check('开始作答', started === 200, `HTTP ${started}`)
  }

  // ---- 1. 接口：分组元数据 ----
  const paper = await evaluate(`fetch('/api/offerings/${OFFERING}/paper').then(r => r.json())`)
  const byNumber = new Map((paper?.items ?? []).map((item) => [item.number, item]))
  const groupOf = (number) => byNumber.get(number)?.groupKey ?? null
  const blankOf = (number) => byNumber.get(number)?.blankIndex ?? null
  check('第 7、8 题同一组', groupOf(7) && groupOf(7) === groupOf(8), `groupKey=${groupOf(7)}`)
  check('第 7、8 题空位序号 0/1', blankOf(7) === 0 && blankOf(8) === 1, `${blankOf(7)}/${blankOf(8)}`)
  check('材料只在组内第一条带', Boolean(byNumber.get(7)?.material) && !byNumber.get(8)?.material)
  check('第 9、10 题同一组', groupOf(9) && groupOf(9) === groupOf(10), `groupKey=${groupOf(9)}`)
  check('第 11、12 题同一组', groupOf(11) && groupOf(11) === groupOf(12), `groupKey=${groupOf(11)}`)
  check('第 7 组材料含 5 个空位', ((byNumber.get(7)?.material ?? '').match(/_{3,}/g) ?? []).length === 5)
  check('材料里没有残留题号提示', !/_{3,}\s*[(（]\s*\d+\s*[)）]/.test(byNumber.get(7)?.material ?? '')
    && !/_{3,}\s*[(（]\s*\d+\s*[)）]/.test(byNumber.get(9)?.material ?? ''))

  // ---- 2. 页面：材料只出现一次 ----
  await navigate(`/exams/${OFFERING}/live`)
  await waitUntil(`!!document.querySelector('.exam-paper')`, { timeout: 20000, label: '答题页出现' })

  const counts = await evaluate(`(() => {
    const text = document.body.innerText
    const occurrences = (needle) => text.split(needle).length - 1
    return {
      passage7: occurrences(${JSON.stringify(PASSAGE_7)}),
      passage9: occurrences(${JSON.stringify(PASSAGE_9)}),
      passage11: occurrences(${JSON.stringify(PASSAGE_11)}),
      groups: document.querySelectorAll('.exam-material-group').length,
      blanks7: document.querySelectorAll('[data-q="7"]').length,
      blanks8: document.querySelectorAll('[data-q="8"]').length,
      gaps7: document.querySelector('[data-q="7"]')?.closest('.exam-material-group')
        ?.querySelectorAll('.inline-blank-gap').length ?? 0,
    }
  })()`)
  check('第 7/8 题的材料只出现一次', counts.passage7 === 1, `出现 ${counts.passage7} 次`)
  check('第 9/10 题的材料只出现一次', counts.passage9 === 1, `出现 ${counts.passage9} 次`)
  check('第 11/12 题的材料只出现一次', counts.passage11 === 1, `出现 ${counts.passage11} 次`)
  check('7、8 两个控件各一个', counts.blanks7 === 1 && counts.blanks8 === 1, `7:${counts.blanks7} 8:${counts.blanks8}`)
  check('没有对应题目的空位只画横线', counts.gaps7 === 3, `${counts.gaps7} 个空位横线`)

  const positions = await evaluate(`(() => {
    const slot7 = document.querySelector('[data-q="7"]')
    const slot8 = document.querySelector('[data-q="8"]')
    const group = slot7?.closest('.exam-material-group')
    const order = group ? [...group.querySelectorAll('[data-q], .inline-blank-gap')]
      .map((node) => node.getAttribute('data-q') ?? 'gap') : []
    return { inGroup: !!group, order }
  })()`)
  check('控件按原文空位顺序排列', JSON.stringify(positions.order) === JSON.stringify(['7', '8', 'gap', 'gap', 'gap']),
    JSON.stringify(positions.order))

  // ---- 3. 交互：点空弹选项、选中答案 ----
  await scrollTo('[data-q="7"]')
  await shot('group-7-8-before')
  await evaluate(`document.querySelector('[data-q="7"] button.inline-blank')?.click()`)
  await waitUntil(`!!document.querySelector('.inline-pop')`, { label: '选项弹层出现' })
  const options = await evaluate(`[...document.querySelectorAll('.inline-pop-opt')].map(b => b.querySelector('b')?.textContent)`)
  check('七选五的选项是 A–G', Array.isArray(options) && options.join('') === 'ABCDEFG', options?.join(''))
  await sleep(300)
  await shot('group-7-8-popover')

  await evaluate(`[...document.querySelectorAll('.inline-pop-opt')]
    .find(b => b.querySelector('b')?.textContent === 'G')?.click()`)
  await sleep(400)
  const picked = await evaluate(`document.querySelector('[data-q="7"] button.inline-blank')?.textContent ?? ''`)
  check('选中 G 后控件显示 G', picked.includes('G'), picked.trim())

  // 等一次自动保存，确认答案进服务端
  await sleep(2200)
  const saved = await evaluate(`document.querySelector('.save-state')?.textContent ?? ''`)
  check('自动保存完成', /已保存|saved/i.test(saved), saved.trim())

  // 截图：三组材料各自只出现一次 + 整页顶部
  await scrollTo('[data-q="7"]')
  await shot('group-7-8-answered')
  await scrollTo('[data-q="9"]')
  await shot('group-9-10')
  await scrollTo('[data-q="11"]')
  await shot('group-11-12')
  await evaluate('window.scrollTo(0, 0)')
  await sleep(400)
  await shot('live-full')

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
