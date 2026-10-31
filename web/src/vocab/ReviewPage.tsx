import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import { Button, Empty, ErrorState, InfoHint, Loading, useToast } from '../ui'
import { useVocabAudioConfig } from './audioConfig'
import { createQuiz } from './quiz'
import { PREPARE_STEPS, prepareWords } from './prepare'
import { VocabTabExtra } from './VocabTabExtra'
import './vocab.css'

/**
 * 今日复习 · 准备页（011 / T595，2026-10-30 拍板按 AC-91.3 字面收口）。
 *
 * 这一页只回答一个问题：**今天还要背多少张、现在开始**。三件事变了：
 * 1. 卡片舞台搬进全屏会话（`/vocab/review/session/:id?source=today`），本页不再自己出卡、
 *    不再自己评分——原来这里是 `VocabReviewCard` + 键盘评分（`ReviewPage.tsx:556` 那套），
 *    与词单 / 词库进去的会话是两份逻辑。
 * 2. 「偏好设置」齿轮与抽屉整个删掉（T597 / AC-91.7）：开始复习了就不再给设置入口，
 *    任务量 / 策略 / 反面内容 / 顺序只在 `/profile` 的「背单词」块改。
 * 3. 页面不再渲染自己的 `h1`（T596 / AC-91.6）：标题由壳层的页签承担，
 *    ⓘ 通过 `VocabTabExtra` 挂到页签行右端。
 *
 * 分数与口径：三个数（今日已复习 / 剩余待复习 / 本次新卡）仍在 ⓘ 里（AC-52.2）；
 * 会话里的评分会计入「今日已复习」——靠会话 scope 的 `source=today`（AC-52.2 口径修订）。
 */

interface QueueItem {
  id: string
  form: string
  dueAt: string
}

interface QueueView {
  items: QueueItem[]
  total: number
  todayReviewed: number
  remaining: number
  newToday: number
  newLimitReached: boolean
}

/** 一次会话的硬上限：后端 `ReviewSessionEndpoint.MAX_WORDS`。超过就分两轮（2026-10-30 拍板）。 */
const MAX_SESSION_WORDS = 1000

