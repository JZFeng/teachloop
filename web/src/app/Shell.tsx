import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { NavLink, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../api/auth'
import { api } from '../api/client'

interface NavItem { to: string; label: string; end?: boolean }

export function Shell({ children }: { children: ReactNode }) {
  const { me, logout, profileComplete } = useAuth()
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [unread, setUnread] = useState(0)

  const teacherNav: NavItem[] = [
    { to: '/', label: t('nav.home'), end: true },
    { to: '/classes', label: t('nav.classes') },
    { to: '/assignments', label: t('nav.assignments') },
    { to: '/papers', label: t('nav.papers') },
    { to: '/grading', label: t('nav.grading') },
    { to: '/review', label: t('nav.review') },
    { to: '/reports', label: t('nav.reports') },
    ...(me?.role === 'ADMIN' ? [{ to: '/admin/ai', label: t('nav.aiSettings') }] : []),
  ]
  const studentNav: NavItem[] = [
    { to: '/', label: t('nav.home'), end: true },
    { to: '/classes', label: t('nav.classes') },
    { to: '/feedback', label: t('nav.feedback') },
  ]
  const items = me?.role === 'STUDENT' ? studentNav : teacherNav

  useEffect(() => {
    let alive = true
    const load = () => {
      api.get<{ count: number }>('/api/notifications/unread-count')
        .then((r) => { if (alive) setUnread(r.count) })
        .catch(() => { /* 通知不可用时不影响主流程 */ })
    }
    load()
    const timer = setInterval(load, 30000)
    return () => { alive = false; clearInterval(timer) }
  }, [])

  return (
    <div className="app-shell">
      <aside className="app-sidebar">
        <div className="brand">{t('app.name')}<span>{t('app.tagline')}</span></div>
        <nav id="nav">
          {items.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) => `nav-link${isActive ? ' on' : ''}`}
            >
              {item.label}
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-foot">
          {/* 资料页入口常驻在这里：顶部「资料」button 只负责提醒补全，补全后消失。 */}
          <NavLink to="/profile" className="sidebar-user" title={t('nav.profile')}>
            {me?.displayName} · {me?.role}
          </NavLink>
          <button className="btn" onClick={() => void logout().then(() => navigate('/login'))}>
            {t('common.logout')}
          </button>
        </div>
      </aside>
      <div className="app-main-wrap">
        <header className="app-topbar">
          <div className="row" style={{ gap: 8 }}>
            {profileComplete === false && (
              <button className="btn sm" onClick={() => navigate('/profile')}>{t('profile.completeAction')}</button>
            )}
            <button className="btn sm" onClick={() => navigate('/notifications')} title={t('nav.notifications')}>
              🔔 {t('nav.notifications')}{unread > 0 ? ` (${unread})` : ''}
            </button>
          </div>
          <div className="small muted">{me?.email}</div>
        </header>
        <main className="app-main">{children}</main>
      </div>
    </div>
  )
}

export function ExamFocusLayout({ children }: { children: ReactNode }) {
  return <div className="exam-focus">{children}</div>
}
