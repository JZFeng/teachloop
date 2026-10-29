import { useCallback, useEffect, useState } from 'react'
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client'
import { Button, Empty, ErrorState, InlineNote, Loading, PageHeader, Progress, useConfirm, useToast } from '../ui'
import { VocabReviewCard } from './VocabReviewCard'
import { reviewCardPhase } from './reviewCardState'
import { PREPARE_STEPS, nextPrepareWindow, prepareWords, type PrepareResult } from './prepare'
import { fetchLists } from './lists'
import { useVocabAudioConfig } from './audioConfig'
import { createQuiz } from './quiz'
import { useWordBlocks } from './prefs'
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
  /** AC-87.9：最近一次小测答错的时间，卡面用它渲染提示 */
  lastQuizWrongAt?: string | null
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

/**
 * 「跳过」计数只存在于本次页面会话里（T452）：后端 `Summary` 没有 skipped 字段，
 * 而用 `seen - known - unknown` 反推会把「从上次位置继续」的已看数算成跳过。
 * 存在 sessionStorage 里可以扛住刷新，换一个 sessionId（继续复习）自然从 0 开始。
 */
function skipKey(sessionId: string): string {
  return `vocab:review:skip:${sessionId}`
}

function readSkipCount(sessionId: string): number {
  try {
    const raw = window.sessionStorage.getItem(skipKey(sessionId))
    const value = Number(raw ?? 0)
    return Number.isFinite(value) && value > 0 ? value : 0
  } catch {
    return 0
  }
}

function writeSkipCount(sessionId: string, count: number): void {
  try {
    window.sessionStorage.setItem(skipKey(sessionId), String(count))
  } catch {
    // 隐私模式下写不进去也不影响本次复习
  }
}

