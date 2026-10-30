import { mkdirSync } from 'node:fs'
import { dirname } from 'node:path'
import { expect, test as setup } from '@playwright/test'

/**
 * 登录态准备（一次，供所有 spec 复用）：teacher + student 各一份 storageState。
 *
 * 为什么要存 storageState 而不是每条用例登录一次：登录本身不是被测对象，
 * 而且每条用例重登会把运行时间翻倍。
 *
 * **为什么还要补资料**：本项目有 `ProfileCompletionFilter`——手机号或邮箱为空时，
 * 任何业务路由都会把人弹到 `/profile`。dev 种子账号只有登录邮箱、没有手机号，
 * 于是"打开 /grading 断言表格"实际落在 /profile 上（2026-10-30 CI 首跑就是这样假绿/假红的：
 * 平板守卫在 /profile 上也满足"不溢出"，业务用例却找不到表格）。
 * 所以这里登录后统一把资料补全，再存 storageState——所有用例拿到的都是"能正常用"的身份。
 *
 * 账号来源见 `docs/process/e2e-playwright.md` §2.3（dev 种子 + `E2E_*` 覆盖）。
 */
const ACCOUNTS = {
  teacher: {
    email: process.env.E2E_TEACHER_EMAIL ?? 'teacher@teachloop.local',
    password: process.env.E2E_TEACHER_PASSWORD ?? 'teachloop123',
    // 手机号在系统里唯一，两个角色给不同的号，避免互相抢占。
    phone: process.env.E2E_TEACHER_PHONE ?? '13800000001',
    file: 'e2e/.auth/teacher.json',
  },
  student: {
    email: process.env.E2E_STUDENT_EMAIL ?? 'student@teachloop.local',
    password: process.env.E2E_STUDENT_PASSWORD ?? 'teachloop123',
    phone: process.env.E2E_STUDENT_PHONE ?? '13800000002',
    file: 'e2e/.auth/student.json',
  },
} as const

for (const [role, account] of Object.entries(ACCOUNTS)) {
  setup(`${role} 登录态`, async ({ page }) => {
    await page.goto('/login')
    await page.getByLabel(/邮箱/).fill(account.email)
    await page.getByLabel(/密码/).fill(account.password)
    await page.getByRole('button', { name: /登\s*录/ }).click()

    // 登录成功后 SPA 会跳到 `/` 或先跳 `/profile`（缺手机号/邮箱时），两者都算登录成功。
    await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 15_000 })
    await expect(page.locator('.login-card')).toHaveCount(0)

    // 用页面自带的 cookie 调资料接口（`page.request` 与页面共享 cookie jar）。
    // 幂等：已经是完整资料时这只是覆盖写入同样两个字段。
    const response = await page.request.put('/api/profile', {
      data: { phone: account.phone, email: account.email },
    })
    expect(response.ok(), `补全 ${role} 资料失败：${response.status()} ${await response.text()}`).toBeTruthy()

    mkdirSync(dirname(account.file), { recursive: true })
    await page.context().storageState({ path: account.file })
  })
}
