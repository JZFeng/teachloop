import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client'
import { Button, Empty, ErrorState, Loading, PageHeader, useToast } from '../ui'
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
      await load()
    } catch (claimError) {
      toast.error((claimError as Error).message)
    } finally {
      setBusy(false)
    }
  }

  if (error) return <ErrorState error={error} onRetry={() => void load()} />
  if (!detail) return <Loading label={t('common.loading')} />

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

      {detail.words.length === 0 ? <Empty>{t('vocab.assignment.empty')}</Empty> : (
        <div className="card" style={{ padding: 0 }}>
          <table className="vocab-table">
            <thead>
              <tr>
                <th>#</th>
                <th>{t('vocab.assignment.word')}</th>
                <th>{t('vocab.assignment.band')}</th>
              </tr>
            </thead>
            <tbody>
              {detail.words.map((word, index) => (
                <tr key={`${word.form}-${index}`}>
                  <td>{index + 1}</td>
                  <td>{word.form}</td>
                  <td>{word.band ? t('vocab.detail.familyBand', { band: word.band }) : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="row" style={{ justifyContent: 'flex-end', marginTop: 12 }}>
        <Button onClick={() => navigate('/vocab/library')}>{t('vocab.assignment.back')}</Button>
      </div>
    </>
  )
}
