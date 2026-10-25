import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import { Button, Empty, ErrorState, Loading, PageHeader, Popover, useToast } from '../ui'
import { SpeakerButton } from './SpeakerButton'
import { PREPARE_STEPS, nextPrepareWindow, prepareWords, type PrepareResult } from './prepare'
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
  contentStatus: string
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
  const [windowSize, setWindowSize] = useState(PREPARE_STEPS[0])
  const [prep, setPrep] = useState<PrepareResult | null>(null)
  const [retrying, setRetrying] = useState(false)

  const refresh = useCallback(() => setReloadKey((value) => value + 1), [])

  useEffect(() => {
    let alive = true
    setLoading(true)
    setError(null)
    setWindowSize(PREPARE_STEPS[0])
    setPrep(null)
    api.get<QueueView>('/api/vocab/review/queue')
      .then(async (value) => {
        if (!alive) return
        setQueue(value)
        setIndex(0)
        setFlipped(false)
        setRated(null)
        setRefetched(false)
        // T426：进入复习就只为前 N 个词准备内容，不再等全量生成
        if (value.items.length > 0) {
          const result = await prepareWords('TODAY', PREPARE_STEPS[0]).catch(() => null)
          if (alive && result) setPrep(result)
        }
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

  const currentReady = current?.contentStatus === 'READY'
  const currentFailed = current?.contentStatus === 'FAILED'
  const preparing = !!current && !currentReady && !currentFailed

  // 数据准备中：每 2 秒问一次准备进度并刷新当前卡的 content，就绪后自动开始
  useEffect(() => {
    if (!preparing) return
    let alive = true
    const tick = async () => {
      try {
        const [nextPrep, nextQueue] = await Promise.all([
          prepareWords('TODAY', windowSize),
          api.get<QueueView>('/api/vocab/review/queue'),
        ])
        if (!alive) return
        setPrep(nextPrep)
        setQueue((previous) => (previous ? { ...previous, items: nextQueue.items } : nextQueue))
      } catch {
        // 单次轮询失败不打断等待，下一次再试
      }
    }
    const timer = setInterval(() => void tick(), 2000)
    return () => { alive = false; clearInterval(timer) }
  }, [preparing, windowSize])

  // 懒加载窗口：背到窗口末尾前 5 张时把下一段（100/200/400…）提前排上
  useEffect(() => {
    if (items.length === 0) return
    if (index < windowSize - 5) return
    const next = nextPrepareWindow(windowSize)
    if (next === windowSize) return
    setWindowSize(next)
    void prepareWords('TODAY', next).then(setPrep).catch(() => { /* 下一轮轮询再试 */ })
  }, [index, windowSize, items.length])

  const retryContent = useCallback(async () => {
    if (!current) return
    setRetrying(true)
    try {
      await api.post('/api/vocab/words/retry', { ids: [current.id] })
      setPrep(await prepareWords('TODAY', windowSize))
    } catch (retryError) {
      toast.error((retryError as Error).message)
    } finally {
      setRetrying(false)
    }
  }, [current, windowSize, toast])

  const submit = useCallback(async (rating: 'KNOWN' | 'DONT_KNOW') => {
    if (!current || rated || !currentReady) return
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
  }, [current, rated, currentReady, toast])

  const next = useCallback(() => {
    if (!current) return
    setIndex((value) => value + 1)
    setFlipped(false)
    setRated(null)
  }, [current])

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (preparing) return
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
  }, [submit, next, rated, preparing])

  const info = useMemo(() => (
    <div className="sm">
      <p>{t('vocab.review.statsReviewed')}: {queue?.todayReviewed ?? 0}</p>
      <p>{t('vocab.review.statsRemaining')}: {queue?.remaining ?? 0}</p>
      <p>{t('vocab.review.statsNew')}: {queue?.newToday ?? 0}
        {queue?.newLimitReached ? ` (${t('vocab.review.newLimit')})` : ''}</p>
      <p className="muted">{t('vocab.review.ratingHint')}</p>
    </div>
  ), [queue, t])

  const header = (
    <PageHeader title={t('vocab.review.title')} actions={(
      <Popover content={info} trigger="click">
        <Button size="small">{t('vocab.review.info')}</Button>
      </Popover>
    )} />
  )

  if (loading) return <Loading label={t('common.loading')} />
  if (error) return <ErrorState error={error} onRetry={refresh} />

  if (current && (preparing || currentFailed)) {
    return (
      <>
        {header}
        <div className="review-stage">
          <div className="review-card-column">
            <div className="review-card">
              <div className="review-word">{current.form}</div>
              {preparing ? (
                <>
                  <div className="muted">{t('vocab.review.preparing')}</div>
                  {prep && (
                    <div className="muted">
                      {t('vocab.review.preparingCount', { ready: prep.ready, total: prep.requested })}
                    </div>
                  )}
                  <div className="review-hint muted">{t('vocab.review.preparingHint')}</div>
                </>
              ) : (
                <>
                  <div className="muted">{t('vocab.review.contentFailed')}</div>
                  <div className="review-actions">
                    <Button variant="primary" loading={retrying} onClick={() => void retryContent()}>
                      {t('vocab.review.retryContent')}
                    </Button>
                  </div>
                </>
              )}
            </div>
          </div>
        </div>
      </>
    )
  }

  if (!current) {
    return (
      <>
        {header}
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
      {header}

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
            <div><SpeakerButton form={current.form} /></div>
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
