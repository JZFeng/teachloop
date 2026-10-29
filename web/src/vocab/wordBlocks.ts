/**
 * 卡片反面的 8 个块（US-88 / T554）。key 是前后端契约的一部分：
 * 后端 `ReviewPrefs.ALL_BLOCKS` 必须与此完全一致，改 key 要两边一起改。
 */
export const WORD_BLOCKS = [
  'core', 'senses', 'examples', 'synonyms', 'antonyms', 'family', 'derivation', 'analysis',
] as const

export type WordBlock = (typeof WORD_BLOCKS)[number]

export const DEFAULT_BLOCKS: string[] = [...WORD_BLOCKS]

/** 设置页里每块的名称与说明（i18n key 后缀，见 locales 的 vocab.prefs.blocks.*）。 */
export const BLOCK_LABELS: Record<string, { name: string; desc: string }> = {
  core: { name: 'vocab.prefs.blocks.core', desc: 'vocab.prefs.blocks.coreDesc' },
  senses: { name: 'vocab.prefs.blocks.senses', desc: 'vocab.prefs.blocks.sensesDesc' },
  examples: { name: 'vocab.prefs.blocks.examples', desc: 'vocab.prefs.blocks.examplesDesc' },
  synonyms: { name: 'vocab.prefs.blocks.synonyms', desc: 'vocab.prefs.blocks.synonymsDesc' },
  antonyms: { name: 'vocab.prefs.blocks.antonyms', desc: 'vocab.prefs.blocks.antonymsDesc' },
  family: { name: 'vocab.prefs.blocks.family', desc: 'vocab.prefs.blocks.familyDesc' },
  derivation: { name: 'vocab.prefs.blocks.derivation', desc: 'vocab.prefs.blocks.derivationDesc' },
  analysis: { name: 'vocab.prefs.blocks.analysis', desc: 'vocab.prefs.blocks.analysisDesc' },
}
