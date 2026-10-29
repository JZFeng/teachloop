import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Button, CheckOutlined, CloseOutlined, Empty, ErrorState, Loading, PageHeader, Progress } from '../ui'
import { getQuiz, retryWrong, submitQuiz, createQuiz, type Quiz, type QuizItem } from './quiz'
import './vocab.css'

/**
 * 复习小测（US-87 / T550）。
 *
 * <p>一条路由两种形态：`IN_PROGRESS` 是整卷（一屏列全部题 + 底部固定条），
 * `SUBMITTED` 是结果（得分 + 逐题解析 + 三个后续动作）。这样「交卷」不需要跳页，
 * 刷新也不会丢状态。
 *
 * <p>为什么是整卷而不是一屏一题：复习场景 60% 以上的词已经过了，逐题翻页比答题还慢；
 * 而且整卷天然支持「交卷前不显示对错」——一屏一题的解析会泄露后面的答案。
 */
export default function VocabQuizPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { id } = useParams<{ id: string }>()
  const [quiz, setQuiz] = useState<Quiz | null>(null)
  const [answers, setAnswers] = useState<Record<string, string>>({})
  const [error, setError] = useState<Error | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(() => {
    if (!id) return
    setError(null)
    getQuiz(id).then(setQuiz).catch((loadError) => setError(loadError as Error))
  }, [id])

  useEffect(load, [load])

  const done = useMemo(
    () => Object.values(answers).filter((value) => value.trim().length > 0).length,
    [answers],
  )

  if (error) return <ErrorState error={error} onRetry={load} />
  if (!quiz) return <Loading label={t('common.loading')} />

  const submitted = quiz.status === 'SUBMITTED'
  const wrong = quiz.items.filter((item) => item.correct === false)

  const submit = async () => {
    setBusy(true)
    try {
      const result = await submitQuiz(quiz.id, quiz.items.map((item) => ({
        itemId: item.id,
        given: answers[item.id] ?? '',
      })))
      setQuiz(result)
      window.scrollTo(0, 0)
    } catch (submitError) {
      setError(submitError as Error)
    } finally {
      setBusy(false)
    }
  }

  const retry = async () => {
    setBusy(true)
    try {
      const next = await retryWrong(quiz.id)
      navigate(`/vocab/quiz/${next.id}`)
    } catch (retryError) {
      setError(retryError as Error)
    } finally {
      setBusy(false)
    }
  }

  const again = async () => {
    setBusy(true)
    try {
      const next = quiz.kind === 'SESSION'
        ? await createQuiz('SESSION', quiz.sourceSessionId ?? undefined)
        : await createQuiz('TODAY')
      navigate(`/vocab/quiz/${next.id}`)
    } catch (againError) {
      setError(againError as Error)
    } finally {
      setBusy(false)
    }
  }

  if (quiz.items.length === 0) {
    return (
      <>
        <PageHeader title={t('vocab.quiz.title')} />
        <Empty>{t('vocab.quiz.tooFew')}</Empty>
      </>
    )
  }

  if (submitted) {
    return (
      <>
        <PageHeader
          title={t('vocab.quiz.resultTitle')}
          sub={t('vocab.quiz.resultSub', { total: quiz.itemCount })}
        />
        <div className="quiz-stage">
          <div className="card quiz-result">
            <div className="quiz-score">
              <span className="quiz-score-big">{quiz.correctCount ?? 0}</span>
              <span className="quiz-score-of">{t('vocab.quiz.scoreOf', { total: quiz.itemCount })}</span>
            </div>
            <p className="muted small">{t('vocab.quiz.resultHint')}</p>

            <div className="quiz-review-list">
              {quiz.items.map((item) => (
                <ResultRow key={item.id} item={item} index={item.position + 1} />
              ))}
            </div>

            <div className="quiz-actions">
              {wrong.length > 0 && (
                <Button variant="primary" loading={busy} onClick={() => void retry()}>
                  {t('vocab.quiz.retryWrong')}
                </Button>
              )}
              <Button loading={busy} onClick={() => void again()}>{t('vocab.quiz.again')}</Button>
              <Button onClick={() => navigate('/vocab/review')}>{t('vocab.quiz.toToday')}</Button>
            </div>
          </div>
        </div>
      </>
    )
  }

  return (
    <>
      <PageHeader
        title={t('vocab.quiz.title')}
        sub={t('vocab.quiz.subtitle', { count: quiz.itemCount })}
      />
      <div className="quiz-stage">
        <div className="card quiz-paper">
          {quiz.items.map((item) => (
            <div className="quiz-row" key={item.id}>
              <div className="quiz-row-no">{item.position + 1}</div>
              <div className="quiz-row-body">
                <div className="quiz-stem">
                  {item.stem.split('____').map((part, index) => (
                    <span key={index}>
                      {index > 0 && <span className="quiz-blank" />}
                      {part}
                    </span>
                  ))}
                </div>
                {item.type === 'SINGLE_CHOICE' ? (
                  <div className="quiz-options">
                    {item.options.map((option) => (
                      <Button
                        key={option.key}
                        size="small"
                        variant={answers[item.id] === option.key ? 'primary' : 'default'}
                        onClick={() => setAnswers((prev) => ({ ...prev, [item.id]: option.key }))}
                      >
                        <span className="quiz-option-key">{option.key}</span>
                        {option.text}
                      </Button>
                    ))}
                  </div>
                ) : (
                  <input
                    className="quiz-fill"
                    value={answers[item.id] ?? ''}
                    placeholder={t('vocab.quiz.fillPlaceholder')}
                    onChange={(event) => setAnswers((prev) => ({ ...prev, [item.id]: event.target.value }))}
                  />
                )}
              </div>
            </div>
          ))}
        </div>

        <div className="quiz-bar">
          <span className="quiz-count">{t('vocab.quiz.progress', { done, total: quiz.itemCount })}</span>
          <Progress value={done} max={quiz.itemCount} />
          {done < quiz.itemCount && (
            <span className="quiz-warn">{t('vocab.quiz.unanswered', { count: quiz.itemCount - done })}</span>
          )}
          <span className="quiz-bar-spacer" />
          <Button variant="text" onClick={() => navigate('/vocab/review')}>{t('vocab.quiz.skip')}</Button>
          <Button variant="primary" loading={busy} onClick={() => void submit()}>
            {t('vocab.quiz.submit')}
          </Button>
        </div>
      </div>
    </>
  )
}

function ResultRow({ item, index }: { item: QuizItem; index: number }) {
  const { t } = useTranslation()
  const ok = item.correct === true
  return (
    <div className="quiz-review-row">
      <span className={`quiz-mark ${ok ? 'is-ok' : 'is-no'}`}>
        {ok ? <CheckOutlined /> : <CloseOutlined />}
      </span>
      <div>
        <p className="quiz-review-stem">
          {index}. {item.stem.split('____').map((part, i) => (
            <span key={i}>
              {i > 0 && <b>{item.answer}</b>}
              {part}
            </span>
          ))}
        </p>
        <div className="quiz-review-meta">
          {item.type === 'SINGLE_CHOICE' ? t('vocab.quiz.youPicked') : t('vocab.quiz.youFilled')}
          {' '}
          {item.given ? item.given : t('vocab.quiz.blank')}
          {' · '}
          {ok ? t('vocab.quiz.correct') : `${t('vocab.quiz.answerLabel')} ${item.answer}`}
        </div>
      </div>
    </div>
  )
}
