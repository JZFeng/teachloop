import { useEffect, useRef, useState } from 'react'
import type { DependencyList, ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client'
import type {
  ClassSummaryView,
  OfferingView,
  ProgressView,
} from '../api/types'
import { offeringState } from './offeringState'
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

/**
 * 班级列文案：只用解析得出的班级名。
 *
 * T325：以前解析不到就退化成裸 UUID（首页「最近作业」里那一串 bd12ab5e-…），
 * 既不像班级也不像人话。现在解析不到就退成一句人话，同时后端只返回「自己的布置」，
 * 从源头保证每个 classId 都解析得出来。
 */
export function classNamesText(
  classIds: string[],
  names: Record<string, string>,
  fallback: string,
): string {
  const known = classIds.map((id) => names[id]).filter((name): name is string => Boolean(name))
  return known.length > 0 ? known.join('、') : fallback
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
  unsubmitted,
  rejected = 0,
}: {
  progress: ProgressView
  publishScores: boolean
  expected: number
  unsubmitted: number
  rejected?: number
}) {
  const { t } = useTranslation('translation')
  // 决策 025：一份作业的状态 = 现在卡在谁手里。3 个待办 + 1 个终态，
  // AI 的派发/在跑/排队不再是状态；失败单独做角标，因为它能和任何主状态并存。
  const view = offeringState({ progress, publishScores, expected, unsubmitted, rejected })
  const tone: Tone =
    view.state === 'uncollected'
      ? 'gray'
      : view.state === 'grading'
        ? 'amber'
        : 'green'
  const label =
    view.state === 'uncollected'
      // 2026-10-25 修订：chip 只表达「卡在谁手里」，数量留在「提交 / 应交」列——
      // 原来的「未交齐 29」既没有单位，又和提交列重复（决策 025 已同步改口）。
      ? t('teacher.offeringState.uncollected')
      : view.state === 'grading'
        ? t('teacher.offeringState.grading', { done: view.gradedCount, total: view.total })
        : view.state === 'to_publish'
          ? t('teacher.offeringState.toPublish')
          : t('teacher.offeringState.published')
  return (
    <>
      <Tag tone={tone}>{label}</Tag>
      {view.failedCount > 0 && (
        <Tag tone="red">{t('teacher.offeringState.failedBadge', { n: view.failedCount })}</Tag>
      )}
    </>
  )
}
