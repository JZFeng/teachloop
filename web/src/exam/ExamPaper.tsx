import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ExamPaperItem } from '../api/types'
import {
  countWrittenWords,
  isBlankAnswer,
  lettersOf,
  parseOptions,
  toleranceFor,
  toggleLetter,
  type ExamPart,
  type ExamOption,
  wordLimitFor,
} from './examUtils'

interface ExamPaperProps {
  parts: ExamPart[]
  answers: Record<string, string>
  marked: Set<number>
  current: number
  disabled: boolean
  onChange: (number: number, value: string) => void
  onToggleMark: (number: number) => void
  onFocusQuestion: (number: number) => void
  registerNode: (number: number, element: HTMLElement | null) => void
}

/** 整卷作答本体（T187）：单一阅读流，作答控件内嵌在题目里。 */
export function ExamPaper({
  parts, answers, marked, current, disabled, onChange, onToggleMark, onFocusQuestion, registerNode,
}: ExamPaperProps) {
  return (
    <div className="exam-paper">
      {parts.map((part) => (
        <section className="exam-part" key={part.key} aria-label={part.title}>
          <h2 className="exam-part-title">
            {part.title}
            <span className="part-points">{part.items.reduce((sum, item) => sum + item.points, 0)} 分</span>
          </h2>
          {part.items.map((item) => (
            <QuestionCard
              key={item.number}
              item={item}
              value={answers[item.number] ?? ''}
              marked={marked.has(item.number)}
              current={current === item.number}
              disabled={disabled}
              onChange={onChange}
              onToggleMark={onToggleMark}
              onFocusQuestion={onFocusQuestion}
              registerNode={registerNode}
            />
          ))}
        </section>
      ))}
    </div>
  )
}

interface QuestionCardProps {
  item: ExamPaperItem
  value: string
  marked: boolean
  current: boolean
  disabled: boolean
  onChange: (number: number, value: string) => void
  onToggleMark: (number: number) => void
  onFocusQuestion: (number: number) => void
  registerNode: (number: number, element: HTMLElement | null) => void
}

function QuestionCard({
  item, value, marked, current, disabled, onChange, onToggleMark, onFocusQuestion, registerNode,
}: QuestionCardProps) {
  const { t } = useTranslation()
  const answered = !isBlankAnswer(item.questionType, value)
  const options = parseOptions(item.options)
  const hasOptions = options.length > 0
  const multi = item.questionType === 'MULTIPLE_CHOICE'
  const choiceLike = hasOptions && (item.questionType === 'SINGLE_CHOICE'
    || item.questionType === 'MULTIPLE_CHOICE' || item.questionType === 'FILL_BLANK')
  const selected = lettersOf(value)

  const pick = (letter: string) => {
    if (disabled) return
    onChange(item.number, multi ? toggleLetter(value, letter) : letter)
  }

  return (
    <div
      id={`q-${item.number}`}
      data-q={item.number}
      ref={(el) => registerNode(item.number, el)}
      className={`exam-question${current ? ' current' : ''}${answered ? ' answered' : ''}${marked ? ' marked' : ''}`}
    >
      <div className="q-head">
        <span className="q-number" aria-hidden="true">{item.number}</span>
        <div className="q-body">
          <p className="exam-stem">
            {item.stem?.trim() ? item.stem : t('exam.noStem')}
            {multi && <span className="small muted"> {t('exam.multiHint')}</span>}
          </p>
          <div className="q-meta">
            <span className="small muted">{t('exam.points', { points: item.points })}</span>
            {item.questionType === 'ESSAY' && (
              <span className="small muted">· {t('exam.essayLimit', { limit: wordLimitFor(item.points) })}</span>
            )}
          </div>

          {choiceLike && (
            <div className={`qopts${options.some((o) => o.text.length > 22) ? ' stack' : ''}`} role={multi ? 'group' : 'radiogroup'}>
              {options.map((option: ExamOption) => {
                const on = multi ? selected.includes(option.letter) : value.trim().toUpperCase() === option.letter
                return (
                  <button
                    type="button"
                    key={option.letter}
                    className={`qopt${multi ? ' cb' : ''}${on ? ' on' : ''}`}
                    aria-pressed={on}
                    disabled={disabled}
                    onClick={() => pick(option.letter)}
                    onFocus={() => onFocusQuestion(item.number)}
                  >
                    <b>{option.letter}</b>
                    <span>{option.text}</span>
                  </button>
                )
              })}
            </div>
          )}

          {!choiceLike && item.questionType !== 'ESSAY' && (
            <input
              className={`blank-input${value.trim() ? ' filled' : ''}`}
              value={value}
              disabled={disabled}
              maxLength={200}
              placeholder={t('exam.blankPlaceholder', { number: item.number })}
              onChange={(event) => onChange(item.number, event.target.value)}
              onFocus={() => onFocusQuestion(item.number)}
            />
          )}

          {item.questionType === 'ESSAY' && (
            <EssayAnswer
              value={value}
              disabled={disabled}
              limit={wordLimitFor(item.points)}
              onChange={(next) => onChange(item.number, next)}
              onFocus={() => onFocusQuestion(item.number)}
            />
          )}

          <div className="q-foot">
            <button
              type="button"
              className={`btn sm q-mark-btn${marked ? ' on' : ''}`}
              aria-pressed={marked}
              disabled={disabled}
              onClick={() => onToggleMark(item.number)}
            >
              {marked ? t('exam.unmark') : t('exam.mark')}
            </button>
            {answered && <span className="small muted">{t('exam.answeredBadge')}</span>}
          </div>
        </div>
      </div>
    </div>
  )
}

