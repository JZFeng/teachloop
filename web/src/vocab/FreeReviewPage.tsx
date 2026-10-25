import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client'
import { Button, Empty, ErrorState, Loading, PageHeader, useConfirm, useToast } from '../ui'
import { PREPARE_STEPS, nextPrepareWindow, prepareWords, type PrepareResult } from './prepare'
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
  contentStatus: string
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

  // 懒加载窗口：快到窗口末尾时提前准备下一段（100/200/400…）
  useEffect(() => {
    if (!word || !sessionId) return
    if (word.cursor < windowSize - 5) return
    const next = nextPrepareWindow(windowSize)
    if (next === windowSize) return
    setWindowSize(next)
    void prepareWords('SESSION', next, { sessionId })
      .then(setPrep)
      .catch(() => { /* 下一轮轮询再试 */ })
  }, [word, windowSize, sessionId])

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
        void advance('PAUSE')
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [advance, preparing])

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
        <div className="review-stage">
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
        <div className="review-stage">
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
