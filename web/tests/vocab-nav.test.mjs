/*
 * 007-role-nav（菜单目录纯数据）+ 011 / T563（背单词三项折叠为一项）。
 *
 * 侧栏只由「菜单目录 + 后端 key 集合」决定，前端不判断角色。
 * 011 之后学生侧栏里「我的词库 / 我的词单 / 今日复习」折成一项「背单词」，落点 /vocab/review；
 * 三个原 key 不变（FR-204），折叠只发生在前端目录层。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { navSpecs, navItemActive, NAV_CATALOG } from '../src/app/nav.ts'

/** 模拟后端 /api/features 的返回：给定授予的 key，其余一律 false。 */
function featuresFor(keys, vocab = false) {
  const out = {}
  for (const item of NAV_CATALOG) out[item.key] = keys.includes(item.key)
  out.vocab = vocab
  return out
}

// 2026-10-30（AC-3.10）：管理员新增「教师管理」（发邀请码）。
const ADMIN_KEYS = ['nav.teachers', 'nav.ai']
// 2026-10-30（AC-3.13）：教师保留学习入口 —— 学生升级成老师后「背单词」不能消失。
const TEACHER_KEYS = [
  'nav.home', 'nav.classes', 'nav.assignments', 'nav.papers',
  'nav.vocab.sets', 'nav.vocab.assign', 'nav.grading', 'nav.review', 'nav.reports',
  'nav.vocab.library', 'nav.vocab.lists', 'nav.vocab.review',
]
const STUDENT_KEYS = [
  'nav.home', 'nav.classes', 'nav.vocab.library', 'nav.vocab.lists', 'nav.vocab.review',
]

const paths = (features) => navSpecs(features).map((item) => item.to)

/** 模拟后端在 vocab 关闭时的行为：vocab 类 key 不会被授予。 */
const withoutVocab = (keys) => keys.filter((key) => !key.startsWith('nav.vocab.'))

test('管理员 vocab 关时两项：教师管理 + 系统设置', () => {
  assert.deepEqual(paths(featuresFor(ADMIN_KEYS)), ['/admin/teachers', '/admin/ai'])
})

test('013：管理员 vocab 开时三项，词书管理 / 教师管理 / 系统设置', () => {
  assert.deepEqual(
    paths(featuresFor([...ADMIN_KEYS, 'nav.vocab.books'], true)),
    ['/admin/vocab/books', '/admin/teachers', '/admin/ai'],
  )
})

test('管理员看不到任何教师项与班级页（首页/班级/试卷/批改/复核/报表/作业）', () => {
  const admin = paths(featuresFor(ADMIN_KEYS))
  for (const forbidden of ['/', '/classes', '/papers', '/grading', '/review', '/reports', '/assignments']) {
    assert.ok(!admin.includes(forbidden), `管理员不应看到 ${forbidden}`)
  }
})

// 2026-10-31：学生从查词页提交的反馈只有管理员这一个读入口。
test('2026-10-31：管理员 vocab 开时四项，单词反馈紧挨词书管理', () => {
  assert.deepEqual(
    paths(featuresFor([...ADMIN_KEYS, 'nav.vocab.books', 'nav.vocab.feedback'], true)),
    ['/admin/vocab/books', '/admin/vocab/feedback', '/admin/teachers', '/admin/ai'],
  )
})

test('单词反馈：没被授权时不出现在侧栏（只由后端 key 决定，前端不判角色）', () => {
  assert.ok(NAV_CATALOG.some((item) => item.key === 'nav.vocab.feedback'))
  assert.ok(!paths(featuresFor(ADMIN_KEYS, true)).includes('/admin/vocab/feedback'))
  // 教师 / 学生的 key 集合里没有它（与 RoleNavMatrix 的 ADMIN 集合一致）。
  for (const keys of [TEACHER_KEYS, STUDENT_KEYS]) {
    assert.ok(!keys.includes('nav.vocab.feedback'), '教师/学生不应被授予 nav.vocab.feedback')
  }
})

test('教师 vocab 关时 7 项，顺序固定', () => {
  assert.deepEqual(paths(featuresFor(withoutVocab(TEACHER_KEYS))), [
    '/', '/classes', '/assignments', '/papers', '/grading', '/review', '/reports',
  ])
})

test('教师 vocab 开时 10 项：词集/发词是两项一级入口，学习三项折成「背单词」排在报表之后', () => {
  const teacher = paths(featuresFor(TEACHER_KEYS, true))
  assert.equal(teacher.length, 10)
  assert.deepEqual(teacher, [
    '/', '/classes', '/assignments', '/papers',
    '/vocab/sets', '/vocab/assign', '/grading', '/review', '/reports',
    '/vocab/review',
  ])
})

