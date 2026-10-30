import { expect, test } from '@playwright/test'
import { createStudentClient, createTeacherClient, TEACHER_STATE } from '../fixtures/clients'
import { findSubmission, givenSubmittedEssay } from '../fixtures/scenario'
import { GradingDetailPage } from '../pages/grading-detail.page'

/**
 * 第一条真正的业务端到端：学生写作提交 → 教师批改列表可见。
 *
 * 走法按 `docs/process/e2e-playwright.md` §5：
 *   建班 / 出卷 / 布置 / 交卷 **全部 API**（`givenSubmittedEssay`），
 *   UI 只做一件事——教师打开批改详情页，由 POM 断言这名学生出现在提交表里。
 *
 * 这条用例同时是"场景工厂 + POM + API 层"三层的冒烟：
 * 任一层写错，它都会红。
 */
test.use({ storageState: TEACHER_STATE })

test('学生交写作卷后，教师批改列表能看到这份提交', async ({ page }) => {
  const teacher = await createTeacherClient()
  const student = await createStudentClient()

  try {
    const scenario = await givenSubmittedEssay({ teacher, student, studentName: '示例学生' })

    // API 侧先确认状态真的落地（不是靠 UI 猜）
    const submission = await findSubmission(teacher, scenario.offeringId, scenario.studentName)
    expect(submission, '教师侧提交表应包含这名学生').toBeTruthy()
    expect(submission?.submissionId).toBe(scenario.submissionId)

    // UI 侧只验"教师能看到"这一件事
    const view = new GradingDetailPage(page)
    await view.openOffering(scenario.offeringId)
    await view.expectSubmissionListed(scenario.studentName)
  } finally {
    await teacher.dispose()
    await student.dispose()
  }
})
