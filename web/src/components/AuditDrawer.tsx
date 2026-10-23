import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, fmtDateTime } from '../api/client'
import type { AuditView } from '../api/types'
import { useEscapeClose } from '../ui'
import { ErrorState, Loading } from './States'

type Translate = ReturnType<typeof useTranslation>['t']
type I18n = ReturnType<typeof useTranslation>['i18n']

/** 内部主键类参数：时间线已经按这个实体过滤，再列出 UUID 只是噪音。 */
const HIDDEN_DETAIL_KEYS = new Set([
  'offeringId', 'paperId', 'studentId', 'submissionId', 'reviewId', 'runId',
  // 复核改分专用：下面单独排版，不再按字段逐个平铺
  'manual', 'scoreChanged', 'aiSubjectiveDraft', 'textDiff',
  'beforeSubjective', 'afterSubjective',
  'beforeComment', 'afterComment', 'beforeStrengths', 'afterStrengths',
  'beforeSuggestions', 'afterSuggestions',
])

/**
 * T184：审计只能从实体行进入，不以左侧一级导航出现。
 *
 * T248：事件名、参数和时间全部翻成中文可读文案——教师看到的是
 * 「学生提交 / 富文本安全过滤 / 开始作答」，不是 `submission:submit {late=0, attempt=1}`。
 */
export function AuditDrawer({ open, onClose, targetType, targetId, title }: {
  open: boolean
  onClose: () => void
  targetType: string
  targetId: string
  title?: string
}) {
  const { t, i18n } = useTranslation('translation')
  const [rows, setRows] = useState<AuditView[] | null>(null)
  const [error, setError] = useState<Error | null>(null)

  // T354：抽屉是弹层，Esc 必须能关；和其他弹层共用一套栈，嵌套时只关最上面那层。
  useEscapeClose(open, onClose)

  useEffect(() => {
    if (!open) return
    setRows(null)
    setError(null)
    api.get<AuditView[]>(`/api/audit?targetType=${encodeURIComponent(targetType)}&targetId=${encodeURIComponent(targetId)}`)
      .then(setRows)
      .catch((e: Error) => setError(e))
  }, [open, targetType, targetId])

  if (!open) return null

  const targetLabel = i18n.exists(`audit.target.${targetType}`)
    ? t(`audit.target.${targetType}`)
    : targetType
  const shortId = targetId.length > 8 ? `${targetId.slice(0, 8)}…` : targetId

  return (
    <div
      style={{ position: 'fixed', inset: 0, background: 'rgba(16,24,40,.28)', zIndex: 50 }}
      onClick={onClose}
      role="presentation"
    >
      <aside
        style={{ position: 'absolute', right: 0, top: 0, bottom: 0, width: 480, maxWidth: '92vw',
          background: 'var(--panel)', padding: 20, overflowY: 'auto', boxShadow: 'var(--shadow-lg)' }}
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label={t('audit.timeline')}
      >
        <div className="row" style={{ justifyContent: 'space-between' }}>
          <h2 style={{ margin: 0 }}>{title ?? t('audit.timeline')}</h2>
          <button className="btn" onClick={onClose}>{t('common.close')}</button>
        </div>
        <div className="hint" style={{ marginTop: 8 }}>
          {targetLabel} · <span className="mono" title={targetId}>{shortId}</span>
          {rows && rows.length > 0 ? ` · ${t('audit.count', { count: rows.length })}` : ''}
        </div>
        <p className="small muted" style={{ margin: '4px 0 0' }}>{t('audit.orderHint')}</p>

        {error && <ErrorState error={error} />}
        {!rows && !error && <Loading />}
        {rows && rows.length === 0 && <p className="muted">{t('audit.empty')}</p>}
        {rows && rows.length > 0 && (
          <ol className="audit-timeline">
            {rows.map((row) => {
              const detail = describeAuditDetail(row.action, row.detail, t, i18n)
              return (
                <li key={row.id}>
                  <span className="audit-dot" aria-hidden="true" />
                  <div className="audit-event">
                    <div className="audit-event-head">
                      <b>{auditEventLabel(row.action, t, i18n)}</b>
                      <span className="small muted">{fmtDateTime(row.createdAt)}</span>
                    </div>
                    {detail && <div className="audit-event-detail">{detail}</div>}
                  </div>
                </li>
              )
            })}
          </ol>
        )}
      </aside>
    </div>
  )
}

/** 分数去掉无意义的尾零：7.50 → 7.5，避免审计里出现「7.50 → 7.5」这种假改动。 */
function formatScore(raw: string): string {
  if (!raw) return ''
  const numeric = Number(raw)
  return Number.isFinite(numeric) ? String(numeric) : raw
}