interface EssayAnswerProps {
  value: string
  disabled: boolean
  limit: number
  onChange: (value: string) => void
  onFocus: () => void
}

/**
 * 写作题文本域（T197 / AC-21.12）：高度 = ⌈word_limit × 1.2 ÷ 每行字数⌉ 行，
 * 每行字数按容器宽度实算（约 width / 8.4），并夹在 6 行与 70vh 之间；宽度变化重算。
 */
function EssayAnswer({ value, disabled, limit, onChange, onFocus }: EssayAnswerProps) {
  const { t } = useTranslation()
  const wrapRef = useRef<HTMLDivElement>(null)
  const areaRef = useRef<HTMLTextAreaElement>(null)
  const [rows, setRows] = useState(6)

  const measure = useCallback(() => {
    const area = areaRef.current
    if (!area) return
    const style = window.getComputedStyle(area)
    const width = area.clientWidth || wrapRef.current?.clientWidth || 620
    const padding = Number.parseFloat(style.paddingLeft) + Number.parseFloat(style.paddingRight)
    const usable = Math.max(180, width - (Number.isNaN(padding) ? 0 : padding))
    const perLine = Math.max(12, usable / 8.4)
    const fontSize = Number.parseFloat(style.fontSize) || 14
    const lineHeight = Number.parseFloat(style.lineHeight) || fontSize * 1.85
    const maxRows = Math.max(6, Math.floor((window.innerHeight * 0.7) / lineHeight))
    const wanted = Math.ceil((limit * 1.2) / perLine)
    setRows(Math.min(maxRows, Math.max(6, wanted)))
  }, [limit])

  useEffect(() => {
    measure()
    const wrap = wrapRef.current
    const area = areaRef.current
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(() => measure())
    if (wrap) observer?.observe(wrap)
    window.addEventListener('resize', measure)
    // 字体/主题切换会改变每行字数，用 area 的尺寸变化兜底
    if (area) observer?.observe(area)
    return () => {
      observer?.disconnect()
      window.removeEventListener('resize', measure)
    }
  }, [measure])

  const words = countWrittenWords(value)
  const tolerance = toleranceFor(limit)
  const inRange = words >= limit - tolerance && words <= limit + tolerance
  const over = words > limit + tolerance

  return (
    <div className="essay-wrap" ref={wrapRef}>
      <textarea
        ref={areaRef}
        className={`writearea${value.trim() ? ' filled' : ''}`}
        rows={rows}
        value={value}
        disabled={disabled}
        spellCheck={false}
        placeholder={t('exam.essayPlaceholder', { limit })}
        onChange={(event) => onChange(event.target.value)}
        onFocus={onFocus}
      />
      <div className={`wcount${inRange ? ' ok' : ''}${over ? ' over' : ''}`}>
        <span className="wn">{t('exam.essayCount', { count: words, limit })}</span>
        {!inRange && (
          <span>· {t('exam.essayTolerance', { min: limit - tolerance, max: limit + tolerance })}</span>
        )}
      </div>
    </div>
  )
}
