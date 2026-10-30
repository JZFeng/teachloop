import { expect, test } from '@playwright/test'
import { STUDENT_STATE, createStudentClient, createTeacherClient } from '../fixtures/clients'
import { createClass, joinClass, leaveClass, myClasses } from '../api/class.api'
import { BasePage } from '../pages/base/BasePage'

/**
 * 011 / T564 / US-92：今日页的「有邀请码？」卡。
 *
 * 串行跑：两条用例都改同一个种子学生的班级归属（一条入班、一条退班），并行会让彼此的前置失效。
 * 这是唯一一个需要 `--workers=1` 的 spec，命令写在 `docs/reports/011-t567-e2e.md`。
 */
test.describe.configure({ mode: 'serial' })
test.use({ storageState: STUDENT_STATE })

test('AC-92.4 / AC-90.3：已入班学生没有邀请码卡，作业卡保留「没有要交的作业。」', async ({ page }) => {
  const teacher = await createTeacherClient()
  const student = await createStudentClient()
  const stamp = Date.now().toString(36)
  try {
    const created = await createClass(teacher, `E2E 邀请卡班 ${stamp}`)
    await joinClass(student, created.inviteCode)
  } finally {
    await teacher.dispose()
    await student.dispose()
  }

  const view = new BasePage(page)
  await view.open('/')
  await view.expectPath('/')

  await expect(page.getByTestId('today-invite-card')).toHaveCount(0)
  // 在班但今天没有待交：卡片必须保留并写一句空态（AC-90.3），不能整卡消失。
  await expect(page.getByTestId('today-assignments-card')).toBeVisible()
  await expect(page.getByTestId('today-assignments-card')).toContainText('没有要交的作业。')
})

test('AC-92.3：无效邀请码内联报错、页面不跳转、输入内容保留', async ({ page }) => {
  const student = await createStudentClient()
  try {
    // 先退掉所有班，「有邀请码？」卡才会出现（AC-92.4 的互补条件）。
    for (const item of await myClasses(student)) {
      await leaveClass(student, item.id)
    }
  } finally {
    await student.dispose()
  }

  const view = new BasePage(page)
  await view.open('/')
  await view.expectPath('/')

  const card = page.getByTestId('today-invite-card')
  await expect(card).toBeVisible()
  const input = page.getByTestId('today-invite-input')
  await input.fill('NOPE-0000')
  await page.getByTestId('today-invite-submit').click()

  // 错误留在卡片内（InlineNote），页面没跳走，输入框里的内容还在。
  await expect(card.locator('.inline-note.err')).toBeVisible()
  await expect(page).toHaveURL(/\/$/)
  await expect(input).toHaveValue('NOPE-0000')
})
