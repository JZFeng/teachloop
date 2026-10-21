import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'

export function Loading({ label }: { label?: string }) {
  const { t } = useTranslation('translation')
  return <div className="card"><span className="muted">{label ?? t('common.loading')}</span></div>
}

export function ErrorState({ error, onRetry }: { error: Error; onRetry?: () => void }) {
  const { t } = useTranslation('translation')
  return (
    <div className="card">
      <p className="inline-note">{t('common.errorWith')} {error.message}</p>
      {onRetry && <button className="btn" onClick={onRetry}>{t('common.retry')}</button>}
    </div>
  )
}

export function Empty({ children }: { children?: ReactNode }) {
  const { t } = useTranslation('translation')
  return <div className="card"><span className="muted">{children ?? t('common.empty')}</span></div>
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
