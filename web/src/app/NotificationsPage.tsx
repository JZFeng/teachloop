import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { api, ApiError, fmtDateTime } from '../api/client'
import type { NotificationView } from '../api/types'
import { Empty, ErrorState, Loading, PageHeader } from '../components/States'
import { notificationRoute } from './notificationRoute'

/** 未读数变更事件：通知页标已读后，侧边栏角标立刻跟着变，不用等 30 秒轮询。 */
export const NOTIFICATIONS_CHANGED = 'teachloop:notifications-changed'

export function notifyUnreadChanged() {
  window.dispatchEvent(new Event(NOTIFICATIONS_CHANGED))
}

export default function NotificationsPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [rows, setRows] = useState<NotificationView[] | null>(null)
  const [unreadOnly, setUnreadOnly] = useState(false)
  const [error, setError] = useState<Error | null>(null)
  const [busy, setBusy] = useState(false)
  const [unread, setUnread] = useState(0)

  const load = useCallback(async () => {
    setError(null)
    try {
      const list = await api.get<NotificationView[]>(
        `/api/notifications?limit=100${unreadOnly ? '&unread=true' : ''}`)
      setRows(list)
      setUnread(list.filter((n) => !n.readAt).length)
    } catch (e) {
      setError(e as Error)
    }
  }, [unreadOnly])

  useEffect(() => {
    void load()
    const timer = setInterval(() => void load(), 30000)
    return () => clearInterval(timer)
  }, [load])

  /** 标已读：本地先改状态（角标立刻 −1），再同步后端；后端失败只回滚这一条。 */
  const markRead = async (id: string) => {
    const before = rows?.find((n) => n.id === id)
    if (!before || before.readAt) return
    setRows((current) => current?.map((n) => (n.id === id ? { ...n, readAt: new Date().toISOString() } : n)) ?? null)
    setUnread((n) => Math.max(0, n - 1))
    notifyUnreadChanged()
    try {
      await api.post(`/api/notifications/${id}/read`)
    } catch (e) {
      if (!(e instanceof ApiError && e.status === 404)) setError(e as Error)
      setRows((current) => current?.map((n) => (n.id === id ? { ...n, readAt: before.readAt } : n)) ?? null)
      setUnread((n) => n + 1)
      notifyUnreadChanged()
    }
  }

  const markAll = async () => {
    setBusy(true)
    try {
      await api.post('/api/notifications/read-all')
      await load()
      notifyUnreadChanged()
    } catch (e) {
      setError(e as Error)
    } finally {
      setBusy(false)
    }
  }

  /** 点整条：先标已读，再跳到对应页面；没有可跳目标时只标已读。 */
  const open = async (n: NotificationView) => {
    const route = notificationRoute(n)
    await markRead(n.id)
    if (route) navigate(route)
  }

  return (
    <>
      <PageHeader title={t('notifications.title')} sub={t('notifications.sub')}
        actions={
          <div className="row">
            <span className="small muted">{t('notifications.unreadCount', { n: unread })}</span>
            <label className="row" style={{ gap: 6 }}>
              <input type="checkbox" checked={unreadOnly} onChange={(e) => setUnreadOnly(e.target.checked)} />
              <span className="small">{t('notifications.unreadOnly')}</span>
            </label>
            <button className="btn" disabled={busy} onClick={() => void markAll()}>{t('notifications.markAll')}</button>
          </div>
        }
      />
      {error && <ErrorState error={error} onRetry={() => void load()} />}
      {!rows && !error && <Loading />}
      {rows && rows.length === 0 && <Empty>{t('notifications.empty')}</Empty>}
      {rows && rows.map((n) => {
        const route = notificationRoute(n)
        return (
          <div
            className={`card${n.readAt ? '' : ' notice-unread'}${route ? ' notice-link' : ''}`}
            key={n.id}
            role={route ? 'link' : undefined}
            tabIndex={route ? 0 : undefined}
            onClick={() => { if (route) void open(n) }}
            onKeyDown={(e) => { if (route && (e.key === 'Enter' || e.key === ' ')) void open(n) }}
          >
            <div className="row" style={{ justifyContent: 'space-between' }}>
              <b>{n.title}</b>
              <span className="small muted">{fmtDateTime(n.createdAt)}</span>
            </div>
            {n.body && <p className="small" style={{ margin: '6px 0' }}>{n.body}</p>}
            <div className="row" style={{ gap: 8 }} onClick={(e) => e.stopPropagation()}>
              {route && (
                <button className="btn sm" onClick={() => void open(n)}>{t('notifications.open')}</button>
              )}
              {!n.readAt && (
                <button className="btn sm" onClick={() => void markRead(n.id)}>{t('notifications.markRead')}</button>
              )}
              {n.readAt && <span className="tag gray">{t('notifications.read')}</span>}
            </div>
          </div>
        )
      })}
    </>
  )
}
