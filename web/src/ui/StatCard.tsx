import type { ReactNode } from 'react'

/**
 * 统计卡：一个大数字 + 一句标签 + 一句补充说明。
 * 可点击时整块是按钮语义（键盘 Enter/Space 也能触发），不是只有一个 div 的 onClick。
 */
export function StatCard({
  value,
  label,
  sub,
  onClick,
  active,
}: {
  value: ReactNode
  label: ReactNode
  sub?: ReactNode
  onClick?: () => void
  active?: boolean
}) {
  const clickable = Boolean(onClick)
  return (
    <div
      className={`tl-stat${clickable ? ' is-clickable' : ''}${active ? ' is-active' : ''}`}
      onClick={onClick}
      role={clickable ? 'button' : undefined}
      tabIndex={clickable ? 0 : undefined}
      onKeyDown={
        clickable
          ? (e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault()
                onClick?.()
              }
            }
          : undefined
      }
    >
      <div className="tl-stat-value">{value}</div>
      <div className="tl-stat-label">{label}</div>
      {sub && <div className="tl-stat-sub">{sub}</div>}
    </div>
  )
}
