import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import {
  Button,
  DeleteOutlined,
  Empty,
  ErrorState,
  InfoCircleOutlined,
  Loading,
  PageHeader,
  Popover,
  StarFilled,
  StarOutlined,
  useConfirm,
  useToast,
} from '../ui'
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
  favorite: boolean
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

/** T427：快捷键表（顺序即展示顺序）；发音两行与卡片上的喇叭一一对应。 */
const SHORTCUTS = [
  { label: 'vocab.review.shortcutNext', keys: 'N / →' },
  { label: 'vocab.review.shortcutDetail', keys: 'Space' },
  { label: 'vocab.review.shortcutUnknown', keys: '1' },
  { label: 'vocab.review.shortcutKnown', keys: '2' },
  { label: 'vocab.audio.playUk', keys: 'U' },
  { label: 'vocab.audio.playUs', keys: 'A' },
  { label: 'vocab.review.shortcutFavorite', keys: 'F' },
  { label: 'vocab.review.shortcutDelete', keys: 'Delete' },
] as const

export default function VocabReviewPage() {
  const { t } = useTranslation()
  const toast = useToast()
  const confirm = useConfirm()
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
  const [gatePrep, setGatePrep] = useState<PrepareResult | null>(null)
  const [gateOpen, setGateOpen] = useState(false)
  const [gateSlow, setGateSlow] = useState(false)

  const refresh = useCallback(() => setReloadKey((value) => value + 1), [])

  useEffect(() => {
    let alive = true
    setLoading(true)
    setError(null)
    setWindowSize(PREPARE_STEPS[0])
    setPrep(null)
    setGatePrep(null)
    setGateOpen(false)
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
          if (alive && result) {
            setPrep(result)
            setGatePrep(result)
            // 用户 2026-10-24：等满首批 N 个词再出卡（常见词通常已提前生成，现场请求很少）
            if (result.pending === 0) setGateOpen(true)
          }
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
  const gateWaiting = items.length > 0 && !gateOpen

  // 兜底：等待超过 60 秒（例如 AI 未配置/生成服务不工作）时允许手动开始，避免整页卡死
  useEffect(() => {
    if (!gateWaiting) { setGateSlow(false); return }
    const timer = setTimeout(() => setGateSlow(true), 60000)
    return () => clearTimeout(timer)
  }, [gateWaiting])

  // 等满首批 N 个词：每 2 秒问一次准备进度并刷新队列，pending 归零才放行
  useEffect(() => {
    if (!gateWaiting) return
    let alive = true
    const tick = async () => {
      try {
        const [nextPrep, nextQueue] = await Promise.all([
          prepareWords('TODAY', PREPARE_STEPS[0]),
          api.get<QueueView>('/api/vocab/review/queue'),
        ])
        if (!alive) return
        setGatePrep(nextPrep)
        setPrep(nextPrep)
        setQueue((previous) => (previous ? { ...previous, items: nextQueue.items } : nextQueue))
        if (nextPrep.pending === 0) setGateOpen(true)
      } catch {
        // 单次轮询失败不打断等待，下一次再试
      }
    }
    const timer = setInterval(() => void tick(), 2000)
    return () => { alive = false; clearInterval(timer) }
  }, [gateWaiting])

  // 后续窗口（100/200/400…）：只等当前卡，不整页阻塞
  useEffect(() => {
    if (gateWaiting || !preparing) return
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
        // 单次轮询失败不打断等待
      }
    }
    const timer = setInterval(() => void tick(), 2000)
    return () => { alive = false; clearInterval(timer) }
  }, [gateWaiting, preparing, windowSize])

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

  // T427：卡面右上角的星标；只改收藏标记，不碰排期
  const toggleFavorite = useCallback(async () => {
    if (!current) return
    const favorite = !current.favorite
    try {
      await api.put(`/api/vocab/words/${current.id}/favorite`, { favorite })
      setQueue((previous) => (previous ? {
        ...previous,
        items: previous.items.map((item) => (
          item.id === current.id ? { ...item, favorite } : item
        )),
      } : previous))
      toast.success(favorite ? t('vocab.review.favoriteAdded') : t('vocab.review.favoriteRemoved'))
    } catch (favoriteError) {
      toast.error((favoriteError as Error).message)
    }
  }, [current, t, toast])

  // T427：删除当前词（软删除，二次确认），删完原地换下一张
  const removeCurrent = useCallback(async () => {
    if (!current) return
    const ok = await confirm({
      title: t('vocab.library.deleteTitle'),
      content: t('vocab.library.deleteBody', { word: current.form }),
      okText: t('vocab.library.delete'),
      cancelText: t('common.cancel'),
    })
    if (!ok) return
    try {
      await api.del(`/api/vocab/words/${current.id}`)
      toast.success(t('vocab.library.deletedToast'))
      setQueue((previous) => {
        if (!previous) return previous
        return {
          ...previous,
          items: previous.items.filter((item) => item.id !== current.id),
          total: Math.max(0, previous.total - 1),
          remaining: Math.max(0, previous.remaining - 1),
        }
      })
      setFlipped(false)
      setRated(null)
    } catch (deleteError) {
      toast.error((deleteError as Error).message)
    }
  }, [confirm, current, t, toast])

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (preparing || gateWaiting) return
      if (event.metaKey || event.ctrlKey || event.altKey) return
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
      } else if (event.key === 'f' || event.key === 'F') {
        event.preventDefault()
        void toggleFavorite()
      } else if (event.key === 'Delete' || event.key === 'Backspace') {
        event.preventDefault()
        void removeCurrent()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [submit, next, rated, preparing, toggleFavorite, removeCurrent])

  // T427：三个数与「评分会改变什么」仍收在 ⓘ 里（AC-52.2），并新增快捷键表
  const info = useMemo(() => (
    <div className="review-info">
      <p className="review-info-stats muted">
        {t('vocab.review.statsLine', {
          reviewed: queue?.todayReviewed ?? 0,
          remaining: queue?.remaining ?? 0,
          newCards: queue?.newToday ?? 0,
        })}
        {queue?.newLimitReached && <span> · {t('vocab.review.newLimit')}</span>}
      </p>
      <table className="review-shortcut-table">
        <thead>
          <tr>
            <th>{t('vocab.review.shortcutAction')}</th>
            <th>{t('vocab.review.shortcutKey')}</th>
          </tr>
        </thead>
        <tbody>
          {SHORTCUTS.map((row) => (
            <tr key={row.label}>
              <td>{t(row.label)}</td>
              <td><kbd>{row.keys === 'Space' ? t('vocab.review.keySpace') : row.keys}</kbd></td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="muted review-info-hint">{t('vocab.review.ratingHint')}</p>
    </div>
  ), [queue, t])

  const header = (
    <PageHeader
      title={t('vocab.review.title')}
      hideTitle
      actions={(
        <Popover content={info} trigger="click" placement="bottomRight">
          <button
            type="button"
            className="review-info-icon"
            aria-label={t('vocab.review.shortcuts')}
            title={t('vocab.review.shortcuts')}
          >
            <InfoCircleOutlined />
          </button>
        </Popover>
      )}
    />
  )

  if (loading) return <Loading label={t('common.loading')} />
  if (error) return <ErrorState error={error} onRetry={refresh} />

  if (gateWaiting) {
    return (
      <>
        {header}
        <div className="review-stage review-stage--today">
          <div className="review-card-column">
            <div className="review-card">
              <div className="review-card-body">
                <div className="review-word">{items[0]?.form}</div>
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
        </div>
      </>
    )
  }

  if (current && (preparing || currentFailed)) {
    return (
      <>
        {header}
        <div className="review-stage review-stage--today">
          <div className="review-card-column">
            <div className="review-card">
              <div className="review-card-body">
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

      <div className="review-stage review-stage--today">
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
            <div className="review-card-tools">
              <button
                type="button"
                className={`review-icon-action${current.favorite ? ' is-on' : ''}`}
                aria-label={current.favorite ? t('vocab.library.unfavorite') : t('vocab.library.favorite')}
                aria-pressed={current.favorite}
                title={`${current.favorite ? t('vocab.library.unfavorite') : t('vocab.library.favorite')} · F`}
                onClick={(event) => { event.stopPropagation(); void toggleFavorite() }}
              >
                {current.favorite ? <StarFilled /> : <StarOutlined />}
              </button>
              <button
                type="button"
                className="review-icon-action is-danger"
                aria-label={t('vocab.library.delete')}
                title={`${t('vocab.library.delete')} · Delete`}
                onClick={(event) => { event.stopPropagation(); void removeCurrent() }}
              >
                <DeleteOutlined />
              </button>
            </div>

            <div className="review-card-body">
              <div className="review-word">{current.form}</div>
              <div className="review-phonetic-row muted">
                <span className="review-phonetic">
                  {t('vocab.detail.phoneticUk')} {phonetic?.uk ?? '—'}
                  <SpeakerButton form={current.form} accent="uk" shortcut="u" compact variant="text" />
                </span>
                <span className="review-phonetic">
                  {t('vocab.detail.phoneticUs')} {phonetic?.us ?? '—'}
                  <SpeakerButton form={current.form} accent="us" shortcut="a" compact variant="text" />
                </span>
              </div>
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

            <div className="review-card-actions" onClick={(event) => event.stopPropagation()}>
              <Button
                size="large"
                className="review-unknown"
                disabled={disabled}
                onClick={() => void submit('DONT_KNOW')}
              >
                {t('vocab.review.unknown')}
              </Button>
              <Button
                size="large"
                variant="primary"
                disabled={disabled}
                onClick={() => void submit('KNOWN')}
              >
                {t('vocab.review.known')}
              </Button>
            </div>
          </div>
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
