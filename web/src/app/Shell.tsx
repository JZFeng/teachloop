import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../api/auth'
import { api } from '../api/client'
import { useFeatures } from '../api/features'
import { useEscapeClose } from '../ui'
import { navItemActive, navSpecs, type NavIcon } from './nav'
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
  SolutionOutlined,
  SyncOutlined,
  TeamOutlined,
  ThemeToggle,
  UnorderedListOutlined,
  UserOutlined,
} from '../ui'
import { NOTIFICATIONS_CHANGED } from './NotificationsPage'
import { shouldMarkProfileBannerRead, shouldShowProfileBanner } from './welcomeGate'
import type { AiModelsView } from '../api/types'

/** 011 / T565：顶部提示条的会话内关闭标记（sessionStorage，不写 localStorage）。 */
const PROFILE_BANNER_KEY = 'teachloop.profile.bannerDismissed'
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
  teachers: <SolutionOutlined />,
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
  // 011 / T565 / AC-93.3：顶部「补全手机号」提示条，关闭后**本会话**不再出现（sessionStorage：
  // 刷新仍在，关掉标签页/换会话就重新提醒——它不是永久屏蔽，只是别在同一次使用里反复弹）。
  const [profileBannerDismissed, setProfileBannerDismissed] = useState(
    () => typeof sessionStorage !== 'undefined'
      && sessionStorage.getItem(PROFILE_BANNER_KEY) === '1',
  )

  // 2026-10-30（用户决定「第一次没填就不打扰」）：人一旦进过 /welcome 或 /profile，这条提示就算读过——
  // 本会话内不再出现，也不必等他去点「关闭」。写的是同一个 sessionStorage 键，刷新后仍然静音。
  useEffect(() => {
    if (!shouldMarkProfileBannerRead({
      profileComplete,
      pathname,
      dismissed: profileBannerDismissed,
    })) return
    setProfileBannerDismissed(true)
    try {
      sessionStorage.setItem(PROFILE_BANNER_KEY, '1')
    } catch {
      // 隐私模式下 sessionStorage 可能不可写：本次会话内仍然已读。
    }
  }, [profileComplete, pathname, profileBannerDismissed])

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
              key={item.key}
              to={item.to}
              end={item.end}
              data-tone={item.tone}
              /* 011 / T563（AC-91.2）：折叠项的选中态按三条路径任一匹配，不能用 NavLink 自带的
               * isActive——那只看 to，站在「我的词库」时侧栏就没有选中项了。 */
              className={() => `nav-link${navItemActive(item, pathname) ? ' on' : ''}`}
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
          {/* 顶栏是**窗口层**（2026-10-30 用户拍板，推翻同一小时提交的 2addd48「左对齐内容列」）：
           * 全局动作（通知）贴窗口右缘；正文才是**列层**（`.app-main` 最大 1240 居中）。
           * 为什么推翻：放进内容列之后铃铛的位置变成窗口宽度的函数——1864px 下距右缘 227px、
           * 2560px 下 575px，窗口一变就觉得「怪」，正是这次报的问题。
           * `.app-topbar-inner` 保留，现在只承载 ≤1023 的菜单按钮（它需要与正文同一条左边缘）。 */}
          <div className="row app-topbar-inner" style={{ gap: 8 }}>
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
          </div>
          {/* 铃铛是 `.app-topbar` 的**直接**子元素，贴的是窗口框；挪进 app-topbar-inner 就会跟列宽一起漂。 */}
          <div className="app-topbar-actions">
            <Button size="small" icon={<BellOutlined aria-hidden="true" />} onClick={() => navigate('/notifications')}>
              {t('nav.notifications')}{unread > 0 ? ` (${unread})` : ''}
            </Button>
          </div>
        </header>
        <main className="app-main">
          {/* AC-93.3：提示条同时给「去补全」与「关闭」；不再用顶栏那个小按钮（提示信息在按钮里读不全）。 */}
          {/* 2026-10-30 Bug（用户截图停在 /welcome）：/welcome 与 /profile 就是这条提示要去的地方，人已经站在目的地（等同已读），在那两页上不显示；判定见 welcomeGate.ts 的 shouldShowProfileBanner。 */}
          {shouldShowProfileBanner({
            profileComplete,
            pathname,
            dismissed: profileBannerDismissed,
          }) && (
            <InlineNote tone="warning" style={{ marginBottom: 16 }}>
              <div className="row" style={{ gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
                <span>{t('today.prompt.text')}</span>
                <Button size="small" variant="primary" onClick={() => navigate('/profile')}>
                  {t('today.prompt.action')}
                </Button>
                <Button
                  size="small"
                  onClick={() => {
                    setProfileBannerDismissed(true)
                    try {
                      sessionStorage.setItem(PROFILE_BANNER_KEY, '1')
                    } catch {
                      // 隐私模式下 sessionStorage 可能不可写：本次会话内仍然已关闭。
                    }
                  }}
                >
                  {t('common.close')}
                </Button>
              </div>
            </InlineNote>
          )}
          {/* AI 未配置：从顶栏挪进正文（2026-10-30 用户拍板）。留在顶栏时它是左簇里唯一会闪现的项，
           * 出现/消失都会把「通知」挤走；作为正文提示条，形态与上面那条「补全手机号」一致。
           * 模型只在 /admin/ai 配：管理员能自己去配（带按钮），教师只能联系管理员。
           * 两条提示可能同时出现，所以这里不用 primary——页首只留一个主按钮
           * （checklist A1a / 2026-10-26：两个实心按钮并排等于没告诉人先做哪个）。 */}
          {aiMissing && (
            <InlineNote tone="warning" style={{ marginBottom: 16 }}>
              <div className="row" style={{ gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
                <span>{me?.role === 'ADMIN' ? t('profile.aiMissingAdmin') : t('profile.aiMissingTeacher')}</span>
                {me?.role === 'ADMIN' && (
                  <Button size="small" onClick={() => navigate('/admin/ai')}>
                    {t('profile.aiMissingAction')}
                  </Button>
                )}
              </div>
            </InlineNote>
          )}
          {children}
        </main>
      </div>
    </div>
  )
}

export function ExamFocusLayout({ children }: { children: ReactNode }) {
  return <div className="exam-focus">{children}</div>
}
