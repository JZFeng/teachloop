/*
 * 金标往返测试（T339）：Markdown 进 → Vditor IR 编辑器内部模型 → Markdown 出，
 * 评分项与合计必须逐项不变。
 *
 * 为什么必须存在：AI 判分与评分标准校验读的都是 Markdown 原文，后端 RubricValidator
 * 按行解析标题 / 列表 / 编号（teachloop-sitting/.../RubricValidator.java）。所见即所得
 * 编辑器一旦在往返中合并行、改写列表或丢分值，教师会在「发布」时才被打回。
 *
 * 这里直接用 Vditor 自带的 Lute 引擎（GopherJS 编译的 Go Markdown 解析器）做往返，
 * 与浏览器里 IR 模式的输入链路是同一套代码：Md2VditorIRDOM → VditorIRDOM2Md。
 *
 * 运行：cd web && pnpm test（需要 Node 22+ 的 --experimental-strip-types）
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { summarizeRubric } from '../src/components/rubric.ts'

const require = createRequire(import.meta.url)
// Lute 是 GopherJS 产物：require 之后挂在 globalThis.Lute 上，不是 ES 模块。
require('vditor/dist/js/lute/lute.min.js')

const lute = globalThis.Lute.New()
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..')

/** 浏览器 IR 模式的同一套转换：Markdown → IR DOM → Markdown。 */
const roundTrip = (markdown) => lute.VditorIRDOM2Md(lute.Md2VditorIRDOM(markdown))

function read(relative) {
  return readFileSync(resolve(repoRoot, relative), 'utf8')
}

/** 评分项的「名称 + 分值」必须逐项相等；顺序也算。 */
function assertSameCriteria(before, after, label) {
  assert.ok(before.criteria.length > 0, `${label}: 原始 rubric 应至少解析出一项`)
  assert.deepEqual(
    after.criteria.map((c) => ({ name: c.name, points: c.points })),
    before.criteria.map((c) => ({ name: c.name, points: c.points })),
    `${label}: 往返后评分项或分值变了`,
  )
  assert.equal(after.total, before.total, `${label}: 往返后合计变了`)
  assert.equal(after.anyPoints, before.anyPoints, `${label}: 分值识别结果变了`)
  assert.equal(after.missingPoints, before.missingPoints, `${label}: 缺分值标记变了`)
}

test('真实课程 rubric（Rubric.md）往返后评分项与合计不变', () => {
  const source = read('docs/rubrics/Rubric.md')
  const before = summarizeRubric(source)
  const after = summarizeRubric(roundTrip(source))
  assertSameCriteria(before, after, 'Rubric.md')
  // 英文课程 rubric 是 3 项 2/4/4：数量写死，防止「解析器改了但两边一起变」。
  assert.deepEqual(after.criteria.map((c) => c.points), [2, 4, 4])
  assert.equal(after.total, 10)
})

test('真实作业 rubric（Overall_Assignment_Rubric.txt）往返后评分项与合计不变', () => {
  const source = read('docs/rubrics/Overall_Assignment_Rubric.txt')
  const before = summarizeRubric(source)
  const after = summarizeRubric(roundTrip(source))
  assertSameCriteria(before, after, 'Overall_Assignment_Rubric.txt')
  assert.deepEqual(after.criteria.map((c) => c.points), [2, 4, 4])
})

test('教师「一条一行、不带列表符号」的写法不会被合并成一段', () => {
  const source = '评分标准\n\n内容 6 分\n结构 4 分\n语言 3 分\n\n总分 13 分\n'
  const roundTripped = roundTrip(source)
  assert.equal(roundTripped.trim(), source.trim(), 'IR 往返把行并掉或补了标记')
  // 这种写法本来就解析不出评分项（后端要求列表/编号/标题），这里只保证编辑器不改原文。
  assert.equal(summarizeRubric(roundTripped).criteria.length, 0)
})

test('中文问句式评分项与 Yes/No 说明行往返后仍按原结构解析', () => {
  const source = [
    '## 评分标准',
    '',
    '1. 是否切题（4 分）',
    '2. 论据是否充分（6 分）',
    '3. 语言准确性（3 分）',
    '',
    '- 是：得分',
    '- 否：0 分',
    '',
    '总分 13 分',
    '',
  ].join('\n')
  const before = summarizeRubric(source)
  const after = summarizeRubric(roundTrip(source))
  assert.deepEqual(
    before.criteria.map((c) => ({ name: c.name, points: c.points })),
    [
      { name: '是否切题', points: 4 },
      { name: '论据是否充分', points: 6 },
      { name: '语言准确性', points: 3 },
    ],
  )
  assertSameCriteria(before, after, '中文 rubric')
  assert.equal(after.total, 13)
})

test('粗体 / 行内代码 / 表格等 Markdown 结构往返后仍可解析', () => {
  const source = [
    '## 评分标准',
    '',
    '1. **内容** — 6 分',
    '2. `结构` — 4 分',
    '',
    '| 项 | 分值 |',
    '| --- | --- |',
    '| 语言 | 3 分 |',
    '',
  ].join('\n')
  const roundTripped = roundTrip(source)
  assert.match(roundTripped, /\*\*内容\*\*/)
  assert.match(roundTripped, /`结构`/)
  assert.match(roundTripped, /\|\s*项\s*\|\s*分值\s*\|/)
  assert.equal(summarizeRubric(roundTripped).total, 10)
})
