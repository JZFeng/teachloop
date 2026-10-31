// 路由已挂：`/admin/vocab/feedback`（App.tsx，守卫 `nav.vocab.feedback`，只发 ADMIN）。
// 学生从查词页提交的反馈从落地起就没有任何读取方，这一页是它唯一的读入口（2026-10-31）。
import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { fmtDateTime } from '../../api/client'
import {
  Button,
  DataTable,
  ErrorState,
  InlineNote,
  PageHeader,
  RowActions,
  Segmented,
  Tag,
  useToast,
} from '../../ui'
import type { Column, Tone } from '../../ui'
import { fetchAdminFeedback, shortSubmitterId, updateFeedbackStatus } from './adminFeedback'
import type { AdminFeedbackItem, FeedbackFilter, FeedbackStatus } from './adminFeedback'

/** 筛选顺序 = 处理顺序：待处理在前，`ALL` 放最后当「翻旧账」。 */
const FILTERS: FeedbackFilter[] = ['OPEN', 'RESOLVED', 'IGNORED', 'ALL']

/** 状态色只表达语义：待处理 amber / 已处理 green / 不处理 gray。 */
const STATUS_TONE: Record<FeedbackStatus, Tone> = {
  OPEN: 'amber',
  RESOLVED: 'green',
  IGNORED: 'gray',
}

/**
 * 管理员的「单词反馈」收件箱。
 *
 * 三件按现有管理页（`AdminTeachersPage` / `AdminBooksPage`）对齐的事：
 *   1. 列表用 `ui/DataTable` 的**服务端分页**（传 `total`，行就是当前页），排序固定
 *      `created_at desc`——后端没有给前端排序参数，表头也不做假排序；
 *   2. 状态筛选是 `ui/Segmented`，缺省 `OPEN`（收件箱默认只看待处理）；
 *   3. 处理动作走 `RowActions`：一级只有「标记已处理」，忽略 / 重开收进「⋯」。
 *
 * **不显示提交人姓名/邮箱**：`users` 表属 identity 模块，后端不跨模块 JOIN，
 * 这里就照实展示 user id 前 8 位（`title` 里是完整 id），不编一个看起来更友好的值。
 */
export default function AdminFeedbackPage() {
  const { t } = useTranslation()
  const toast = useToast()

  const [filter, setFilter] = useState<FeedbackFilter>('OPEN')
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(20)
  const [items, setItems] = useState<AdminFeedbackItem[] | null>(null)
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<Error | null>(null)
  /** 正在改状态的那一行：只锁那一行的按钮，不锁整张表。 */
  const [busyId, setBusyId] = useState<number | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const result = await fetchAdminFeedback(filter, page, pageSize)
      setItems(result.items)
      setTotal(result.total)
    } catch (loadError) {
      // 错误留在页面上（InlineNote），不清空已有数据：改状态失败时列表不该跟着消失。
      setError(loadError as Error)
    } finally {
      setLoading(false)
    }
  }, [filter, page, pageSize])

  useEffect(() => { void load() }, [load])

  const statusLabel = (status: FeedbackStatus) => t(`vocab.feedbackAdmin.status.${status}`)

  const changeStatus = async (row: AdminFeedbackItem, status: FeedbackStatus) => {
    setBusyId(row.id)
    setError(null)
    try {
      const updated = await updateFeedbackStatus(row.id, status)
      toast.success(t('vocab.feedbackAdmin.updated', { word: updated.wordForm ?? `#${row.id}` }))
      // 状态变了就可能不属于当前筛选（OPEN 列表里点了「忽略」）：重拉一次，总数也跟着对。
      await load()
    } catch (updateError) {
      setError(updateError as Error)
      toast.error((updateError as Error).message)
    } finally {
      setBusyId(null)
    }
  }

  const columns: Column<AdminFeedbackItem>[] = [
    {
      key: 'word',
      title: t('vocab.feedbackAdmin.colWord'),
      value: (row) => row.wordForm ?? '',
      render: (row) => (
        <>
          <b>{row.wordForm ?? `#${row.id}`}</b>
          {row.wordLanguage && <span className="muted small"> {row.wordLanguage}</span>}
        </>
      ),
    },
    {
      key: 'note',
      title: t('vocab.feedbackAdmin.colNote'),
      value: (row) => row.note ?? '',
      render: (row) => (
        row.note
          ? <span className="small">{row.note}</span>
          : <span className="muted small">{t('vocab.feedbackAdmin.noNote')}</span>
      ),
    },
    {
      key: 'submitter',
      title: t('vocab.feedbackAdmin.colSubmitter'),
      value: (row) => row.submitterId,
      render: (row) => (
        <span className="mono small" title={row.submitterId}>{shortSubmitterId(row.submitterId)}</span>
      ),
    },
    {
      key: 'status',
      title: t('vocab.feedbackAdmin.colStatus'),
      value: (row) => row.status,
      render: (row) => <Tag tone={STATUS_TONE[row.status]}>{statusLabel(row.status)}</Tag>,
    },
    {
      key: 'createdAt',
      title: t('vocab.feedbackAdmin.colCreated'),
      value: (row) => row.createdAt,
      render: (row) => <span className="small">{fmtDateTime(row.createdAt)}</span>,
    },
    {
      key: 'actions',
      title: t('vocab.feedbackAdmin.colActions'),
      value: () => '',
      render: (row) => (
        <RowActions
          main={row.status === 'RESOLVED' ? undefined : {
            key: 'resolve',
            label: t('vocab.feedbackAdmin.resolve'),
            busy: busyId === row.id,
            onClick: () => void changeStatus(row, 'RESOLVED'),
          }}
          items={[
            ...(row.status === 'IGNORED' ? [] : [{
              key: 'ignore',
              label: t('vocab.feedbackAdmin.ignore'),
              onClick: () => void changeStatus(row, 'IGNORED'),
            }]),
            ...(row.status === 'OPEN' ? [] : [{
              key: 'reopen',
              label: t('vocab.feedbackAdmin.reopen'),
              onClick: () => void changeStatus(row, 'OPEN'),
            }]),
          ]}
        />
      ),
    },
  ]

  if (error && items === null) return <ErrorState error={error} onRetry={() => void load()} />

  return (
    <>
      <PageHeader
        title={t('vocab.feedbackAdmin.title')}
        sub={t('vocab.feedbackAdmin.sub')}
        info={t('vocab.feedbackAdmin.info')}
        actions={(
          <Button disabled={loading} onClick={() => void load()}>
            {t('vocab.feedbackAdmin.refresh')}
          </Button>
        )}
      />

      {error && <InlineNote tone="error">{error.message}</InlineNote>}

      <DataTable
        rows={items ?? []}
        columns={columns}
        rowKey={(row) => String(row.id)}
        page={page}
        pageSize={pageSize}
        total={total}
        loading={loading}
        toolbar={(
          <Segmented
            size="small"
            value={filter}
            onChange={(value) => {
              setFilter(value as FeedbackFilter)
              setPage(1)
            }}
            options={FILTERS.map((key) => ({ value: key, label: t(`vocab.feedbackAdmin.filter.${key}`) }))}
          />
        )}
        onPageChange={setPage}
        onPageSizeChange={(next) => { setPageSize(next); setPage(1) }}
        empty={t('vocab.feedbackAdmin.empty')}
      />
    </>
  )
}
