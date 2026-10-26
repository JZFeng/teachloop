import { useEffect } from 'react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom'
import { trackEvent } from './api/events'
import { ensureCsrf } from './api/client'
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
import LoginPage from './app/LoginPage'
import { loginNextFrom, loginPath, safeNextPath } from './app/redirect'
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
import FeedbackListPage from './student/FeedbackListPage'
import FeedbackDetailPage from './student/FeedbackDetailPage'
import ExamEntryPage from './exam/ExamEntryPage'
import ExamLivePage from './exam/ExamLivePage'
import AssignmentPage from './exam/AssignmentPage'

function RequireAuth() {
  const { me, loading } = useAuth()
  const location = useLocation()
  if (loading) return <div style={{ padding: 40 }}><Loading /></div>
  if (me) return <Outlet />
  // 带上原地址：学生从班群点开预习链接但没登录时，登录后要回到那次布置，而不是首页。
  return <Navigate to={loginPath(loginNextFrom(location.pathname, location.search, location.hash))} replace />
}

/** `/login` 的入口：已登录的人带着 next 来（例如刷新登录页），直接回跳，不要再登一次。 */
function LoginRoute() {
  const { me } = useAuth()
  const location = useLocation()
  const next = safeNextPath(new URLSearchParams(location.search).get('next'))
  return me ? <Navigate to={next ?? '/'} replace /> : <LoginPage />
}

/** 根路由按角色分流：教师看跨班首页，学生看自己的考试/作业。 */
function HomeRouter() {
  const { me } = useAuth()
  const { features, loaded } = useFeatures()
  if (!loaded) return <div style={{ padding: 40 }}><Loading /></div>
  // 没有 nav.home 的角色（管理员）直接落到班级页（AC-63.5）：这里只看 key，不看角色。
  if (features['nav.home'] !== true) return <Navigate to="/classes" replace />
  return me?.role === 'STUDENT' ? <StudentHomePage /> : <TeacherHomePage />
}

/**
 * 007-role-nav：路由级可见性，key 由后端 `/api/features` 给出。
 * 未加载时不放行也不重定向（避免深链被吃掉）；不具备该 key 时回到班级页。
 */
function RequireFeature({ feature, children }: { feature: string; children: ReactNode }) {
  const { features, loaded } = useFeatures()
  if (!loaded) return <div style={{ padding: 40 }}><Loading /></div>
  if (features[feature] !== true) return <Navigate to="/classes" replace />
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

export default function App() {
  const { features, loaded } = useFeatures()
  const location = useLocation()

  useEffect(() => {
    void ensureCsrf()
  }, [])

  useEffect(() => {
    trackEvent('page_view', { route: location.pathname })
  }, [location.pathname])

  return (
    <Routes>
      <Route path="/login" element={<LoginRoute />} />
      <Route path="/setup" element={<SetupPage />} />
      <Route element={<RequireAuth />}>
        <Route element={<ShellLayout />}>
          <Route index element={<HomeRouter />} />
          <Route path="classes" element={<ClassesPage />} />
          {/* T389：开关关闭时不注册 vocab 路由（AC-56.3 / plan §11）。 */}
          {features.vocab && (
            <>
              <Route path="vocab/library" element={<VocabLibraryPage />} />
              <Route path="vocab/review" element={<VocabReviewPage />} />
              <Route path="vocab/sets" element={<VocabSetsPage />} />
              {/* US-69：词单不占导航，从「我的词库」工具条进（AC-69.11） */}
              <Route path="vocab/lists" element={<VocabListsPage />} />
              <Route path="vocab/lists/:id" element={<VocabListDetailPage />} />
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
          <Route path="feedback" element={<FeedbackListPage />} />
          <Route path="feedback/:offeringId" element={<FeedbackDetailPage />} />
          <Route path="notifications" element={<NotificationsPage />} />
          <Route path="admin/ai" element={<AdminAiPage />} />
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
