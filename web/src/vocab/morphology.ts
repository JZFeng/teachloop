/**
 * 卡片反面的两条显示规则（2026-10-31 方案 5）：
 *
 * <ol>
 *   <li>{@link familyAddsNothing}：词族只是「词根 + 派生词 + 词形变化」的并集时不渲染；</li>
 *   <li>{@link splitSenseText}：一个义项里挤了「1. …；2. …；3. …」时拆成多行。</li>
 * </ol>
 *
 * <p>背景：inflect 的 `morphology.family` 8 条 = 派生词 4 + 词形变化 3 + 词根本身 1，
 * 是严格超集、**0 个新词**。旧卡片把「派生词 / 屈折 / 词族」三段都渲染出来，
 * 15 行里 7 行是重复的（47%），这是用户说的「内容重复导致的阅读疲劳」的主要来源。
 *
 * <p>两条都是「怎么显示」的规则，不改内容契约：`family` 与 `senses` 数据保持原样。
 */

export interface MorphWord {
  word?: string
}

export interface MorphologyLike {
  derivatives?: MorphWord[]
  inflections?: MorphWord[]
  family?: MorphWord[]
}

function normalize(value: string): string {
  return value.trim().toLowerCase()
}

/** 词族是否只是「词根 + 派生词 + 词形变化」的并集（是 → 不渲染整段）。 */
export function familyAddsNothing(
  head: string,
  morphology?: MorphologyLike | null,
): boolean {
  const family = morphology?.family ?? []
  if (family.length === 0) return false
  const known = new Set<string>()
  if (head.trim()) known.add(normalize(head))
  for (const item of morphology?.derivatives ?? []) {
    if (item.word) known.add(normalize(item.word))
  }
  for (const item of morphology?.inflections ?? []) {
    if (item.word) known.add(normalize(item.word))
  }
  // 缺 word 的坏数据不算「额外词」，避免因为一条脏数据把整段渲染出来。
  return family.every((item) => !item.word || known.has(normalize(item.word)))
}

/**
 * 把一条义项里内嵌的「1. …；2. …；3. …」拆成多行。
 *
 * <p>AI 生成的内容常把多个义项写进同一个 `senses[].zh` 字符串（真实数据：
 * `1.（动词）改变词形（如屈折变化）；2.（声音）抑扬顿挫；3.（使）弯曲或转向。`），
 * 卡片上就变成一堵文字墙。这里只在出现 **≥2 个编号**时拆，拆完由渲染器补编号；
 * 没有编号、或只有一个编号的句子原样返回一条，绝不猜断句。
 */
export function splitSenseText(text?: string | null): string[] {
  const source = (text ?? '').trim()
  if (!source) return []
  const marks = [...source.matchAll(/(?:^|[；;]\s*)(\d+)[.、．]\s*/g)]
  if (marks.length < 2) return [source]
  const parts: string[] = []
  marks.forEach((mark, index) => {
    const start = (mark.index ?? 0) + mark[0].length
    const end = index + 1 < marks.length ? marks[index + 1].index ?? source.length : source.length
    const body = source.slice(start, end).replace(/[；;]\s*$/, '').trim()
    if (body) parts.push(body)
  })
  return parts.length >= 2 ? parts : [source]
}
