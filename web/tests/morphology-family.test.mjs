import assert from 'node:assert/strict'
import test from 'node:test'

import { familyAddsNothing } from '../src/vocab/morphology.ts'

/**
 * 词族渲染规则（2026-10-31 方案 5）：词族只是「词根 + 派生词 + 词形变化」的并集时不渲染。
 *
 * 数据取自真实词条 inflect：词族 8 条 = 派生词 4 + 词形变化 3 + 词根本身 1，
 * 0 个新词（这也是「15 行里 7 行重复」的来源）。
 */
const inflect = {
  derivatives: [
    { word: 'inflection' }, { word: 'inflectional' },
    { word: 'inflexion' }, { word: 'inflexional' },
  ],
  inflections: [{ word: 'inflects' }, { word: 'inflected' }, { word: 'inflecting' }],
  family: [
    { word: 'inflect' }, { word: 'inflects' }, { word: 'inflected' }, { word: 'inflecting' },
    { word: 'inflection' }, { word: 'inflectional' }, { word: 'inflexion' }, { word: 'inflexional' },
  ],
}

test('词族只是并集时不渲染（inflect：8 条 0 新词）', () => {
  assert.equal(familyAddsNothing('inflect', inflect), true)
})

test('词族含额外词时渲染（复合词：classroom / classmate 不在派生/变形里）', () => {
  const morphology = {
    derivatives: [{ word: 'classy' }],
    inflections: [],
    family: [{ word: 'class' }, { word: 'classroom' }, { word: 'classmate' }],
  }
  assert.equal(familyAddsNothing('class', morphology), false)
})

test('大小写与首尾空白不影响判断', () => {
  const morphology = {
    derivatives: [{ word: ' inflection ' }],
    inflections: [{ word: 'INFLECTS' }],
    family: [{ word: 'Inflect' }, { word: 'inflects' }, { word: 'Inflection' }],
  }
  assert.equal(familyAddsNothing('inflect', morphology), true)
})

test('没有词族 / 没有 morphology 时不渲染整段', () => {
  assert.equal(familyAddsNothing('inflect', null), false)
  assert.equal(familyAddsNothing('inflect', { derivatives: [{ word: 'x' }] }), false)
})

test('坏数据（缺 word）不算额外词，不会把整段顶出来', () => {
  const morphology = {
    derivatives: [{ word: 'inflection' }],
    inflections: [],
    family: [{ word: 'inflect' }, { word: 'inflection' }, {}],
  }
  assert.equal(familyAddsNothing('inflect', morphology), true)
})

// ---- splitSenseText：义项里内嵌「1. …；2. …；3. …」时拆行 ----
import { splitSenseText } from '../src/vocab/morphology.ts'

test('拆开挤在一个字符串里的三个编号义项（真实 inflect 数据）', () => {
  const raw = '1.（动词）改变词形（如屈折变化）；2.（声音）抑扬顿挫；3.（使）弯曲或转向。'
  assert.deepEqual(splitSenseText(raw), [
    '（动词）改变词形（如屈折变化）',
    '（声音）抑扬顿挫',
    '（使）弯曲或转向。',
  ])
})

test('没有编号 / 只有一个编号时原样返回一条，不猜断句', () => {
  assert.deepEqual(splitSenseText('渴望的；热切的'), ['渴望的；热切的'])
  assert.deepEqual(splitSenseText('1. 只有一条'), ['1. 只有一条'])
  assert.deepEqual(splitSenseText('   '), [])
  assert.deepEqual(splitSenseText(null), [])
})

test('半角分号与「、」编号也认', () => {
  assert.deepEqual(
    splitSenseText('1. 甲; 2、乙'),
    ['甲', '乙'],
  )
})
