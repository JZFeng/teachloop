import { test } from '@playwright/test'
import { BasePage } from '../pages/base/BasePage'
import { TEACHER_ROUTES } from '../utils/routes'

/**
 * 平板守卫 · 登录后业务页。登录态来自 `e2e/fixtures/auth.setup.ts` 产出的 storageState，
 * 所以这里不出现任何登录动作（API-first 的雏形：能用前置准备解决的，不走 UI）。
 */
test.use({ storageState: 'e2e/.auth/teacher.json' })

for (const route of TEACHER_ROUTES) {
  test(`业务页不溢出、主操作在首屏：${route}`, async ({ page }) => {
    const view = new BasePage(page)
    await view.open(route)
    await view.expectPath(route)
    await view.expectTabletSafe()
  })
}
