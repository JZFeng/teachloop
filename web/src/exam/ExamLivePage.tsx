import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api, ApiError, fmtDuration } from '../api/client'
import { trackEvent } from '../api/events'
import type {
  AnswerSaveResponse,
  ExamPaper as ExamPaperView,
  MyAttemptsView,
  SessionView,
  SubmitResponse,
} from '../api/types'
import { ErrorState, Loading } from '../components/States'
import { ExamTree } from './ExamTree'
import { ExamPaper } from './ExamPaper'
import { SubmitCheckDialog } from './SubmitCheckDialog'
import { ExamResult } from './ExamResult'
import {
  buildAnswerList,
  clearDraft,
  fmtTimeOfDay,
  groupParts,
  isBlankAnswer,
  partKeyOfNumber,
  readDraft,
  writeDraft,
} from './examUtils'
import './exam.css'

const AUTOSAVE_DELAY = 1500

/**
 * T187/T188/T189/T195/T197：专注模式作答。
 * 计时以服务端剩余秒数为准，本地每秒只做显示插值；答案变化 debounce 自动保存。
 */
export default function ExamLivePage() {
  const { offeringId = '' } = useParams<{ offeringId: string }>()
  const navigate = useNavigate()
  const { t } = useTranslation()

  const [paper, setPaper] = useState<ExamPaperView | null>(null)
  const [session, setSession] = useState<SessionView | null>(null)
  const [answers, setAnswers] = useState<Record<string, string>>({})
  const [marked, setMarked] = useState<Set<number>>(new Set())
  const [current, setCurrent] = useState(1)
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<Error | null>(null)
  const [remaining, setRemaining] = useState<number | null>(null)
  const [started, setStarted] = useState(false)
  const [locked, setLocked] = useState(false)
  const [submitted, setSubmitted] = useState(false)
  const [autoSubmitted, setAutoSubmitted] = useState(false)
  const [savedAt, setSavedAt] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [restoredNote, setRestoredNote] = useState(false)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [submitResult, setSubmitResult] = useState<SubmitResponse | null>(null)
  const [attempts, setAttempts] = useState<MyAttemptsView | null>(null)

  const nodesRef = useRef(new Map<number, HTMLElement>())
  const suppressSpyRef = useRef(0)
  const dirtyRef = useRef(false)

  const parts = useMemo(
    () => groupParts(paper?.items ?? [], t('exam.wholePaper')),
    [paper, t],
  )
  const disabled = locked || submitted

  const done = useMemo(
    () => (paper?.items ?? []).filter((item) => !isBlankAnswer(item.questionType, answers[item.number])).length,
    [paper, answers],
  )
  const total = paper?.items.length ?? 0
  const markedList = useMemo(() => [...marked].sort((a, b) => a - b), [marked])
  const missing = useMemo(
    () => (paper?.items ?? [])
      .filter((item) => isBlankAnswer(item.questionType, answers[item.number]))
      .map((item) => item.number),
    [paper, answers],
  )
  const latestAttempt = useMemo(() => {
    const list = attempts?.attempts ?? []
    if (!list.length) return null
    return [...list].sort((a, b) => a.attemptNo - b.attemptNo)[list.length - 1]
  }, [attempts])

  // ---- 进入：先取试卷快照与作答会话；已交卷/锁定直接进入只读态 ----
  useEffect(() => {
    if (!offeringId) return
    let cancelled = false
    const run = async () => {
      setLoading(true)
      setError(null)
      try {
        const [loadedPaper, loadedSession] = await Promise.all([
          api.get<ExamPaperView>(`/api/offerings/${offeringId}/paper`),
          api.get<SessionView>(`/api/offerings/${offeringId}/session`),
        ])
        if (cancelled) return
        setPaper(loadedPaper)
        setSession(loadedSession)
        setStarted(loadedSession.started)
        setSubmitted(loadedSession.submitted)
        setAutoSubmitted(loadedSession.autoSubmitted)
        setLocked(loadedSession.locked)
        setRemaining(loadedSession.started ? loadedSession.remainingSeconds : null)

        if (!loadedSession.submitted && !loadedSession.locked) {
          const draft = readDraft(offeringId)
          const hasDraft = !!draft && (
            Object.keys(draft.answers).length > 0 || draft.marked.length > 0
          )
          // 服务端不返回已保存答案，只能以本机草稿恢复；判分与锁定仍以服务端为准。
          if (draft && hasDraft && draft.submissionId === loadedSession.submissionId) {
            setAnswers(draft.answers)
            setMarked(new Set(draft.marked))
            setSavedAt(new Date(draft.updatedAt).toISOString())
            setRestoredNote(true)
            // 恢复后立刻回写服务端：本机草稿是这台设备上最新的作答
            dirtyRef.current = true
          }
        }
      } catch (loadError) {
        if (!cancelled) setError(loadError as Error)
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    void run()
    return () => {
      cancelled = true
    }
  }, [offeringId])

  // 首次进入时把第一题设为当前题，并展开父级部分
  useEffect(() => {
    if (!paper) return
    const groups = groupParts(paper.items, t('exam.wholePaper'))
    setExpanded((prev) => (prev.size > 0 ? prev : new Set(groups[0] ? [groups[0].key] : [])))
    setCurrent((prev) => {
      const exists = paper.items.some((item) => item.number === prev)
      return exists ? prev : (paper.items[0]?.number ?? prev)
    })
  }, [paper, t])

  // 当前题变化（滚动联动或点击）时自动展开父级部分，保证高亮节点在树里可见（AC-21.4）
  useEffect(() => {
    if (!parts.length) return
    const key = partKeyOfNumber(parts, current)
    if (!key) return
    setExpanded((prev) => (prev.has(key) ? prev : new Set(prev).add(key)))
  }, [current, parts])

  // ---- 计时：服务端剩余秒数为准，本地每秒插值（AC-26.7 / AC-21.17） ----
  useEffect(() => {
    if (!started || submitted || locked || remaining === null) return
    const id = window.setInterval(() => {
      setRemaining((prev) => (prev === null ? null : Math.max(0, prev - 1)))
    }, 1000)
    return () => window.clearInterval(id)
  }, [started, submitted, locked, remaining === null])

  // 归零即锁定：不再接受任何输入（AC-21.18）
  useEffect(() => {
    if (remaining !== null && remaining <= 0 && started && !submitted && !locked) setLocked(true)
  }, [remaining, started, submitted, locked])

  // 到点后轮询 /session，确认服务端已完成自动交卷
  useEffect(() => {
    if (!locked || submitted || !started || !offeringId) return
    let cancelled = false
    const tick = async () => {
      try {
        const next = await api.get<SessionView>(`/api/offerings/${offeringId}/session`)
        if (cancelled) return
        setSession(next)
        setRemaining(next.remainingSeconds)
        setAutoSubmitted(next.autoSubmitted)
        if (next.submitted) {
          setSubmitted(true)
          setLocked(true)
        }
      } catch {
        // 网络抖动时保持轮询，不把错误弹到答题界面上
      }
    }
    void tick()
    const id = window.setInterval(() => void tick(), 3000)
    return () => {
      cancelled = true
      window.clearInterval(id)
    }
  }, [locked, submitted, started, offeringId])

  // 交卷后取回尝试记录，用于展示客观分与批改状态
  useEffect(() => {
    if (!submitted || !offeringId) return
    let cancelled = false
    api.get<MyAttemptsView>(`/api/offerings/${offeringId}/my-submissions`)
      .then((view) => {
        if (!cancelled) setAttempts(view)
      })
      .catch(() => {
        // 分数拿不到时结果页显示「—」，不编造
      })
    return () => {
      cancelled = true
    }
  }, [submitted, offeringId])

  // ---- 自动保存：答案变化 debounce 1.5s 写服务端；同时写本地草稿 ----
  const saveNow = useCallback(async (nextAnswers: Record<string, string>, nextMarked: Set<number>) => {
    if (!offeringId || !paper) return
    setSaving(true)
    setSaveError(null)
    try {
      const response = await api.put<AnswerSaveResponse>(`/api/offerings/${offeringId}/answers`, {
        answers: buildAnswerList(paper.items, nextAnswers),
      })
      setSavedAt(response.serverTime)
      trackEvent('answer_save', { offeringId, answered: Object.keys(nextAnswers).length })
      if (typeof response.remainingSeconds === 'number') setRemaining(response.remainingSeconds)
      writeDraft({
        offeringId,
        submissionId: response.submissionId,
        answers: nextAnswers,
        marked: [...nextMarked],
        updatedAt: Date.now(),
      })
      if (response.remainingSeconds <= 0) setLocked(true)
    } catch (saveFailure) {
      if (saveFailure instanceof ApiError && saveFailure.status === 409) {
        setLocked(true)
        setSaveError(t('exam.saveConflict'))
      } else {
        setSaveError((saveFailure as Error).message)
      }
    } finally {
      setSaving(false)
    }
  }, [offeringId, paper, t])

  useEffect(() => {
    if (!paper || !started || submitted || locked) return
    if (!dirtyRef.current) return
    writeDraft({
      offeringId,
      submissionId: session?.submissionId ?? null,
      answers,
      marked: [...marked],
      updatedAt: Date.now(),
    })
    const id = window.setTimeout(() => {
      void saveNow(answers, marked)
    }, AUTOSAVE_DELAY)
    return () => window.clearTimeout(id)
  }, [answers, marked, paper, started, submitted, locked, offeringId, session?.submissionId, saveNow])

  // ---- 目录树与滚动联动 ----
  const registerNode = useCallback((number: number, element: HTMLElement | null) => {
    if (element) nodesRef.current.set(number, element)
    else nodesRef.current.delete(number)
  }, [])

  const focusQuestion = useCallback((number: number) => {
    setCurrent((prev) => (prev === number ? prev : number))
    const key = partKeyOfNumber(parts, number)
    if (key) setExpanded((prev) => (prev.has(key) ? prev : new Set(prev).add(key)))
  }, [parts])

  useEffect(() => {
    if (!paper || !started || disabled) return
    const nodes = nodesRef.current
    const visible = new Set<number>()
    const observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        const number = Number((entry.target as HTMLElement).dataset.q)
        if (Number.isNaN(number)) continue
        if (entry.isIntersecting) visible.add(number)
        else visible.delete(number)
      }
      if (Date.now() < suppressSpyRef.current) return
      if (!visible.size) return
      const next = Math.min(...visible)
      setCurrent((prev) => (prev === next ? prev : next))
    }, { rootMargin: '-76px 0px -52% 0px', threshold: 0 })
    nodes.forEach((node) => observer.observe(node))
    return () => observer.disconnect()
  }, [paper, started, disabled])

  const jumpTo = useCallback((number: number) => {
    const element = nodesRef.current.get(number)
    suppressSpyRef.current = Date.now() + 1000
    setCurrent(number)
    const key = partKeyOfNumber(parts, number)
    if (key) setExpanded((prev) => (prev.has(key) ? prev : new Set(prev).add(key)))
    setDrawerOpen(false)
    if (!element) return
    element.scrollIntoView({ behavior: 'smooth', block: 'start' })
    window.setTimeout(() => {
      const target = element.querySelector<HTMLElement>(
        'textarea:not([disabled]), input:not([disabled]), button.qopt:not([disabled])',
      )
      target?.focus({ preventScroll: true })
    }, 420)
  }, [parts])

  const handleAnswer = useCallback((number: number, value: string) => {
    dirtyRef.current = true
    setAnswers((prev) => ({ ...prev, [number]: value }))
  }, [])

  const toggleMark = useCallback((number: number) => {
    dirtyRef.current = true
    setMarked((prev) => {
      const next = new Set(prev)
      if (next.has(number)) next.delete(number)
      else next.add(number)
      return next
    })
  }, [])

  const togglePart = useCallback((key: string) => {
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }, [])

  // ---- 交卷 ----
  const doSubmit = useCallback(async () => {
    if (!paper || submitting) return
    setSubmitting(true)
    setSubmitError(null)
    try {
      const response = await api.post<SubmitResponse>(`/api/offerings/${offeringId}/submissions`, {
        answers: buildAnswerList(paper.items, answers),
      })
      setSubmitResult(response)
      trackEvent('submit', { offeringId })
      setSubmitted(true)
      setLocked(true)
      setDialogOpen(false)
      clearDraft(offeringId)
    } catch (submitFailure) {
      if (submitFailure instanceof ApiError && submitFailure.status === 409) {
        setLocked(true)
        setDialogOpen(false)
      }
      setSubmitError((submitFailure as Error).message)
    } finally {
      setSubmitting(false)
    }
  }, [paper, submitting, offeringId, answers])

  const exitExam = useCallback(async () => {
    if (paper && started && !locked && !submitted && dirtyRef.current) {
      try {
        await saveNow(answers, marked)
      } catch {
        // 退出失败不拦人，草稿仍在本地
      }
    }
    navigate('/')
  }, [paper, started, locked, submitted, saveNow, answers, marked, navigate])

  // ---- 渲染 ----
  if (loading) {
    return <div className="exam-focus-page"><Loading label={t('exam.loadingLive')} /></div>
  }
  if (error) {
    return (
      <div className="exam-focus-page">
        <div className="exam-focus-card">
          <ErrorState error={error} />
          <button type="button" className="btn" style={{ marginTop: 12 }} onClick={() => navigate('/')}>
            {t('exam.backHome')}
          </button>
        </div>
      </div>
    )
  }
  if (submitted) {
    return (
      <ExamResult
        title={paper?.title ?? ''}
        attempt={latestAttempt}
        result={submitResult}
        totalPoints={paper?.totalPoints ?? submitResult?.totalPoints ?? 0}
        autoSubmitted={autoSubmitted}
        onHome={() => navigate('/')}
        onFeedback={() => navigate(`/feedback/${offeringId}`)}
      />
    )
  }
  if (!started) {
    return (
      <div className="exam-focus-page">
        <div className="exam-focus-card">
          <div className="card">
            <h1>{paper?.title ?? t('exam.liveTitle')}</h1>
            <div className="sub">{t('exam.notStartedSub')}</div>
            <div className="inline-note">{t('exam.notStartedNote')}</div>
            <div className="row">
              <button type="button" className="btn pri" onClick={() => navigate(`/exams/${offeringId}`)}>
                {t('exam.goEntry')}
              </button>
              <button type="button" className="btn" onClick={() => navigate('/')}>{t('exam.backHome')}</button>
            </div>
          </div>
        </div>
      </div>
    )
  }

  const warn = remaining !== null && remaining <= 300
  const timeUp = locked && !submitted
  const title = paper?.title ?? t('exam.liveTitle')

  return (
    <div className="exam-focus">
      <div className="exam-bar">
        <button
          type="button"
          className="btn sm exam-tree-toggle"
          aria-expanded={drawerOpen}
          onClick={() => setDrawerOpen((prev) => !prev)}
        >
          {t('exam.treeButton', { done, total })}
        </button>
        <div className="exam-bar-title" title={title}>{title}</div>
        <span className={`tag remaining${warn && !timeUp ? ' warn' : ''}${timeUp ? ' over' : ''}`}>
          {timeUp
            ? t('exam.timeUp')
            : remaining !== null
              ? t('exam.remaining', { time: fmtDuration(remaining) })
              : t('exam.remainingUnknown')}
        </span>
        <span className="small muted">{t('exam.progress', { done, total })}</span>
        <div className="spacer" />
        <span className="save-state">
          {saving ? t('exam.saving') : savedAt ? t('exam.savedAt', { time: fmtTimeOfDay(savedAt) }) : ''}
        </span>
        <button
          type="button"
          className={`btn sm q-mark-btn${marked.has(current) ? ' on' : ''}`}
          disabled={disabled}
          onClick={() => toggleMark(current)}
        >
          {marked.has(current) ? t('exam.unmark') : t('exam.mark')}
        </button>
        <button type="button" className="btn sm pri" disabled={disabled || submitting} onClick={() => setDialogOpen(true)}>
          {t('exam.submit')}
        </button>
        <button type="button" className="btn sm" onClick={() => void exitExam()}>{t('exam.exit')}</button>
      </div>

      <div className="exam-live-wrap">
        {restoredNote && <div className="exam-banner info">{t('exam.restoredDraft')}</div>}
        {saveError && <div className="exam-banner danger">{t('exam.saveFailed', { message: saveError })}</div>}
        {timeUp && (
          <div className="exam-banner danger">
            {autoSubmitted || session?.autoSubmitted ? t('exam.timeUpConfirmed') : t('exam.timeUpBanner')}
          </div>
        )}

        <div className="exam-layout">
          <ExamTree
            title={title}
            parts={parts}
            answers={answers}
            marked={marked}
            current={current}
            expanded={expanded}
            open={drawerOpen}
            done={done}
            total={total}
            onTogglePart={togglePart}
            onJump={jumpTo}
          />
          {drawerOpen && (
            <div className="exam-drawer-backdrop" role="presentation" onClick={() => setDrawerOpen(false)} />
          )}
          <div>
            <ExamPaper
              parts={parts}
              answers={answers}
              marked={marked}
              current={current}
              disabled={disabled}
              onChange={handleAnswer}
              onToggleMark={toggleMark}
              onFocusQuestion={focusQuestion}
              registerNode={registerNode}
            />
            <div className="exam-submit-bar">
              <span className="save-state">
                {saving ? t('exam.saving') : savedAt ? t('exam.savedAt', { time: fmtTimeOfDay(savedAt) }) : ''}
              </span>
              <button type="button" className="btn pri" disabled={disabled || submitting} onClick={() => setDialogOpen(true)}>
                {t('exam.submit')}
              </button>
            </div>
          </div>
        </div>
      </div>

      {dialogOpen && (
        <SubmitCheckDialog
          total={total}
          missing={missing}
          marked={markedList}
          parts={parts}
          submitting={submitting}
          error={submitError}
          onJump={(number) => {
            setDialogOpen(false)
            setSubmitError(null)
            jumpTo(number)
          }}
          onClose={() => {
            if (!submitting) {
              setDialogOpen(false)
              setSubmitError(null)
            }
          }}
          onConfirm={() => void doSubmit()}
        />
      )}
    </div>
  )
}
