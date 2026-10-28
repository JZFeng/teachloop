import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
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
  refreshProfile: () => Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null)
  const [loading, setLoading] = useState(true)
  const [profileComplete, setProfileComplete] = useState<boolean | null>(null)

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
    try {
      const p = await api.get<ProfileLite>('/api/profile')
      setProfileComplete(Boolean(p.phone?.trim() && p.email?.trim()))
    } catch {
      setProfileComplete(null)
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  useEffect(() => {
    if (me) void refreshProfile()
    else setProfileComplete(null)
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

  const value = useMemo(
    () => ({ me, loading, login, logout, refresh, profileComplete, refreshProfile }),
    [me, loading, login, logout, refresh, profileComplete, refreshProfile],
  )
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth 必须在 AuthProvider 内使用')
  return ctx
}
