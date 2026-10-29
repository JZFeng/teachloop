import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Navigate, Outlet, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { trackEvent } from './api/events'
import { api, ensureCsrf, setProfileIncompleteHandler } from './api/client'
import { useAuth } from './api/auth'
import { useFeatures } from './api/features'
import { Loading } from './components/States'
import { ErrorBoundary } from './components/ErrorBoundary'
import { Button } from './ui'
import { ExamFocusLayout, Shell } from './app/Shell'
import VocabLibraryPage from './vocab/LibraryPage'
import VocabReviewPage from './vocab/ReviewPage'
import VocabSetsPage from './vocab/SetsPage'
import VocabSetEditPage from './vocab/SetEditPage'
import VocabAssignPage from './vocab/AssignPage'
import VocabWordDetailPage from './vocab/WordDetailPage'
import VocabFreeReviewPage from './vocab/FreeReviewPage'
import VocabAddWordsPage from './vocab/AddWordsPage'
import VocabAssignmentPreviewPage from './vocab/AssignmentPreviewPage'
import VocabListsPage from './vocab/ListsPage'
import VocabListDetailPage from './vocab/ListDetailPage'
import VocabQuizPage from './vocab/QuizPage'
import LoginPage from './app/LoginPage'
import RegisterPage from './app/RegisterPage'
import ActivatePage from './app/ActivatePage'
import ForgotPasswordPage from './app/ForgotPasswordPage'
import { loginNextFrom, loginPath, safeNextPath } from './app/redirect'
import { PROFILE_PATH, profileDetour, profilePathWithNext } from './app/profileGate'
import { featureGate } from './app/featureGate'
import NotificationsPage from './app/NotificationsPage'
import ProfilePage from './app/ProfilePage'
import SetupPage from './app/SetupPage'
import AdminAiPage from './app/AdminAiPage'
import TeacherHomePage from './teacher/HomePage'
import ClassesPage from './teacher/ClassesPage'
import PapersPage from './teacher/PapersPage'
import PaperWizardPage from './teacher/PaperWizardPage'
import OfferingCreatePage from './teacher/OfferingCreatePage'
import AssignmentsPage from './teacher/AssignmentsPage'
import AssignmentCreatePage from './teacher/AssignmentCreatePage'
import GradingOverviewPage from './teacher/GradingOverviewPage'
import GradingDetailPage from './teacher/GradingDetailPage'
import ReviewPage from './teacher/ReviewPage'
import ReportsPage from './teacher/ReportsPage'
import ClassSummaryPage from './teacher/ClassSummaryPage'
import StudentHomePage from './student/HomePage'
import FeedbackDetailPage from './student/FeedbackDetailPage'
import ExamEntryPage from './exam/ExamEntryPage'
import ExamLivePage from './exam/ExamLivePage'
import AssignmentPage from './exam/AssignmentPage'

function RequireAuth() {
  const { me, loading, profileComplete, profileChecked } = useAuth()
  const location = useLocation()
  if (loading) return <div style={{ padding: 40 }}><Loading /></div>
  if (!me) {
    // 带上原地址：学生从班群点开预习链接但没登录时，登录后要回到那次布置，而不是首页。
    return <Navigate to={loginPath(loginNextFrom(location.pathname, location.search, location.hash))} replace />
  }
  // 2026-10-27（Bug）：缺手机号/邮箱的人先补资料，不能先放他进业务页撞 403。
  // 判定没回来之前挂 Loading：先渲染再改道会闪一下「出错了：请先补全手机号与邮箱」。
  if (!profileChecked) return <div style={{ padding: 40 }}><Loading /></div>
  const detour = profileDetour({ pathname: location.pathname, role: me.role, profileComplete })
  if (detour) {
    // 带上原地址：补全保存后回到他本来要去的页面（学生从班群点开的链接不能丢）。
    return <Navigate to={profilePathWithNext(loginNextFrom(location.pathname, location.search, location.hash))} replace />
  }
  return <Outlet />
}

/**
 * 接口级兜底：任何请求收到 403 `PROFILE_INCOMPLETE` 都改道 /profile（2026-10-27 Bug）。
 *
 * 路由守卫已经覆盖正常入口；这里处理守卫覆盖不到的情况——会话中途资料被清空、
 * 或者某个页面先发了请求。挂在这里是因为只有 Router 内部才能 `navigate`。
 */
function ProfileIncompleteWatcher() {
  const navigate = useNavigate()
  const location = useLocation()
  const { refreshProfile } = useAuth()
  // 回调用 ref 读当前位置：注册一次就够，不必每次换页重注册。
  const here = useRef(location)
  here.current = location
  useEffect(() => {
    setProfileIncompleteHandler(() => {
      void refreshProfile()
      const at = here.current
      // 登录页、首次部署页、资料页本身不动作：前两个不该被记成补全后的去处，后者会自己转自己。
      if (at.pathname === PROFILE_PATH || at.pathname === '/login' || at.pathname === '/setup') return
      navigate(profilePathWithNext(loginNextFrom(at.pathname, at.search, at.hash)), { replace: true })
    })
    return () => setProfileIncompleteHandler(null)
  }, [navigate, refreshProfile])
  return null
}

