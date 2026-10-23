import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom'
import { trackEvent } from './api/events'
import { ensureCsrf } from './api/client'
import { useAuth } from './api/auth'
import { Loading } from './components/States'
import { ErrorBoundary } from './components/ErrorBoundary'
import { Button } from './ui'
import { ExamFocusLayout, Shell } from './app/Shell'
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
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
