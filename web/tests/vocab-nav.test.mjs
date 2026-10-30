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

const ADMIN_KEYS = ['nav.ai']
const TEACHER_KEYS = [
  'nav.home', 'nav.classes', 'nav.assignments', 'nav.papers',
  'nav.vocab.sets', 'nav.vocab.assign', 'nav.grading', 'nav.review', 'nav.reports',
]
const STUDENT_KEYS = [
  'nav.home', 'nav.classes', 'nav.vocab.library', 'nav.vocab.lists', 'nav.vocab.review',
]

const paths = (features) => navSpecs(features).map((item) => item.to)

/** 模拟后端在 vocab 关闭时的行为：vocab 类 key 不会被授予。 */
const withoutVocab = (keys) => keys.filter((key) => !key.startsWith('nav.vocab.'))

test('管理员恰好一项：AI 设置', () => {
  assert.deepEqual(paths(featuresFor(ADMIN_KEYS)), ['/admin/ai'])
})

test('管理员看不到任何教师项与班级页（首页/班级/试卷/批改/复核/报表/作业）', () => {
  const admin = paths(featuresFor(ADMIN_KEYS))
  for (const forbidden of ['/', '/classes', '/papers', '/grading', '/review', '/reports', '/assignments']) {
    assert.ok(!admin.includes(forbidden), `管理员不应看到 ${forbidden}`)
  }
})

test('教师 vocab 关时 7 项，顺序固定', () => {
  assert.deepEqual(paths(featuresFor(withoutVocab(TEACHER_KEYS))), [
    '/', '/classes', '/assignments', '/papers', '/grading', '/review', '/reports',
  ])
})

test('教师 vocab 开时 9 项，词集/发词不受折叠影响（仍是两项一级入口）', () => {
  const teacher = paths(featuresFor(TEACHER_KEYS, true))
  assert.equal(teacher.length, 9)
  assert.deepEqual(teacher, [
    '/', '/classes', '/assignments', '/papers',
    '/vocab/sets', '/vocab/assign', '/grading', '/review', '/reports',
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

test('教师不出现学生专属的词库/词单/复习入口', () => {
  const teacher = paths(featuresFor(TEACHER_KEYS, true))
  for (const studentOnly of ['/vocab/library', '/vocab/lists', '/vocab/review']) {
    assert.ok(!teacher.includes(studentOnly), `教师不应看到 ${studentOnly}`)
  }
})

test('教师与学生互不越界', () => {
  const teacher = paths(featuresFor(TEACHER_KEYS, true))
  const student = paths(featuresFor(STUDENT_KEYS, true))
  assert.ok(!teacher.includes('/feedback'))
  assert.ok(!student.includes('/feedback'))
  assert.ok(!student.includes('/grading'))
  assert.ok(!student.includes('/reports'))
  assert.ok(!teacher.includes('/admin/ai'))
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