test('AC-91.1 / 91.5：学生 vocab 开时 3 项，三项折叠成一项，落点 /vocab/review', () => {
  assert.deepEqual(paths(featuresFor(STUDENT_KEYS, true)), ['/', '/classes', '/vocab/review'])
})

test('AC-91.4：学生 vocab 关时 2 项，折叠项整项不出现', () => {
  const student = paths(featuresFor(withoutVocab(STUDENT_KEYS), false))
  assert.deepEqual(student, ['/', '/classes'])
  assert.ok(!student.includes('/vocab/review'))
})

test('折叠项文案与 key 用 nav.vocab + nav.vocab.review，没有新增一级 key', () => {
  const [folded] = navSpecs(featuresFor(STUDENT_KEYS, true)).filter((item) => item.to === '/vocab/review')
  assert.equal(folded.labelKey, 'nav.vocab')
  assert.equal(folded.key, 'nav.vocab.review')
  assert.ok(NAV_CATALOG.some((item) => item.key === 'nav.vocab.review'))
  assert.ok(!NAV_CATALOG.some((item) => item.key === 'nav.vocab'))
})

test('AC-91.2：站在三个 Tab 任一地址时侧栏都高亮「背单词」', () => {
  const folded = navSpecs(featuresFor(STUDENT_KEYS, true)).find((item) => item.to === '/vocab/review')
  for (const pathname of ['/vocab/review', '/vocab/library', '/vocab/lists', '/vocab/lists/abc']) {
    assert.equal(navItemActive(folded, pathname), true, `${pathname} 应高亮`)
  }
  for (const pathname of ['/', '/classes', '/vocab/sets', '/vocab/reviewx']) {
    assert.equal(navItemActive(folded, pathname), false, `${pathname} 不应高亮`)
  }
})

test('AC-91.3：复习会话全屏页（/vocab/review/session/:id）仍算「背单词」', () => {
  const folded = navSpecs(featuresFor(STUDENT_KEYS, true)).find((item) => item.to === '/vocab/review')
  assert.equal(navItemActive(folded, '/vocab/review/session/abc'), true)
})

test('首页只在自己的地址高亮（end 语义）', () => {
  const home = navSpecs(featuresFor(STUDENT_KEYS, true)).find((item) => item.to === '/')
  assert.equal(navItemActive(home, '/'), true)
  assert.equal(navItemActive(home, '/classes'), false)
})

test('AC-3.13：教师保留学习入口（这三项对学生是/曾是入口，对教师是同一个折叠项）', () => {
  // 2026-10-30 用户否掉了「词库/词单/复习是学生专属」这个假设：背单词属于人人可用的功能层。
  // 折叠后侧栏只有一项「背单词」（落点 /vocab/review），词库/词单是页内 Tab，不再是独立路径。
  const teacher = navSpecs(featuresFor(TEACHER_KEYS, true))
  const folded = teacher.filter((item) => item.to === '/vocab/review')
  assert.equal(folded.length, 1, '教师侧栏应恰有一项背单词')
  assert.equal(folded[0].key, 'nav.vocab.review')
  assert.equal(folded[0].labelKey, 'nav.vocab')
  assert.ok(!paths(featuresFor(TEACHER_KEYS, true)).includes('/vocab/library'))
})

test('教师与学生互不越界（学习入口不算越界）', () => {
  const teacher = paths(featuresFor(TEACHER_KEYS, true))
  const student = paths(featuresFor(STUDENT_KEYS, true))
  assert.ok(!teacher.includes('/feedback'))
  assert.ok(!student.includes('/feedback'))
  assert.ok(!student.includes('/grading'))
  assert.ok(!student.includes('/reports'))
  assert.ok(!teacher.includes('/admin/ai'))
  assert.ok(!teacher.includes('/admin/teachers'))
  // 两边都有背单词：它是功能层，不是角色专属
  assert.ok(teacher.includes('/vocab/review'))
  assert.ok(student.includes('/vocab/review'))
})

test('features 为空或不认识 key 时侧栏为空（不放空承诺）', () => {
  assert.deepEqual(navSpecs({}), [])
  assert.deepEqual(navSpecs({ 'nav.unknown': true }), [])
})

test('目录里的 key 唯一（前后端约定不重复）', () => {
  const keys = NAV_CATALOG.map((item) => item.key)
  assert.equal(new Set(keys).size, keys.length)
})

test('折叠后不会出现两个「背单词」：组内只保留第一项位置', () => {
  const student = navSpecs(featuresFor(STUDENT_KEYS, true))
  assert.equal(student.filter((item) => (item.activePaths ?? []).length > 0).length, 1)
})
