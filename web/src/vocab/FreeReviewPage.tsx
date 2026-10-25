import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client'
import { Button, Empty, ErrorState, Loading, PageHeader, useConfirm, useToast } from '../ui'
import { VocabReviewCard } from './VocabReviewCard'
import { PREPARE_STEPS, nextPrepareWindow, prepareWords, type PrepareResult } from './prepare'
import './vocab.css'

interface SessionWord {
  id: string
  form: string
  /** 词条语言（en/zh/es）：例句喇叭按它选音色（019，后端读路径补齐） */
  language?: string
  content: {
    senses?: Array<{ pos?: string; zh?: string; en?: string }>
    examples?: Array<{ en?: string; zh?: string }>
    analysis?: string
    phonetic?: { uk?: string; us?: string }
  } | null
  contentStatus: string
  favorite?: boolean
  paused?: boolean
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
  const [windowSize, setWindowSize] = useState(PREPARE_STEPS[0])
  const [prep, setPrep] = useState<PrepareResult | null>(null)
  const [retrying, setRetrying] = useState(false)
  const [gatePrep, setGatePrep] = useState<PrepareResult | null>(null)
  const [gateOpen, setGateOpen] = useState(false)
  const [gateSlow, setGateSlow] = useState(false)

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

  // T426/T428：进入「复习这些词」先准备首批 N 个词，等满再出卡
  useEffect(() => {
    setWindowSize(PREPARE_STEPS[0])
    setPrep(null)
    setGatePrep(null)
    setGateOpen(false)
    if (!sessionId) return
    let alive = true
    void prepareWords('SESSION', PREPARE_STEPS[0], { sessionId })
      .then((result) => {
        if (!alive) return
        setPrep(result)
        setGatePrep(result)
        if (result.pending === 0) setGateOpen(true)
      })
      .catch(() => { /* 准备失败不阻塞，轮询会再试 */ })
    return () => { alive = false }
  }, [sessionId])

  const preparing = !!word && word.contentStatus !== 'READY' && word.contentStatus !== 'FAILED'
  const contentFailed = word?.contentStatus === 'FAILED'
  const gateWaiting = !!word && !gateOpen

  // 兜底：等待超过 60 秒时允许手动开始，避免整页卡死
  useEffect(() => {
    if (!gateWaiting) { setGateSlow(false); return }
    const timer = setTimeout(() => setGateSlow(true), 60000)
    return () => clearTimeout(timer)
  }, [gateWaiting])

  // 等满首批 N 个词：每 2 秒刷新进度，pending 归零再放行
  useEffect(() => {
    if (!gateWaiting) return
    let alive = true
    const tick = async () => {
      try {
        const result = await prepareWords('SESSION', PREPARE_STEPS[0], { sessionId })
        if (!alive) return
        setGatePrep(result)
        setPrep(result)
        if (result.pending === 0) {
          setGateOpen(true)
          await load()
        }
      } catch {
        // 单次失败不打断等待
      }
    }
    const timer = setInterval(() => void tick(), 2000)
    return () => { alive = false; clearInterval(timer) }
  }, [gateWaiting, sessionId, load])

  // 后续窗口（100/200/400…）：只等当前卡，不整页阻塞
  useEffect(() => {
    if (gateWaiting || !preparing) return
    let alive = true
    const tick = async () => {
      try {
        const result = await prepareWords('SESSION', windowSize, { sessionId })
        if (!alive) return
        setPrep(result)
        await load()
      } catch {
        // 单次失败不打断等待
      }
    }
    const timer = setInterval(() => void tick(), 2000)
    return () => { alive = false; clearInterval(timer) }
  }, [gateWaiting, preparing, windowSize, sessionId, load])

  // 窗口推进：快到末尾时只把窗口放大，不阻塞页面
  useEffect(() => {
    if (!word) return
    if (word.cursor < windowSize - 5) return
    const next = nextPrepareWindow(windowSize)
    if (next !== windowSize) setWindowSize(next)
  }, [word, windowSize])

  // 异步预取：当前窗口一打开就把下一段排上，别让学生走到边界再等
  useEffect(() => {
    if (!gateOpen || !sessionId) return
    const next = nextPrepareWindow(windowSize)
    if (next === windowSize) return
    void prepareWords('SESSION', next, { sessionId }).catch(() => { /* 下一轮轮询再试 */ })
  }, [gateOpen, windowSize, sessionId])

