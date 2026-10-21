import i18n from '../i18n'
import type { ApiErrorBody } from './types'

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly detail?: string | null

  constructor(status: number, body: ApiErrorBody | null) {
    super(body?.message ?? `HTTP ${status}`)
    this.status = status
    this.code = body?.code ?? 'UNKNOWN'
    this.detail = body?.detail
  }
}

/** T033：生产环境双提交 CSRF；开发环境没有 cookie 时自然不带。 */
export function csrfHeaders(): Record<string, string> {
  const match = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))
  if (!match) return {}
  return { 'X-XSRF-TOKEN': decodeURIComponent(match.slice('XSRF-TOKEN='.length)) }
}

export async function ensureCsrf(): Promise<void> {
  try {
    await fetch('/api/csrf', { credentials: 'include' })
  } catch {
    // 后端未启用 CSRF 或离线时不影响主流程
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { ...csrfHeaders() }
  // 后端按 Accept-Language 决定 message 语言；跟着界面语言走，而不是浏览器的语言。
  headers['Accept-Language'] = i18n.language || 'zh-CN'
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const res = await fetch(path, {
    method,
    credentials: 'include',
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!res.ok) {
    let parsed: ApiErrorBody | null = null
    try {
      parsed = (await res.json()) as ApiErrorBody
    } catch {
      // 非 JSON 错误体（网关 502 等）也走 ApiError
    }
    throw new ApiError(res.status, parsed)
  }
  if (res.status === 204) {
    return undefined as T
  }
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body),
  del: <T>(path: string) => request<T>('DELETE', path),
}

/** 把后端时间（ISO 8601，UTC）格式化为本地时间；空值返回 "—"。 */
export function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  // 跟随界面语言：中文界面显示 2026/10/20 22:18，而不是浏览器默认的 10/20/2026, 10:18 PM。
  return d.toLocaleString(i18n.language || undefined, {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  })
}

export function fmtDuration(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  const pad = (n: number) => String(n).padStart(2, '0')
  return h > 0 ? `${h}:${pad(m)}:${pad(sec)}` : `${pad(m)}:${pad(sec)}`
}
