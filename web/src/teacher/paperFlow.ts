import type { PaperDetail, PaperItem } from '../api/types'
import type { RubricSummary } from '../components/rubric'

/**
 * 试卷向导的纯逻辑（D-34：Markdown 原文是唯一内容源）。
 *
 * 为什么单独成文件、不放组件里：这些判定（目标总分、缺答案、题号断档、评分项列怎么排、
 * 草稿能不能恢复）每一条都是「判错了老师就会看到错数字」的地方，必须能单测；
 * 页面只负责取值、渲染与发请求。
 *
 * 验收口径见 `specs/002-v2-platform/spec.md` AC-4.24–4.29。
 */

export type WizardStep = 1 | 2 | 3 | 4

export const WIZARD_STEPS: readonly WizardStep[] = [1, 2, 3, 4]

/** 本地草稿（v2）。旧版（五步、带 sections/criteria）不再解析，见 normalizeDraft。 */
export interface DraftV2 {
  paperId: string | null
  step: WizardStep
  title: string
  durationMinutes: string
  authorName: string
  reviewerName: string
  targetTotalPoints: string
  contentMarkdown: string
}

export interface PreviewRow {
  sectionTitle: string
  number: number
  type: PaperItem['questionType']
  points: number
  stem: string
  answerKey: string
  essay: boolean
  /** 客观题没有标准答案（写作题不算缺答案：它由评分项判分）。 */
  missing: boolean
}

export interface PreviewSummary {
  itemCount: number
  itemsTotal: number
  missingCount: number
  /** 题号断档：1..最大值之间缺的号（只提醒，不阻断）。 */
  gaps: number[]
}

export interface EssayTab {
  number: number
  points: number
  rubricMarkdown: string
  sectionTitle: string
}

export type TargetVerdict = 'none' | 'match' | 'mismatch'

export interface PublishCheck {
  ok: boolean
  reason: 'locked' | 'empty' | 'missingAnswers' | 'mismatch' | null
}

/** 重建会丢掉的界面内编辑（AC-4.24③）：三项都为 0 才不用二次确认。 */
export interface DeleteSummary {
  sections: number
  items: number
  rubrics: number
  explanations: number
  criteriaNames: number
  legacyCriteriaPoints: number
}

const EPSILON = 0.001

export function blankDraft(): DraftV2 {
  return {
    paperId: null,
    step: 1,
    title: '',
    durationMinutes: '',
    authorName: '',
    reviewerName: '',
    targetTotalPoints: '',
    contentMarkdown: '',
  }
}

/** 服务端详情 → 草稿。原文为空（旧卷）时编辑器留空，不凭空造内容。 */
export function draftFromDetail(detail: PaperDetail, step: WizardStep = 1): DraftV2 {
  return {
    paperId: detail.paper.id,
    step,
    title: detail.paper.title,
    durationMinutes:
      detail.paper.defaultDurationMinutes === null ? '' : String(detail.paper.defaultDurationMinutes),
    authorName: detail.paper.authorName ?? '',
    reviewerName: detail.paper.reviewerName ?? '',
    targetTotalPoints:
      detail.paper.targetTotalPoints === null || detail.paper.targetTotalPoints === undefined
        ? ''
        : String(detail.paper.targetTotalPoints),
    contentMarkdown: detail.contentMarkdown ?? '',
  }
}

