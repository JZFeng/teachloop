/**
 * 资料未补全不是错误，是「先去补全资料」。
 *
 * 背景（2026-10-27 Bug）：后端 `ProfileCompletionFilter`（teachloop-identity）对缺手机号或邮箱的
 * 非管理员用户，除 `/api/auth/me`、`/api/profile` 外一律返回 403 `PROFILE_INCOMPLETE`。
 * 前端此前没有这个分支：学生第一次登录被 `navigate('/')` 丢进业务页，页面的统一取数钩子
 * 把这条 403 当成故障，渲染成「出错了：请先补全手机号与邮箱」的红框（截图落在 /classes）。
 *
 * 这里的判断只做一件事：**要不要把当前地址改成 /profile**。跳转本身由 `App.tsx` 的
 * `RequireProfile`（路由级）和 `LoginPage`（登录后第一跳）执行，接口级兜底见
 * `web/src/api/client.ts` 的 `setProfileIncompleteHandler`。
 *
 * 纯函数、不碰 DOM，好让 `web/tests/profile-gate.test.mjs` 直接测。
 */

import { safeNextPath } from './redirect.ts'

export const PROFILE_PATH = '/profile'

export interface ProfileGateInput {
  pathname: string
  role: string | null
  /** null 表示还没查到（不做判断）；true/false 来自 `/api/profile` 的实时结果。 */
  profileComplete: boolean | null
}

/**
 * 返回需要改去的地址，null 表示放行。
 *
 * - `profileComplete !== false`：未知或已补全都放行——宁可少拦，也不要因为一次查询失败把人锁死在资料页。
 * - 管理员放行：后端 `ProfileCompletionFilter` 对 ADMIN 豁免（CLI 建的账号可能本来就没有手机号），
 *   前端保持同一口径，否则管理员会被自己的资料页锁住。
 * - 已经在 /profile 放行：否则守卫会把自己无限重定向。
 */
export function profileDetour(input: ProfileGateInput): string | null {
  const { pathname, role, profileComplete } = input
  if (profileComplete !== false) return null
  if (role === 'ADMIN') return null
  if (pathname === PROFILE_PATH) return null
  return PROFILE_PATH
}

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
