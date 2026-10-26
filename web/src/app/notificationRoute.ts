import type { NotificationView } from '../api/types'

/** 只依赖通知的三个字段，便于单测（node --experimental-strip-types 读不了 .tsx）。 */
export type NotificationTarget = Pick<NotificationView, 'kind' | 'targetType' | 'targetId'>

/**
 * 通知要跳到哪个页面（T250，2026-10-25 补 vocab）。
 *
 * 先看 kind（决定「去哪类页面」），再看 targetType（决定「哪个具体页面」）：
 * - `feedback_published` / `scores_published`：都指向布置的反馈页 `/feedback/{offeringId}`；
 *   历史数据里 feedback_published 存过提交 id（targetType=submission），拿不到布置 id，退到首页
 *   ——2026-10-26（AC-72.8）删掉 `/feedback` 列表页后，那里已经没有可跳的页面了。
 * - `offering_published`（含改造前的 `offering`）：exam → `/exams/{id}`，其余 → `/assignments/{id}`。
 * - **`vocab_assignment`（vocab 发词通知）→ `/vocab/assignments/{id}`**：那就是学生预习/领取页
 *   （`AssignmentPreviewPage`），词集名、截止时间、逐词列表、加入词库都在这一页。
 *
 * 两个字段都**转小写再比**：vocab 的发射方写的是大写 `VOCAB_ASSIGNMENT`
 * （`VocabularyAssignmentEndpoint`），其余发射方写小写；大小写敏感会让 vocab 通知永远
 * 匹配不到目标页，表现就是「查看」按钮不出现、整卡也点不动。
 */
export function notificationRoute(n: NotificationTarget): string | null {
  if (!n.targetId) return null
  const kind = (n.kind ?? '').toLowerCase()
  const target = (n.targetType ?? '').toLowerCase()
  if (kind === 'feedback_published' || kind === 'scores_published') {
    return target === 'submission' ? '/' : `/feedback/${n.targetId}`
  }
  if (target === 'vocab_assignment') return `/vocab/assignments/${n.targetId}`
  if (target === 'exam') return `/exams/${n.targetId}`
  if (target === 'assignment' || target === 'offering') return `/assignments/${n.targetId}`
  return null
}