  const retryContent = useCallback(async () => {
    if (!word) return
    setRetrying(true)
    try {
      await api.post('/api/vocab/words/retry', { ids: [word.id] })
      setPrep(await prepareWords('SESSION', windowSize, { sessionId }))
    } catch (retryError) {
      toast.error((retryError as Error).message)
    } finally {
      setRetrying(false)
    }
  }, [word, windowSize, sessionId, toast])

  const toggleFavorite = useCallback(async () => {
    if (!word) return
    const favorite = !word.favorite
    try {
      await api.put(`/api/vocab/words/${word.id}/favorite`, { favorite })
      setWord((previous) => (previous ? { ...previous, favorite } : previous))
      toast.success(favorite ? t('vocab.review.favoriteAdded') : t('vocab.review.favoriteRemoved'))
    } catch (favoriteError) {
      toast.error((favoriteError as Error).message)
    }
  }, [word, t, toast])

  const advance = useCallback(async (action: 'KNOWN' | 'DONT_KNOW' | 'PAUSE' | 'SKIP' | 'TOGGLE_PAUSE') => {
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
      if (preparing || gateWaiting) return
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
        void advance('TOGGLE_PAUSE')
      } else if (event.key === 'n' || event.key === 'N' || event.key === 'ArrowRight') {
        // T436：明细视图可跳过当前词，不评分、不动排期
        void advance('SKIP')
      } else if (event.key === 'f' || event.key === 'F') {
        event.preventDefault()
        void toggleFavorite()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [advance, preparing, toggleFavorite])

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

  if (gateWaiting) {
    return (
      <>
        <PageHeader title={t('vocab.freeReview.title')}
          sub={word.cursor > 0 ? t('vocab.freeReview.lastSeen', { cursor: word.cursor, total: word.total }) : undefined}
          actions={<Button size="small" onClick={() => void end()}>{t('vocab.freeReview.end')}</Button>} />
        <div className="review-stage review-stage--today">
          <div className="review-card-column">
            <div className="review-card">
              <div className="review-word">{word.form}</div>
              <div className="muted">{t('vocab.review.preparing')}</div>
              {gatePrep && (
                <div className="muted">
                  {t('vocab.review.preparingCount', { ready: gatePrep.ready, total: gatePrep.requested })}
                </div>
              )}
              <div className="review-hint muted">{t('vocab.review.preparingHint')}</div>
              {gateSlow && (
                <div className="review-actions">
                  <Button onClick={() => setGateOpen(true)}>{t('vocab.review.startAnyway')}</Button>
                </div>
              )}
            </div>
          </div>
        </div>
      </>
    )
  }

  if (preparing || contentFailed) {
    return (
      <>
        <PageHeader title={t('vocab.freeReview.title')}
          sub={word.cursor > 0 ? t('vocab.freeReview.lastSeen', { cursor: word.cursor, total: word.total }) : undefined}
          actions={<Button size="small" onClick={() => void end()}>{t('vocab.freeReview.end')}</Button>} />
        <div className="review-stage review-stage--today">
          <div className="review-card-column">
            <div className="review-card">
              <div className="review-word">{word.form}</div>
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

  return (
    <>
      <PageHeader title={t('vocab.freeReview.title')}
        sub={word.cursor > 0 ? t('vocab.freeReview.lastSeen', { cursor: word.cursor, total: word.total }) : undefined}
        actions={<Button size="small" onClick={() => void end()}>{t('vocab.freeReview.end')}</Button>} />

      <div className="review-stage review-stage--today">
        <div className="review-card-column">
          <VocabReviewCard
            word={word}
            flipped={flipped}
            onFlip={() => setFlipped((value) => !value)}
            onToggleFavorite={() => void toggleFavorite()}
            paused={!!word.paused}
            onTogglePause={() => void advance('TOGGLE_PAUSE')}
            onRate={(rating) => void advance(rating)}
            ratingDisabled={busy || !!word.paused}
            notice={word.paused ? t('vocab.freeReview.pausedHint') : undefined}
            centered
          />
          <div className="review-session-foot">
            <span className="review-keys muted">{t('vocab.freeReview.keys')}</span>
          </div>
        </div>

        <Button
          size="large"
          className={`review-next${flipped ? ' is-highlighted' : ''}`}
          variant={flipped ? 'primary' : 'default'}
          disabled={busy}
          onClick={() => void advance('SKIP')}
        >
          {t('vocab.review.next')}
        </Button>
      </div>
    </>
  )
}
