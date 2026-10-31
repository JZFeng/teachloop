/*
 * 2026-10-30（Bug，blocker）：侧栏「词书管理」指向 `/admin/vocab/books`，App.tsx 却从没注册这条路由。
 * 点击命中的是兜底 `*` → `/` → HomeRouter 再按角色改道 `/admin/ai`，表现是「点了没反应」；
 * 地址栏直接输入同样落到 `*`，因为路由树里根本没有这个 pattern。
 *
 * 这里用源码扫描锁两件事，成本很低、不依赖浏览器：
 *   1. 侧栏目录里每一项 `to` 都能在 App.tsx 的路由树里找到（防同类回归：只加目录项不加路由时，
 *      单元测试全绿而入口是死的）；
 *   2. `/admin/vocab/books` 与它的导入页都挂了 `nav.vocab.books` 守卫（学生/教师不能深链进来）。
 *
 * 扫描要点：React Router 的路由是**嵌套**的（`<Route path="vocab">` 下面才是 `review`），
 * 所以要把开标签按栈拼成完整路径；`<Route>` 的 `element={<X />}` 里含 `>`，
 * 不能拿 `<Route[^>]*>` 这种正则硬切，得按引号/花括号状态逐个字符走。
 *
 * 说明：这是**结构校验**，真实渲染效果由浏览器验收覆盖。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { NAV_CATALOG } from '../src/app/nav.ts'

const APP_SRC = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8')

/** 从 `<Route` 位置往后扫到该开标签真正的结尾，返回 `{ tag, selfClosing, end }`。 */
function scanOpeningTag(src, start) {
  let quote = null
  let braces = 0
  for (let i = start; i < src.length; i += 1) {
    const ch = src[i]
    if (quote) {
      if (ch === quote) quote = null
      continue
    }
    if (ch === '"' || ch === "'" || ch === '`') { quote = ch; continue }
    if (ch === '{') { braces += 1; continue }
    if (ch === '}') { braces -= 1; continue }
    if (ch === '>' && braces === 0) {
      const tag = src.slice(start, i + 1)
      return { tag, selfClosing: src[i - 1] === '/', end: i + 1 }
    }
  }
  throw new Error('未闭合的 <Route> 标签')
}

/**
 * 解析 App.tsx 的路由树，返回 `{ patterns, hasIndex, routes }`。
 * `patterns` 是完整路径（不含前导斜杠）；`routes` 保留每条的原始标签文本以便断言守卫。
 */
function parseRoutes(src) {
  const patterns = new Set()
  const routes = []
  const stack = [];
  let index = 0
  while (index < src.length) {
    const open = src.indexOf('<Route', index)
    const close = src.indexOf('</Route>', index)
    if (open === -1 && close === -1) break
    if (close !== -1 && (open === -1 || close < open)) {
      stack.pop()
      index = close + '</Route>'.length
      continue
    }
    const { tag, selfClosing, end } = scanOpeningTag(src, open)
    const pathMatch = /\bpath="([^"]*)"/.exec(tag)
    const hasIndex = /\bindex(?:=|\s|\/?>)/.test(tag.replace(/^<Route/, ''))
    const segments = pathMatch ? [...(stack.at(-1)?.segments ?? []), pathMatch[1]] : (stack.at(-1)?.segments ?? [])
    const full = segments.join('/')
    patterns.add(full)
    routes.push({ full, hasIndex, tag })
    if (pathMatch) stack.push({ segments })
    if (selfClosing) {
      if (pathMatch) stack.pop()
    }
    index = end
  }
  return { patterns, hasIndex: routes.some((route) => route.hasIndex), routes }
}

const { patterns, hasIndex, routes } = parseRoutes(APP_SRC)

/** 取路由完整路径为 `full` 的那一条（含嵌套前缀）。 */
function route(full) {
  const found = routes.find((item) => item.full === full)
  assert.ok(found, `App.tsx 里没有解析到路径为 ${full} 的 <Route>`)
  return found.tag
}

test('侧栏目录每一项都有对应路由（目录项不能是死链）', () => {
  const missing = []
  for (const item of NAV_CATALOG) {
    if (item.to === '/') {
      if (!hasIndex) missing.push(`${item.key} → /（缺少 index 路由）`)
      continue
    }
    const pattern = item.to.replace(/^\//, '')
    if (!patterns.has(pattern)) missing.push(`${item.key} → ${item.to}`)
  }
  assert.deepEqual(missing, [], `以下目录项没有注册路由：${missing.join('、')}`)
})

test('013 词书管理：列表页与导入页都注册了，且都挂 nav.vocab.books 守卫', () => {
  assert.ok(patterns.has('admin/vocab/books'), '缺少 /admin/vocab/books')
  assert.ok(patterns.has('admin/vocab/books/:bookId/import'), '缺少 /admin/vocab/books/:bookId/import')
  for (const full of ['admin/vocab/books', 'admin/vocab/books/:bookId/import']) {
    assert.match(route(full), /RequireFeature feature="nav\.vocab\.books"/,
      `/admin/... 的 ${full} 必须挂 nav.vocab.books 守卫`)
  }
})

test('管理员的两条一级入口（词书管理、系统设置）都在路由树里', () => {
  for (const path of ['admin/vocab/books', 'admin/ai']) {
    assert.ok(patterns.has(path), `管理员入口 /${path} 没有路由`)
  }
})
