import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Button, InlineNote, Loading } from '../../ui'
import { emptyReview } from '../today'
import type { ReviewView } from '../today'

interface ReviewCardProps {
  /** 复习切片；这一段取数失败时为 null（后端把该段置空，整体仍 200）。 */
  review: ReviewView | null
  loading: boolean
  /** `partial.review === 'error'`（AC-94.2：只有这张卡报错，其余照常）。 */
  error: boolean
  onRetry: () => void
}

/**
 * 今日复习卡：大数字是待复习张数，主按钮进 `today-review`（`/vocab/review`）。
 *
 * 空词库不是「0 张待复习」的一句话，而是两个动作（AC-90.5 / FR-212）：
 * 没有词的人看着「0」并不知道下一步是什么，所以空态直接给「加词」「导入词表」。
 * 空态判定在 `emptyReview()`（纯函数，`web/tests/today-page.test.mjs` 测过），不在这里重写。
 */
export default function ReviewCard({ review, loading, error, onRetry }: ReviewCardProps) {
  const { t } = useTranslation()
  const navigate = useNavigate()

  return (
    <section className="card today-card" data-testid="today-review-card">
      <div className="today-card-head">
        <h2>{t('today.review.title')}</h2>
      </div>
      <div className="today-card-body">
        {loading ? (
          <Loading label={t('today.loading')} />
        ) : error || !review ? (
          <InlineNote tone="error" className="today-error">
            <span>{t('today.loadError')}</span>
            <Button size="small" onClick={onRetry}>{t('common.retry')}</Button>
          </InlineNote>
        ) : emptyReview(review) ? (
          <div className="today-review-empty">
            <b className="today-review-empty-title">{t('today.review.emptyTitle')}</b>
            <p className="today-review-empty-body">{t('today.review.emptyBody')}</p>
            <div className="today-actions">
              <Button
                variant="primary"
                data-testid="today-review-empty-add"
                onClick={() => navigate('/vocab/add')}
              >
                {t('today.review.add')}
              </Button>
              <Button onClick={() => navigate('/vocab/add?tab=file')}>{t('today.review.import')}</Button>
            </div>
          </div>
        ) : (
          <div className="today-review">
            <div className="today-review-figures">
              <div className="today-review-label">{t('today.review.dueLabel')}</div>
              <div className="today-review-due">
                <span className="today-review-count">{review.due}</span>
                <span className="today-review-unit">{t('today.review.unit')}</span>
              </div>
              <div className="today-review-stats">
                {t('today.review.stats', {
                  reviewed: review.reviewedToday,
                  newToday: review.newToday,
                  limit: review.newLimit,
                })}
              </div>
            </div>
            <div className="today-review-act">
              <Button
                variant="primary"
                data-testid="today-review-start"
                onClick={() => navigate('/vocab/review')}
              >
                {t('today.review.start')}
              </Button>
            </div>
          </div>
        )}
      </div>
    </section>
  )
}
