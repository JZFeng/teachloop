/** T141：前端事件上报。只允许白名单事件名，绝不发送题干/作文/答案等正文。 */
const ALLOWED = new Set(['page_view', 'action_click', 'exam_start', 'answer_save', 'submit'])

export function trackEvent(name: string, props?: Record<string, string | number | boolean>): void {
  if (!ALLOWED.has(name)) return
  void fetch('/api/events', {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ events: [{ name, props }] }),
  }).catch(() => { /* 事件上报失败不影响主流程 */ })
}
