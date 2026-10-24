import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { NavLink, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../api/auth'
import { api } from '../api/client'
import { useFeatures } from '../api/features'
import { navSpecs, type NavIcon } from './nav'
import {
  AppstoreOutlined,
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
  SendOutlined,
  SyncOutlined,
  TeamOutlined,
  ThemeToggle,
  UserOutlined,
} from '../ui'
import { NOTIFICATIONS_CHANGED } from './NotificationsPage'
import type { AiModelsView } from '../api/types'

/** 导航规格只带 icon 名，真正的 ReactNode 在这里映射（见 app/nav.ts）。 */
const NAV_ICONS: Record<NavIcon, ReactNode> = {
  home: <HomeOutlined />,
  classes: <TeamOutlined />,
  assignments: <BookOutlined />,
  papers: <FileTextOutlined />,
  grading: <HighlightOutlined />,
  review: <AuditOutlined />,
  reports: <BarChartOutlined />,
  ai: <RobotOutlined />,
  feedback: <CommentOutlined />,
  library: <BookOutlined />,
  vocabReview: <SyncOutlined />,
  sets: <AppstoreOutlined />,
  assign: <SendOutlined />,
}

export function Shell({ children }: { children: ReactNode }) {
  const { me, logout, profileComplete } = useAuth()
  const { features } = useFeatures()
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [unread, setUnread] = useState(0)
  const [aiMissing, setAiMissing] = useState(false)

  // 三个功能位（作业/试卷 create、批改/复核 assess、报表/我的反馈 insights）之外，
  // 首页与班级作为「空间位」各有一个颜色：home 海军蓝、classes 淡紫（决策 006 2026-10-23 修订）。
  // 背单词是 practice 位；开关关闭时两项完全不出现（app/nav.ts）。
  const items = navSpecs(me?.role ?? '', features.vocab)

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
              <span className="nav-ico" aria-hidden="true">{NAV_ICONS[item.icon]}</span>
              <span>{t(item.labelKey)}</span>
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
