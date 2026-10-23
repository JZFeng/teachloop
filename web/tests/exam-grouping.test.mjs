/*
 * 答题页材料分组（T321 / T355）：同一篇材料只能渲染一次。
 *
 * 这条测试守的是本题回归的边界：
 * - 材料里的空可以比本卷题数多（七选五原文 5 个空、只导入第 7、8 题），仍要分组；
 * - 一个空不能放两道题、空位序号不能跳出材料，否则退回逐题渲染（宁可重复，不可拆错）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { buildPaperNodes } from '../src/exam/examUtils.ts'

const MATERIAL = 'How can English be learned well? Teachers are experienced. '
  + 'But remember to work in a way that suits you. ______ Keep a notebook.\n'
  + 'The school life may be hard, but you are not alone. ______\n'
  + 'It is important to live well. ______\n'
  + '______ Having a lot of schoolwork does not mean quitting other activities.'

function item(number, blankIndex, material = null, groupKey = 'g7') {
  return {
    number,
    questionType: 'SINGLE_CHOICE',
    points: 1,
    stem: null,
    options: 'A. one\nB. two\nC. three',
    sectionPosition: 3,
    sectionTitle: '第二部分 阅读理解',
    groupKey,
    material,
    questionStem: null,
    blankIndex,
  }
}

test('空比题多：材料只出现一次，控件按空位序号落位', () => {
  const nodes = buildPaperNodes([
    item(7, 0, MATERIAL),
    item(8, 1),
  ])

  assert.equal(nodes.length, 1)
  assert.equal(nodes[0].kind, 'blank')
  assert.equal(nodes[0].key, 'g7')
  assert.deepEqual(nodes[0].items.map((member) => member.blankIndex), [0, 1])
})

test('题与空一一对应时行为不变', () => {
  const twoBlanks = 'First text here. ______ Second text here. ______'
  const nodes = buildPaperNodes([
    item(9, 0, twoBlanks),
    item(10, 1),
  ])

  assert.equal(nodes.length, 1)
  assert.equal(nodes[0].kind, 'blank')
  assert.deepEqual(nodes[0].items.map((member) => member.blankIndex), [0, 1])
})

test('同一个空被两道题占了：整组退回逐题渲染', () => {
  const nodes = buildPaperNodes([
    item(7, 1, MATERIAL),
    item(8, 1),
  ])

  assert.deepEqual(nodes.map((node) => node.kind), ['solo', 'solo'])
})

test('空位序号跳出材料范围：退回逐题渲染', () => {
  const nodes = buildPaperNodes([
    item(7, 0, MATERIAL),
    item(8, 9),
  ])

  assert.deepEqual(nodes.map((node) => node.kind), ['solo', 'solo'])
})

test('题比空多：退回逐题渲染', () => {
  const oneBlank = 'Only one blank in this passage. ______ and no more.'
  const nodes = buildPaperNodes([
    item(7, 0, oneBlank),
    item(8, 1),
  ])

  assert.deepEqual(nodes.map((node) => node.kind), ['solo', 'solo'])
})

test('阅读题（材料 + 空行 + 问题）仍按材料组渲染', () => {
  const passage = 'I walked into the room twenty years after leaving high school. '
    + 'The faces around me had not changed much, and the years came back at once.'
  const reading = (number, question) => ({
    ...item(number, null, number === 11 ? passage : null, 'g11'),
    questionStem: question,
  })
  const nodes = buildPaperNodes([
    reading(11, 'What did the writer see first?'),
    reading(12, 'Why did the writer return?'),
  ])

  assert.equal(nodes.length, 1)
  assert.equal(nodes[0].kind, 'reading')
  assert.equal(nodes[0].items.length, 2)
})
