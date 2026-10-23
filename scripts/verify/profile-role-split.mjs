#!/usr/bin/env node
/*
 * 验证 D-30：/profile 按角色分栏；教师可自定义本地 provider（空 Key）；批改时机可保存。
 * 用法：node scripts/verify/profile-role-split.mjs
 * 依赖：本机 Chrome + QA 5174（scripts/env.sh deploy qa 后的快照）。
 */
import { spawn } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import path from 'node:path'

const ROOT = path.resolve(import.meta.dirname, '../..')
const CHROME = process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const BASE = process.env.BASE ?? 'http://127.0.0.1:5174'
const CDP = process.env.CDP ?? 'http://127.0.0.1:9342'
const OUT = path.join(ROOT, process.env.OUT_DIR ?? 'docs/reports/t380-profile-role-split')
const PROFILE = path.join(ROOT, '.cache/chrome-profile-role-split')
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

async function api(pathname) {
  return evaluate(`fetch(${JSON.stringify(pathname)}).then(async r => ({ status: r.status, body: await r.json().catch(() => null) }))`)
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

async function setSelect(id, value) {
  await evaluate(`(() => {
    const el = document.querySelector(${JSON.stringify('#' + id)});
    if (!el) return false;
    const setter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value').set;
    setter.call(el, ${JSON.stringify(value)});
    el.dispatchEvent(new Event('change', { bubbles: true }));
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
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1200, deviceScaleFactor: 1, mobile: false })

  // ---- 教师 ----
  check('教师登录', (await login(TEACHER)) === 200)
  await navigate('/profile')
  await waitUntil(`!!document.querySelector('#ai-provider')`, { label: '教师 AI 设置卡' })
  const originalAi = (await api('/api/profile/ai-settings')).body
  const teacherView = await evaluate(`({
    title: document.querySelector('.tl-page-header h1')?.textContent?.trim() ?? document.body.innerText.slice(0, 80),
    hasLanguage: !!document.querySelector('#profile-language'),
    hasTheme: !!document.querySelector('.tl-theme-toggle'),
    hasProvider: !!document.querySelector('#ai-provider'),
    hasDisplayName: !!document.querySelector('#ai-display-name'),
    hasBaseUrl: !!document.querySelector('#ai-base-url'),
    hasModel: !!document.querySelector('#ai-model'),
    hasGrading: !!document.querySelector('#grading-mode'),
    hasTime: !!document.querySelector('#grading-time'),
    hasZone: !!document.querySelector('#grading-zone'),
    bodyHasTeacherTitle: document.body.innerText.includes('教师设置'),
    bodyHasAiTitle: document.body.innerText.includes('AI 模型（自定义 provider）'),
    bodyHasGradingTitle: document.body.innerText.includes('作业批改时机'),
  })`)
  check('教师看到账号/语言/主题', teacherView.hasLanguage && teacherView.hasTheme, JSON.stringify(teacherView))
  check('教师看到自定义 provider 字段', teacherView.hasProvider && teacherView.hasDisplayName && teacherView.hasBaseUrl && teacherView.hasModel)
  check('教师看到批改时机字段', teacherView.hasGrading && teacherView.hasTime && teacherView.hasZone)
  check('教师页标题与分区文案', teacherView.bodyHasTeacherTitle && teacherView.bodyHasAiTitle && teacherView.bodyHasGradingTitle)

  // 本地 provider：无 Key 保存
  await setInput('ai-provider', 'ollama')
  await setInput('ai-display-name', 'QA 本地 Ollama')
  await setInput('ai-base-url', 'http://127.0.0.1:11434/v1')
  await setInput('ai-model', 'qwen3:8b')
  check('点击保存 AI 设置', await clickButton('保存 AI 设置'))
  await sleep(800)
  const savedAi = await api('/api/profile/ai-settings')
  check('本地 provider 保存后 source=user', savedAi.status === 200 && savedAi.body?.source === 'user', JSON.stringify(savedAi.body))
  check('本地 provider 保存后 configured=true（已有 Key 保留）', savedAi.body?.configured === true, JSON.stringify(savedAi.body))
  check('显示名称已保存', savedAi.body?.displayName === 'QA 本地 Ollama', String(savedAi.body?.displayName))

  // 批改时机：切成 DUE_DATE 保存
  await setSelect('grading-mode', 'DUE_DATE')
  check('点击保存批改时机', await clickButton('保存批改时机'))
  await sleep(800)
  const savedGrading = await api('/api/profile/grading-preferences')
  check('批改时机保存为 DUE_DATE', savedGrading.status === 200 && savedGrading.body?.triggerMode === 'DUE_DATE', JSON.stringify(savedGrading.body))
  // 恢复默认，避免污染 QA 后续行为
  await evaluate(`fetch('/api/profile/grading-preferences', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ triggerMode: 'FIXED_TIME', fixedTime: '02:30', timeZone: 'Asia/Shanghai', catchUp: true }) }).then(r => r.status)`)
  await shot('teacher-profile')

  // ---- 学生 ----
  await evaluate(`fetch('/api/auth/logout', { method: 'POST' })`)
  check('学生登录', (await login(STUDENT)) === 200)
  await navigate('/profile')
  await waitUntil(`!!document.querySelector('#profile-language')`, { label: '学生资料页' })
  const studentView = await evaluate(`({
    hasLanguage: !!document.querySelector('#profile-language'),
    hasTheme: !!document.querySelector('.tl-theme-toggle'),
    hasProvider: !!document.querySelector('#ai-provider'),
    hasGrading: !!document.querySelector('#grading-mode'),
    hasAiTitle: document.body.innerText.includes('AI 模型（自定义 provider）'),
    hasGradingTitle: document.body.innerText.includes('作业批改时机'),
    hasPhone: !!document.querySelector('#profile-phone'),
  })`)
  check('学生看到账号与语言/主题', studentView.hasPhone && studentView.hasLanguage && studentView.hasTheme)
  check('学生看不到 AI 与批改时机', !studentView.hasProvider && !studentView.hasGrading && !studentView.hasAiTitle && !studentView.hasGradingTitle)
  await shot('student-profile')

  // 恢复 QA 原有 AI 配置（Key 不在这里读写；PUT 不传 Key 即保留原密文）。
  await evaluate(`fetch('/api/auth/logout', { method: 'POST' })`)
  await login(TEACHER)
  if (originalAi) {
    await evaluate(`fetch('/api/profile/ai-settings', {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        provider: ${JSON.stringify(originalAi.provider)},
        displayName: ${JSON.stringify(originalAi.displayName)},
        baseUrl: ${JSON.stringify(originalAi.baseUrl)},
        model: ${JSON.stringify(originalAi.model)},
        apiKey: null,
      }),
    }).then(r => r.status)`)
  }

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
