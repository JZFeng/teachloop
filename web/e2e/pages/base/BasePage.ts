import { expect, type Page } from '@playwright/test'

/**
 * 所有 POM 的基类：只放**跨页面相同**的三件事——打开页面、等稳定、平板断言。
 *
 * 为什么不给每页各写一份等待：等法不统一是 e2e 最常见的抖动来源。这里把
 * 「SPA 已经渲染出 body + 网络静下来了」这一条固定住，页面 class 只关心自己的内容。
 *
 * 平板断言的口径与 `docs/process/ui-review-checklist.md` 的 D3 完全一致：
 * 溢出基准用**当前 project 的视口宽**，不用 `innerWidth`——页面上若出现没有 viewport meta
 * 的嵌入内容，Chrome 会把布局视口撑开，`innerWidth` 就不再是设备宽（2026-10-30 实测）。
 */
export class BasePage {
  constructor(protected readonly page: Page) {}

  async open(path: string): Promise<void> {
    await this.page.goto(path)
    await this.waitSettled()
  }

  /**
   * 等页面稳定：文档加载完 + body 可见 + （最多等 3 秒的）网络静默。
   *
   * 为什么不裸等 `networkidle`：dev 下 Vite 的 HMR WebSocket 与页面里的 30 秒轮询会让
   * "网络静默 500ms" 迟迟不成立，实测会把整条用例拖到 30 秒超时（2026-10-30 第一次跑就踩到）。
   * 这里给 3 秒上限，超时按"已渲染"继续——平板上要量的是布局，不是网络。
   */
  async waitSettled(): Promise<void> {
    await this.page.waitForLoadState('load')
    await this.page.locator('body').waitFor({ state: 'visible' })
    await this.page.waitForLoadState('networkidle', { timeout: 3_000 }).catch(() => undefined)
  }

  /** 横向溢出：`scrollWidth` 不得超过视口宽 + 2px（容忍亚像素舍入）。 */
  async expectNoHorizontalOverflow(): Promise<void> {
    const width = this.page.viewportSize()?.width ?? 0
    const scrollWidth = await this.page.evaluate(() => document.documentElement.scrollWidth)
    expect(scrollWidth, `横向溢出：scrollWidth=${scrollWidth} > 视口宽 ${width}`).toBeLessThanOrEqual(width + 2)
  }

  /** 页面级主操作必须在首屏内；页面本身没有主按钮时跳过（例如忘记密码第 2 步之前）。 */
  async expectPrimaryActionInViewport(): Promise<void> {
    const cta = this.page.locator('.ant-btn-primary').first()
    if ((await cta.count()) === 0) return
    await expect(cta, '页面级主按钮应落在首屏内').toBeInViewport()
  }

  /** 正文不得小于 14px（AGENTS.md UI 约束第 2 条）。 */
  async expectBodyFontAtLeast(minimum = 14): Promise<void> {
    const size = await this.page.evaluate(() => Number.parseFloat(getComputedStyle(document.body).fontSize))
    expect(size, `正文字号 ${size}px 小于 ${minimum}px`).toBeGreaterThanOrEqual(minimum)
  }

  /** 平板的四条断言一次跑完，spec 里只写一行。 */
  async expectTabletSafe(): Promise<void> {
    await this.expectNoHorizontalOverflow()
    await this.expectPrimaryActionInViewport()
    await this.expectBodyFontAtLeast()
  }
}
