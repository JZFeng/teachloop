import { expect, test } from '@playwright/test'
import { STUDENT_STATE, createStudentClient } from '../fixtures/clients'
import { pasteWords } from '../api/vocab.api'
import { BasePage } from '../pages/base/BasePage'
import { STUDENT_ROUTES } from '../utils/routes'

/**
 * 011 / T562 / T563 / T567：学生的「今日」页与背单词模块壳。
 *
 * 断言的是**量得出来的东西**（AGENTS.md UI 约束第 5 条 + docs/process/ui-review-checklist.md D3）：
 * 平板两档不横向溢出、主操作在首屏、侧栏只有一项「背单词」、Tab 顺序与选中态。
 * 这一组在每个 project 都跑，所以 1440 / 834×1194 / 1024×1366 三档各自给一份证据；
 * AC-90.6 的两档平板就是 ipad-air-11 与 ipad-pro-portrait 两个 project。
 */
test.use({ storageState: STUDENT_STATE })

test.beforeAll(async () => {
  // 复习卡的主按钮「开始复习」只在有待复习卡时出现（空词库是「加词/导入」两个动作）。
  // 造 3 个词属于数据前置，不是用例步骤（docs/process/e2e-playwright.md §5）。
  const student = await createStudentClient()
  try {
    await pasteWords(student, 'e2eapple\ne2ebanana\ne2echerry')
  } finally {
    await student.dispose()
  }
})

for (const route of STUDENT_ROUTES) {
  test(`学生页不溢出、主操作在首屏：${route}`, async ({ page }) => {
    const view = new BasePage(page)
    await view.open(route)
    await view.expectPath(route)
    await view.expectTabletSafe()
  })
}

test('AC-90.6：今日页「开始复习」在首屏内（平板两档由 project 视口决定）', async ({ page }) => {
  const view = new BasePage(page)
  await view.open('/')

  await expect(page.getByTestId('today-page')).toBeVisible()
  const start = page.getByTestId('today-review-start')
  await expect(start, '复习主按钮必须落在首屏内').toBeInViewport()
  await expect(start).toBeVisible()
})

test('AC-90.7：今日页数据只请求一次 /api/student/today，且不按 offering 逐条请求', async ({ page }) => {
  const calls: string[] = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (url.pathname.startsWith('/api/')) calls.push(url.pathname)
  })

  const view = new BasePage(page)
  await view.open('/')
  await expect(page.getByTestId('today-page')).toBeVisible()

  expect(calls.filter((path) => path === '/api/student/today')).toHaveLength(1)
  expect(calls.filter((path) => path.startsWith('/api/offerings'))).toHaveLength(0)
})

test('AC-91.1 / 91.5：侧栏只有一项「背单词」，Tab 顺序是 今日复习 / 我的词单 / 我的词库', async ({ page }) => {
  const view = new BasePage(page)
  await view.open('/vocab/review')

  const nav = page.locator('#nav .nav-link')
  await expect(nav.filter({ hasText: '背单词' })).toHaveCount(1)
  await expect(nav.filter({ hasText: '我的词库' })).toHaveCount(0)
  await expect(nav.filter({ hasText: '我的词单' })).toHaveCount(0)

  const tabs = page.locator('.tl-tab')
  await expect(tabs).toHaveCount(3)
  await expect(tabs.nth(0)).toContainText('今日复习')
  await expect(tabs.nth(1)).toContainText('我的词单')
  await expect(tabs.nth(2)).toContainText('我的词库')
  await expect(tabs.nth(0)).toHaveAttribute('aria-selected', 'true')
})

test('AC-91.2：直接打开 /vocab/library 时对应 Tab 高亮，「背单词」保持选中', async ({ page }) => {
  const view = new BasePage(page)
  await view.open('/vocab/library')

  await expect(page.locator('.tl-tab').nth(2)).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator('#nav .nav-link').filter({ hasText: '背单词' })).toHaveClass(/on/)
})