export default function VocabReviewPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const toast = useToast()
  const { quiz: quizEnabled } = useVocabAudioConfig()
  const [queue, setQueue] = useState<QueueView | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<Error | null>(null)
  const [reloadKey, setReloadKey] = useState(0)
  const [starting, setStarting] = useState(false)
  const [startingQuiz, setStartingQuiz] = useState(false)

  const refresh = useCallback(() => setReloadKey((value) => value + 1), [])

  useEffect(() => {
    let alive = true
    setLoading(true)
    setError(null)
    api.get<QueueView>('/api/vocab/review/queue')
      .then((value) => {
        if (!alive) return
        setQueue(value)
        // 内容预生成：进这一页就开始准备首批，学生点「开始复习」时第一张多半已经好了。
        // 准备不到也没关系——会话页有自己的逐卡「准备中」态。
        if (value.items.length > 0) {
          void prepareWords('TODAY', PREPARE_STEPS[0]).catch(() => { /* 会话页会再试 */ })
        }
      })
      .catch((loadError) => { if (alive) setError(loadError as Error) })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [reloadKey])

  const remaining = queue?.remaining ?? 0
  const todayReviewed = queue?.todayReviewed ?? 0

  /** 三个数与「评分会改变什么」收在 ⓘ 里（AC-52.2）。快捷键表跟着卡片走，已在会话页。 */
  const info = useMemo(() => (
    <div className="review-info">
      <p className="review-info-stats muted">
        {t('vocab.review.statsLine', {
          reviewed: todayReviewed,
          remaining,
          newCards: queue?.newToday ?? 0,
        })}
        {queue?.newLimitReached && <span> · {t('vocab.review.newLimit')}</span>}
      </p>
      <p className="muted review-info-hint">{t('vocab.review.ratingHint')}</p>
    </div>
  ), [queue?.newLimitReached, queue?.newToday, remaining, t, todayReviewed])

  /**
   * 把今天到期的词取齐，最多 {@link MAX_SESSION_WORDS} 个。
   * 队列接口一页最多 100 张（AC-52.4），所以按 `dueAt|id` 游标往后翻；翻到上限或没有更多为止。
   */
  const collectDueIds = useCallback(async (first: QueueView): Promise<{ ids: string[]; capped: boolean }> => {
    const ids: string[] = []
    const seen = new Set<string>()
    let last = first.items[first.items.length - 1]
    let more = first.remaining
    let newServed = first.newToday
    for (const item of first.items) {
      if (seen.has(item.id)) continue
      seen.add(item.id)
      ids.push(item.id)
    }
    while (ids.length < MAX_SESSION_WORDS && more > 0 && last) {
      const cursor = `${last.dueAt}|${last.id}`
      const next = await api.get<QueueView>(
        `/api/vocab/review/queue?after=${encodeURIComponent(cursor)}&newServed=${newServed}`)
      if (!next.items.length) break
      for (const item of next.items) {
        if (seen.has(item.id)) continue
        seen.add(item.id)
        ids.push(item.id)
      }
      newServed += next.newToday
      more = next.remaining
      last = next.items[next.items.length - 1]
    }
    return { ids: ids.slice(0, MAX_SESSION_WORDS), capped: ids.length >= MAX_SESSION_WORDS }
  }, [])

  const startReview = useCallback(async () => {
    if (!queue || starting) return
    setStarting(true)
    try {
      const { ids } = await collectDueIds(queue)
      if (!ids.length) {
        // 队列刚好被别人（另一个标签页 / 小测）消耗光：刷新这一页，别把用户扔进空会话
        refresh()
        setStarting(false)
        return
      }
      const created = await api.post<{ id: string }>('/api/vocab/review-sessions', {
        source: 'today',
        wordIds: ids,
        fromStart: true,
      })
      navigate(`/vocab/review/session/${created.id}?source=today`)
    } catch (startError) {
      toast.error((startError as Error).message)
      setStarting(false)
    }
  }, [collectDueIds, navigate, queue, refresh, starting, toast])

  const startQuiz = useCallback(() => {
    setStartingQuiz(true)
    createQuiz('TODAY')
      .then((created) => navigate(`/vocab/quiz/${created.id}`))
      .catch((quizError) => {
        setStartingQuiz(false)
        toast.error((quizError as Error).message)
      })
  }, [navigate, toast])

  if (loading) return <Loading label={t('common.loading')} />
  if (error) return <ErrorState error={error} onRetry={refresh} />

  // 今天已经背完（剩余 0）：复习过就给小测入口，没复习过就是空词库（AC-87.1 / AC-52.5）
  if (remaining === 0) {
    const canQuiz = quizEnabled && todayReviewed >= 3
    return (
      <>
        <VocabTabExtra><InfoHint>{info}</InfoHint></VocabTabExtra>
        {todayReviewed > 0 ? (
          <div className="card quiz-entry">
            <div className="quiz-entry-num">
              {todayReviewed}
              <small>{t('vocab.quiz.wordsUnit')}</small>
            </div>
            <p className="muted small">{t('vocab.quiz.entryTitle')}</p>
            {canQuiz ? (
              <>
                <Button variant="primary" size="large" loading={startingQuiz} onClick={startQuiz}>
                  {t('vocab.quiz.startToday')}
                </Button>
                <p className="muted small quiz-entry-hint">{t('vocab.quiz.entryBody')}</p>
              </>
            ) : (
              <p className="muted small">{t('vocab.quiz.tooFew')}</p>
            )}
          </div>
        ) : (
          <Empty>
            <div>{t('vocab.review.empty')}</div>
            <div style={{ marginTop: 8 }}>
              <Link to="/vocab/library">{t('vocab.review.goLibrary')}</Link>
            </div>
          </Empty>
        )}
      </>
    )
  }

  // 「有新卡额度用完」的边角：到期数 > 0，但队列这一页为空（全是 NEW、今日额度已满）。
  // 这时不能给一个点了没反应的「开始复习」，要说清为什么开不了（AC-52.3）。
  const startable = (queue?.items.length ?? 0) > 0
  const capped = remaining > MAX_SESSION_WORDS
  return (
    <>
      <VocabTabExtra><InfoHint>{info}</InfoHint></VocabTabExtra>
      <section className="card today-card vocab-ready" data-testid="today-review-ready">
        <div className="today-card-body">
          <div className="today-review">
            <div className="today-review-figures">
              <div className="today-review-label">{t('today.review.dueLabel')}</div>
              <div className="today-review-due">
                <span className="today-review-count">{remaining}</span>
                <span className="today-review-unit">{t('today.review.unit')}</span>
              </div>
              {/* 三个数用与 ⓘ 同一句口径（AC-52.2）；每日上限的数值不在这里编，只在上限用完时说明 */}
              <div className="today-review-stats">
                {t('vocab.review.statsLine', {
                  reviewed: todayReviewed,
                  remaining,
                  newCards: queue?.newToday ?? 0,
                })}
                {queue?.newLimitReached && <span> · {t('vocab.review.newLimit')}</span>}
              </div>
            </div>
            <div className="today-review-act">
              <Button
                variant="primary"
                data-testid="today-review-focus-start"
                loading={starting}
                disabled={!startable}
                onClick={() => void startReview()}
              >
                {t('today.review.start')}
              </Button>
              <div className="muted small">
                {!startable
                  ? t('vocab.review.newLimit')
                  : capped
                    ? t('vocab.review.roundCap', { count: MAX_SESSION_WORDS })
                    : t('vocab.review.enterFocus')}
              </div>
            </div>
          </div>
        </div>
      </section>
    </>
  )
}
