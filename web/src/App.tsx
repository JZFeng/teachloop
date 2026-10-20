import { useEffect } from 'react'
import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom'
import { trackEvent } from './api/events'
import { ensureCsrf } from './api/client'
import { useAuth } from './api/auth'
import { Loading } from './components/States'
import { ExamFocusLayout, Shell } from './app/Shell'
import LoginPage from './app/LoginPage'
import NotificationsPage from './app/NotificationsPage'
import TeacherHomePage from './teacher/HomePage'
import ClassesPage from './teacher/ClassesPage'
import PapersPage from './teacher/PapersPage'
import PaperWizardPage from './teacher/PaperWizardPage'
import OfferingCreatePage from './teacher/OfferingCreatePage'
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
  return (
    <Shell>
      <Outlet />
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
      <Route element={<RequireAuth />}>
        <Route element={<ShellLayout />}>
          <Route index element={<HomeRouter />} />
          <Route path="classes" element={<ClassesPage />} />
          <Route path="papers" element={<PapersPage />} />
          <Route path="papers/new" element={<PaperWizardPage />} />
          <Route path="papers/:paperId" element={<PaperWizardPage />} />
          <Route path="offerings/new" element={<OfferingCreatePage />} />
          <Route path="grading" element={<GradingOverviewPage />} />
          <Route path="grading/:offeringId" element={<GradingDetailPage />} />
          <Route path="review" element={<ReviewPage />} />
          <Route path="reports" element={<ReportsPage />} />
          <Route path="offerings/:offeringId/summary" element={<ClassSummaryPage />} />
          <Route path="assignments/:offeringId" element={<AssignmentPage />} />
          <Route path="feedback" element={<FeedbackListPage />} />
          <Route path="feedback/:offeringId" element={<FeedbackDetailPage />} />
          <Route path="notifications" element={<NotificationsPage />} />
        </Route>
        <Route path="/exams/:offeringId" element={<Shell><ExamEntryPage /></Shell>} />
        <Route path="/exams/:offeringId/live" element={<ExamFocusLayout><ExamLivePage /></ExamFocusLayout>} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
