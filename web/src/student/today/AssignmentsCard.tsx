import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button, InlineNote, Loading, Tag } from '../../ui'
import {
  ASSIGNMENT_LIMIT,
  daysOverdue,
  formatMoment,
  isOverdue,
  visibleAssignments,
} from '../today'
import type { AssignmentView } from '../today'

interface AssignmentsCardProps {
  /** 后端给的是**全部**待办（不截断）；这里自己取前 3 条。 */
  items: AssignmentView[]
  loading: boolean
  /** `partial.assignments === 'error'`（AC-94.2）。 */
  error: boolean
  onRetry: () => void
}

/**
 * 待交作业卡。
 *
 * 三条硬规则：
 * 1. **永远渲染**（AC-90.3）。没有作业时写「没有要交的作业。」而不是整卡消失——
 *    整卡消失之后学生分不清「今天没作业」和「接口挂了」。
 * 2. 逾期条的截止标签是红的（AC-90.2）：逾期单独一个红标签，未到期才显示具体日期。
 * 3. 「查看全部 N 条」在**页内展开**（AC-90.2 / spec §9 第 2 条），不跳新路由。
 *    N 是后端给的全部条数，不是展开后的可见条数。
 */
export default function AssignmentsCard({ items, loading, error, onRetry }: AssignmentsCardProps) {
  const { t, i18n } = useTranslation()
  const [expanded, setExpanded] = useState(false)
  // 一次渲染内固定「现在」：同屏两条作业不能因为毫秒差出现一条逾期一条不逾期。
  const now = useMemo(() => new Date(), [])
  const locale = i18n.language || 'zh-CN'
  const visible = expanded ? visibleAssignments(items, items.length) : visibleAssignments(items)

  return (
    <section className="card today-card" data-testid="today-assignments-card">
      <div className="today-card-head">
        <h2>{t('today.assignments.title')}</h2>
        {!loading && !error && items.length > ASSIGNMENT_LIMIT && (
          <Button
            variant="text"
            size="small"
            className="today-card-more"
            aria-expanded={expanded}
            onClick={() => setExpanded((value) => !value)}
          >
            {expanded
              ? t('today.assignments.collapse')
              : t('today.assignments.expand', { count: items.length })}
          </Button>
        )}
      </div>
      <div className="today-card-body">
        {loading ? (
          <Loading label={t('today.loading')} />
        ) : error ? (
          <InlineNote tone="error" className="today-error">
            <span>{t('today.loadError')}</span>
            <Button size="small" onClick={onRetry}>{t('common.retry')}</Button>
          </InlineNote>
        ) : items.length === 0 ? (
          <p className="today-empty">{t('today.assignments.empty')}</p>
        ) : (
          <div className="today-rows">
            {visible.map((item) => {
              const overdue = isOverdue(item.dueAt, now)
              const submitted = item.status === 'submitted'
              return (
                <div className="today-row" key={item.id}>
                  <div className="today-row-main">
                    <div className="today-row-title">{item.title}</div>
                    <div className="today-row-meta">{item.className}</div>
                  </div>
                  <div className="today-row-tags">
                    {/* 没有截止时间的作业不编一句「截止」：只留状态标签，缺数据就少显示一件事。 */}
                    {item.dueAt &&
                      (overdue ? (
                        <Tag tone="red">
                          {t('today.assignments.overdue', { days: daysOverdue(item.dueAt, now) })}
                        </Tag>
                      ) : (
                        <Tag tone="gray">
                          {t('today.assignments.due', { date: formatMoment(item.dueAt, locale) })}
                        </Tag>
                      ))}
                    <Tag tone={submitted ? 'green' : 'blue'}>
                      {submitted
                        ? t('today.assignments.statusSubmitted')
                        : t('today.assignments.statusTodo')}
                    </Tag>
                  </div>
                </div>
              )
            })}
          </div>
        )}
      </div>
    </section>
  )
}
