// 考试端纯函数：解析选项、判定作答状态、分组、草稿与字数。
// 只放可单测的纯逻辑，不碰 React 与网络。
import type { ExamPaperItem } from '../api/types'

export const LETTERS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'

export interface ExamOption {
  letter: string
  text: string
}

/** 显式前缀：A. / A、 / A) / A： */
const EXPLICIT_PREFIX = /^([A-Z])\s*[.、)）:：．]\s*(.*)$/s
/** 宽松前缀：A + 至少一个空白。仅在整列都按 A/B/C 顺序排列时才采信。 */
const LOOSE_PREFIX = /^([A-Z])\s+(.+)$/s

function matchExplicit(line: string): { letter: string; text: string } | null {
  const m = EXPLICIT_PREFIX.exec(line)
  if (!m || !m[2].trim()) return null
  return { letter: m[1], text: m[2].trim() }
}

function matchLoose(line: string): { letter: string; text: string } | null {
  const m = LOOSE_PREFIX.exec(line)
  if (!m || !m[2].trim()) return null
  return { letter: m[1], text: m[2].trim() }
}

/**
 * 后端把选项存成一段文本，不一定带字母前缀。
 * 支持：JSON 数组 / "A. xxx" / "A xxx"（整列顺序为 A、B、C 时）/ 纯文本行。
 */
export function parseOptions(raw: string | null | undefined): ExamOption[] {
  if (!raw) return []
  const trimmed = raw.trim()
  if (!trimmed) return []

  if (trimmed.startsWith('[')) {
    try {
      const parsed: unknown = JSON.parse(trimmed)
      if (Array.isArray(parsed)) {
        return parsed
          .filter((v) => typeof v === 'string')
          .map((text, i) => {
            const raw = String(text).trim()
            const explicit = matchExplicit(raw)
            return {
              letter: LETTERS[i] ?? String(i + 1),
              text: explicit ? explicit.text : raw,
            }
          })
          .filter((o) => o.text.length > 0)
      }
    } catch {
      // 不是 JSON 就继续按行解析
    }
  }

  const lines = trimmed
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
  if (!lines.length) return []

  const explicit = lines.map(matchExplicit)
  if (explicit.every((o) => o !== null) && sequentialLetters(explicit.map((o) => o!.letter))) {
    return explicit.map((o) => o!)
  }

  const loose = lines.map(matchLoose)
  if (lines.length > 1 && loose.every((o) => o !== null) && sequentialLetters(loose.map((o) => o!.letter))) {
    return loose.map((o) => o!)
  }

  return lines.map((text, i) => ({ letter: LETTERS[i] ?? String(i + 1), text }))
}

function sequentialLetters(letters: string[]): boolean {
  return letters.every((letter, i) => letter === LETTERS[i])
}

/** 多选答案统一成大写、去重、升序的字母串。 */
export function sortLetters(value: string | null | undefined): string {
  return Array.from(new Set((value ?? '').toUpperCase().replace(/[^A-Z]/g, '').split('')))
    .sort()
    .join('')
}

export function lettersOf(value: string | null | undefined): string[] {
  return sortLetters(value).split('').filter(Boolean)
}

export function toggleLetter(value: string | null | undefined, letter: string): string {
  const set = new Set(lettersOf(value))
  if (set.has(letter)) set.delete(letter)
  else set.add(letter)
  return [...set].sort().join('')
}

export function isChoiceType(type: ExamPaperItem['questionType']): boolean {
  return type === 'SINGLE_CHOICE' || type === 'MULTIPLE_CHOICE'
}

/** 未答：单选/填空无值，多选一个都没勾，写作无正文（AC-21.6）。 */
export function isBlankAnswer(type: ExamPaperItem['questionType'], value: string | null | undefined): boolean {
  if (type === 'MULTIPLE_CHOICE') return lettersOf(value).length === 0
  return (value ?? '').trim().length === 0
}

export interface ExamPart {
  key: string
  title: string
  position: number
  items: ExamPaperItem[]
}

