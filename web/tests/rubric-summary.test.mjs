/*
 * 共享夹具（docs/rubrics/fixtures/rubric-cases.json）的前端侧测试（T340）。
 *
 * 同一份用例同时被：
 *   - teachloop-sitting 的 RubricValidatorCasesTest（发布校验）
 *   - teachloop-grading 的 RubricCriteriaTest（判分侧的评分项与分值）
 *   - 这里（「AI 将看到」面板的摘要）
 * 读取，防止三处解析规则再次漂移。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { summarizeRubric } from '../src/components/rubric.ts'

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const fixture = JSON.parse(readFileSync(resolve(repoRoot, 'docs/rubrics/fixtures/rubric-cases.json'), 'utf8'))
const round = (value) => Math.round(value * 100) / 100

function rubricText(testCase) {
  if (testCase.file) return readFileSync(resolve(repoRoot, testCase.file), 'utf8')
  return testCase.rubric
}

for (const testCase of fixture.cases) {
  test(`摘要与夹具一致：${testCase.name}`, () => {
    const summary = summarizeRubric(rubricText(testCase))

    assert.deepEqual(
      summary.criteria.map((c) => ({ name: c.name, points: c.points })),
      testCase.parsed,
      '评分项或分值变了',
    )
    assert.deepEqual(summary.ignored.map((c) => c.name), testCase.ignored, '被忽略的非评分项变了')

    const pointed = testCase.parsed.filter((c) => c.points !== null)
    assert.equal(summary.total, round(pointed.reduce((sum, c) => sum + c.points, 0)))
    assert.equal(summary.anyPoints, pointed.length > 0)
    assert.equal(summary.missingPoints, testCase.parsed.some((c) => c.points === null))
  })
}

test('用户那段 6 个标题的 rubric：只留 4 项，2 行标题/说明被忽略', () => {
  const testCase = fixture.cases.find((c) => c.name === 'lesson-plan-heading-labels')
  const summary = summarizeRubric(testCase.rubric)

  assert.equal(summary.criteria.length, 4)
  assert.deepEqual(summary.criteria.map((c) => c.points), [2, 6, 3, 3])
  assert.equal(summary.total, 14)
  assert.equal(summary.missingPoints, false)
  assert.deepEqual(summary.ignored.map((c) => c.name), [
    'Lesson Plan Components',
    "If any part is missing, you'll be asked to edit and resubmit.",
  ])
})

test('92 字符的长问句不会被丢弃', () => {
  const testCase = fixture.cases.find((c) => c.name === 'lesson-plan-heading-labels')
  const summary = summarizeRubric(testCase.rubric)
  const longName = summary.criteria.map((c) => c.name).find((name) => name.length > 80)

  assert.ok(longName, '应保留超过 80 字符的评分项')
  assert.equal(longName.length > 80, true)
})
