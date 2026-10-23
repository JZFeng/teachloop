import { useTranslation } from 'react-i18next'
import { summarizeRubric } from './rubric'

export interface RubricPreviewProps {
  rubric: string
  /** 作业总分；NaN / 0 / 不传时显示「—」，作业页的原行为。 */
  totalPoints?: number
  /**
   * 是否判断「合计 = 总分」。
   * 作业页需要（提交会被后端拒绝）；试卷级 rubric 不需要（它可能只是主观题评分项，
   * 合计不等于试卷总分），传 false 后只展示解析结果。
   */
  showTotalCheck?: boolean
  /** 已翻译的标题；默认沿用作业页的「AI 将看到」。 */
  title?: string
}

/**
 * 「AI 将看到」：按后端同一套逐行规则解析出的评分项 + 分值 + 合计。
 *
 * T340：没有分值的分组标题/说明不算评分项，只提示数量。
 * T341：从 AssignmentCreatePage 抽出来，试卷向导复用（那里不判断总分）。
 */
export function RubricPreview({ rubric, totalPoints, showTotalCheck = true, title }: RubricPreviewProps) {
  const { t } = useTranslation('translation')
  const summary = summarizeRubric(rubric)
  const expected = Number.isFinite(totalPoints) && (totalPoints ?? 0) > 0 ? (totalPoints as number) : null
  const matched = expected !== null && !summary.missingPoints && Math.abs(summary.total - expected) < 0.01
  const heading = title ?? t('teacher.assignmentCreate.aiPreview')

  return (
    <div className="rubric-preview">
      <div className="rubric-preview-head">
        <b>{heading}</b>
        {summary.criteria.length > 0 && (
          <span className="small muted">
            {showTotalCheck
              ? t('teacher.assignmentCreate.aiPreviewSum', {
                  total: summary.total,
                  expected: expected ?? '—',
                })
              : t('teacher.wizard.rubricPreviewTotal', { total: summary.total })}
          </span>
        )}
      </div>
      {summary.criteria.length === 0 ? (
        <div className="small muted">{t('teacher.assignmentCreate.aiPreviewEmpty')}</div>
      ) : (
        <>
          <ol className="rubric-preview-list">
            {summary.criteria.map((criterion, index) => (
              <li key={`${criterion.name}-${index}`}>
                <span>{criterion.name}</span>
                <span className="mono">{criterion.points ?? '—'}</span>
              </li>
            ))}
          </ol>
          {showTotalCheck ? (
            <div className="small muted">
              {summary.missingPoints
                ? t('teacher.assignmentCreate.aiPreviewMissingPoints')
                : !summary.anyPoints
                  ? t('teacher.assignmentCreate.aiPreviewEvenSplit')
                  : matched
                    ? t('teacher.assignmentCreate.aiPreviewMatched')
                    : t('teacher.assignmentCreate.aiPreviewMismatch', { expected: expected ?? '—' })}
            </div>
          ) : (
            <div className="small muted">{t('teacher.wizard.rubricPreviewHint')}</div>
          )}
          {summary.ignored.length > 0 ? (
            <div
              className="small muted rubric-preview-note"
              title={summary.ignored.map((criterion) => criterion.name).join('\n')}
            >
              {t('teacher.assignmentCreate.aiPreviewIgnored', { count: summary.ignored.length })}
            </div>
          ) : null}
        </>
      )}
    </div>
  )
}
