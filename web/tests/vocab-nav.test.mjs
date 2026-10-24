/*
 * T389 验收：功能开关关闭时 vocab 导航不出现，打开时学生两项 / 教师两项。
 *
 * 直接测纯数据函数（app/nav.ts），不依赖浏览器或登录态；它同时守住
 * 「教师入口不塞进班级页、学生不看到教师项」这条口径。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { navSpecs } from '../src/app/nav.ts'

const paths = (role, enabled) => navSpecs(role, enabled).map((item) => item.to)
const vocabPaths = (role, enabled) => paths(role, enabled).filter((to) => to.startsWith('/vocab'))

test('开关关闭：学生与教师都没有 vocab 入口', () => {
  assert.deepEqual(vocabPaths('STUDENT', false), [])
  assert.deepEqual(vocabPaths('TEACHER', false), [])
  assert.deepEqual(vocabPaths('ADMIN', false), [])
})

test('开关打开：学生恰好两项，顺序为我的词库 → 今日复习', () => {
  assert.deepEqual(vocabPaths('STUDENT', true), ['/vocab/library', '/vocab/review'])
  const labels = navSpecs('STUDENT', true)
    .filter((item) => item.to.startsWith('/vocab'))
    .map((item) => item.labelKey)
  assert.deepEqual(labels, ['vocab.nav.library', 'vocab.nav.review'])
})

test('开关打开：教师恰好两项（词集 / 发词），管理员同教师另加 AI', () => {
  assert.deepEqual(vocabPaths('TEACHER', true), ['/vocab/sets', '/vocab/assign'])
  assert.deepEqual(vocabPaths('ADMIN', true), ['/vocab/sets', '/vocab/assign'])
  assert.ok(paths('ADMIN', true).includes('/admin/ai'))
  assert.ok(!paths('TEACHER', true).includes('/admin/ai'))
})

test('关闭时原有导航完全不变（不丢项、不改顺序）', () => {
  assert.deepEqual(paths('STUDENT', false), ['/', '/classes', '/feedback'])
  assert.deepEqual(paths('TEACHER', false), [
    '/', '/classes', '/assignments', '/papers', '/grading', '/review', '/reports',
  ])
})

test('打开时 vocab 两项落在学生反馈之前、教师批改之前', () => {
  const student = paths('STUDENT', true)
  assert.ok(student.indexOf('/vocab/library') < student.indexOf('/feedback'))
  const teacher = paths('TEACHER', true)
  assert.ok(teacher.indexOf('/vocab/assign') < teacher.indexOf('/grading'))
})
