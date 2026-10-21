import { useEffect, useRef, useState } from 'react'
import type { DependencyList, ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { api } from '../api/client'
import type {
  ClassSummaryView,
  OfferingView,
  ProgressView,
} from '../api/types'
import './teacher.css'

/** 所有教师端页面共用的外壳：补上 mockup.css 里未抽出的教师端展示类。 */
export function TeacherPage({ children }: { children: ReactNode }) {
  return <div className="teacher-page">{children}</div>
}

// 标签统一由组件层提供（antd Tag），这里只做转发，避免两套标签样式。
import { Tag } from '../ui'
import type { Tone } from '../ui'
export { Tag }
export type { Tone }

export interface AsyncData<T> {
  data: T | null
  loading: boolean
  error: Error | null
  reload: () => void
}

/**
 * 极简数据加载钩子：GET 类页面统一走 loading / error / reload。
 * 不引入额外依赖，网络请求的取消用 alive 标记处理。
 */
export function useApiData<T>(loader: () => Promise<T>, deps: DependencyList = []): AsyncData<T> {
  const [data, setData] = useState<T | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<Error | null>(null)
  const [tick, setTick] = useState(0)
  const loaderRef = useRef(loader)
  loaderRef.current = loader

  useEffect(() => {
    let alive = true
    setLoading(true)
    setError(null)
    loaderRef
      .current()
      .then((value) => {
        if (alive) setData(value)
      })
      .catch((err: unknown) => {
        if (alive) setError(err instanceof Error ? err : new Error(String(err)))
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
    // deps 由调用方显式声明；tick 只用于 reload。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick])

  return { data, loading, error, reload: () => setTick((n) => n + 1) }
}

export function errorMessage(err: unknown): string {
  if (err instanceof Error) return err.message
  return String(err)
}

/** 后端返回的数字可能是 0 / 0.0 / 12.50，展示时去掉无意义的小数位。 */
export function fmtNum(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  const n = Number(value)
  if (Number.isNaN(n)) return String(value)
  if (Number.isInteger(n)) return String(n)
  return n.toFixed(2).replace(/\.?0+$/, '')
}

/** class-summary 的 onTimeRate 已经是 0–100 的百分数。 */
export function fmtRate(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  return `${fmtNum(value)}%`
}

export function fmtLate(seconds: number): string {
  if (!seconds || seconds <= 0) return '—'
  if (seconds >= 3600) {
    const hours = Math.floor(seconds / 3600)
    const minutes = Math.round((seconds % 3600) / 60)
    return minutes > 0 ? `${hours}h ${minutes}m` : `${hours}h`
  }
  return `${Math.max(1, Math.round(seconds / 60))}m`
}

export function toLocalInput(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** datetime-local 是本机时间，提交前转成后端要求的 ISO-8601（UTC，带 Z）。 */
export function fromLocalInput(value: string): string | null {
  if (!value) return null
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return null
  return d.toISOString()
}

export function classNamesText(classIds: string[], names: Record<string, string>): string {
  return classIds.map((id) => names[id] ?? id).join('、')
}

export interface OfferingAggregate {
  progress: ProgressView
  summary: ClassSummaryView[]
  expected: number
  submitted: number
  rejected: number
  unsubmitted: number
}

/**
 * 教师首页 / 批改总览 / 报表共用的跨班聚合。
 * 数据只来自 /progress 与 /class-summary，不在前端编造任何数字。
 */
export async function loadAggregates(
  offerings: OfferingView[],
): Promise<Record<string, OfferingAggregate>> {
  const entries = await Promise.all(
    offerings.map(async (offering) => {
      const [progress, summary] = await Promise.all([
        api.get<ProgressView>(`/api/offerings/${offering.id}/progress`),
        api.get<ClassSummaryView[]>(`/api/offerings/${offering.id}/class-summary`),
      ])
      const expected = summary.length
        ? summary.reduce((acc, row) => acc + Number(row.expected), 0)
        : offering.classes.reduce((acc, row) => acc + Number(row.memberCount), 0)
      const submitted = summary.reduce((acc, row) => acc + Number(row.submitted), 0)
      const rejected = summary.reduce((acc, row) => acc + Number(row.rejected), 0)
      const aggregate: OfferingAggregate = {
        progress,
        summary,
        expected,
        submitted,
        rejected,
        unsubmitted: Math.max(0, expected - submitted),
      }
      return [offering.id, aggregate] as const
    }),
  )
  return Object.fromEntries(entries)
}

/** 服务端状态 → 展示文案；状态枚举与 GradingState 保持一致。 */
export function submissionStateKey(state: string): string {
  switch (state) {
    case 'DONE':
      return 'done'
    case 'RUNNING':
      return 'running'
    case 'PENDING':
      return 'pending'
    case 'QUEUED':
      return 'queued'
    case 'FAILED':
      return 'failed'
    case 'SKIPPED':
      return 'skipped'
    case 'DRAFT':
      return 'draft'
    default:
      return 'unknown'
  }
}

export function SubmissionStateTag({
  state,
  submittedAt,
  rejectedAt,
}: {
  state: string
  submittedAt: string | null
  rejectedAt: string | null
}) {
  const { t } = useTranslation('translation')
  if (rejectedAt) return <Tag tone="gray">{t('teacher.status.rejected')}</Tag>
  if (!submittedAt) return <Tag tone="gray">{t('teacher.status.unsubmitted')}</Tag>
  const key = submissionStateKey(state)
  const tone: Tone =
    key === 'done'
      ? 'green'
      : key === 'failed'
        ? 'red'
        : key === 'running'
          ? 'amber'
          : key === 'pending' || key === 'queued'
            ? 'amber'
            : 'gray'
  return <Tag tone={tone}>{t(`teacher.status.${key}`)}</Tag>
}

export function OfferingStateTag({
  progress,
  publishScores,
  expected,
  rejected = 0,
}: {
  progress: ProgressView
  publishScores: boolean
  expected: number
  rejected?: number
}) {
  const { t } = useTranslation('translation')
  if (progress.failed > 0) return <Tag tone="red">{t('teacher.status.hasFailed')}</Tag>
  if (progress.running > 0) return <Tag tone="amber">{t('teacher.status.grading')}</Tag>
  if (progress.pending > 0) return <Tag tone="amber">{t('teacher.status.pending')}</Tag>
  if (progress.total === 0) return <Tag tone="gray">{t('teacher.status.noSubmission')}</Tag>
  // progress.total 含已拒收（SKIPPED）与未交卷草稿；已拒收也算处理完毕。
  if (progress.done + rejected >= progress.total) {
    return publishScores ? (
      <Tag tone="green">{t('teacher.status.scoresPublished')}</Tag>
    ) : (
      <Tag tone="green">{t('teacher.status.publishable')}</Tag>
    )
  }
  if (expected > progress.total) return <Tag tone="gray">{t('teacher.status.waitingSubmissions')}</Tag>
  return <Tag tone="gray">{t('teacher.status.inProgress')}</Tag>
}

/** T183 / AC-24.3：每页条数也写进 URL，刷新后保持一致。 */
export function PageSizePicker({ pageSize }: { pageSize: number }) {
  const { t } = useTranslation('translation')
  const [, setParams] = useSearchParams()

  const change = (size: number) => {
    setParams((prev) => {
      const next = new URLSearchParams(prev)
      next.set('pageSize', String(size))
      next.delete('page')
      return next
    })
  }

  return (
    <label className="small muted" style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
      {t('teacher.common.pageSize')}
      <select
        value={String(pageSize)}
        onChange={(e) => change(Number(e.target.value))}
        style={{ width: 'auto', padding: '4px 8px' }}
      >
        <option value="20">20</option>
        <option value="50">50</option>
        <option value="100">100</option>
      </select>
    </label>
  )
}
