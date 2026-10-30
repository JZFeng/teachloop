import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client'
import { useFeatures } from '../api/features'
import { ErrorState, InlineNote, Loading, PageHeader } from '../ui'
import AssignmentsCard from './today/AssignmentsCard'
import ExamsCard from './today/ExamsCard'
import InviteCard from './today/InviteCard'
import ListsCard from './today/ListsCard'
import ReviewCard from './today/ReviewCard'
import { firstClassName, formatTodayHeading } from './today'
import type { StudentTodayView } from './today'
import './today.css'

/**
 * 学生「今日」页（US-90 / FR-198）：所有学生登录后的同一个落地页。
 *
 * 这个页面只有一件事要做：把「今天要做什么」按顺序摆在学生面前。
 * 因此三条结构性决定：
 *
 * 1. **一次请求**。数据全部来自 `GET /api/student/today`（FR-207 / AC-90.7）；
 *    每张卡不自己发请求，页面的 `loading` / `partial` 状态直接往下传。
 *    这样「哪张卡坏了」是接口说的，不是四张卡各自猜的（AC-94.2）。
 * 2. **不按有无班级分流路由**（FR-199）。同一个地址、同一个页头，差异只在卡片：
 *    有班 → 作业卡 + 考试卡；无班 → 词单卡 + 邀请码卡。
 * 3. **班级不是概览**（AC-90.8 / FR-213）。页头副标题只写第一个作业/考试的班名，
 *    页面上没有「我的班级」卡；班级入口留在侧栏。
 *
 * 页头**不写连续天数**：这个数字今天没有数据源（spec §6 把学习记录卡列为第二期），
 * 编一个「连续 3 天」比不写更糟——学生能记住自己昨天有没有背。
 */
export default function StudentTodayPage() {
  const { t, i18n } = useTranslation()
  const { features } = useFeatures()
  const [data, setData] = useState<StudentTodayView | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<Error | null>(null)
  // 加入班级成功后的提示：卡片会因 inClass 变 true 而卸载，提示只能挂在页面上（AC-92.1）。
  const [joinedClassName, setJoinedClassName] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setData(await api.get<StudentTodayView>('/api/student/today'))
    } catch (loadError) {
      setError(loadError as Error)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  // 页头的「今天」在渲染期算一次就够：跨零点时用户刷新即更新，不必为它挂定时器。
  const now = useMemo(() => new Date(), [])
  const locale = i18n.language || 'zh-CN'
  const heading = formatTodayHeading(now, locale)
  const classLabel = data?.inClass ? firstClassName(data.assignments, data.exams) : null
  const subtitle = classLabel
    ? t('today.subtitleClass', { className: classLabel })
    : data
      ? t('today.subtitlePersonal')
      : null
  const zoneNote =
    data && data.zone && data.defaultZone && data.zone !== data.defaultZone
      ? t('today.zoneNote', { zone: data.zone })
      : null
  // vocab 模块关闭时，复习卡与词单卡都不出现（AC-91.4 的同一开关）。
  const vocabOn = features.vocab === true

  const handleJoined = useCallback(
    (name: string) => {
      if (name) setJoinedClassName(name)
      void load()
    },
    [load],
  )

  return (
    <div className="today-page" data-testid="today-page">
      <PageHeader
        title={t('today.title')}
        sub={
          <span className="today-head-sub">
            <span className="today-head-date">{heading}</span>
            {subtitle && <span>{subtitle}</span>}
          </span>
        }
      />

      {zoneNote && <p className="today-zone-note">{zoneNote}</p>}
      {joinedClassName && (
        <InlineNote tone="success">{t('today.invite.success', { className: joinedClassName })}</InlineNote>
      )}

      {!data && loading ? (
        <Loading label={t('today.loading')} />
      ) : !data ? (
        <ErrorState
          error={error ?? new Error(t('today.loadError'))}
          onRetry={() => void load()}
        />
      ) : (
        <div className="today-cards">
          {vocabOn && (
            <ReviewCard
              review={data.review}
              loading={loading}
              error={data.partial?.review === 'error'}
              onRetry={() => void load()}
            />
          )}
          {data.inClass && (
            <AssignmentsCard
              items={data.assignments ?? []}
              loading={loading}
              error={data.partial?.assignments === 'error'}
              onRetry={() => void load()}
            />
          )}
          {data.inClass && (
            <ExamsCard
              items={data.exams ?? []}
              loading={loading}
              error={data.partial?.exams === 'error'}
              onRetry={() => void load()}
            />
          )}
          {!data.inClass && vocabOn && <ListsCard />}
          {!data.inClass && <InviteCard onJoined={handleJoined} />}
        </div>
      )}
    </div>
  )
}
