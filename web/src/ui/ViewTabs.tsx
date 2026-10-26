import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'

/**
 * 页内视图切换（2026-10-26）：作业详情页的「提交明细 / 成绩汇总」用这个。
 *
 * 为什么不用 antd Segmented：用户反馈那两块「像副标题的灰字，等于没看到」。
 * 这里做成带下划线的页签，且每项可以带计数/角标——与下方内容同名，才读得出来「这是两个视图」。
 * 用真 <Link> 而不是按钮：可右键新开、可 Tab、地址栏能直接贴给别人。
 */
export interface ViewTabItem {
  key: string
  label: ReactNode
  to: string
  /** 标签后的计数（如提交 2）或状态角标（如「未公布 · 预览」）。 */
  badge?: ReactNode
}

export function ViewTabs({ items, selected }: { items: ViewTabItem[]; selected: string }) {
  return (
    <nav className="tl-tabs" role="tablist">
      {items.map((item) => {
        const on = item.key === selected
        return (
          <Link
            key={item.key}
            to={item.to}
            role="tab"
            aria-selected={on}
            className={`tl-tab${on ? ' on' : ''}`}
            data-tab={item.key}
          >
            <span>{item.label}</span>
            {item.badge}
          </Link>
        )
      })}
    </nav>
  )
}