/** `/login` 的入口：已登录的人带着 next 来（例如刷新登录页），直接回跳，不要再登一次。 */
function LoginRoute() {
  const { me } = useAuth()
  const location = useLocation()
  const next = safeNextPath(new URLSearchParams(location.search).get('next'))
  return me ? <Navigate to={next ?? '/'} replace /> : <LoginPage />
}

/**
 * 角色落地页：按后端给的 key 选第一个可用入口（2026-10-27：管理员不再有班级，
 * 只剩「系统设置」（原 AI 设置），因此不再固定落 /classes）。
 */
function landingFor(features: Record<string, boolean>): string {
  if (features['nav.home'] === true) return '/'
  if (features['nav.classes'] === true) return '/classes'
  if (features['nav.ai'] === true) return '/admin/ai'
  return '/profile'
}

/** 根路由按角色分流：教师看跨班首页，学生看自己的考试/作业。 */
function HomeRouter() {
  const { me } = useAuth()
  const { features, loaded } = useFeatures()
  if (!loaded) return <div style={{ padding: 40 }}><Loading /></div>
  // 没有 nav.home 的角色（管理员）落到自己唯一的入口（AC-63.5 修订）：这里只看 key，不看角色。
  if (features['nav.home'] !== true) return <Navigate to={landingFor(features)} replace />
  return me?.role === 'STUDENT' ? <StudentHomePage /> : <TeacherHomePage />
}

/**
 * 007-role-nav：路由级可见性，key 由后端 `/api/features` 给出。
 * 未加载时不放行也不重定向（避免深链被吃掉）；不具备该 key 时回到自己的落地页。
 */
function RequireFeature({ feature, children }: { feature: string; children: ReactNode }) {
  const { features, loaded } = useFeatures()
  // 判定收在 featureGate.ts：三态（等开关 / 放行 / 退回落地页）由纯函数决定，测试直接跑它。
  const gate = featureGate({ loaded, features, feature })
  if (gate === 'loading') return <div style={{ padding: 40 }}><Loading /></div>
  if (gate === 'deny') return <Navigate to={landingFor(features)} replace />
  return <>{children}</>
}

function ShellLayout() {
  const { t } = useTranslation('translation')
  const location = useLocation()
  return (
    <Shell>
      {/* 按 pathname 复位：一页崩了不把故障带进下一页（T325）。 */}
      <ErrorBoundary
        key={location.pathname}
        fallback={
          <div className="card">
            <h2>{t('app.crashTitle')}</h2>
            <p className="muted">{t('app.crashBody')}</p>
            <Button variant="primary" onClick={() => window.location.reload()}>
              {t('app.crashReload')}
            </Button>
          </div>
        }
      >
        <Outlet />
      </ErrorBoundary>
    </Shell>
  )
}

