/**
 * 卡片反面块解析（T594）：后端 v2 输出有序 `content.blocks`，前端按它渲染；
 * v1 旧内容没有 `blocks` 字段，回退到「偏好顺序 + content.* 字段」。
 *
 * 这里钉死回退与不回退的边界，页面只负责按结果渲染。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { resolveCardBlocks } from '../src/vocab/reviewBlocks.ts'
import { WORD_BLOCKS } from '../src/vocab/wordBlocks.ts'

const ids = (blocks) => blocks.map((block) => block.id)

test('后端 blocks 的顺序原样采用（与 WORD_BLOCKS 不同也照用）', () => {
  const content = {
    blocks: [
      { id: 'analysis', payload: '词源说明' },
      { id: 'core', payload: { core: '核心' } },
      { id: 'senses', payload: [{ zh: '奋斗' }] },
    ],
    // 旧字段也在场，但 blocks 在场时一律以 blocks 为准
    coreSemantics: { core: '不该被用' },
    senses: [{ zh: '不该被用' }],
  }
  const resolved = resolveCardBlocks(content, ['image', 'family'])
  assert.deepEqual(ids(resolved), ['analysis', 'core', 'senses'])
  assert.deepEqual(resolved[1].payload, { core: '核心' })
})

test('blocks 为空数组 → 输出空数组，绝不回退到 content.*', () => {
  const content = {
    blocks: [],
    coreSemantics: { core: '有内容也不回退' },
    senses: [{ zh: '有内容也不回退' }],
    analysis: '有内容也不回退',
  }
  assert.deepEqual(resolveCardBlocks(content, WORD_BLOCKS, []), [])
})

test('没有 blocks 字段 → 回退到 order + content 字段', () => {
  const content = {
    coreSemantics: { core: '核心' },
    senses: [{ zh: '奋斗' }],
    analysis: '一段构词分析',
  }
  const resolved = resolveCardBlocks(content, ['analysis', 'core', 'senses'], [])
  assert.deepEqual(ids(resolved), ['analysis', 'core', 'senses'])
  assert.equal(resolved[0].payload, '一段构词分析')
  assert.deepEqual(resolved[1].payload, { core: '核心' })
})

test('回退时 hidden 生效（顺序不决定有无）', () => {
  const content = {
    coreSemantics: { core: '核心' },
    senses: [{ zh: '奋斗' }],
    analysis: '一段构词分析',
  }
  const resolved = resolveCardBlocks(content, ['core', 'senses', 'analysis'], ['senses'])
  assert.deepEqual(ids(resolved), ['core', 'analysis'])
})

test('未知 id 跳过（blocks 与回退两条路径）', () => {
  const fromBackend = resolveCardBlocks({
    blocks: [
      { id: 'foo', payload: { anything: true } },
      { id: 'core', payload: { core: '核心' } },
    ],
  })
  assert.deepEqual(ids(fromBackend), ['core'])

  const fromFields = resolveCardBlocks(
    { coreSemantics: { core: '核心' } },
    ['foo', 'core'],
    [],
  )
  assert.deepEqual(ids(fromFields), ['core'])
})

test('payload 缺失 / null 跳过', () => {
  const resolved = resolveCardBlocks({
    blocks: [
      { id: 'core' },
      { id: 'senses', payload: null },
      { id: 'examples', payload: [{ en: 'Still here.' }] },
    ],
  })
  assert.deepEqual(ids(resolved), ['examples'])
})

test('image 块 payload 透传（含 url）', () => {
  const image = { url: 'https://example.com/word.png' }
  const resolved = resolveCardBlocks({
    blocks: [
      { id: 'core', payload: { core: '核心' } },
      { id: 'image', payload: image },
    ],
  })
  assert.deepEqual(ids(resolved), ['core', 'image'])
  assert.deepEqual(resolved[1].payload, image)

  // 回退路径同样能取到 content.image
  const fallback = resolveCardBlocks({ image }, ['image'], [])
  assert.deepEqual(ids(fallback), ['image'])
  assert.deepEqual(fallback[0].payload, image)
})

test('analysis 的 payload 是字符串也能正确取出', () => {
  const resolved = resolveCardBlocks({
    blocks: [{ id: 'analysis', payload: '前缀 re- + 词根 view' }],
  })
  assert.equal(resolved.length, 1)
  assert.equal(resolved[0].payload, '前缀 re- + 词根 view')
})

test('回退时空字段（undefined / null / 空串 / 空数组）不占位', () => {
  const resolved = resolveCardBlocks(
    { senses: [], analysis: '', family: undefined, synonyms: null, examples: [{ en: 'Hi.' }] },
    ['senses', 'analysis', 'family', 'synonyms', 'examples'],
    [],
  )
  assert.deepEqual(ids(resolved), ['examples'])
})

test('content 为空 → 空数组', () => {
  assert.deepEqual(resolveCardBlocks(null), [])
  assert.deepEqual(resolveCardBlocks(undefined), [])
})

test('V3 的 6 块原样透传（morphology / etymology 不被 WORD_BLOCKS 过滤）', () => {
  const morphology = {
    modernNote: '现代英语中 eager 是整体词。',
    derivatives: [{ word: 'eagerly', partOfSpeech: 'adverb', meaning: '热切地' }],
    family: [{ word: 'eager', partOfSpeech: 'adjective', meaning: '渴望的' }],
  }
  const content = {
    blocks: [
      { id: 'senses', payload: [{ pos: 'adj.', zh: '渴望的' }] },
      { id: 'morphology', payload: morphology },
      { id: 'core', payload: { core: '热切' } },
      { id: 'examples', payload: [{ en: 'She is eager.', zh: '她很渴望。' }] },
      { id: 'synonyms', payload: { items: [], antonyms: [] } },
      { id: 'etymology', payload: { text: '源自拉丁语 acer。' } },
    ],
  }
  const resolved = resolveCardBlocks(content)
  assert.deepEqual(ids(resolved), [
    'senses', 'morphology', 'core', 'examples', 'synonyms', 'etymology',
  ])
  assert.deepEqual(resolved[1].payload, morphology)
})
