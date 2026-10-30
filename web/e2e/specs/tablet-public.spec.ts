import { test } from '@playwright/test'
import { BasePage } from '../pages/base/BasePage'
import { PUBLIC_ROUTES } from '../utils/routes'

/**
 * 平板守卫 · 未登录页（`docs/process/e2e-playwright.md` §7）。
 *
 * 这些页面在已登录状态下会被弹回首页，所以显式清空 storageState。
 */
test.use({ storageState: { cookies: [], origins: [] } })

for (const route of PUBLIC_ROUTES) {
  test(`未登录页不溢出、主操作在首屏：${route}`, async ({ page }) => {
    const view = new BasePage(page)
    await view.open(route)
    await view.expectPath(route)
    await view.expectTabletSafe()
  })
}