/** 复核改分：主观分前后对比 + 实际改过的三段文本，null 不显示。 */
function describeReviewEdit(pairs: [string, string][], t: Translate): string {
  const value = (key: string) => {
    const raw = pairs.find(([name]) => name === key)?.[1] ?? ''
    return raw === 'null' ? '' : raw
  }
  const parts: string[] = []
  const before = formatScore(value('beforeSubjective'))
  const after = formatScore(value('afterSubjective'))
  if (before || after) {
    // 7.50 → 7.5 这种只是数值格式差异，不算改分。
    parts.push(before === after
      ? `${t('audit.review.score')}：${after || before}`
      : `${t('audit.review.score')}：${before || '—'} → ${after || '—'}`)
  }
  const fields: [string, string][] = [
    ['comment', 'audit.review.comment'],
    ['strengths', 'audit.review.strengths'],
    ['suggestions', 'audit.review.suggestions'],
  ]
  for (const [field, labelKey] of fields) {
    const beforeValue = value(`before${field.charAt(0).toUpperCase()}${field.slice(1)}`)
    const afterValue = value(`after${field.charAt(0).toUpperCase()}${field.slice(1)}`)
    if (beforeValue === afterValue) continue
    parts.push(`${t(labelKey)}：${afterValue || t('audit.review.cleared')}`)
  }
  return parts.length ? parts.join(' · ') : t('audit.review.noChange')
}

/** 事件名 → 中文：`submission:submit` 读成「提交作业」，没有翻译的保留原始代号。 */
function auditEventLabel(action: string, t: Translate, i18n: I18n): string {
  // i18next 的 key 分隔符是 `.`、命名空间分隔符是 `:`，所以事件代号里的冒号换成下划线。
  const key = `audit.event.${action.replace(/:/g, '_')}`
  return i18n.exists(key) ? t(key) : action
}

/**
 * 参数串 → 一行中文：`{late=0, attempt=1}` 读成「第 1 次提交 · 按时」。
 * 后端历史数据是 Java Map.toString()，新数据可能是 JSON，两种都解析。
 */
function describeAuditDetail(action: string, detail: string | null, t: Translate, i18n: I18n): string {
  if (!detail || !detail.trim()) return ''
  const pairs = parseDetail(detail)
  if (!pairs.length) return detail.trim()
  // 复核改分是「前后值 + 三段文本」，按字段平铺读不懂，单独排版成一句话。
  if (action === 'review:edited') return describeReviewEdit(pairs, t)
  return pairs
    .filter(([key]) => !HIDDEN_DETAIL_KEYS.has(key))
    .map(([key, value]) => {
      const formatted = formatDetailValue(key, value, t, i18n)
      // 这一组参数本身就是一句完整的话：「按时提交」「第 1 次提交」，再套「标签：」反而别扭。
      if (key === 'late' || key === 'attempt' || key === 'attemptNo') return formatted
      const label = i18n.exists(`audit.field.${key}`) ? t(`audit.field.${key}`) : key
      return `${label}：${formatted}`
    })
    .join(' · ')
}

function parseDetail(detail: string): [string, string][] {
  const raw = detail.trim()
  if (!raw.startsWith('{') || !raw.endsWith('}')) return []
  // 新格式：合法 JSON
  try {
    const parsed: unknown = JSON.parse(raw)
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return Object.entries(parsed as Record<string, unknown>).map(([key, value]) => [
        key,
        typeof value === 'string' ? value : String(value),
      ])
    }
  } catch {
    // 落到 Java Map.toString() 的 `{k=v, k2=v2}` 解析
  }
  const pairs: [string, string][] = []
  for (const match of raw.matchAll(/([A-Za-z0-9_]+)=([^,}]*)/g)) {
    pairs.push([match[1], match[2].trim()])
  }
  return pairs
}

function formatDetailValue(key: string, value: string, t: Translate, i18n: I18n): string {
  if (key === 'late') {
    const seconds = Number(value)
    return Number.isFinite(seconds) && seconds > 0
      ? t('audit.lateSeconds', { seconds })
      : t('audit.onTime')
  }
  if (key === 'attempt' || key === 'attemptNo') {
    return t('audit.attemptNo', { n: Number(value) })
  }
  if (key === 'deadlineAt') return fmtDateTime(value)
  const enumKey = `audit.value.${value}`
  if (i18n.exists(enumKey)) return t(enumKey)
  return value
}
