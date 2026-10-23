import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ExamPaperItem } from '../api/types'
import { FlagOutlined, Popover, useEscapeClose } from '../ui'
import {
  buildPaperNodes,
  countWrittenWords,
  inlineChoice,
  isBlankAnswer,
  lettersOf,
  parseOptions,
  toleranceFor,
  toggleLetter,
  type BlankMember,
  type ExamPart,
  type ExamOption,
  type PaperNode,
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

/** 整卷作答本体（T187 / T321）：单一阅读流，材料只出现一次，作答控件内嵌在题目/材料里。 */
export function ExamPaper({
  parts, answers, marked, current, disabled, onChange, onToggleMark, onFocusQuestion, registerNode,
}: ExamPaperProps) {
  const { t } = useTranslation('translation')
  return (
    <div className="exam-paper">
      {parts.map((part) => (
        <section className="exam-part" key={part.key} aria-label={part.title}>
          <h2 className="exam-part-title">
            {part.title}
            <span className="part-points">{t('exam.partPoints', { points: part.items.reduce((sum, item) => sum + item.points, 0) })}</span>
          </h2>
          <PartItems
            part={part}
            answers={answers}
            marked={marked}
            current={current}
            disabled={disabled}
            onChange={onChange}
            onToggleMark={onToggleMark}
            onFocusQuestion={onFocusQuestion}
            registerNode={registerNode}
          />
        </section>
      ))}
    </div>
  )
}

interface PartItemsProps {
  part: ExamPart
  answers: Record<string, string>
  marked: Set<number>
  current: number
  disabled: boolean
  onChange: (number: number, value: string) => void
  onToggleMark: (number: number) => void
  onFocusQuestion: (number: number) => void
  registerNode: (number: number, element: HTMLElement | null) => void
}

function PartItems({ part, ...rest }: PartItemsProps) {
  const nodes = useMemo(() => buildPaperNodes(part.items), [part.items])
  return (
    <>
      {nodes.map((node) => {
        if (node.kind === 'solo') {
          return (
            <QuestionCard
              key={`solo-${node.item.number}`}
              item={node.item}
              {...rest}
            />
          )
        }
        if (node.kind === 'reading') {
          return (
            <ReadingGroup
              key={`read-${node.key}`}
              node={node}
              {...rest}
            />
          )
        }
        return (
          <BlankGroup
            key={`blank-${node.key}`}
            node={node}
            {...rest}
          />
        )
      })}
    </>
  )
}

interface GroupProps extends Omit<PartItemsProps, 'part'> {
  node: Extract<PaperNode, { kind: 'reading' | 'blank' }>
}

function ReadingGroup({ node, ...rest }: GroupProps & { node: Extract<PaperNode, { kind: 'reading' }> }) {
  const { t } = useTranslation()
  const from = node.items[0].number
  const to = node.items[node.items.length - 1].number
  return (
    <>
      <div className="exam-material">
        <div className="exam-material-head">
          <span className="exam-material-label">{t('exam.material')}</span>
          <span className="exam-material-range">{t('exam.materialShared', { from, to })}</span>
        </div>
        <div className="exam-material-body reading">{node.material}</div>
      </div>
      {node.items.map((item) => (
        <QuestionCard
          key={`read-q-${item.number}`}
          item={item}
          stem={item.questionStem ?? item.stem}
          groupKey={node.key}
          {...rest}
        />
      ))}
    </>
  )
}

function BlankGroup({ node, ...rest }: GroupProps & { node: Extract<PaperNode, { kind: 'blank' }> }) {
  const { t } = useTranslation()
  const [open, setOpen] = useState<number | null>(null)
  const from = node.items[0].item.number
  const to = node.items[node.items.length - 1].item.number
  const choice = inlineChoice(node.items[0].item)
  // T355：按空位序号落位；材料里的空可以比本卷的题多（七选五只导入其中几题）。
  const byBlank = new Map(node.items.map((member) => [member.blankIndex, member]))
  let cursor = 0
  return (
    <div className="exam-material-group" data-group={node.key}>
      <div className="exam-material">
        <div className="exam-material-head">
          <span className="exam-material-label">{t('exam.material')}</span>
          <span className="exam-material-range">{t('exam.materialShared', { from, to })}</span>
        </div>
        <div className="exam-material-hint">
          {choice ? t('exam.materialChoiceHint') : t('exam.materialFillHint')}
        </div>
        <div className="exam-material-body inline">
          {node.material.split(/(_{3,})/).map((chunk, index) => {
            if (!/^_{3,}$/.test(chunk)) return <span key={`t-${index}`}>{chunk}</span>
            const blankIndex = cursor
            cursor += 1
            const member = byBlank.get(blankIndex)
            // 原文里有、本卷却没有对应题目的空：保留横线，不生成作答控件。
            if (!member) {
              return <span className="inline-blank-gap" key={`gap-${index}`} aria-hidden="true">{chunk}</span>
            }
            return (
              <InlineBlank
                key={`b-${index}-${member.item.number}`}
                member={member}
                choice={choice}
                open={open === member.item.number}
                setOpen={setOpen}
                {...rest}
              />
            )
          })}
        </div>
      </div>
    </div>
  )
}

interface InlineBlankProps extends Omit<PartItemsProps, 'part'> {
  member: BlankMember
  choice: boolean
  open: boolean
  setOpen: (number: number | null) => void
}

function InlineBlank({
  member, choice, open, setOpen, answers, marked, current, disabled,
  onChange, onToggleMark, onFocusQuestion, registerNode,
}: InlineBlankProps) {
  const { t } = useTranslation()
  const item = member.item
  const number = item.number
  const value = answers[number] ?? ''
  const isMarked = marked.has(number)
  const isCurrent = current === number

  // T354：完形/七选五的空会弹 A–G 选项，这也是一层弹层，Esc 收起。
  useEscapeClose(open && !disabled, () => setOpen(null))

  if (!choice) {
    return (
      <span
        className={`inline-fill-wrap${isCurrent ? ' current' : ''}${isMarked ? ' marked' : ''}`}
        data-q={number}
        ref={(element) => registerNode(number, element)}
      >
        <span className="inline-blank-no">{number}</span>
        <input
          className={`inline-fill${value.trim() ? ' filled' : ''}`}
          value={value}
          disabled={disabled}
          maxLength={200}
          size={10}
          aria-label={t('exam.blankFill', { number })}
          onChange={(event) => onChange(number, event.target.value)}
          onFocus={() => onFocusQuestion(number)}
        />
        <button
          type="button"
          className={`inline-flag${isMarked ? ' on' : ''}`}
          disabled={disabled}
          aria-pressed={isMarked}
          aria-label={isMarked ? t('exam.unmarkThis', { number }) : t('exam.markThis', { number })}
          title={isMarked ? t('exam.unmarkThis', { number }) : t('exam.markThis', { number })}
          onClick={() => onToggleMark(number)}
        >
          <FlagOutlined />
        </button>
      </span>
    )
  }

  const options = parseOptions(item.options)
  const selected = value.trim().toUpperCase()
  return (
    <span
      className={`inline-blank-slot${isCurrent ? ' current' : ''}${isMarked ? ' marked' : ''}`}
      data-q={number}
      ref={(element) => registerNode(number, element)}
    >
      <Popover
        open={!disabled && open}
        onOpenChange={(next) => setOpen(next ? number : null)}
        trigger="click"
        placement="bottomLeft"
        arrow={false}
        content={(
          <div className="inline-pop">
            <div className="inline-pop-head">
              <span className="inline-pop-no">{number}</span>
              <span>{t('exam.points', { points: item.points })}</span>
            </div>
            <div className="inline-pop-opts" role="radiogroup" aria-label={t('exam.blankChoose', { number })}>
              {options.map((option) => (
                <button
                  type="button"
                  key={option.letter}
                  className={`inline-pop-opt${selected === option.letter ? ' on' : ''}`}
                  aria-pressed={selected === option.letter}
                  disabled={disabled}
                  onClick={() => {
                    onChange(number, option.letter)
                    setOpen(null)
                  }}
                >
                  <b>{option.letter}</b>
                  <span>{option.text}</span>
                </button>
              ))}
            </div>
            <div className="inline-pop-foot">
              <span className="inline-pop-hint">{t('exam.inlinePopHint')}</span>
              <button
                type="button"
                className={`inline-pop-mark${isMarked ? ' on' : ''}`}
                disabled={disabled}
                aria-pressed={isMarked}
                onClick={() => onToggleMark(number)}
              >
                <FlagOutlined />
                <span>{isMarked ? t('exam.unmark') : t('exam.mark')}</span>
              </button>
              <button
                type="button"
                className="inline-pop-clear"
                disabled={disabled}
                onClick={() => {
                  onChange(number, '')
                  setOpen(null)
                }}
              >
                {t('exam.clearAnswer')}
              </button>
            </div>
          </div>
        )}
      >
        <button
          type="button"
          className={`inline-blank${selected ? ' answered' : ''}${isMarked ? ' marked' : ''}${isCurrent ? ' current' : ''}`}
          disabled={disabled}
          aria-label={t('exam.blankChoose', { number })}
          aria-pressed={Boolean(selected)}
          onFocus={() => onFocusQuestion(number)}
        >
          <span className="inline-blank-no">{number}</span>
          <span className="inline-blank-value">{selected || '—'}</span>
        </button>
      </Popover>
    </span>
  )
}

interface QuestionCardProps extends Omit<PartItemsProps, 'part'> {
  item: ExamPaperItem
  stem?: string | null
  groupKey?: string
}

function QuestionCard({
  item, stem, groupKey, answers, marked, current, disabled, onChange, onToggleMark, onFocusQuestion, registerNode,
}: QuestionCardProps) {
  const { t } = useTranslation()
  const value = answers[item.number] ?? ''
  const answered = !isBlankAnswer(item.questionType, value)
  const options = parseOptions(item.options)
  const hasOptions = options.length > 0
  const multi = item.questionType === 'MULTIPLE_CHOICE'
  const choiceLike = hasOptions && (item.questionType === 'SINGLE_CHOICE'
    || item.questionType === 'MULTIPLE_CHOICE' || item.questionType === 'FILL_BLANK')
  const selected = lettersOf(value)
  const isMarked = marked.has(item.number)

  const pick = (letter: string) => {
    if (disabled) return
    onChange(item.number, multi ? toggleLetter(value, letter) : letter)
  }

  return (
    <div
      id={`q-${item.number}`}
      data-q={item.number}
      data-group={groupKey}
      ref={(element) => registerNode(item.number, element)}
      className={`exam-question${current === item.number ? ' current' : ''}${answered ? ' answered' : ''}${isMarked ? ' marked' : ''}`}
    >
      <div className="q-head">
        <span className="q-number" aria-hidden="true">{item.number}</span>
        <div className="q-body">
          <p className="exam-stem">
            {(stem ?? item.stem)?.trim() ? (stem ?? item.stem) : t('exam.noStem')}
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
              className={`btn sm q-mark-btn${isMarked ? ' on' : ''}`}
              aria-pressed={isMarked}
              disabled={disabled}
              onClick={() => onToggleMark(item.number)}
            >
              {isMarked ? t('exam.unmark') : t('exam.mark')}
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
