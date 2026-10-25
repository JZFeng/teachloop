/**
 * 登录后的回跳地址（2026-10-24 学生反馈：老师把预习链接发到班群，学生没登录，
 * 登录完被丢回首页，链接里的作业就找不到了）。
 *
 * 只接受**站内绝对路径**：`/` 开头，且不是 `//host`、`/\host` 这类协议相对地址，
 * 否则 `navigate()` 会把学生送到外站（开放重定向）。
 */
export function safeNextPath(raw: string | null | undefined): string | null {
  if (!raw) return null
  const value = raw.trim()
  if (!value.startsWith('/')) return null
  if (value.startsWith('//') || value.startsWith('/\\')) return null
  // 控制字符会让浏览器把地址解析成别的东西，直接判不在范围内。
  if (/[\u0000-\u001f\u007f]/.test(value)) return null
  return value
}

/** 当前地址（含查询与 hash）作为回跳地址；首页不需要带，避免登录后地址栏多一个 ?next=%2F。 */
export function loginNextFrom(pathname: string, search = '', hash = ''): string | null {
  if (!pathname || pathname === '/') return null
  return safeNextPath(`${pathname}${search}${hash}`)
}

/** `/login?next=…` 的地址，交给 RequireAuth 用。 */
export function loginPath(next: string | null): string {
  return next ? `/login?next=${encodeURIComponent(next)}` : '/login'
}
