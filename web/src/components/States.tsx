import type { ReactNode } from 'react'

export function Loading({ label = '加载中…' }: { label?: string }) {
  return <div className="card"><span className="muted">{label}</span></div>
}

export function ErrorState({ error, onRetry }: { error: Error; onRetry?: () => void }) {
  return (
    <div className="card">
      <p className="inline-note">出错了：{error.message}</p>
      {onRetry && <button className="btn" onClick={onRetry}>重试</button>}
    </div>
  )
}

export function Empty({ children }: { children?: ReactNode }) {
  return <div className="card"><span className="muted">{children ?? '暂无数据'}</span></div>
}

export function PageHeader({ title, sub, actions }: { title: string; sub?: string; actions?: ReactNode }) {
  return (
    <>
      <h1>{title}</h1>
      {sub && <div className="sub">{sub}</div>}
      {actions && <div className="row" style={{ marginBottom: 12 }}>{actions}</div>}
    </>
  )
}

export function StatCard({ n, label, sub, onClick }: { n: ReactNode; label: string; sub?: string; onClick?: () => void }) {
  return (
    <div className="stat" onClick={onClick} role={onClick ? 'button' : undefined} tabIndex={onClick ? 0 : undefined}>
      <div className="n">{n}</div>
      <div className="l">{label}</div>
      {sub && <div className="sub">{sub}</div>}
    </div>
  )
}
