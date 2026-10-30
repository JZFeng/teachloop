/**
 * 资料未补全不是错误，是「先去补全资料」。
 *
 * 背景（2026-10-27 Bug）：后端 `ProfileCompletionFilter`（teachloop-identity）对缺手机号或邮箱的
 * 非管理员用户，除 `/api/auth/me`、`/api/profile` 外一律返回 403 `PROFILE_INCOMPLETE`。
 * 前端此前没有这个分支：学生第一次登录被 `navigate('/')` 丢进业务页，页面的统一取数钩子
 * 把这条 403 当成故障，渲染成「出错了：请先补全手机号与邮箱」的红框（截图落在 /classes）。
 *
 * 2026-10-30（011 / T561，US-93）之后语义已改：普通读取请求不再被过滤器拦，
 * 所以**不再有「资料未补全就强制改道 /profile」这条规则**。现在的入口是：
 *   - 首次登录 → `/welcome` 收集页，可跳过（`app/welcomeGate.ts`）；
 *   - 之后 → Shell 顶部可关闭的提示条；
 *   - 教师建班/发词 → 端点返回 403 `PROFILE_INCOMPLETE`，当前页面显示错误，不再整页改道。
 * 本文件只保留「补全后回哪里」的 URL 工具（登录、资料页保存都在用）。
 *
 * 纯函数、不碰 DOM，好让 `web/tests/profile-gate.test.mjs` 直接测。
 */

import { safeNextPath } from './redirect.ts'

export const PROFILE_PATH = '/profile'

/**
 * 改道到资料页时把原地址带上（`/profile?next=/classes`），补全保存后再回去。
 *
 * 2026-10-24 修过一次「登录后链接丢了」：学生从班群点开预习链接，登录完被丢回首页，
 * 作业就找不到了。这次新增改道不能把它带回来——被拦下的深链同样是「有意义的原地址」。
 */
export function profilePathWithNext(next: string | null | undefined): string {
  return next ? `${PROFILE_PATH}?next=${encodeURIComponent(next)}` : PROFILE_PATH
}

/**
 * 从 `/profile?next=…` 取回补全后要去的地址。
 *
 * 直接复用登录回跳的 `safeNextPath`：只认站内绝对路径，其余一律丢掉（防开放重定向）。
 * 同一套校验不写两遍——2026-10-24 踩过的坑，别再分叉。
 */
export function nextAfterProfile(raw: string | null | undefined): string | null {
  return safeNextPath(raw)
}
