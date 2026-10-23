import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { NavLink, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../api/auth'
import { api } from '../api/client'
import {
  AuditOutlined,
  BarChartOutlined,
  BellOutlined,
  BookOutlined,
  Brand,
  Button,
  CommentOutlined,
  FileTextOutlined,
  HighlightOutlined,
  HomeOutlined,
  LogoutOutlined,
  RobotOutlined,
  TeamOutlined,
  ThemeToggle,
  UserOutlined,
} from '../ui'
import { NOTIFICATIONS_CHANGED } from './NotificationsPage'
import type { AiModelsView } from '../api/types'

type NavTone = 'create' | 'assess' | 'insights' | 'home' | 'classes'
interface NavItem { to: string; label: string; icon: ReactNode; end?: boolean; tone?: NavTone }

export function Shell({ children }: { children: ReactNode }) {
  const { me, logout, profileComplete } = useAuth()
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [unread, setUnread] = useState(0)
  const [aiMissing, setAiMissing] = useState(false)

  // 三个功能位（作业/试卷 create、批改/复核 assess、报表/我的反馈 insights）之外，
  // 首页与班级作为「空间位」各有一个颜色：home 海军蓝、classes 淡紫（决策 006 2026-10-23 修订）。
  // 资料、通知、管理员 AI 仍是中性，不配 tone。
  const teacherNav: NavItem[] = [
    { to: '/', label: t('nav.home'), icon: <HomeOutlined />, end: true, tone: 'home' },
    { to: '/classes', label: t('nav.classes'), icon: <TeamOutlined />, tone: 'classes' },
    { to: '/assignments', label: t('nav.assignments'), icon: <BookOutlined />, tone: 'create' },
    { to: '/papers', label: t('nav.papers'), icon: <FileTextOutlined />, tone: 'create' },
    { to: '/grading', label: t('nav.grading'), icon: <HighlightOutlined />, tone: 'assess' },
    { to: '/review', label: t('nav.review'), icon: <AuditOutlined />, tone: 'assess' },
    { to: '/reports', label: t('nav.reports'), icon: <BarChartOutlined />, tone: 'insights' },
    ...(me?.role === 'ADMIN'
      ? [{ to: '/admin/ai', label: t('nav.aiSettings'), icon: <RobotOutlined /> }]
      : []),
  ]
  // 学生端同构：首页 home、班级 classes、反馈 assess。
  const studentNav: NavItem[] = [
    { to: '/', label: t('nav.home'), icon: <HomeOutlined />, end: true, tone: 'home' },
    { to: '/classes', label: t('nav.classes'), icon: <TeamOutlined />, tone: 'classes' },
    { to: '/feedback', label: t('nav.feedback'), icon: <CommentOutlined />, tone: 'assess' },
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
    // 通知页标已读后立刻刷新角标，不必等下一次轮询（T250）。
    window.addEventListener(NOTIFICATIONS_CHANGED, load)
    return () => {
      alive = false
      clearInterval(timer)
      window.removeEventListener(NOTIFICATIONS_CHANGED, load)
    }
  }, [])

  useEffect(() => {
    if (me?.role !== 'TEACHER' && me?.role !== 'ADMIN') {
      setAiMissing(false)
      return
    }
    let alive = true
    api.get<AiModelsView>('/api/ai-models')
      .then((value) => { if (alive) setAiMissing(!value.currentProviderId) })
      .catch(() => { if (alive) setAiMissing(false) })
    return () => { alive = false }
  }, [me?.id, me?.role])

  return (
    <div className="app-shell">
      <aside className="app-sidebar">
        <div className="brand"><Brand variant="text" size="md" tagline /></div>
        <nav id="nav">
          {items.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              data-tone={item.tone}
              className={({ isActive }) => `nav-link${isActive ? ' on' : ''}`}
            >
              <span className="nav-ico" aria-hidden="true">{item.icon}</span>
              <span>{item.label}</span>
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-foot">
          <ThemeToggle />
          {/* 资料页入口常驻在这里：顶部「资料」button 只负责提醒补全，补全后消失。 */}
          <NavLink to="/profile" className="sidebar-user" title={t('nav.profile')}>
            <span className="foot-ico" aria-hidden="true"><UserOutlined /></span>
            <span>{me?.displayName} · {me?.role}</span>
          </NavLink>
          <Button
            block
            icon={<LogoutOutlined aria-hidden="true" />}
            onClick={() => void logout().then(() => navigate('/login'))}
          >
            {t('common.logout')}
          </Button>
        </div>
      </aside>
      <div className="app-main-wrap">
        <header className="app-topbar">
          <div className="row" style={{ gap: 8 }}>
            {profileComplete === false && (
              <Button size="small" variant="primary" onClick={() => navigate('/profile')}>
                {t('profile.completeAction')}
              </Button>
            )}
            {aiMissing && (
              <Button size="small" variant="primary" onClick={() => navigate('/profile')}>
                {t('profile.aiMissingAction')}
              </Button>
            )}
            <Button size="small" icon={<BellOutlined aria-hidden="true" />} onClick={() => navigate('/notifications')}>
              {t('nav.notifications')}{unread > 0 ? ` (${unread})` : ''}
            </Button>
          </div>
          <NavLink to="/profile" className="topbar-user" title={t('nav.profile')}>
            {me?.displayName} · {me?.email}
          </NavLink>
        </header>
        <main className="app-main">{children}</main>
      </div>
    </div>
  )
}

export function ExamFocusLayout({ children }: { children: ReactNode }) {
  return <div className="exam-focus">{children}</div>
}
