import { test } from '@playwright/test'
import { createStudentClient, createTeacherClient, STUDENT_STATE } from '../fixtures/clients'
import { givenSubmittedEssay } from '../fixtures/scenario'
import { StudentExamPage } from '../pages/student-exam.page'

/**
 * 学生侧：交卷后打开作答页，应该看到「已交卷」与这份卷的标题。
 *
 * 与 `essay-submission.spec.ts` 用同一个场景工厂——两条例例共用一套前置，
 * 谁坏了能立刻定位是造数坏了还是页面坏了。
 */
test.use({ storageState: STUDENT_STATE })

test('学生交卷后打开作答页能看到结果与提交内容', async ({ page }) => {
  const teacher = await createTeacherClient()
  const student = await createStudentClient()

  try {
    const scenario = await givenSubmittedEssay({ teacher, student, studentName: '示例学生' })

    const view = new StudentExamPage(page)
    await view.openExam(scenario.offeringId)
    await view.expectSubmitted()
    await view.expectPaperTitle(scenario.paperTitle)
  } finally {
    await teacher.dispose()
    await student.dispose()
  }
})
