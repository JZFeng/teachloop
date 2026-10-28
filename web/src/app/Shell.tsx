import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../api/auth'
import { api } from '../api/client'
import { useFeatures } from '../api/features'
import { useEscapeClose } from '../ui'
import { navSpecs, type NavIcon } from './nav'
import {
  AppstoreOutlined,
  AuditOutlined,
  BarChartOutlined,
  BellOutlined,
  BookOutlined,
  Brand,
  Button,
  FileTextOutlined,
  HighlightOutlined,
  HomeOutlined,
  InlineNote,
  LogoutOutlined,
  MenuOutlined,
  RobotOutlined,
  SendOutlined,
  SettingOutlined,
  SyncOutlined,
  TeamOutlined,
  ThemeToggle,
  UnorderedListOutlined,
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
  settings: <SettingOutlined />,
  library: <BookOutlined />,
  lists: <UnorderedListOutlined />,
  vocabReview: <SyncOutlined />,
  sets: <AppstoreOutlined />,
  assign: <SendOutlined />,
}

export function Shell({ children }: { children: ReactNode }) {
  const { me, logout, profileComplete } = useAuth()
  const { features, loaded: featuresLoaded } = useFeatures()
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const [unread, setUnread] = useState(0)
  const [aiMissing, setAiMissing] = useState(false)
  // 平板竖屏（≤1023，见 app.css 的 tablet 断点）侧栏是抽屉：顶栏按钮开合，选中导航后自动收起。
  const [navOpen, setNavOpen] = useState(false)

  // 三个功能位（作业/试卷 create、批改/复核 assess、报表/成绩与评语 insights）之外，
  // 首页与班级作为「空间位」各有一个颜色：home 海军蓝、classes 淡紫（决策 006 2026-10-23 修订）。
  // 可见性全部来自后端 key 集合；features 未回来前不渲染业务菜单，避免闪出全量再收缩（AC-63.11）。
  const items = featuresLoaded ? navSpecs(features) : []

  // 抽屉的行为三条：点导航收起、Esc 收起、开着的时侯锁住背景滚动（否则抽屉后面还能滚）。
  useEffect(() => { setNavOpen(false) }, [pathname])
  useEscapeClose(navOpen, () => setNavOpen(false))
  useEffect(() => {
    if (!navOpen) return
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => { document.body.style.overflow = prev }
  }, [navOpen])

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
      .then((value) => { if (alive) setAiMissing(!value.currentKey) })
      .catch(() => { if (alive) setAiMissing(false) })
    return () => { alive = false }
  }, [me?.id, me?.role])

  return (
    <div className="app-shell">
      {navOpen && (
        <div className="app-nav-backdrop" aria-hidden="true" onClick={() => setNavOpen(false)} />
      )}
      <aside className={`app-sidebar${navOpen ? ' open' : ''}`}>
        <div className="brand"><Brand variant="text" size="md" tagline /></div>
        {/* onClick 收抽屉：点当前页链接时 pathname 不变，只靠 [pathname] 的 effect 关不掉。 */}
        <nav id="nav" onClick={() => setNavOpen(false)}>
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
            {/* ≤1023 才显示（CSS 控制）：侧栏在那个宽度是抽屉，没有这个按钮平板竖屏就没有导航。 */}
            <Button
              className="app-nav-toggle"
              size="small"
              icon={<MenuOutlined aria-hidden="true" />}
              aria-expanded={navOpen}
              aria-controls="nav"
              aria-label={t('nav.menu')}
              onClick={() => setNavOpen((prev) => !prev)}
            >
              {t('nav.menu')}
            </Button>
            {profileComplete === false && (
              <Button size="small" variant="primary" onClick={() => navigate('/profile')}>
                {t('profile.completeAction')}
              </Button>
            )}
            {/* 模型只在 /admin/ai 配；教师页没有 AI 入口，所以教师看到的是提示而不是跳转。 */}
            {aiMissing && (me?.role === 'ADMIN'
              ? (
                /* Must not share weight with "complete profile": a missing profile blocks use,
                   while a missing AI key only affects generation. Two solid buttons side by side
                   leave no visual answer to "which one first" (checklist A1a, 2026-10-26). */
                <Button size="small" variant="default" onClick={() => navigate('/admin/ai')}>
                  {t('profile.aiMissingAction')}
                </Button>
              )
              : <InlineNote tone="warning" className="compact">{t('profile.aiMissingTeacher')}</InlineNote>)}
            <Button size="small" icon={<BellOutlined aria-hidden="true" />} onClick={() => navigate('/notifications')}>
              {t('nav.notifications')}{unread > 0 ? ` (${unread})` : ''}
            </Button>
          </div>
        </header>
        <main className="app-main">{children}</main>
      </div>
    </div>
  )
}

export function ExamFocusLayout({ children }: { children: ReactNode }) {
  return <div className="exam-focus">{children}</div>
}
