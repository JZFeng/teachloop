import { defineConfig } from '@playwright/test'

/**
 * Playwright 配置（阶段 1：骨架 + 平板守卫）。
 *
 * 设计文档：`docs/process/e2e-playwright.md`。三条约定在这里落地：
 *   1. 视口只认三档 project（1440 / 834×1194 / 1024×1366），对应 AGENTS.md UI 约束第 5 条；
 *   2. 登录态由 `e2e/fixtures/auth.setup.ts` 统一产一次，spec 复用 storageState，不重复登录；
 *   3. 默认打本机 dev（127.0.0.1:5173），已起着就复用，不重复拉服务。
 *
 * 用 chromium 跑 iPad 视口而不是 webkit：`devices['iPad (gen 7)']` 默认 webkit，
 * 会要求多装一套浏览器；平板守卫量的是布局（溢出/首屏），chromium 的 CSS 引擎足够，
 * 触摸相关行为用 `hasTouch` 模拟。
 */
const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5173'

/** iPad 视口块：触摸 + 移动端布局，但浏览器固定 chromium。 */
const ipad = (width: number, height: number) => ({
  browserName: 'chromium' as const,
  viewport: { width, height },
  hasTouch: true,
  isMobile: true,
  deviceScaleFactor: 2,
})

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : [['list']],
  timeout: 30_000,
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    actionTimeout: 8_000,
  },
  projects: [
    // 只产出登录态，不跑断言
    { name: 'setup', testMatch: /auth\.setup\.ts/ },
    {
      name: 'desktop-1440',
      use: { browserName: 'chromium', viewport: { width: 1440, height: 900 } },
      dependencies: ['setup'],
    },
    {
      name: 'ipad-air-11',
      use: ipad(834, 1194),
      dependencies: ['setup'],
    },
    {
      name: 'ipad-pro-portrait',
      use: ipad(1024, 1366),
      dependencies: ['setup'],
    },
  ],
  webServer: {
    command: 'pnpm dev',
    url: baseURL,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
})
