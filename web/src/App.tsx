import { useEffect } from 'react'
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
import LoginPage from './app/LoginPage'
import NotificationsPage from './app/NotificationsPage'
import ProfilePage from './app/ProfilePage'
import SetupPage from './app/SetupPage'
import AdminAiPage from './app/AdminAiPage'
import TeacherHomePage from './teacher/HomePage'
import ClassesPage from './teacher/ClassesPage'
import PapersPage from './teacher/PapersPage'
import PaperWizardPage from './teacher/PaperWizardPage'
import ImportPaperPage from './teacher/ImportPaperPage'
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
  if (loading) return <div style={{ padding: 40 }}><Loading /></div>
  return me ? <Outlet /> : <Navigate to="/login" replace />
}

/** 根路由按角色分流：教师看跨班首页，学生看自己的考试/作业。 */
function HomeRouter() {
  const { me } = useAuth()
  return me?.role === 'STUDENT' ? <StudentHomePage /> : <TeacherHomePage />
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
  const { me } = useAuth()
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
      <Route path="/login" element={me ? <Navigate to="/" replace /> : <LoginPage />} />
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
              <Route path="vocab/sets/new" element={<VocabSetEditPage />} />
              <Route path="vocab/sets/:id" element={<VocabSetEditPage />} />
              <Route path="vocab/assign" element={<VocabAssignPage />} />
              <Route path="vocab/words/by-form/:form" element={<VocabWordDetailPage />} />
              <Route path="vocab/review/session/:id" element={<VocabFreeReviewPage />} />
              <Route path="vocab/add" element={<VocabAddWordsPage />} />
            </>
          )}
          <Route path="papers" element={<PapersPage />} />
          <Route path="papers/new" element={<PaperWizardPage />} />
          <Route path="papers/import" element={<ImportPaperPage />} />
          <Route path="papers/:paperId" element={<PaperWizardPage />} />
          <Route path="offerings/new" element={<OfferingCreatePage />} />
          <Route path="assignments" element={<AssignmentsPage />} />
          {/* 静态段在前：/assignments/new 是教师建作业，/assignments/:offeringId 是学生作业页 */}
          <Route path="assignments/new" element={<AssignmentCreatePage />} />
          <Route path="grading" element={<GradingOverviewPage />} />
          <Route path="grading/:offeringId" element={<GradingDetailPage />} />
          <Route path="review" element={<ReviewPage />} />
          <Route path="reports" element={<ReportsPage />} />
          <Route path="offerings/:offeringId/summary" element={<ClassSummaryPage />} />
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
