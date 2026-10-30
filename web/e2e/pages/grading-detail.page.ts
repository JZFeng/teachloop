import { expect } from '@playwright/test'
import { BasePage } from './base/BasePage'

/**
 * 批改详情页（`/grading/:offeringId`，`GradingDetailPage.tsx`）。
 *
 * 阶段 2 只包"能看到谁交了"这一件事——批改动作本身（通过/驳回/复核）在阶段 3 逐页补。
 */
export class GradingDetailPage extends BasePage {
  async openOffering(offeringId: string): Promise<void> {
    await this.open(`/grading/${offeringId}`)
  }

  /** 提交表里应出现这名学生（表格行按学生名匹配）。 */
  async expectSubmissionListed(studentName: string): Promise<void> {
    const row = this.page.locator('.tl-table tbody tr').filter({ hasText: studentName })
    await expect(row.first(), `批改列表里应能看到「${studentName}」的提交`).toBeVisible()
  }

  /** 页面标题应是试卷名（顺带验证 offering 不是空壳）。 */
  async expectPaperTitle(title: string): Promise<void> {
    await expect(this.page.getByText(title, { exact: false }).first()).toBeVisible()
  }
}
