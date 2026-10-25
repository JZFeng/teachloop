/*
 * 只读 Markdown 解析器的回归测试：夹具是 2026-10-24 学生真实作业里存的原文。
 *
 * 为什么必须用真实文本：这些 bug 不是语法书上的边角，而是老师从 Word / 课程页
 * 复制进来的常见写法——`***Instructions***`、列表项之间空一行、`a.` / `b.` 子项。
 * 旧的「直接产出 React 元素」写法没法单测，于是三处一起坏在了学生页面上。
 *
 * 运行：cd web && pnpm test
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { parseMarkdown, parseInline } from '../src/components/markdown/parser.ts'

/** 学生作业 2150b208 里实际存的题目要求（与老师给的 写作3.md 同源）。 */
const INSTRUCTIONS = `***Instructions***

  1. Based on the reading you have chosen, create a lesson plan using the ASU Lesson Plan Template. Choose or create activities that demonstrate both intensive and extensive techniques.

  2. Fill out the lesson plan template with your activities for a 50-60 minute reading lesson plan and save it for later.

  3. Use at least one intensive reading activity and one extensive reading activity in your lesson plan.

  4. Copy and paste the text of your lesson plan to submit for this assignment in the "My Submission" box,

      a. include all components of the lesson plan and

      b. label each component clearly.

If you want support, ask for help in the discussion boards.

***Reminders:***

1. Save all of your work from each assignment in this course in a secure location so you can refer back to them in the future. You are being asked to create a lesson plan for this assignment, and you will be asked to alter it in the next assignment to add ideas learned in the next module.  

2. Save each of your lesson plans from this course because you will be asked to submit a lesson plan from this course in the Capstone 2 course.`

/** 同一场作业的评分标准原文（与 评分标准3.md 同源，分值单位在库里是「分」）。 */
const RUBRIC = `- Did the learner submit a lesson plan related to a reading in English? （2  分）
- Did the learner include all the components as provided in the lesson plan template in English?  （6分）
- Lesson Plan Components: materials, objectives, warm-up, objective discussion, instruct, model, guided practice, independent practice, and assessment. （3分）

  1. If any part is missing, you’ll be asked to edit and resubmit.
  2. Did the learner use at least one intensive reading activity in English?
- Did the learner use at least one extensive reading activity in English?  （3分）`

/** 把行内树拍平成纯文本，便于断言内容。 */
function inlineText(nodes) {
  return nodes
    .map((node) => {
      if (node.type === 'text' || node.type === 'code') return node.value
      if (node.type === 'br') return '\n'
      return inlineText(node.children)
    })
    .join('')
}

test('***粗斜体*** 渲染成 strong > em，星号不残留', () => {
  const blocks = parseMarkdown('***Instructions***')
  assert.equal(blocks.length, 1)
  const [paragraph] = blocks
  assert.equal(paragraph.type, 'paragraph')
  assert.equal(inlineText(paragraph.children), 'Instructions')
  assert.equal(paragraph.children[0].type, 'strong')
  assert.equal(paragraph.children[0].children[0].type, 'em')
})

test('列表项之间空一行仍是一个列表，编号从 1 连续', () => {
  const blocks = parseMarkdown(INSTRUCTIONS)
  const list = blocks.find((block) => block.type === 'list' && block.ordered)
  assert.ok(list, '应解析出有序列表')
  assert.equal(list.items.length, 4, '四个事项应在同一个 <ol> 里（旧实现断成四个列表，各显示 1.）')
  assert.equal(list.start, 1)
  assert.match(inlineText(list.items[1].blocks[0].children), /^Fill out the lesson plan template/)
})

test('6 空格缩进的 a. / b. 挂到第 4 项下，渲染成字母子列表', () => {
  const blocks = parseMarkdown(INSTRUCTIONS)
  const list = blocks.find((block) => block.type === 'list' && block.ordered)
  const fourth = list.items[3]
  const nested = fourth.blocks.find((block) => block.type === 'list')
  assert.ok(nested, 'a./b. 应成为第 4 项里的子列表')
  assert.equal(nested.ordered, true)
  assert.equal(nested.alpha, 'a')
  assert.equal(nested.items.length, 2)
  assert.match(inlineText(nested.items[0].blocks[0].children), /^include all components/)
  assert.match(inlineText(nested.items[1].blocks[0].children), /^label each component clearly\.$/)
})

test('Reminders 两个事项同样是一个列表（旧实现各显示 1.）', () => {
  const blocks = parseMarkdown(INSTRUCTIONS)
  const reminders = blocks.find(
    (block) => block.type === 'list' && block.ordered && block.items.length === 2,
  )
  assert.ok(reminders, 'Reminders 应解析成一个两项列表')
  assert.equal(reminders.items.length, 2)
})

test('评分标准：四条要点是一个列表，第 3 条下挂两条编号子项', () => {
  const blocks = parseMarkdown(RUBRIC)
  const list = blocks.find((block) => block.type === 'list' && !block.ordered)
  assert.ok(list, '应解析出无序列表')
  assert.equal(list.items.length, 4, '四条要点应同属一个 <ul>')
  const nested = list.items[2].blocks.find((block) => block.type === 'list')
  assert.ok(nested, '第 3 条下的编号子项应成为子列表')
  assert.equal(nested.ordered, true)
  assert.equal(nested.items.length, 2)
  assert.match(inlineText(nested.items[0].blocks[0].children), /^If any part is missing/)
})

test('编号不从 1 开始时保留起始值', () => {
  const [list] = parseMarkdown('3. third\n4. fourth')
  assert.equal(list.type, 'list')
  assert.equal(list.start, 3)
  assert.equal(list.items.length, 2)
})

test('行内混合：斜体、粗体、粗斜体、行内代码互不吞并', () => {
  const nodes = parseInline('*a* **b** ***c*** `d`')
  const meaningful = nodes.filter((node) => node.type !== 'text')
  assert.deepEqual(meaningful.map((node) => node.type), ['em', 'strong', 'strong', 'code'])
  assert.equal(inlineText([meaningful[2]]), 'c')
  assert.equal(meaningful[2].children[0].type, 'em', '粗斜体内部是 em')
  assert.equal(meaningful[3].value, 'd')
})

test('原生 HTML 标签只会是文字，不产生 HTML 结构', () => {
  const blocks = parseMarkdown('<script>alert(1)</script>')
  assert.equal(blocks[0].type, 'paragraph')
  assert.equal(inlineText(blocks[0].children), '<script>alert(1)</script>')
  // 整棵树里只有 text/inline token，没有任何 html 类型
  assert.ok(!JSON.stringify(blocks).includes('"type":"html"'))
})

test('段内软换行合并成一个空格，行尾两个空格才是硬换行', () => {
  const soft = parseMarkdown('one line\ncontinues here')
  assert.equal(inlineText(soft[0].children), 'one line continues here')
  assert.ok(!soft[0].children.some((node) => node.type === 'br'))

  const hard = parseMarkdown('first line  \nsecond line')
  assert.ok(hard[0].children.some((node) => node.type === 'br'))
  assert.equal(inlineText(hard[0].children), 'first line\nsecond line')
})

test('列表项内被软换行拆开的句子会接回去', () => {
  const blocks = parseMarkdown('1. use the ASU Lesson Plan Template\n. Choose or create activities')
  const [list] = blocks
  assert.equal(list.type, 'list')
  const text = inlineText(list.items[0].blocks[0].children)
  assert.equal(text, 'use the ASU Lesson Plan Template . Choose or create activities')
  assert.ok(!list.items[0].blocks[0].children.some((node) => node.type === 'br'))
})
