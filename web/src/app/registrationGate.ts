/*
 * 注册入口的可用性判断（2026-10-28 Bug：生产注册恒 404「该注册方式不可用」）。
 *
 * 后端 `GET /api/auth/channels` 只下发「开关打开 + 配置齐全」的通道
 * （`ChannelRegistry.available`：`identity_channels.enabled` 为真，且通道实现 `configured()`）。
 * 事故根因是邮件通道 `enabled=false`：接口返回 `[]`，而注册页把 `channel: 'email'` 写死
 * 并照常渲染表单，用户填完姓名/邮箱/密码才拿到一个语义为「通道不存在」的 404。
 *
 * 这里把判断抽成纯函数：页面据此决定「渲染表单」还是「直说暂不可用」，
 * 管理端据此提示「已保存但未启用」，node 测试（`web/tests/registration-gate.test.mjs`）直接覆盖。
 */

export interface ChannelSummary {
  channel: string
}

/** 注册页是否渲染表单：只有邮箱通道真的可用才渲染。空列表 = 没有任何注册方式。 */
export function emailSignupOpen(channels: ChannelSummary[]): boolean {
  return channels.some((c) => c.channel === 'email')
}

export type ChannelReadiness = 'ready' | 'disabled' | 'incomplete' | 'unreadable'

export interface ChannelConfigState {
  enabled: boolean
  fromAddress: string | null
  baseUrl: string | null
  hasSecret: boolean
  /** 后端解不开密钥时为 true（`TEACHLOOP_SECURITY_SECRET` 变过），按未配置处理。 */
  secretUnreadable: boolean
}

/** 与后端 `IdentityChannelAdminEndpoint` 同一条形状校验：拦「teachloop admin」这类显示名。 */
function looksLikeEmail(value: string | null): boolean {
  return !!value && /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(value.trim())
}

/**
 * 通道在「用户能否注册/登录」这件事上到底处在哪一档。
 *
 * 顺序有意为之：密钥解不开 > 三项不齐 > 没启用。前两档光开开关也不生效，
 * 提示要先说配置问题，否则用户会以为「勾上就行了」。
 */
export function channelReadiness(row: ChannelConfigState): ChannelReadiness {
  if (row.secretUnreadable) return 'unreadable'
  if (!looksLikeEmail(row.fromAddress) || !row.baseUrl?.trim() || !row.hasSecret) return 'incomplete'
  return row.enabled ? 'ready' : 'disabled'
}
