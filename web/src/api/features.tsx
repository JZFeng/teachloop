import { createContext, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { api } from './client'
import { useAuth } from './auth'

/**
 * 后端 /api/features 的形状（007-role-nav）：`key → 是否可用` 的扁平布尔。
 *
 * 里面同时装两类 key：
 * - `nav.*`：角色菜单可见性（后端 `RoleNavMatrix` 算好，前端不判断角色）
 * - `vocab`：模块是否上线（开关关闭时 vocab 路由不注册）
 */
export type Features = Record<string, boolean>

interface FeaturesState {
  features: Features
  /**
   * 当前用户的开关是否已经有确定答案：登录态解析完 + /api/features 已返回（成功或失败）。
   * 只有 loaded 为 true 时，路由才允许把未知路径重定向到首页——否则深链
   * /vocab/library 会在开关回来之前被通配路由吃掉（T389 浏览器验收发现的缺陷）。
   */
  loaded: boolean
}

const OFF: Features = {}

const FeaturesContext = createContext<FeaturesState>({ features: OFF, loaded: false })

/** 只收布尔，丢弃形状异常的值：宁可少显示，也不放空承诺。 */
function normalize(value: unknown): Features {
  const out: Features = {}
  if (value && typeof value === 'object') {
    for (const [key, flag] of Object.entries(value as Record<string, unknown>)) {
      out[key] = Boolean(flag)
    }
  }
  return out
}

export function FeaturesProvider({ children }: { children: ReactNode }) {
  const { me, loading, profileComplete } = useAuth()
  const [features, setFeatures] = useState<Features>(OFF)
  const [fetchedFor, setFetchedFor] = useState<string | null>(null)

  useEffect(() => {
    if (loading) return
    if (!me) {
      setFeatures(OFF)
      setFetchedFor(null)
      return
    }
    // 资料未补全时 /api/features 一定被 ProfileCompletionFilter 拦成 403，这一枪不用打；
    // 补全保存后 profileComplete 变 true，本 effect 重跑，侧栏才会重新出现（2026-10-27 Bug）。
    if (profileComplete === false) {
      setFeatures(OFF)
      setFetchedFor(me.id)
      return
    }
    let alive = true
    // 重新取期间按「未知」处理：补全资料后 profileComplete 由 false 变 true 会重跑本 effect，
    // 若此时仍挂着补全前的空集，RequireFeature 会把刚跳回的深链（如 /classes）又改写成落地页
    // （2026-10-27 验收发现：保存后 next 白带）。
    setFetchedFor(null)
    api.get<Features>('/api/features')
      .then((value) => {
        if (!alive) return
        setFeatures(normalize(value))
        setFetchedFor(me.id)
      })
      .catch(() => {
        // 拿不到就按关闭处理：宁可少显示，也不放空承诺。
        if (!alive) return
        setFeatures(OFF)
        setFetchedFor(me.id)
      })
    return () => {
      alive = false
    }
  }, [me?.id, loading, profileComplete])

  const loaded = !loading && (me ? fetchedFor === me.id : true)
  const value = useMemo(() => ({ features, loaded }), [features, loaded])
  return <FeaturesContext.Provider value={value}>{children}</FeaturesContext.Provider>
}

export function useFeatures(): FeaturesState {
  return useContext(FeaturesContext)
}
