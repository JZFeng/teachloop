import type { ReactNode } from 'react'

/** 页面标题区：标题 / 说明 / 操作按钮，三件套统一间距。 */
export function PageHeader({
  title,
  sub,
  actions,
}: {
  title: ReactNode
  sub?: ReactNode
  actions?: ReactNode
}) {
  return (
    <header className="tl-page-head">
      <div className="tl-page-head-text">
        <h1 className="tl-page-title">{title}</h1>
        {sub && <p className="tl-page-sub">{sub}</p>}
      </div>
      {actions && <div className="tl-page-actions">{actions}</div>}
    </header>
  )
}
