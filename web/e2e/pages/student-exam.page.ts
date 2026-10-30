import { expect } from '@playwright/test'
import { BasePage } from './base/BasePage'

/**
 * 学生作答页（`/exams/:offeringId`，`ExamLivePage.tsx`）。
 *
 * 阶段 2 只覆盖"已交卷后能看到结果"这一路：提交动作本身走 API（场景工厂），
 * 页面负责把结果渲染出来——这是 UI 真正该被验的部分。
 */
export class StudentExamPage extends BasePage {
  async openExam(offeringId: string): Promise<void> {
    await this.open(`/exams/${offeringId}`)
  }

  /** 交卷后应出现「已交卷」标题。 */
  async expectSubmitted(): Promise<void> {
    await expect(this.page.getByText('已交卷').first(), '交卷后应显示「已交卷」').toBeVisible()
  }

  /**
   * 结果页应回显**试卷标题**，证明打开的是这份卷、而不是别的考试。
   *
   * 注意：这里不验"作文正文回显"——考试结果页（`ExamResult.tsx`）只渲染标题、分数与状态，
   * 正文回显属于作业页（`AssignmentPage.tsx` 的「我提交的内容」），阶段 3 覆盖作业类型时再验。
   */
  async expectPaperTitle(title: string): Promise<void> {
    // 交卷后学生进的是「考前须知 + 我的状态」这屏（`ExamLivePage` 的入场页），
    // 标题在二级标题里，形如「<试卷名>终结性考试」。
    const heading = this.page.getByRole('heading', { level: 2 }).filter({ hasText: title }).first()
    await expect(heading, `入场页应显示试卷标题「${title}」`).toBeVisible()
  }
}
