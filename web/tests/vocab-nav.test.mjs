/*
 * T389 + 007-role-nav / T477 验收：侧栏只由「菜单目录 + 后端 key 集合」决定，
 * 前端不再判断角色。
 *
 * 测纯数据函数（app/nav.ts），不依赖浏览器或登录态；断言的是三种角色最终看到什么。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { navSpecs, NAV_CATALOG } from '../src/app/nav.ts'

/** 模拟后端 /api/features 的返回：给定授予的 key，其余一律 false。 */
function featuresFor(keys, vocab = false) {
  const out = {}
  for (const item of NAV_CATALOG) out[item.key] = keys.includes(item.key)
  out.vocab = vocab
  return out
}

const ADMIN_KEYS = ['nav.classes', 'nav.ai']
const TEACHER_KEYS = [
  'nav.home', 'nav.classes', 'nav.assignments', 'nav.papers',
  'nav.vocab.sets', 'nav.vocab.assign', 'nav.grading', 'nav.review', 'nav.reports',
]
// 2026-10-26（AC-72.8）：学生不再有 nav.feedback 菜单项（列表页删除，详情从行内进）。
// 2026-10-26（AC-69.11 修订）：新增 nav.vocab.lists「我的词单」，紧跟我的词库。
const STUDENT_KEYS = [
  'nav.home', 'nav.classes', 'nav.vocab.library', 'nav.vocab.lists', 'nav.vocab.review',
]

const paths = (features) => navSpecs(features).map((item) => item.to)

/** 模拟后端在 vocab 关闭时的行为：vocab 类 key 不会被授予。 */
const withoutVocab = (keys) => keys.filter((key) => !key.startsWith('nav.vocab.'))

test('管理员恰好两项：班级 → AI 设置', () => {
  assert.deepEqual(paths(featuresFor(ADMIN_KEYS)), ['/classes', '/admin/ai'])
})

test('管理员看不到任何教师项（首页/试卷/批改/复核/报表/作业）', () => {
  const admin = paths(featuresFor(ADMIN_KEYS))
  for (const forbidden of ['/', '/papers', '/grading', '/review', '/reports', '/assignments']) {
    assert.ok(!admin.includes(forbidden), `管理员不应看到 ${forbidden}`)
  }
})

test('教师 vocab 关时 7 项，顺序固定', () => {
  assert.deepEqual(paths(featuresFor(withoutVocab(TEACHER_KEYS))), [
    '/', '/classes', '/assignments', '/papers', '/grading', '/review', '/reports',
  ])
})

test('教师 vocab 开时 9 项，词集/发词插在试卷之后、批改之前', () => {
  const teacher = paths(featuresFor(TEACHER_KEYS, true))
  assert.equal(teacher.length, 9)
  assert.deepEqual(teacher, [
    '/', '/classes', '/assignments', '/papers',
    '/vocab/sets', '/vocab/assign', '/grading', '/review', '/reports',
  ])
})

test('学生 vocab 关时 2 项；开时 5 项，词库/词单/复习插在班级之后', () => {
  assert.deepEqual(paths(featuresFor(withoutVocab(STUDENT_KEYS))), ['/', '/classes'])
  assert.deepEqual(paths(featuresFor(STUDENT_KEYS, true)), [
    '/', '/classes', '/vocab/library', '/vocab/lists', '/vocab/review',
  ])
})

test('我的词单紧跟、且只紧跟在我的词库后面', () => {
  const student = paths(featuresFor(STUDENT_KEYS, true))
  assert.equal(student.indexOf('/vocab/lists'), student.indexOf('/vocab/library') + 1)
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
  // AC-72.8 之后「成绩与评语」不再是侧栏项，两边都不该出现
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