function AppRoutes() {
  const { features, loaded } = useFeatures()
  const location = useLocation()
  // 全新实例没有任何管理员时，谁打开首页都只能看到登录页（而这时根本没有账号），
  // 所以按 /api/setup/status 的 needsAdmin 自动把人送到 /setup。
  const [needsAdmin, setNeedsAdmin] = useState(false)

  useEffect(() => {
    void ensureCsrf()
  }, [])

  useEffect(() => {
    if (!['/', '/login', '/setup'].includes(location.pathname)) return
    let alive = true
    api
      .get<{ needsAdmin: boolean }>('/api/setup/status')
      .then((s) => { if (alive) setNeedsAdmin(Boolean(s.needsAdmin)) })
      .catch(() => { if (alive) setNeedsAdmin(false) })
    return () => { alive = false }
  }, [location.pathname])

  useEffect(() => {
    trackEvent('page_view', { route: location.pathname })
  }, [location.pathname])

  if (needsAdmin && location.pathname !== '/setup') {
    return <Navigate to="/setup" replace />
  }

  return (
    <Routes>
      <Route path="/login" element={<LoginRoute />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route path="/activate" element={<ActivatePage />} />
      <Route path="/forgot" element={<ForgotPasswordPage />} />
      <Route path="/setup" element={<SetupPage />} />
      <Route element={<RequireAuth />}>
        <Route element={<ShellLayout />}>
          <Route index element={<HomeRouter />} />
          {/* 2026-10-27：班级页按 key 守卫——管理员没有 nav.classes，深链 /classes 直接回落地页（AC-63.6 / 63.9）。 */}
          <Route path="classes" element={<RequireFeature feature="nav.classes"><ClassesPage /></RequireFeature>} />
          {/* T389：开关关闭时不注册 vocab 路由（AC-56.3 / plan §11）。 */}
          {features.vocab && (
            <>
              <Route path="vocab/library" element={<VocabLibraryPage />} />
              <Route path="vocab/review" element={<VocabReviewPage />} />
              <Route path="vocab/sets" element={<VocabSetsPage />} />
              {/* US-69：词单页。2026-10-26 起侧栏有「我的词单」一级入口（AC-69.11 修订），
                 * 词库工具条的「词单」按钮仍保留，两个入口到同一页。 */}
              <Route path="vocab/lists" element={<VocabListsPage />} />
              <Route path="vocab/lists/:id" element={<VocabListDetailPage />} />
              <Route path="vocab/quiz/:id" element={<VocabQuizPage />} />
              <Route path="vocab/sets/new" element={<VocabSetEditPage />} />
              <Route path="vocab/sets/:id" element={<VocabSetEditPage />} />
              <Route path="vocab/assign" element={<VocabAssignPage />} />
              <Route path="vocab/words/by-form/:form" element={<VocabWordDetailPage />} />
              <Route path="vocab/review/session/:id" element={<VocabFreeReviewPage />} />
              <Route path="vocab/add" element={<VocabAddWordsPage />} />
              <Route path="vocab/assignments/:id" element={<VocabAssignmentPreviewPage />} />
            </>
          )}
          <Route path="papers" element={<RequireFeature feature="nav.papers"><PapersPage /></RequireFeature>} />
          <Route path="papers/new" element={<RequireFeature feature="nav.papers"><PaperWizardPage /></RequireFeature>} />
          <Route path="papers/:paperId" element={<RequireFeature feature="nav.papers"><PaperWizardPage /></RequireFeature>} />
          <Route path="offerings/new" element={<RequireFeature feature="nav.papers"><OfferingCreatePage /></RequireFeature>} />
          <Route path="assignments" element={<RequireFeature feature="nav.assignments"><AssignmentsPage /></RequireFeature>} />
          {/* 静态段在前：/assignments/new 是教师建作业，/assignments/:offeringId 是学生作业页 */}
          <Route path="assignments/new" element={<RequireFeature feature="nav.assignments"><AssignmentCreatePage /></RequireFeature>} />
          <Route path="grading" element={<RequireFeature feature="nav.grading"><GradingOverviewPage /></RequireFeature>} />
          <Route path="grading/:offeringId" element={<RequireFeature feature="nav.grading"><GradingDetailPage /></RequireFeature>} />
          <Route path="review" element={<RequireFeature feature="nav.review"><ReviewPage /></RequireFeature>} />
          <Route path="reports" element={<RequireFeature feature="nav.reports"><ReportsPage /></RequireFeature>} />
          <Route path="offerings/:offeringId/summary" element={<RequireFeature feature="nav.reports"><ClassSummaryPage /></RequireFeature>} />
          {/* 学生作业页不按 nav.assignments 拦：学生没有这个 key，但必须能作答 */}
          <Route path="assignments/:offeringId" element={<AssignmentPage />} />
          {/* 2026-10-26（AC-72.8）：`/feedback` 列表页删除，只留详情；入口在首页卡片与作业页行内。 */}
          <Route path="feedback/:offeringId" element={<FeedbackDetailPage />} />
          <Route path="notifications" element={<NotificationsPage />} />
          {/* 2026-10-29（Bug）：`/admin/ai` 之前没挂守卫，学生/教师深链进来能整页渲染系统设置，
              只有接口被后端 403 挡住。按 AC-63.6 的反向镜像处理：没有 nav.ai 的人退回自己的落地页，
              页面壳与内容一律不渲染。别名 `/admin/settings` 同样要拦。 */}
          <Route path="admin/ai" element={<RequireFeature feature="nav.ai"><AdminAiPage /></RequireFeature>} />
          {/* 2026-10-28：页面改叫「系统设置」，/admin/settings 作为别名收口到同一屏。 */}
          <Route path="admin/settings" element={<RequireFeature feature="nav.ai"><Navigate to="/admin/ai" replace /></RequireFeature>} />
          <Route path="profile" element={<ProfilePage />} />
          {/* 组件标准不再单独成页：旧链接到资料页偏好设置里的面板，不再占侧栏入口。 */}
          <Route path="ui-kit" element={<Navigate to="/profile?panel=ui-kit" replace />} />
        </Route>
        <Route path="/exams/:offeringId" element={<Shell><ExamEntryPage /></Shell>} />
        <Route path="/exams/:offeringId/live" element={<ExamFocusLayout><ExamLivePage /></ExamFocusLayout>} />
      </Route>
      {/* 深链 /vocab/* 时开关还没回来，先别重定向；否则开关回来 URL 已经变了（T389 浏览器验收发现）。 */}
      <Route path="*" element={loaded ? <Navigate to="/" replace /> : <Loading />} />
    </Routes>
  )
}

/** 外层壳：先挂资料兜底监听，再渲染路由（Router 之外拿不到 navigate）。 */
export default function App() {
  return (
    <>
      <ProfileIncompleteWatcher />
      <AppRoutes />
    </>
  )
}