function asString(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function asStep(value: unknown): WizardStep {
  const n = Number(value)
  return n === 2 || n === 3 || n === 4 ? n : 1
}

/**
 * 规整 localStorage 里读到的草稿。
 *
 * 旧版草稿（五步 + `sections` 数组 + `sectionForm` 之类）在 D-34 之后语义变了：
 * 宁可当作没有草稿，也不能拿旧形状去渲染四步页面。
 */
export function normalizeDraft(raw: unknown): DraftV2 | null {
  if (raw === null || typeof raw !== 'object') return null
  const value = raw as Record<string, unknown>
  if ('sections' in value && !('contentMarkdown' in value)) return null
  if (!('step' in value) && !('paperId' in value) && !('contentMarkdown' in value)) return null
  const paperId = typeof value.paperId === 'string' && value.paperId !== '' ? value.paperId : null
  return {
    paperId,
    step: asStep(value.step),
    title: asString(value.title),
    durationMinutes: asString(value.durationMinutes),
    authorName: asString(value.authorName),
    reviewerName: asString(value.reviewerName),
    targetTotalPoints: asString(value.targetTotalPoints),
    contentMarkdown: asString(value.contentMarkdown),
  }
}

export function flattenItems(detail: PaperDetail | null): Array<{ sectionTitle: string; item: PaperItem }> {
  if (!detail) return []
  return detail.sections.flatMap((section) =>
    section.items.map((item) => ({ sectionTitle: section.title, item })),
  )
}

export function previewRows(detail: PaperDetail | null): PreviewRow[] {
  return flattenItems(detail).map(({ sectionTitle, item }) => {
    const essay = item.questionType === 'ESSAY'
    const answerKey = item.answerKey ?? ''
    return {
      sectionTitle,
      number: item.number,
      type: item.questionType,
      points: Number(item.points),
      stem: item.stem ?? '',
      answerKey,
      essay,
      missing: !essay && answerKey.trim() === '',
    }
  })
}

export function previewSummary(detail: PaperDetail | null): PreviewSummary {
  const rows = previewRows(detail)
  const numbers = rows.map((row) => row.number).sort((a, b) => a - b)
  const gaps: number[] = []
  if (numbers.length > 0) {
    const seen = new Set(numbers)
    for (let n = numbers[0]; n <= numbers[numbers.length - 1]; n += 1) {
      if (!seen.has(n)) gaps.push(n)
    }
  }
  return {
    itemCount: rows.length,
    itemsTotal: rows.reduce((sum, row) => sum + row.points, 0),
    missingCount: rows.filter((row) => row.missing).length,
    gaps,
  }
}

/** 第 4 步的 tab：只取写作题，按题号升序（一题一 tab，AC-4.28①）。 */
export function essayTabs(detail: PaperDetail | null): EssayTab[] {
  return flattenItems(detail)
    .filter(({ item }) => item.questionType === 'ESSAY')
    .map(({ sectionTitle, item }) => ({
      number: item.number,
      points: Number(item.points),
      rubricMarkdown: item.rubricMarkdown ?? '',
      sectionTitle,
    }))
    .sort((a, b) => a.number - b.number)
}

export function rowsTotal(rows: PreviewRow[]): number {
  return rows.reduce((sum, row) => sum + row.points, 0)
}

/** 目标总分 vs 自动合计；目标为空表示「不校验」（AC-4.26）。 */
export function compareTarget(target: number | string | null | undefined, actual: number): TargetVerdict {
  if (target === null || target === undefined || target === '') return 'none'
  const wanted = Number(target)
  if (!Number.isFinite(wanted)) return 'none'
  return Math.abs(wanted - actual) <= EPSILON ? 'match' : 'mismatch'
}

export function publishCheck(input: {
  itemCount: number
  actual: number
  target: number | string | null | undefined
  missingCount: number
  locked: boolean
}): PublishCheck {
  if (input.locked) return { ok: false, reason: 'locked' }
  if (input.itemCount === 0 || input.actual <= 0) return { ok: false, reason: 'empty' }
  if (input.missingCount > 0) return { ok: false, reason: 'missingAnswers' }
  if (compareTarget(input.target, input.actual) === 'mismatch') return { ok: false, reason: 'mismatch' }
  return { ok: true, reason: null }
}

/**
 * 第 3 步「下一步」的评分项体检（2026-10-25 用户拍板）。
 *
 * - `empty`：完全没填 → **拦**（评分项必填，不进下一步）；
 * - `mismatch`：评分项合计 ≠ 本题满分 → 只提醒，不拦；
 * - `noPoints`：写了文字但一个分值都没写 → 只提醒（后端判分时按均分，不是错）。
 *
 * 为什么把「不一致」定为不拦：老师常常先写要点、分值后补，拦下来只会逼他随便编数字；
 * 而「没填」是明确的缺口，放过去等于让 AI 无标准判分。
 */
export type RubricVerdict = 'empty' | 'matched' | 'mismatch' | 'noPoints'

export interface RubricScoreCheck {
  number: number
  points: number
  /** 有分值的评分项之和（没写分值时为 0）。 */
  total: number
  verdict: RubricVerdict
}

export interface RubricGate {
  /** 有完全没填的题 → true（只有这一条会拦下一步）。 */
  blocked: boolean
  /** 完全没填的题号。 */
  empty: number[]
  /** 合计与本题满分不一致的题。 */
  mismatch: RubricScoreCheck[]
  /** 有文字但没写任何分值的题号。 */
  noPoints: number[]
  checks: RubricScoreCheck[]
}

/** 单题判定：解析摘要在页面里算好传进来，这里只做「算不算对得上」的判断（保持纯函数可单测）。 */
export function essayRubricCheck(tab: EssayTab, text: string, summary: RubricSummary): RubricScoreCheck {
  if (text.trim() === '') {
    return { number: tab.number, points: tab.points, total: 0, verdict: 'empty' }
  }
  if (!summary.anyPoints) {
    return { number: tab.number, points: tab.points, total: 0, verdict: 'noPoints' }
  }
  // 0.01 容差：前端合计保留两位小数，题项分值可能有 1.5 / 0.5 这种半分数。
  const matched = Math.abs(summary.total - tab.points) <= 0.01
  return { number: tab.number, points: tab.points, total: summary.total, verdict: matched ? 'matched' : 'mismatch' }
}

export function rubricGate(checks: RubricScoreCheck[]): RubricGate {
  const empty = checks.filter((entry) => entry.verdict === 'empty').map((entry) => entry.number)
  const mismatch = checks.filter((entry) => entry.verdict === 'mismatch')
  const noPoints = checks.filter((entry) => entry.verdict === 'noPoints').map((entry) => entry.number)
  return { blocked: empty.length > 0, empty, mismatch, noPoints, checks }
}

/**
 * 预览发布页「评分项」列的排布（AC-4.29①）：没有 rubric 就是「无 / N/A」，
 * 有就按题号分块；块与块之间由渲染层空一行。
 */
export function rubricBlocks(tabs: EssayTab[], emptyLabel: string): Array<{ number: number | null; text: string; filled: boolean }> {
  if (tabs.length === 0) return [{ number: null, text: emptyLabel, filled: false }]
  return tabs.map((tab) => {
    const text = tab.rubricMarkdown.trim()
    return { number: tab.number, text: text === '' ? emptyLabel : stripListMarkers(text), filled: text !== '' }
  })
}

/**
 * 预览发布页只显示评分项的文字，不显示 Markdown 的列表记号：
 * 老师填的是「- 表达理解（3 分）」，表格里再吐一遍 `-` 就是噪音（AC-4.29①）。
 */
export function stripListMarkers(markdown: string): string {
  return markdown
    .split('\n')
    .map((line) => line.replace(/^(?:\s*(?:[-*+]|\d+[.)])\s*)+/, '').replace(/\s+$/, ''))
    .join('\n')
    .trim()
}

