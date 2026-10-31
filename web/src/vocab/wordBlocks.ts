/**
 * 卡片反面的 9 个块（US-88 / T554，T594 起加第 9 块 image）。key 是前后端契约的一部分：
 * 后端 `ReviewPrefs.ALL_BLOCKS` 必须与此完全一致，改 key 要两边一起改。
 */
export const WORD_BLOCKS = [
  'core', 'senses', 'examples', 'synonyms', 'antonyms', 'family', 'derivation', 'analysis', 'image',
] as const

export type WordBlock = (typeof WORD_BLOCKS)[number]

export const DEFAULT_BLOCKS: string[] = [...WORD_BLOCKS]

/** 设置页里每块的名称与说明（i18n key，见 locales 的 vocab.blocks.*）。 */
export const BLOCK_LABELS: Record<string, { name: string; desc: string }> = {
  core: { name: 'vocab.blocks.core', desc: 'vocab.blocks.coreDesc' },
  senses: { name: 'vocab.blocks.senses', desc: 'vocab.blocks.sensesDesc' },
  examples: { name: 'vocab.blocks.examples', desc: 'vocab.blocks.examplesDesc' },
  synonyms: { name: 'vocab.blocks.synonyms', desc: 'vocab.blocks.synonymsDesc' },
  antonyms: { name: 'vocab.blocks.antonyms', desc: 'vocab.blocks.antonymsDesc' },
  family: { name: 'vocab.blocks.family', desc: 'vocab.blocks.familyDesc' },
  derivation: { name: 'vocab.blocks.derivation', desc: 'vocab.blocks.derivationDesc' },
  analysis: { name: 'vocab.blocks.analysis', desc: 'vocab.blocks.analysisDesc' },
  image: { name: 'vocab.blocks.image', desc: 'vocab.blocks.imageDesc' },
}
