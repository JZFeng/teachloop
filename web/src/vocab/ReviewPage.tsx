import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import { Button, Empty, ErrorState, Loading, PageHeader, Popover, useToast } from '../ui'
import './vocab.css'

interface QueueItem {
  id: string
  form: string
  content: {
    senses?: Array<{ pos?: string; zh?: string; en?: string }>
    examples?: Array<{ en?: string; zh?: string }>
    analysis?: string
    phonetic?: { uk?: string; us?: string }
  } | null
  state: string
  newCard: boolean
}

interface QueueView {
  items: QueueItem[]
  total: number
  todayReviewed: number
  remaining: number
  newToday: number
  newLimitReached: boolean
}

type Rated = 'KNOWN' | 'DONT_KNOW' | null

export default function VocabReviewPage() {
  const { t } = useTranslation()
  const toast = useToast()
  const [queue, setQueue] = useState<QueueView | null>(null)
  const [index, setIndex] = useState(0)
  const [flipped, setFlipped] = useState(false)
  const [rated, setRated] = useState<Rated>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<Error | null>(null)
  const [reloadKey, setReloadKey] = useState(0)
  const [refetched, setRefetched] = useState(false)

  const refresh = useCallback(() => setReloadKey((value) => value + 1), [])

  useEffect(() => {
    let alive = true
    setLoading(true)
    setError(null)
    api.get<QueueView>('/api/vocab/review/queue')
      .then((value) => {
        if (!alive) return
        setQueue(value)
        setIndex(0)
        setFlipped(false)
        setRated(null)
        setRefetched(false)
      })
      .catch((loadError) => { if (alive) setError(loadError as Error) })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [reloadKey])

  const items = queue?.items ?? []
  const current = items[index]
  const done = !loading && !error && items.length > 0 && index >= items.length

  useEffect(() => {
    if (!done || refetched) return
    // 一轮看完后再拉一次队列；没有就进空态
    setRefetched(true)
    const timer = setTimeout(() => refresh(), 300)
    return () => clearTimeout(timer)
  }, [done, refetched, refresh])

  const submit = useCallback(async (rating: 'KNOWN' | 'DONT_KNOW') => {
    if (!current || rated) return
    try {
      await api.post('/api/vocab/review', {
        wordId: current.id,
        rating,
        elapsedMs: 0,
      }, { 'Idempotency-Key': crypto.randomUUID() })
      if (rating === 'KNOWN') {
        setIndex((value) => value + 1)
        setFlipped(false)
        setRated(null)
      } else {
        setRated('DONT_KNOW')
        setFlipped(true)
      }
    } catch (submitError) {
      toast.error((submitError as Error).message)
    }
  }, [current, rated, toast])

  const next = useCallback(() => {
    if (!current) return
    setIndex((value) => value + 1)
    setFlipped(false)
    setRated(null)
  }, [current])

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null
      if (target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) return
      if (event.key === ' ') {
        event.preventDefault()
        setFlipped((value) => !value)
      } else if (event.key === '1') {
        void submit('DONT_KNOW')
      } else if (event.key === '2') {
        void submit('KNOWN')
      } else if (event.key === 'n' || event.key === 'N' || event.key === 'ArrowRight') {
        if (rated) next()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [submit, next, rated])

  const info = useMemo(() => (
    <div className="sm">
      <p>{t('vocab.review.statsReviewed')}: {queue?.todayReviewed ?? 0}</p>
      <p>{t('vocab.review.statsRemaining')}: {queue?.remaining ?? 0}</p>
      <p>{t('vocab.review.statsNew')}: {queue?.newToday ?? 0}
        {queue?.newLimitReached ? ` (${t('vocab.review.newLimit')})` : ''}</p>
      <p className="muted">{t('vocab.review.ratingHint')}</p>
    </div>
  ), [queue, t])

  if (loading) return <Loading label={t('common.loading')} />
  if (error) return <ErrorState error={error} onRetry={refresh} />

  if (!current) {
    return (
      <>
        <PageHeader title={t('vocab.review.title')} />
        <Empty>
          <div>{t('vocab.review.empty')}</div>
          <div style={{ marginTop: 8 }}>
            <Link to="/vocab/library">{t('vocab.review.goLibrary')}</Link>
          </div>
        </Empty>
      </>
    )
  }

  const content = current.content
  const phonetic = content?.phonetic
  const disabled = rated !== null

  return (
    <>
      <PageHeader title={t('vocab.review.title')} actions={(
        <Popover content={info} trigger="click">
          <Button size="small">{t('vocab.review.info')}</Button>
        </Popover>
      )} />

      <div className="review-stage">
        <div className="review-card-column">
          <div
            className={`review-card${flipped ? ' is-flipped' : ''}`}
            role="button"
            tabIndex={0}
            aria-label={flipped ? t('vocab.review.back') : t('vocab.review.front')}
            onClick={() => setFlipped((value) => !value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') setFlipped((value) => !value)
            }}
          >
            <div className="review-word">{current.form}</div>
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
                    {content!.senses!.map((sense, senseIndex) => (
                      <li key={senseIndex}>
                        {sense.pos && <span className="chip">{sense.pos}</span>} {sense.zh ?? sense.en}
                      </li>
                    ))}
                  </ul>
                )}
                {(content?.examples?.length ?? 0) > 0 && (
                  <div className="review-examples">
                    {content!.examples!.map((example, exampleIndex) => (
                      <div key={exampleIndex}>
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
            <Button className="review-unknown" disabled={disabled} onClick={() => void submit('DONT_KNOW')}>
              {t('vocab.review.unknown')}
            </Button>
            <Button variant="primary" disabled={disabled} onClick={() => void submit('KNOWN')}>
              {t('vocab.review.known')}
            </Button>
          </div>
          <div className="review-keys muted">{t('vocab.review.keys')}</div>
        </div>

        <Button
          className={`review-next${rated ? ' is-highlighted' : ''}`}
          variant={rated ? 'primary' : 'default'}
          disabled={!rated}
          onClick={next}
        >
          {t('vocab.review.next')}
        </Button>
      </div>
    </>
  )
}