export default function VocabFreeReviewPage() {
  const { id = '' } = useParams()
  const { t } = useTranslation()
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const toast = useToast()
  const confirm = useConfirm()
  /** AC-69.7：从词单进来的这一轮，窄条显示范围、小结里回词单详情 */
  const fromListId = searchParams.get('from') ?? ''
  const [listName, setListName] = useState(
    (location.state as { listName?: string } | null)?.listName ?? '',
  )
  const [skipped, setSkipped] = useState(0)
  const [word, setWord] = useState<SessionWord | null | undefined>(undefined)
  const [summary, setSummary] = useState<Summary | null>(null)
  const { quiz: quizEnabled } = useVocabAudioConfig()
  const wordBlocks = useWordBlocks()
  const [startingQuiz, setStartingQuiz] = useState(false)
  const [flipped, setFlipped] = useState(false)
  /** 已评「不认识」：仍停在这一张，等「下一个」推走（照 today-review，AC-53.8）。 */
  const [rated, setRated] = useState<'KNOWN' | 'DONT_KNOW' | null>(null)
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
        setRated(null)
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

  useEffect(() => { setSkipped(readSkipCount(sessionId)) }, [sessionId])

  // 刷新后 route state 会丢：用 GET /lists 找回词单名（拿不到就显示通用标题，不阻塞复习）
  useEffect(() => {
    if (!fromListId || listName) return
    let alive = true
    fetchLists()
      .then((lists) => {
        const found = lists.find((item) => item.id === fromListId)
        if (alive && found) setListName(found.name)
      })
      .catch(() => { /* 窄条退化成通用标题 */ })
    return () => { alive = false }
  }, [fromListId, listName])

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

  // 状态之外再核一次内容（2026-10-29 用户报 Bug）：库里写着 READY 但内容解析不到时，
  // 直接渲染卡面会得到一张没有音标/释义/例句的空卡；这里降级成「准备中」，等 prepare 重新入队。
  const phase = reviewCardPhase(word?.contentStatus, word?.content)
  const contentFailed = phase === 'failed'
  const preparing = !!word && phase === 'preparing'
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
      // 重试只是入队：这里要盯着当前卡，直到它变 READY 才把「内容生成失败」换掉，
      // 否则用户点完重试仍然停在同一屏（T453 联调实测：job 已 SUCCEEDED，页面不会自己刷新）。
      for (let attempt = 0; attempt < 15; attempt += 1) {
        const result = await prepareWords('SESSION', windowSize, { sessionId }).catch(() => null)
        if (result) setPrep(result)
        const current = await api.get<SessionWord | null>(
          `/api/vocab/review-sessions/${sessionId}/current`)
        if (current && current.contentStatus === 'READY') {
          setWord(current)
          setFlipped(false)
          return
        }
        await new Promise((resolve) => { setTimeout(resolve, 2000) })
      }
      toast.info(t('vocab.review.retryStillPreparing'))
    } catch (retryError) {
      toast.error((retryError as Error).message)
    } finally {
      setRetrying(false)
    }
  }, [word, windowSize, sessionId, toast, t])

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
    // 已评「不认识」时评分已落库，只剩「下一个」；不再接受第二次评分/暂停。
    if (!word || busy || rated) return
    setBusy(true)
    try {
      await api.post(`/api/vocab/review-sessions/${sessionId}/advance`, { action, elapsedMs: 0 })
      if (action === 'SKIP') {
        const next = readSkipCount(sessionId) + 1
        writeSkipCount(sessionId, next)
        setSkipped(next)
      }
      await load()
    } catch (advanceError) {
      toast.error((advanceError as Error).message)
    } finally {
      setBusy(false)
    }
  }, [word, busy, rated, sessionId, load, toast])

  /**
   * 点「不认识」：先翻到反面，停在原地（2026-10-26 用户报 Bug）。
   *
   * `today-review`（`ReviewPage.submit`）一直是这个行为：评分只落库，卡面翻到反面给学生看
   * 释义/例句，再由「下一个」推进。`free-review` 之前直接 `load()`，学生还没看到释义就被换走了。
   */
  const rateDontKnow = useCallback(async () => {
    if (!word || busy || rated || word.paused) return
    setBusy(true)
    try {
      await api.post(`/api/vocab/review-sessions/${sessionId}/advance`,
        { action: 'DONT_KNOW', elapsedMs: 0 })
      setRated('DONT_KNOW')
      setFlipped(true)
    } catch (rateError) {
      toast.error((rateError as Error).message)
    } finally {
      setBusy(false)
    }
  }, [word, busy, rated, sessionId, toast])

  /**
   * 「下一个」：已评分时只推进到下一张（评分已经落库，不再发请求）；
   * 未评分时仍是「跳过」（SKIP，不写评分、不动排期）。
   */
  const next = useCallback(async () => {
    if (!word || busy) return
    if (!rated) {
      await advance('SKIP')
      return
    }
    setBusy(true)
    try {
      await load()
    } finally {
      setBusy(false)
    }
  }, [word, busy, rated, advance, load])

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
    navigate(`/vocab/review/session/${next.id}${location.search}`, { replace: true })
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
        void rateDontKnow()
      } else if (event.key === '2') {
        void advance('KNOWN')
      } else if (event.key === 's' || event.key === 'S') {
        void advance('TOGGLE_PAUSE')
      } else if (event.key === 'n' || event.key === 'N' || event.key === 'ArrowRight') {
        // 已评分 → 推进下一张；未评分 → 跳过（T436：不写评分、不动排期）
        void next()
      } else if (event.key === 'f' || event.key === 'F') {
        event.preventDefault()
        void toggleFavorite()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [advance, next, rateDontKnow, preparing, toggleFavorite])

  /**
   * 窄条：照词库页的 `vocab-banner`（不新造样式）。
   * 小结页不再重复给按钮——那张卡里已经有主按钮「返回词单」。
   */
  const scopeBanner = (total: number, withAction = true) => (
    fromListId ? (
      <div className="vocab-banner">
        <span>
          {t('vocab.lists.reviewScope', { name: listName || t('vocab.lists.title'), count: total })}
        </span>
        {withAction && (
          <Button size="small" onClick={() => navigate(`/vocab/lists/${fromListId}`)}>
            {t('vocab.freeReview.backToList')}
          </Button>
        )}
      </div>
    ) : null
  )

  if (error) return <ErrorState error={error} onRetry={() => void load()} />

  if (summary) {
    return (
      <>
        <PageHeader title={t('vocab.freeReview.summaryTitle')} />
        {scopeBanner(summary.total, false)}
        <div className="card free-review-summary">
          <div className="row" style={{ gap: 18, flexWrap: 'wrap' }}>
            <span>{t('vocab.freeReview.summarySeen', { count: summary.seen })}</span>
            <span>{t('vocab.freeReview.summaryKnown', { count: summary.known })}</span>
            <span>{t('vocab.freeReview.summaryUnknown', { count: summary.unknown })}</span>
            <span>{t('vocab.freeReview.summaryPaused', { count: summary.paused })}</span>
            <span>{t('vocab.freeReview.summarySkipped', { count: skipped })}</span>
          </div>
          <div className="row" style={{ gap: 8, marginTop: 16, flexWrap: 'wrap' }}>
            {quizEnabled && sessionId && (
              <Button
                variant="primary"
                loading={startingQuiz}
                onClick={() => {
                  setStartingQuiz(true)
                  createQuiz('SESSION', sessionId)
                    .then((created) => navigate(`/vocab/quiz/${created.id}`))
                    .catch(() => setStartingQuiz(false))
                }}
              >
                {t('vocab.quiz.startSession')}
              </Button>
            )}
            {fromListId && (
              <Button variant="primary" onClick={() => navigate(`/vocab/lists/${fromListId}`)}>
                {t('vocab.freeReview.backToList')}
              </Button>
            )}
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
        {scopeBanner(word.total)}
        <div className="review-stage review-stage--today">
          <div className="review-card-column">
            <div className="review-card">
              <div className="review-word">{word.form}</div>
              <div className="muted">{t('vocab.review.preparing')}</div>
              {gatePrep && (
                <>
                  <div className="muted">
                    {t('vocab.review.preparingCount', { ready: gatePrep.ready, total: gatePrep.requested })}
                  </div>
                  <div className="review-prep-progress">
                    <Progress value={gatePrep.ready} max={gatePrep.requested}
                      label={t('vocab.review.preparingCount', {
                        ready: gatePrep.ready, total: gatePrep.requested,
                      })} />
                  </div>
                </>
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
        {scopeBanner(word.total)}
        <div className="review-stage review-stage--today">
          <div className="review-card-column">
            <div className="review-card">
              <div className="review-word">{word.form}</div>
              {preparing ? (
                <>
                  <div className="muted">{t('vocab.review.preparing')}</div>
                  {prep && (
                    <>
                      <div className="muted">
                        {t('vocab.review.preparingCount', { ready: prep.ready, total: prep.requested })}
                      </div>
                      <div className="review-prep-progress">
                        <Progress value={prep.ready} max={prep.requested}
                          label={t('vocab.review.preparingCount', {
                            ready: prep.ready, total: prep.requested,
                          })} />
                      </div>
                    </>
                  )}
                  <div className="review-hint muted">{t('vocab.review.preparingHint')}</div>
                </>
              ) : (
                <>
                  <InlineNote tone="error">{t('vocab.review.contentFailed')}</InlineNote>
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

      {scopeBanner(word.total)}

      <div className="review-stage review-stage--today">
        <div className="review-card-column">
          <VocabReviewCard
            word={word}
            flipped={flipped}
            onFlip={() => setFlipped((value) => !value)}
            onToggleFavorite={() => void toggleFavorite()}
            paused={!!word.paused}
            onTogglePause={() => void advance('TOGGLE_PAUSE')}
            onRate={(rating) => { void (rating === 'DONT_KNOW' ? rateDontKnow() : advance('KNOWN')) }}
            ratingDisabled={busy || !!word.paused || rated !== null}
            blocks={wordBlocks.blocks}
            hidden={wordBlocks.hidden}
            notice={word.paused ? t('vocab.freeReview.pausedHint') : undefined}
            emptyHint={t('vocab.review.contentMissing')}
            centered
          />
          <div className="review-session-foot">
            <span className="review-keys muted">{t('vocab.freeReview.keys')}</span>
          </div>
        </div>

        <Button
          size="large"
          className={`review-next${flipped || rated ? ' is-highlighted' : ''}`}
          variant={flipped || rated ? 'primary' : 'default'}
          disabled={busy}
          onClick={() => void next()}
        >
          {t('vocab.review.next')}
        </Button>
      </div>
    </>
  )
}
