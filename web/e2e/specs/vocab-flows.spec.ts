import { expect, test } from '@playwright/test'
import { STUDENT_STATE, createStudentClient, createTeacherClient } from '../fixtures/clients'
import { createClass, joinClass } from '../api/class.api'
import { addWordsToSet, assignSet, claimAssignment, createSet, pasteWords, pendingAssignments } from '../api/vocab.api'
import { BasePage } from '../pages/base/BasePage'

/**
 * 011 / T567：三条学生路径（不跑 UI 造数据，全部 API 前置 + 1–2 个 UI 动作）。
 *
 * 1. 加词 → 今日页 → 开始复习
 * 2. 登录态 → 我的词单
 * 3. 教师发词 → 学生领取 → 今日页复习卡出现
 *
 * 偏离说明：原需求写的是「注册→加词…」，但注册链路要收激活邮件
 * （`docs/process/e2e-playwright.md` §2.3 明确不能用于自动化），这里用 dev 种子学生
 * 代替「新账号」，其余步骤一致。
 */

test.describe('学生三条路径', () => {
  test.use({ storageState: STUDENT_STATE })

  test('路径 1：加词 → 今日页出现复习卡 → 点「开始复习」进入今日复习', async ({ page }) => {
    const suffix = Date.now().toString(36)
    const student = await createStudentClient()
    try {
      const pasted = await pasteWords(student, `e2e${suffix}apple\ne2e${suffix}banana`)
      expect(pasted.counts.created).toBeGreaterThanOrEqual(2)
    } finally {
      await student.dispose()
    }

    const view = new BasePage(page)
    await view.open('/')
    await view.expectPath('/')

    const start = page.getByTestId('today-review-start')
    await expect(start).toBeVisible()
    await start.click()

    await expect(page).toHaveURL(/\/vocab\/review$/)
    await expect(page.locator('.tl-tab').nth(0)).toHaveAttribute('aria-selected', 'true')
  })

  test('路径 2：登录态打开我的词单，Tab 高亮且页面停在 /vocab/lists', async ({ page }) => {
    const view = new BasePage(page)
    await view.open('/vocab/lists')

    await view.expectPath('/vocab/lists')
    await expect(page.locator('.tl-tab').nth(1)).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByTestId('vocab-shell')).toBeVisible()
  })

  test('路径 3：教师发词 → 学生领取 → 今日页复习卡可用', async ({ page }) => {
    const suffix = Date.now().toString(36)
    const teacher = await createTeacherClient()
    const student = await createStudentClient()
    let assignmentId = ''
    try {
      const created = await createClass(teacher, `E2E 发词班 ${suffix}`)
      await joinClass(student, created.inviteCode)

      const set = await createSet(teacher, `E2E 词集 ${suffix}`)
      await addWordsToSet(teacher, set.id, [
        `e2e${suffix}alpha`,
        `e2e${suffix}beta`,
        `e2e${suffix}gamma`,
      ])
      const assignment = await assignSet(teacher, set.id, [created.id])
      assignmentId = assignment.id

      const pending = await pendingAssignments(student)
      expect(pending.some((item) => item.id === assignmentId), '发词后学生应看到待领取').toBe(true)

      const claimed = await claimAssignment(student, assignmentId)
      expect(claimed.added).toBeGreaterThanOrEqual(3)

      const after = await pendingAssignments(student)
      expect(after.some((item) => item.id === assignmentId), '领取后不应再出现在待领取').toBe(false)
    } finally {
      await teacher.dispose()
      await student.dispose()
    }

    const view = new BasePage(page)
    await view.open('/')
    await view.expectPath('/')
    // 领取到的词进入今日复习队列：复习卡在主按钮可用态（不是空词库的两个动作）。
    await expect(page.getByTestId('today-review-start')).toBeVisible()
    await expect(page.getByTestId('today-review-empty-add')).toHaveCount(0)
  })
})
