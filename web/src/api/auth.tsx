import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { api, ensureCsrf } from './client'
import type { Me } from './types'

interface ProfileLite {
  phone: string | null
  email: string | null
}

interface AuthState {
  me: Me | null
  loading: boolean
  login: (email: string, password: string) => Promise<Me>
  logout: () => Promise<void>
  refresh: () => Promise<void>
  /** 手机号与邮箱是否都填了：null 表示还没查/查不到。顶部据此决定是否继续提示「资料」。 */
  profileComplete: boolean | null
  /**
   * 当前登录人的资料判定是否已经有答案（含「查询失败」）。守卫只在这之后才决定要不要改道，
   * 否则登录后的第一帧还没拿到结果，业务页会先渲染、再撞一串 403（2026-10-27 Bug）。
   */
  profileChecked: boolean
  /** 重算资料是否补全；返回结果本身，登录页据此决定第一跳去 /profile 还是回跳地址。 */
  refreshProfile: () => Promise<boolean | null>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null)
  const [loading, setLoading] = useState(true)
  const [profileComplete, setProfileComplete] = useState<boolean | null>(null)
  // 判定挂在哪个用户身上：换人（登出再登录）后旧结果不算数，守卫要等新结果。
  const [profileCheckedFor, setProfileCheckedFor] = useState<string | null>(null)
  const meRef = useRef<Me | null>(null)
  meRef.current = me

  const refresh = useCallback(async () => {
    try {
      setMe(await api.get<Me>('/api/auth/me'))
    } catch {
      setMe(null)
    } finally {
      setLoading(false)
    }
  }, [])

  /**
   * 资料是否补全：与后端 ProfileCompletionFilter 同一判定（手机号 + 邮箱都非空）。
   * 单独查 /api/profile 而不是塞进 /api/auth/me，是为了补全保存后能立刻重算、顶部提示立即消失。
   */
  const refreshProfile = useCallback(async () => {
    const ownerId = meRef.current?.id ?? null
    try {
      const p = await api.get<ProfileLite>('/api/profile')
      const complete = Boolean(p.phone?.trim() && p.email?.trim())
      setProfileComplete(complete)
      return complete
    } catch {
      // 查不到就按「未知」处理：宁可少拦，也不要因为一次网络抖动把人锁死在资料页。
      setProfileComplete(null)
      return null
    } finally {
      if (ownerId) setProfileCheckedFor(ownerId)
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  useEffect(() => {
    if (me) void refreshProfile()
    else {
      setProfileComplete(null)
      setProfileCheckedFor(null)
    }
  }, [me, refreshProfile])

  const login = useCallback(async (email: string, password: string) => {
    const user = await api.post<Me>('/api/auth/login', { email, password })
    // 2026-10-27（Bug）：登录后换一次 CSRF token，避免随后第一个写请求（如改密码）带着旧 token 被 403。
    await ensureCsrf()
    setMe(user)
    return user
  }, [])

  const logout = useCallback(async () => {
    try {
      await api.post('/api/auth/logout')
    } finally {
      setMe(null)
    }
  }, [])

  const profileChecked = me ? profileCheckedFor === me.id : false

  const value = useMemo(
    () => ({ me, loading, login, logout, refresh, profileComplete, profileChecked, refreshProfile }),
    [me, loading, login, logout, refresh, profileComplete, profileChecked, refreshProfile],
  )
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth 必须在 AuthProvider 内使用')
  return ctx
}
