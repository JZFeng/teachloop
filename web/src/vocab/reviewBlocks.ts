/**
 * 卡片反面「渲染哪些块、按什么顺序」（T594）。
 *
 * <p>v2 内容由后端输出有序 `content.blocks`（顺序 = 用户偏好顺序，关掉的块不出现，
 * payload 缺失的块不出现）；v1 旧内容没有 `blocks` 字段，前端回退到
 * 「偏好顺序 + content.* 字段」按 key 取，兼容保留一个版本。
 *
 * <p>纯模块：不含 JSX、不引 React，node 测试可以直接 import。
 */
// 带 `.ts` 后缀：node 测试用 --experimental-strip-types 直接跑这个模块，必须显式扩展名
// （与 src/app/profileGate.ts 的 `./redirect.ts` 同一约定）。
import { WORD_BLOCKS, type WordBlock } from './wordBlocks.ts'

export interface ReviewCardBlock {
  id: string
  payload?: unknown
}

/** 回退渲染时块 id → content 字段名（与后端 payload 同一份值）。 */
export const BLOCK_FIELDS: Record<WordBlock, string> = {
  core: 'coreSemantics',
  senses: 'senses',
  examples: 'examples',
  synonyms: 'synonyms',
  antonyms: 'antonyms',
  family: 'family',
  derivation: 'derivation',
  analysis: 'analysis',
  image: 'image',
}

/** 只描述回退路径要读的字段；`blocks` 是后端新契约。 */
export interface CardBlockSource {
  blocks?: unknown
  coreSemantics?: unknown
  senses?: unknown
  examples?: unknown
  synonyms?: unknown
  antonyms?: unknown
  family?: unknown
  derivation?: unknown
  analysis?: unknown
  image?: unknown
}

function isWordBlock(id: string): id is WordBlock {
  return (WORD_BLOCKS as readonly string[]).includes(id)
}

/** 回退路径的「空」：缺省 / 空串 / 空数组都不占位。 */
function isEmptyPayload(value: unknown): boolean {
  if (value === undefined || value === null) return true
  if (typeof value === 'string') return value.trim() === ''
  if (Array.isArray(value)) return value.length === 0
  return false
}

/**
 * 解析卡片反面要渲染的块。
 *
 * <ul>
 *   <li>`content.blocks` 是数组 → 原样按后端顺序输出，只保留已知 id、跳过空 payload。
 *       **空数组输出空数组，绝不回退**——后端按契约说「没有块」。</li>
 *   <li>没有 `blocks` 字段（v1 旧内容）→ 按 `order ?? WORD_BLOCKS` 遍历，跳过 `hidden`
 *       与空字段，payload 取 `content[BLOCK_FIELDS[id]]`。</li>
 * </ul>
 */
export function resolveCardBlocks(
  content: CardBlockSource | null | undefined,
  order?: readonly string[] | null,
  hidden?: readonly string[] | null,
): ReviewCardBlock[] {
  if (!content) return []

  const rawBlocks = content.blocks
  if (Array.isArray(rawBlocks)) {
    const fromBackend: ReviewCardBlock[] = []
    for (const entry of rawBlocks) {
      if (!entry || typeof entry !== 'object') continue
      const id = (entry as { id?: unknown }).id
      if (typeof id !== 'string' || !isWordBlock(id)) continue
      const payload = (entry as { payload?: unknown }).payload
      if (payload === undefined || payload === null) continue
      fromBackend.push({ id, payload })
    }
    return fromBackend
  }

  const off = new Set(hidden ?? [])
  const orderIds = (order ?? WORD_BLOCKS).filter(
    (id): id is WordBlock => typeof id === 'string' && isWordBlock(id) && !off.has(id),
  )
  const fromFields: ReviewCardBlock[] = []
  const fields: Record<string, unknown> = { ...content }
  for (const id of orderIds) {
    const payload = fields[BLOCK_FIELDS[id]]
    if (isEmptyPayload(payload)) continue
    fromFields.push({ id, payload })
  }
  return fromFields
}
