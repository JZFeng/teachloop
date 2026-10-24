import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client'
import { Button, Empty, ErrorState, Loading, PageHeader, useConfirm, useToast } from '../ui'
import './vocab.css'

interface SessionWord {
  id: string
  form: string
  content: {
    senses?: Array<{ pos?: string; zh?: string; en?: string }>
    examples?: Array<{ en?: string; zh?: string }>
    analysis?: string
    phonetic?: { uk?: string; us?: string }
  } | null
  cursor: number
  total: number
}

interface Summary {
  total: number
  seen: number
  known: number
  unknown: number
  paused: number
}

interface SessionView {
  id: string
  total: number
  cursor: number
}

export default function VocabFreeReviewPage() {
  const { id = '' } = useParams()
  const { t } = useTranslation()
  const navigate = useNavigate()
  const toast = useToast()
  const confirm = useConfirm()
  const [word, setWord] = useState<SessionWord | null | undefined>(undefined)
  const [summary, setSummary] = useState<Summary | null>(null)
  const [flipped, setFlipped] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<Error | null>(null)
  const [sessionId, setSessionId] = useState(id)

  const load = useCallback(async () => {
    setError(null)
    try {
      const current = await api.get<SessionWord | null>(`/api/vocab/review-sessions/${sessionId}/current`)
      if (current) {
        setWord(current)
        setFlipped(false)
      } else {
        const ended = await api.post<Summary>(`/api/vocab/review-sessions/${sessionId}/end`, {})
        setSummary(ended)
        setWord(null)
      }
    } catch (loadError) {
      setError(loadError as Error)
    }
  }, [sessionId])

  useEffect(() => { void load() }, [load])

  const advance = useCallback(async (action: 'KNOWN' | 'DONT_KNOW' | 'PAUSE') => {
    if (!word || busy) return
    setBusy(true)
    try {
      await api.post(`/api/vocab/review-sessions/${sessionId}/advance`, { action, elapsedMs: 0 })
      await load()
    } catch (advanceError) {
      toast.error((advanceError as Error).message)
    } finally {
      setBusy(false)
    }
  }, [word, busy, sessionId, load, toast])

  const end = useCallback(async () => {
    const ok = await confirm({
      title: t('vocab.freeReview.endTitle'),
      content: t('vocab.freeReview.endBody'),
      okText: t('vocab.freeReview.end'),
      cancelText: t('common.cancel'),
    })
    if (!ok) return
    const ended = await api.post<Summary>(`/api/vocab/review-sessions/${sessionId}/end`, {})
    setSummary(ended)
    setWord(null)
  }, [confirm, t, sessionId])

  const continueReview = async () => {
    const next = await api.post<SessionView>(`/api/vocab/review-sessions/${sessionId}/continue`, {})
    setSummary(null)
    setSessionId(next.id)
    navigate(`/vocab/review/session/${next.id}`, { replace: true })
  }

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null
      if (target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) return
      if (event.key === ' ') {
        event.preventDefault()
        setFlipped((value) => !value)
      } else if (event.key === '1') {
        void advance('DONT_KNOW')
      } else if (event.key === '2') {
        void advance('KNOWN')
      } else if (event.key === 's' || event.key === 'S') {
        void advance('PAUSE')
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [advance])

  if (error) return <ErrorState error={error} onRetry={() => void load()} />

  if (summary) {
    return (
      <>
        <PageHeader title={t('vocab.freeReview.summaryTitle')} />
        <div className="card free-review-summary">
          <div className="row" style={{ gap: 18, flexWrap: 'wrap' }}>
            <span>{t('vocab.freeReview.summarySeen', { count: summary.seen })}</span>
            <span>{t('vocab.freeReview.summaryKnown', { count: summary.known })}</span>
            <span>{t('vocab.freeReview.summaryUnknown', { count: summary.unknown })}</span>
            <span>{t('vocab.freeReview.summaryPaused', { count: summary.paused })}</span>
          </div>
          <div className="row" style={{ gap: 8, marginTop: 16, flexWrap: 'wrap' }}>
            <Button onClick={() => navigate('/vocab/library')}>{t('vocab.freeReview.backToLibrary')}</Button>
            {summary.seen < summary.total && (
              <Button variant="primary" onClick={() => void continueReview()}>
                {t('vocab.freeReview.continue')}
              </Button>
            )}
            <Button onClick={() => navigate('/vocab/review')}>{t('vocab.freeReview.backToToday')}</Button>
          </div>
        </div>
      </>
    )
  }

  if (word === undefined) return <Loading label={t('common.loading')} />
  if (word === null) return <Empty>{t('vocab.freeReview.empty')}</Empty>

  const content = word.content
  const phonetic = content?.phonetic

  return (
    <>
      <PageHeader title={t('vocab.freeReview.title')}
        sub={word.cursor > 0 ? t('vocab.freeReview.lastSeen', { cursor: word.cursor, total: word.total }) : undefined}
        actions={<Button size="small" onClick={() => void end()}>{t('vocab.freeReview.end')}</Button>} />

      <div className="review-stage">
        <div className="review-card-column">
          <div
            className={`review-card${flipped ? ' is-flipped' : ''}`}
            role="button"
            tabIndex={0}
            aria-label={flipped ? t('vocab.review.back') : t('vocab.review.front')}
            onClick={() => setFlipped((value) => !value)}
            onKeyDown={(event) => { if (event.key === 'Enter') setFlipped((value) => !value) }}
          >
            <div className="review-word">{word.form}</div>
            {phonetic && (
              <div className="row muted" style={{ gap: 12, justifyContent: 'center' }}>
                <span>{t('vocab.detail.phoneticUk')} {phonetic.uk ?? '—'}</span>
                <span>{t('vocab.detail.phoneticUs')} {phonetic.us ?? '—'}</span>
              </div>
            )}
            {flipped && (
              <div className="review-back">
                {(content?.senses?.length ?? 0) > 0 && (
                  <ul className="vocab-sense">
                    {content!.senses!.map((sense, index) => (
                      <li key={index}>
                        {sense.pos && <span className="chip">{sense.pos}</span>} {sense.zh ?? sense.en}
                      </li>
                    ))}
                  </ul>
                )}
                {(content?.examples?.length ?? 0) > 0 && (
                  <div className="review-examples">
                    {content!.examples!.map((example, index) => (
                      <div key={index}>
                        <div>{example.en}</div>
                        {example.zh && <div className="muted">{example.zh}</div>}
                      </div>
                    ))}
                  </div>
                )}
                {content?.analysis && <p className="muted">{content.analysis}</p>}
              </div>
            )}
            {!flipped && <div className="review-hint muted">{t('vocab.review.showAnswer')}</div>}
          </div>

          <div className="review-actions">
            <Button disabled={busy} onClick={() => void advance('DONT_KNOW')}>{t('vocab.review.unknown')}</Button>
            <Button variant="primary" disabled={busy} onClick={() => void advance('KNOWN')}>{t('vocab.review.known')}</Button>
            <Button disabled={busy} onClick={() => void advance('PAUSE')}>{t('vocab.freeReview.pause')}</Button>
          </div>
          <div className="review-keys muted">{t('vocab.freeReview.keys')}</div>
        </div>
      </div>
    </>
  )
}
