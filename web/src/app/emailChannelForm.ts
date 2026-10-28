/*
 * 邮件通道表单的纯映射（2026-10-28 改版）。
 *
 * 管理端把邮件通道摊成「发件人名称 / 发件人邮箱 / SMTP 服务器 / 端口 / 加密方式 / 认证账号 / 授权码」
 * 七个字段，但库里只有三列可用：`from_address`、`base_url`（host:port）、`extra`（jsonb）。
 * 映射规则集中在下面两个函数里，页面只负责渲染：
 *   - `from_address` = 发件人邮箱（也是默认认证账号）
 *   - `base_url`     = `${host}:${port}`
 *   - `extra`        = { name, user, encryption }：发件人名称、认证账号、加密方式
 *
 * 抽成纯函数的原因：web/tests/email-channel-form.test.mjs 能直接断言映射，
 * 不用起浏览器，也不会出现「改了一个字段忘了写回 extra」这种只有上线才发现的问题。
 */

export type SmtpSecurity = 'ssl' | 'starttls' | 'none'

/** 后端 `GET /api/admin/identity-channels` 的一行（`extra` 是 jsonb 对象）。 */
export interface EmailChannelRow {
  channel: string
  enabled: boolean
  driver: string
  fromAddress: string | null
  baseUrl: string | null
  hasSecret: boolean
  secretMask: string | null
  secretUnreadable: boolean
  dailyQuota: number | null
  extra: Record<string, unknown> | null
}

/** 表单状态；`secret` 为空表示「不修改已保存的授权码」。 */
export interface EmailForm {
  enabled: boolean
  fromName: string
  fromAddress: string
  host: string
  port: string
  security: SmtpSecurity
  username: string
  secret: string
}

/** `PUT /api/admin/identity-channels/email` 的请求体。 */
export interface EmailUpdateBody {
  enabled: boolean
  driver: string
  fromAddress: string
  baseUrl: string
  secret: string | null
  dailyQuota: number | null
  extra: Record<string, string>
}

export const DEFAULT_SMTP_PORT = 465

/** 缺端口按 465（与后端 `SmtpEmailChannel` 同一约定），不是非法输入。 */
export function parseHostPort(baseUrl: string | null): { host: string; port: string } {
  const value = (baseUrl ?? '').trim()
  if (!value) {
    return { host: '', port: String(DEFAULT_SMTP_PORT) }
  }
  const idx = value.lastIndexOf(':')
  if (idx > 0) {
    const port = value.slice(idx + 1)
    if (/^\d+$/.test(port)) {
      return { host: value.slice(0, idx), port }
    }
  }
  return { host: value, port: String(DEFAULT_SMTP_PORT) }
}

function text(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function asSecurity(value: unknown): SmtpSecurity {
  return value === 'starttls' || value === 'none' ? value : 'ssl'
}

/** 库里的行 → 表单。认证账号不回填发件邮箱：留空由后端回落，改发件人时不会留下旧账号。 */
export function toEmailForm(row: EmailChannelRow): EmailForm {
  const extra = row.extra ?? {}
  const { host, port } = parseHostPort(row.baseUrl)
  return {
    enabled: row.enabled,
    fromName: text(extra.name),
    fromAddress: row.fromAddress ?? '',
    host,
    port,
    security: asSecurity(extra.encryption),
    username: text(extra.user),
    secret: '',
  }
}

/** 表单 → 请求体；空白的可选项不写进 extra，让后端走默认值。 */
export function buildEmailUpdate(form: EmailForm, dailyQuota: number | null): EmailUpdateBody {
  const extra: Record<string, string> = { encryption: form.security }
  if (form.fromName.trim()) extra.name = form.fromName.trim()
  if (form.username.trim()) extra.user = form.username.trim()
  return {
    enabled: form.enabled,
    driver: 'smtp',
    fromAddress: form.fromAddress.trim(),
    baseUrl: `${form.host.trim()}:${form.port.trim()}`,
    secret: form.secret.trim() ? form.secret : null,
    dailyQuota,
    extra,
  }
}

export type EmailFormIssue = 'host' | 'port' | 'fromAddress' | 'secret'

/**
 * 保存前的即时校验。`hasSecret` 为 true 时授权码留空是合法的（表示不修改）；
 * 首次配置（库里没有授权码）必须填，否则后端也会 400，这里只是先一步拦住。
 */
export function validateEmailForm(form: EmailForm, hasSecret: boolean): EmailFormIssue | null {
  if (!form.host.trim()) return 'host'
  const port = Number(form.port)
  if (!Number.isInteger(port) || port <= 0 || port > 65535) return 'port'
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(form.fromAddress.trim())) return 'fromAddress'
  if (!hasSecret && !form.secret.trim()) return 'secret'
  return null
}
