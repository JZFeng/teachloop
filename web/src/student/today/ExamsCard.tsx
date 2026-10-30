import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Button, InlineNote, Loading, Tag } from '../../ui'
import { daysUntil, formatMoment, visibleExams } from '../today'
import type { ExamView } from '../today'

interface ExamsCardProps {
  items: ExamView[]
  loading: boolean
  /** `partial.exams === 'error'`（AC-94.2）。 */
  error: boolean
  onRetry: () => void
}

/**
 * 即将开始的考试卡。
 *
 * AC-90.4：没有考试时**整卡不出现**（与作业卡相反——考试是「有则提醒」，不是待办清单，
 * 一个永远写着「没有考试」的卡只会占掉首屏）。有考试时最多 2 条、按开始时间升序，
 * 标签写「今天 / 明天 / N 天后」。
 *
 * 取数失败时仍然渲染：那不是一个事实（「没有考试」），而是一次故障，必须让人看见并能重试
 * （AC-94.2）。只有「加载完成且确实没有考试」才整卡消失。
 */
export default function ExamsCard({ items, loading, error, onRetry }: ExamsCardProps) {
  const { t, i18n } = useTranslation()
  const now = useMemo(() => new Date(), [])
  const locale = i18n.language || 'zh-CN'

  if (!loading && !error && items.length === 0) return null

  const visible = visibleExams(items)

  const countdown = (startsAt: string): string => {
    const days = daysUntil(startsAt, now)
    if (days <= 0) return t('today.exams.today')
    if (days === 1) return t('today.exams.tomorrow')
    return t('today.exams.inDays', { days })
  }

  return (
    <section className="card today-card" data-testid="today-exams-card">
      <div className="today-card-head">
        <h2>{t('today.exams.title')}</h2>
      </div>
      <div className="today-card-body">
        {loading ? (
          <Loading label={t('today.loading')} />
        ) : error ? (
          <InlineNote tone="error" className="today-error">
            <span>{t('today.loadError')}</span>
            <Button size="small" onClick={onRetry}>{t('common.retry')}</Button>
          </InlineNote>
        ) : (
          <div className="today-rows">
            {visible.map((item) => (
              <div className="today-row" key={item.id}>
                <div className="today-row-main">
                  <div className="today-row-title">{item.title}</div>
                  <div className="today-row-meta">
                    <span>{item.className}</span>
                    {item.startsAt && (
                      <span>{t('today.exams.starts', { date: formatMoment(item.startsAt, locale) })}</span>
                    )}
                  </div>
                </div>
                <div className="today-row-tags">
                  {item.startsAt && <Tag tone="blue">{countdown(item.startsAt)}</Tag>}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </section>
  )
}
