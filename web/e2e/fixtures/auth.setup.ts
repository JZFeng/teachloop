import { mkdirSync } from 'node:fs'
import { dirname } from 'node:path'
import { expect, test as setup } from '@playwright/test'

/**
 * 登录态准备（一次，供所有 spec 复用）。
 *
 * 为什么要存 storageState 而不是每条用例登录一次：登录本身不是被测对象，
 * 而且每条用例重登会把运行时间翻倍（本项目 dev 账号第 1 次登录还要过 profile 补全判定）。
 *
 * 账号来源（`docs/process/e2e-playwright.md` §2.3）：
 *   - 默认用 dev 种子账号 `teacher@teachloop.local` / `teachloop123`（`DevDataSeeder.java:41-42`）；
 *   - CI 或别的环境用 `E2E_TEACHER_EMAIL` / `E2E_TEACHER_PASSWORD` 覆盖；
 *   - 需要学生身份时再加一个 setup，产出 `student.json`（阶段 2 随场景工厂一起补）。
 */
const TEACHER = {
  email: process.env.E2E_TEACHER_EMAIL ?? 'teacher@teachloop.local',
  password: process.env.E2E_TEACHER_PASSWORD ?? 'teachloop123',
}

const AUTH_FILE = 'e2e/.auth/teacher.json'

setup('teacher 登录态', async ({ page }) => {
  await page.goto('/login')
  await page.getByLabel(/邮箱/).fill(TEACHER.email)
  await page.getByLabel(/密码/).fill(TEACHER.password)
  await page.getByRole('button', { name: '登 录' }).click()

  // 登录成功后 SPA 会跳到 `/` 或先跳 `/profile`（缺手机号/邮箱时），两者都算成功。
  await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 15_000 })
  await expect(page.locator('.login-card')).toHaveCount(0)

  mkdirSync(dirname(AUTH_FILE), { recursive: true })
  await page.context().storageState({ path: AUTH_FILE })
})
