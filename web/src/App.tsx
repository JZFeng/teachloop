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
import BookDetailPage from './vocab/books/BookDetailPage'
import BookCopyReviewPage from './vocab/books/BookCopyReviewPage'
import AdminBooksPage from './vocab/books/AdminBooksPage'
import BookImportPage from './vocab/books/BookImportPage'
import LoginPage from './app/LoginPage'
import RegisterPage from './app/RegisterPage'
import ActivatePage from './app/ActivatePage'
import ForgotPasswordPage from './app/ForgotPasswordPage'
import { loginNextFrom, loginPath, safeNextPath } from './app/redirect'
import { shouldPrompt, isPrompted } from './app/welcomeGate'
import { featureGate } from './app/featureGate'
import NotificationsPage from './app/NotificationsPage'
import ProfilePage from './app/ProfilePage'
import SetupPage from './app/SetupPage'
import AdminAiPage from './app/AdminAiPage'
import AdminTeachersPage from './app/AdminTeachersPage'
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
import StudentTodayPage from './student/TodayPage'
import WelcomePage from './app/WelcomePage'
import FeedbackDetailPage from './student/FeedbackDetailPage'
import VocabShell, { VocabFocusLayout } from './vocab/VocabShell'
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
  // 判定没回来之前挂 Loading：先渲染再改道会闪一下。
  if (!profileChecked) return <div style={{ padding: 40 }}><Loading /></div>
  // 011 / T565（AC-93.2）：资料未补全不再强制改道 /profile，而是**首次**引导去 /welcome。
  // 「以后再说」写 localStorage（teachloop.profile.prompted），本设备不再自动出现；
  // 之后只保留 Shell 顶部那条可关闭提示条（AC-93.3）。管理员豁免（与后端一致）。
  if (me.role !== 'ADMIN'
    && shouldPrompt({
      profileIncomplete: profileComplete === false,
      pathname: location.pathname,
      prompted: isPrompted(),
    })) {
    return <Navigate to="/welcome" replace />
  }
  return <Outlet />
}

/**
 * 接口级兜底：请求收到 403 `PROFILE_INCOMPLETE` 时**只刷新资料判定**，不再改道。
 *
 * 011 / T561（US-93）之后，普通读取请求不再被这个过滤器拦；能收到它的只剩「教师建班/发词」
 * 这类写操作。此时把人从表单页踢到 /profile 会丢掉他刚填的内容，正确反应是让当前页面
 * 显示 403 文案、顶部提示条同时亮起来（它读的就是这里刷新的 `profileComplete`）。
 */