/** 按 sectionPosition 分组；没有 section 的题归入「本卷」。 */
export function groupParts(items: ExamPaperItem[], wholePaperTitle: string): ExamPart[] {
  const parts = new Map<string, ExamPart>()
  for (const item of items) {
    const title = (item.sectionTitle ?? '').trim()
    const position = item.sectionPosition ?? 0
    const key = title ? `s${position}:${title}` : 'whole'
    const part = parts.get(key)
    if (part) part.items.push(item)
    else parts.set(key, { key, title: title || wholePaperTitle, position, items: [item] })
  }
  return [...parts.values()].sort((a, b) => a.position - b.position)
}

export function partKeyOfNumber(parts: ExamPart[], number: number): string | null {
  for (const part of parts) {
    if (part.items.some((item) => item.number === number)) return part.key
  }
  return null
}

/** 提交给后端的作答列表：跳过未答，多选统一排序。 */
export function buildAnswerList(
  items: ExamPaperItem[],
  answers: Record<string, string>,
): { number: number; answer: string }[] {
  return items
    .filter((item) => !isBlankAnswer(item.questionType, answers[item.number]))
    .map((item) => ({
      number: item.number,
      answer:
        item.questionType === 'MULTIPLE_CHOICE'
          ? sortLetters(answers[item.number])
          : (answers[item.number] ?? '').trim(),
    }))
}

const CJK = /[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]/g
const LATIN_WORD = /[A-Za-z0-9]+(?:['’-][A-Za-z0-9]+)*/g

/** 英文按空格切词、中文按字符计（AC-21.13）。 */
export function countWrittenWords(text: string | null | undefined): number {
  const value = text ?? ''
  const cjk = (value.match(CJK) ?? []).length
  const latin = (value.replace(CJK, ' ').match(LATIN_WORD) ?? []).length
  return cjk + latin
}

/** 后端没有 word_limit：写作题 points>=15 视为 80 词，否则 max(40, points×5)。 */
export function wordLimitFor(points: number): number {
  if (points >= 15) return 80
  return Math.max(40, Math.round(points * 5))
}

export function toleranceFor(limit: number): number {
  return Math.round(limit * 0.2)
}

export const DRAFT_PREFIX = 'teachloop.draft.'

export interface ExamDraft {
  offeringId: string
  submissionId: string | null
  answers: Record<string, string>
  marked: number[]
  updatedAt: number
}

export function draftKey(offeringId: string): string {
  return `${DRAFT_PREFIX}${offeringId}`
}

function safeStorage(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.localStorage
  } catch {
    return null
  }
}

export function readDraft(offeringId: string): ExamDraft | null {
  const storage = safeStorage()
  if (!storage) return null
  try {
    const raw = storage.getItem(draftKey(offeringId))
    if (!raw) return null
    const parsed: unknown = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object') return null
    const draft = parsed as Partial<ExamDraft>
    if (draft.offeringId !== offeringId) return null
    const answers: Record<string, string> = {}
    if (draft.answers && typeof draft.answers === 'object') {
      for (const [key, value] of Object.entries(draft.answers)) {
        if (typeof value === 'string') answers[key] = value
      }
    }
    return {
      offeringId,
      submissionId: typeof draft.submissionId === 'string' ? draft.submissionId : null,
      answers,
      marked: Array.isArray(draft.marked) ? draft.marked.filter((n): n is number => typeof n === 'number') : [],
      updatedAt: typeof draft.updatedAt === 'number' ? draft.updatedAt : 0,
    }
  } catch {
    return null
  }
}

export function writeDraft(draft: ExamDraft): void {
  const storage = safeStorage()
  if (!storage) return
  try {
    storage.setItem(draftKey(draft.offeringId), JSON.stringify(draft))
  } catch {
    // 存储满/被禁用时静默失败：断点续答是增强，不是正确性前提
  }
}

export function clearDraft(offeringId: string): void {
  const storage = safeStorage()
  if (!storage) return
  try {
    storage.removeItem(draftKey(offeringId))
  } catch {
    // ignore
  }
}

/** 展示用数字：BigDecimal 到前端是 number，保留最多两位小数。 */
export function fmtScore(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return '—'
  const n = Number(value)
  return Number.isInteger(n) ? String(n) : String(Number(n.toFixed(2)))
}

export function fmtTimeOfDay(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}
