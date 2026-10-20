import { useEffect, useState } from 'react'
import { api, fmtDateTime } from '../api/client'
import type { AuditView } from '../api/types'
import { ErrorState, Loading } from './States'

/** T184：审计只能从实体行进入，不以左侧一级导航出现。 */
export function AuditDrawer({ open, onClose, targetType, targetId, title }: {
  open: boolean
  onClose: () => void
  targetType: string
  targetId: string
  title?: string
}) {
  const [rows, setRows] = useState<AuditView[] | null>(null)
  const [error, setError] = useState<Error | null>(null)

  useEffect(() => {
    if (!open) return
    setRows(null)
    setError(null)
    api.get<AuditView[]>(`/api/audit?targetType=${encodeURIComponent(targetType)}&targetId=${encodeURIComponent(targetId)}`)
      .then(setRows)
      .catch((e: Error) => setError(e))
  }, [open, targetType, targetId])

  if (!open) return null
  return (
    <div
      style={{ position: 'fixed', inset: 0, background: 'rgba(16,24,40,.28)', zIndex: 50 }}
      onClick={onClose}
      role="presentation"
    >
      <aside
        style={{ position: 'absolute', right: 0, top: 0, bottom: 0, width: 420, maxWidth: '92vw',
          background: 'var(--panel)', padding: 20, overflowY: 'auto', boxShadow: 'var(--shadow-lg)' }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="row" style={{ justifyContent: 'space-between' }}>
          <h2 style={{ margin: 0 }}>{title ?? '审计时间线'}</h2>
          <button className="btn" onClick={onClose}>关闭</button>
        </div>
        <div className="hint" style={{ marginTop: 8 }}>{targetType} · {targetId}</div>
        {error && <ErrorState error={error} />}
        {!rows && !error && <Loading />}
        {rows && rows.length === 0 && <p className="muted">没有审计记录。</p>}
        {rows && rows.map((r) => (
          <div key={r.id} className="card" style={{ marginTop: 10 }}>
            <div className="row" style={{ justifyContent: 'space-between' }}>
              <b>{r.action}</b>
              <span className="small muted">{fmtDateTime(r.createdAt)}</span>
            </div>
            {r.detail && <div className="small muted mono">{r.detail}</div>}
          </div>
        ))}
      </aside>
    </div>
  )
}
