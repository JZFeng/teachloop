import { useTranslation } from 'react-i18next'
import { useEscapeClose } from '../ui'
import type { ExamPart } from './examUtils'

interface SubmitCheckDialogProps {
  total: number
  missing: number[]
  marked: number[]
  parts: ExamPart[]
  submitting: boolean
  error: string | null
  onJump: (number: number) => void
  onClose: () => void
  onConfirm: () => void
}

/** T195 / AC-21.6：提交前未答检查；按部分分组列出未答与已标记题号，未答不阻止提交。 */
export function SubmitCheckDialog({
  total, missing, marked, parts, submitting, error, onJump, onClose, onConfirm,
}: SubmitCheckDialogProps) {
  const { t } = useTranslation()

  // T354：弹层统一走弹层栈；提交中不接受关闭，但仍由这层拦住 Esc。
  useEscapeClose(true, onClose, { canClose: () => !submitting })

  const groups = parts
    .map((part) => ({
      part,
      numbers: part.items
        .filter((item) => missing.includes(item.number))
        .map((item) => item.number),
    }))
    .filter((group) => group.numbers.length > 0)

  const allAnswered = missing.length === 0

  return (
    <div className="exam-modal" role="presentation" onClick={() => { if (!submitting) onClose() }}>
      <div
        className="exam-modal-box"
        role="dialog"
        aria-modal="true"
        aria-label={t('exam.checkDialog')}
        onClick={(event) => event.stopPropagation()}
      >
        <h3>
          {allAnswered
            ? t('exam.checkAllDone', { total })
            : t('exam.checkTitle', { count: missing.length })}
          {marked.length > 0 && <span className="small muted"> · {t('exam.checkMarked', { count: marked.length })}</span>}
        </h3>
        <p className="small muted">
          {allAnswered ? t('exam.checkAllHint') : t('exam.checkHint')}
        </p>

        {!allAnswered && (
          <div className="mgroups">
            {groups.map((group) => (
              <div key={group.part.key}>
                <div className="mgroup-title">
                  {group.part.title} · {t('exam.checkGroupCount', { count: group.numbers.length })}
                </div>
                <div className="mchips">
                  {group.numbers.map((number) => (
                    <button
                      type="button"
                      key={number}
                      className={`mchip${marked.includes(number) ? ' mk' : ''}`}
                      onClick={() => onJump(number)}
                    >
                      {number}
                    </button>
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}

        {marked.length > 0 && (
          <div className="mgroups">
            <div className="mgroup-title">{t('exam.checkMarkedTitle')}</div>
            <div className="mchips">
              {marked.map((number) => (
                <button type="button" key={number} className="mchip mk" onClick={() => onJump(number)}>
                  {number}
                </button>
              ))}
            </div>
          </div>
        )}

        {error && <div className="inline-note" style={{ marginTop: 14 }}>{error}</div>}

        <div className="mfoot">
          <button type="button" className="btn sm" disabled={submitting} onClick={onClose}>
            {allAnswered ? t('exam.keepChecking') : t('exam.keepAnswering')}
          </button>
          <button type="button" className="btn sm pri" disabled={submitting} onClick={onConfirm}>
            {submitting ? t('exam.submitting') : allAnswered ? t('exam.confirmSubmit') : t('exam.submitAnyway')}
          </button>
        </div>
      </div>
    </div>
  )
}
