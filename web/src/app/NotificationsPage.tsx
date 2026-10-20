import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, ApiError, fmtDateTime } from '../api/client'
import type { NotificationView } from '../api/types'
import { Empty, ErrorState, Loading, PageHeader } from '../components/States'

export default function NotificationsPage() {
  const { t } = useTranslation()
  const [rows, setRows] = useState<NotificationView[] | null>(null)
  const [unreadOnly, setUnreadOnly] = useState(false)
  const [error, setError] = useState<Error | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    setError(null)
    try {
      setRows(await api.get<NotificationView[]>(`/api/notifications?limit=100${unreadOnly ? '&unread=true' : ''}`))
    } catch (e) {
      setError(e as Error)
    }
  }, [unreadOnly])

  useEffect(() => {
    void load()
    const timer = setInterval(() => void load(), 30000)
    return () => clearInterval(timer)
  }, [load])

  const markRead = async (id: string) => {
    try {
      await api.post(`/api/notifications/${id}/read`)
      await load()
    } catch (e) {
      if (!(e instanceof ApiError && e.status === 404)) setError(e as Error)
    }
  }

  const markAll = async () => {
    setBusy(true)
    try {
      await api.post('/api/notifications/read-all')
      await load()
    } catch (e) {
      setError(e as Error)
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <PageHeader title={t('notifications.title')} sub={t('notifications.sub')}
        actions={
          <div className="row">
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
      {rows && rows.map((n) => (
        <div className={`card${n.readAt ? '' : ' notice-unread'}`} key={n.id}>
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <b>{n.title}</b>
            <span className="small muted">{fmtDateTime(n.createdAt)}</span>
          </div>
          {n.body && <p className="small" style={{ margin: '6px 0' }}>{n.body}</p>}
          <div className="row" style={{ gap: 8 }}>
            {n.targetType === 'offering' && n.targetId && (
              <a className="btn sm" href={`/feedback/${n.targetId}`}>{t('nav.feedback')}</a>
            )}
            {!n.readAt && (
              <button className="btn sm" onClick={() => void markRead(n.id)}>{t('notifications.markRead')}</button>
            )}
            {n.readAt && <span className="tag gray">{t('notifications.read')}</span>}
          </div>
        </div>
      ))}
    </>
  )
}