/** 重建确认框要数的量（AC-4.24③）。旧卷的评分项金额由「后端总分 − 题项合计」推导。 */
export function deleteSummary(detail: PaperDetail | null): DeleteSummary {
  const items = flattenItems(detail)
  const itemsTotal = items.reduce((sum, entry) => sum + Number(entry.item.points), 0)
  const total = detail ? Number(detail.paper.totalPoints) : 0
  return {
    sections: detail?.sections.length ?? 0,
    items: items.length,
    rubrics: items.filter(({ item }) => (item.rubricMarkdown ?? '').trim() !== '').length,
    explanations: 0,
    criteriaNames: detail?.criteriaNames.length ?? 0,
    legacyCriteriaPoints: Math.max(0, Math.round((total - itemsTotal) * 100) / 100),
  }
}

export function needsReplaceConfirm(summary: DeleteSummary): boolean {
  return summary.items > 0 || summary.criteriaNames > 0 || summary.legacyCriteriaPoints > 0
}

/** 生成中的四个阶段（不确定进度只给阶段，不给百分比，AC-4.24⑤）。 */
export const PROGRESS_STAGES = ['reading', 'splitting', 'pairing', 'writing'] as const

export function stageIndex(elapsedMs: number): number {
  const index = Math.floor(Math.max(0, elapsedMs) / 1200)
  return Math.min(PROGRESS_STAGES.length - 1, index)
}

/** 表单值 → 接口值：空串是 null，数字字符串转 number，非法数字也当 null（后端不猜）。 */
export function toNumberOrNull(value: string): number | null {
  const text = value.trim()
  if (text === '') return null
  const n = Number(text)
  return Number.isFinite(n) ? n : null
}

export function metaPayload(draft: DraftV2): {
  authorName: string | null
  reviewerName: string | null
  targetTotalPoints: number | null
} {
  const author = draft.authorName.trim()
  const reviewer = draft.reviewerName.trim()
  return {
    authorName: author === '' ? null : author,
    reviewerName: reviewer === '' ? null : reviewer,
    targetTotalPoints: toNumberOrNull(draft.targetTotalPoints),
  }
}

/** 基本信息的校验：标题必填；时长要么空要么正整数；目标总分要么空要么正数。 */
export type MetaProblem = 'title' | 'duration' | 'target' | null

export function findMetaProblem(draft: DraftV2): MetaProblem {
  if (draft.title.trim() === '') return 'title'
  const duration = draft.durationMinutes.trim()
  if (duration !== '') {
    const minutes = Number(duration)
    if (!Number.isInteger(minutes) || minutes <= 0) return 'duration'
  }
  const target = draft.targetTotalPoints.trim()
  if (target !== '') {
    const n = Number(target)
    if (!Number.isFinite(n) || n <= 0) return 'target'
  }
  return null
}

/** 原文头部的提示值只回填空白字段（AC-4.27④）：老师手填的一律不覆盖。 */
export function applyMetaHints(
  draft: DraftV2,
  hints: { authorName?: string | null; reviewerName?: string | null; targetTotalPoints?: number | null } | null | undefined,
): DraftV2 {
  if (!hints) return draft
  return {
    ...draft,
    authorName: draft.authorName.trim() === '' ? (hints.authorName ?? '') : draft.authorName,
    reviewerName: draft.reviewerName.trim() === '' ? (hints.reviewerName ?? '') : draft.reviewerName,
    targetTotalPoints:
      draft.targetTotalPoints.trim() === '' && hints.targetTotalPoints !== null && hints.targetTotalPoints !== undefined
        ? String(hints.targetTotalPoints)
        : draft.targetTotalPoints,
  }
}