function ProfileIncompleteWatcher() {
  const { refreshProfile } = useAuth()
  useEffect(() => {
    setProfileIncompleteHandler(() => {
      void refreshProfile()
    })
    return () => setProfileIncompleteHandler(null)
  }, [refreshProfile])
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

/** 根路由按角色分流：教师看跨班首页，学生看统一的「今日」页（011 / US-90，AC-90.1）。 */
function HomeRouter() {
  const { me } = useAuth()
  const { features, loaded } = useFeatures()
  if (!loaded) return <div style={{ padding: 40 }}><Loading /></div>
  // 没有 nav.home 的角色（管理员）落到自己唯一的入口（AC-63.5 修订）：这里只看 key，不看角色。
  if (features['nav.home'] !== true) return <Navigate to={landingFor(features)} replace />
  return me?.role === 'STUDENT' ? <StudentTodayPage /> : <TeacherHomePage />
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
              {/* 011 / T563（AC-91.1 / 91.2 / 91.5）：三个页面共用一个页内 Tab 壳，侧栏只有一项。
                * 地址不变（/vocab/review、/vocab/lists、/vocab/library），Tab 就是真链接。 */}
              <Route path="vocab" element={<VocabShell />}>
                <Route index element={<Navigate to="/vocab/review" replace />} />
                <Route path="review" element={<VocabReviewPage />} />
                <Route path="lists" element={<VocabListsPage />} />
                {/* 词单详情留在壳内：从「我的词单」点进去，Tab 仍然高亮「我的词单」。 */}
                <Route path="lists/:id" element={<VocabListDetailPage />} />
                <Route path="library" element={<VocabLibraryPage />} />
              </Route>
              <Route path="vocab/sets" element={<VocabSetsPage />} />
              <Route path="vocab/quiz/:id" element={<VocabQuizPage />} />
              <Route path="vocab/sets/new" element={<VocabSetEditPage />} />
              <Route path="vocab/sets/:id" element={<VocabSetEditPage />} />
              <Route path="vocab/assign" element={<VocabAssignPage />} />
              <Route path="vocab/words/by-form/:form" element={<VocabWordDetailPage />} />
              <Route path="vocab/add" element={<VocabAddWordsPage />} />
              <Route path="vocab/assignments/:id" element={<VocabAssignmentPreviewPage />} />
              {/* 013：系统词书（学生侧）。加入后进副本复习，再落到全屏会话 */}
              <Route path="vocab/books/:id" element={<BookDetailPage />} />
              <Route path="vocab/books/:bookId/review" element={<BookCopyReviewPage />} />
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
          {/* 2026-10-29 (bug): `/admin/ai` had no guard, so a deep link rendered the whole
              settings page for students/teachers and only the API returned 403. Mirrors
              AC-63.6: without nav.ai the route falls back to the user's landing page, and
              the alias `/admin/settings` is guarded the same way. */}
          {/* AC-3.10（2026-10-30）：管理员的教师管理页（发邀请码）。 */}
          <Route path="admin/teachers" element={<RequireFeature feature="nav.teachers"><AdminTeachersPage /></RequireFeature>} />
          <Route path="admin/ai" element={<RequireFeature feature="nav.ai"><AdminAiPage /></RequireFeature>} />
          {/* 2026-10-28：页面改叫「系统设置」，/admin/settings 作为别名收口到同一屏。 */}
          <Route path="admin/settings" element={<RequireFeature feature="nav.ai"><Navigate to="/admin/ai" replace /></RequireFeature>} />
          {/* 013 词书管理（2026-10-30 bug）：`nav.ts:74` 早就把侧栏项指向 `/admin/vocab/books`，
              但这两条路由从没注册，点进去命中兜底 `*` → `/` → 管理员落地页 `/admin/ai`，
              深链同样打不开。守卫用 `nav.vocab.books`（RoleNavMatrix 只发给 ADMIN，
              且 isVocabKey 让它跟着 vocab 模块开关走），与 `/admin/ai` 同级。
              `?edit=<id>` 选中右侧编辑面板；导入页返回时还带这个参数（BookImportPage:45）。 */}
          <Route path="admin/vocab/books" element={<RequireFeature feature="nav.vocab.books"><AdminBooksPage /></RequireFeature>} />
          <Route path="admin/vocab/books/:bookId/import" element={<RequireFeature feature="nav.vocab.books"><BookImportPage /></RequireFeature>} />
          {/* 011 / T565：首次登录的资料收集页；「以后再说」后不再自动出现（localStorage）。 */}
          <Route path="welcome" element={<WelcomePage />} />
          <Route path="profile" element={<ProfilePage />} />
          {/* 组件标准不再单独成页：旧链接到资料页偏好设置里的面板，不再占侧栏入口。 */}
          <Route path="ui-kit" element={<Navigate to="/profile?panel=ui-kit" replace />} />
        </Route>
        {/* 011 / T563（AC-91.3）：复习会话全屏，没有侧栏也没有 Tab。 */}
        {features.vocab && (
          <Route
            path="/vocab/review/session/:id"
            element={<VocabFocusLayout><VocabFreeReviewPage /></VocabFocusLayout>}
          />
        )}
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
