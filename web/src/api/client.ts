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

/**
 * 2026-10-27（Bug）：改密码第一次点保存必然 403，刷新再点就成功。
 * 原因：浏览器里的 XSRF-TOKEN cookie 丢失/轮换后，首个写请求没有带上有效 token，
 * 服务端 403 的同时会回写一个新的 XSRF-TOKEN，所以第二次就过了。
 * 处理：写请求前若没有 token 先取一次；仍然 403 就换新 token 重试一次（首个请求已被拒绝，无副作用）。
 */
async function sendWithCsrf<T>(method: string, path: string, body: unknown,
                               extraHeaders?: Record<string, string>): Promise<Response> {
  const build = (): Promise<Response> => {
    const headers: Record<string, string> = { ...csrfHeaders(), ...extraHeaders }
    // 后端按 Accept-Language 决定 message 语言；跟着界面语言走，而不是浏览器的语言。
    headers['Accept-Language'] = i18n.language || 'zh-CN'
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    return fetch(path, {
      method,
      credentials: 'include',
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  }
  if (method !== 'GET' && Object.keys(csrfHeaders()).length === 0) {
    await ensureCsrf()
  }
  let res = await build()
  if (res.status === 403 && method !== 'GET') {
    await ensureCsrf()
    res = await build()
  }
  return res
}

async function request<T>(method: string, path: string, body?: unknown,
                          extraHeaders?: Record<string, string>): Promise<T> {
  const res = await sendWithCsrf<T>(method, path, body, extraHeaders)
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

async function requestForm<T>(path: string, form: FormData): Promise<T> {
  // multipart 的 Content-Type（含 boundary）必须由浏览器自己写，手写会丢 boundary。
  const headers: Record<string, string> = { ...csrfHeaders(), 'Accept-Language': i18n.language || 'zh-CN' }
  const res = await fetch(path, { method: 'POST', credentials: 'include', headers, body: form })
  if (!res.ok) {
    let parsed: ApiErrorBody | null = null
    try {
      parsed = (await res.json()) as ApiErrorBody
    } catch {
      // 非 JSON 错误体（网关 502 等）也走 ApiError
    }
    throw new ApiError(res.status, parsed)
  }
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown, headers?: Record<string, string>) => request<T>('POST', path, body, headers),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body),
  del: <T>(path: string) => request<T>('DELETE', path),
  /** 文件上传（multipart）；试卷「生成考题」用它。 */
  postForm: <T>(path: string, form: FormData) => requestForm<T>(path, form),
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
