import { useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import type { ExamPaperItem } from '../api/types'
import { isBlankAnswer, type ExamPart } from './examUtils'

interface ExamTreeProps {
  title: string
  parts: ExamPart[]
  answers: Record<string, string>
  marked: Set<number>
  current: number
  expanded: Set<string>
  open: boolean
  done: number
  total: number
  onTogglePart: (key: string) => void
  onJump: (number: number) => void
}

/** 试卷目录树（T188/T189）：两级（部分 → 题目），题目节点带作答状态。 */
export function ExamTree({
  title, parts, answers, marked, current, expanded, open, done, total, onTogglePart, onJump,
}: ExamTreeProps) {
  const { t } = useTranslation()
  const scrollRef = useRef<HTMLDivElement>(null)

  // 保持当前高亮节点在树的可视区内（AC-21.4）
  useEffect(() => {
    const container = scrollRef.current
    if (!container) return
    const node = container.querySelector<HTMLElement>(`[data-tree-q="${current}"]`)
    if (!node) return
    const box = container.getBoundingClientRect()
    const rect = node.getBoundingClientRect()
    if (rect.top < box.top + 4) container.scrollTop += rect.top - box.top - 8
    else if (rect.bottom > box.bottom - 4) container.scrollTop += rect.bottom - box.bottom + 8
  }, [current, expanded])

  return (
    <aside className={`exam-tree${open ? ' open' : ''}`} aria-label={t('exam.tree')}>
      <div className="tree-head">
        <b title={title}>{title}</b>
        <div className="prog">{t('exam.treeProgress', { done, total, marked: marked.size })}</div>
      </div>
      <div className="tree-scroll" ref={scrollRef}>
        {parts.map((part) => {
          const partDone = part.items.filter((item) => !isBlankAnswer(item.questionType, answers[item.number])).length
          const allDone = part.items.length > 0 && partDone === part.items.length
          const isOpen = expanded.has(part.key)
          return (
            <div className="tree-part-wrap" key={part.key}>
              <button
                type="button"
                className={`tree-part${allDone ? ' done' : ''}`}
                aria-expanded={isOpen}
                onClick={() => onTogglePart(part.key)}
              >
                <span className="tree-caret">{isOpen ? '▾' : '▸'}</span>
                <span className="tree-part-title">{part.title}</span>
                <span className="tree-part-count">
                  {t('exam.partProgress', { done: partDone, total: part.items.length })}
                </span>
              </button>
              {isOpen && (
                <div className="tree-kids">
                  {part.items.map((item: ExamPaperItem) => {
                    const answered = !isBlankAnswer(item.questionType, answers[item.number])
                    const isMarked = marked.has(item.number)
                    return (
                      <button
                        type="button"
                        key={item.number}
                        data-tree-q={item.number}
                        className={`tree-item${answered ? ' done' : ''}${isMarked ? ' marked' : ''}${current === item.number ? ' current' : ''}`}
                        aria-current={current === item.number ? 'true' : undefined}
                        title={t(answered ? 'exam.treeAnswered' : 'exam.treeUnanswered', { number: item.number })}
                        onClick={() => onJump(item.number)}
                      >
                        <span className="dot" />
                        {item.number}
                      </button>
                    )
                  })}
                </div>
              )}
            </div>
          )
        })}
      </div>
    </aside>
  )
}
