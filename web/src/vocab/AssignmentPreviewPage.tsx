import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api, ApiError } from '../api/client'
import { Button, DataTable, ErrorState, Loading, PageHeader, useToast } from '../ui'
import type { Column } from '../ui'
import './vocab.css'

interface Word {
  form: string
  band?: number | null
}

interface Detail {
  id: string
  setName: string
  dueAt: string | null
  note?: string
  expired: boolean
  claimed: boolean
  words: Word[]
}

export default function VocabAssignmentPreviewPage() {
  const { id = '' } = useParams()
  const { t } = useTranslation()
  const navigate = useNavigate()
  const toast = useToast()
  const [detail, setDetail] = useState<Detail | null>(null)
  const [error, setError] = useState<Error | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    setError(null)
    try {
      setDetail(await api.get<Detail>(`/api/vocab/assignments/${id}`))
    } catch (loadError) {
      setError(loadError as Error)
    }
  }, [id])

  useEffect(() => { void load() }, [load])

  const claim = async () => {
    setBusy(true)
    try {
      const result = await api.post<{ added: number; duplicated: number }>(
        `/api/vocab/assignments/${id}/claim`, {})
      toast.success(t('vocab.assignment.claimedDone', {
        added: result.added, duplicated: result.duplicated,
      }))
      // 加入后直接去词库，并按「加入时间倒序」看：刚加入的排最上面，已在库的词保持原位不动。
      navigate('/vocab/library?sort=createdAt&dir=desc')
    } catch (claimError) {
      toast.error((claimError as Error).message)
    } finally {
      setBusy(false)
    }
  }

  // 老师点开自己复制出去的学生链接：后端给 403，这里给一句人话，而不是裸的 404 报错。
  if (error instanceof ApiError && error.status === 403) {
    return (
      <>
        <PageHeader title={t('vocab.assignment.teacherLinkTitle')} />
        <div className="card">
          <p>{t('vocab.assignment.teacherLinkBody')}</p>
          <Button onClick={() => navigate('/vocab/sets')}>{t('vocab.assignment.backToSets')}</Button>
        </div>
      </>
    )
  }
  if (error) return <ErrorState error={error} onRetry={() => void load()} />
  if (!detail) return <Loading label={t('common.loading')} />

  const previewRows = detail.words.map((word, index) => ({ ...word, rowNumber: index + 1 }))
  const previewColumns: Column<(typeof previewRows)[number]>[] = [
    { key: 'rowNumber', title: '#', value: (row) => row.rowNumber },
    { key: 'form', title: t('vocab.assignment.word'), value: (row) => row.form },
    {
      key: 'band',
      title: t('vocab.assignment.band'),
      value: (row) => row.band ?? 0,
      render: (row) => (row.band ? t('vocab.detail.familyBand', { count: row.band * 1000 }) : '—'),
    },
  ]

  return (
    <>
      <PageHeader title={detail.setName} sub={detail.note} actions={(
        <Button variant="primary" busy={busy} disabled={detail.claimed} onClick={() => void claim()}>
          {detail.claimed ? t('vocab.assignment.claimed') : t('vocab.assignment.claim')}
        </Button>
      )} />

      <div className="row" style={{ gap: 8, marginBottom: 12, flexWrap: 'wrap' }}>
        {detail.dueAt && (
          <span className="chip">
            {t('vocab.assignment.due')} {new Date(detail.dueAt).toLocaleString()}
          </span>
        )}
        {detail.expired && <span className="chip">{t('vocab.assignment.expired')}</span>}
      </div>

      <DataTable
        rows={previewRows}
        columns={previewColumns}
        rowKey={(row) => `${row.form}-${row.rowNumber}`}
        empty={t('vocab.assignment.empty')}
      />

      <div className="row" style={{ justifyContent: 'flex-end', marginTop: 12 }}>
        <Button onClick={() => navigate('/vocab/library')}>{t('vocab.assignment.back')}</Button>
      </div>
    </>
  )
}
