import { createContext, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { api } from './client'
import { useAuth } from './auth'

/** 后端 /api/features 的形状：只放「模块是否上线」这类布尔开关。 */
export interface Features {
  vocab: boolean
}

interface FeaturesState {
  features: Features
  /**
   * 当前用户的开关是否已经有确定答案：登录态解析完 + /api/features 已返回（成功或失败）。
   * 只有 loaded 为 true 时，路由才允许把未知路径重定向到首页——否则深链
   * /vocab/library 会在开关回来之前被通配路由吃掉（T389 浏览器验收发现的缺陷）。
   */
  loaded: boolean
}

const OFF: Features = { vocab: false }

const FeaturesContext = createContext<FeaturesState>({ features: OFF, loaded: false })

export function FeaturesProvider({ children }: { children: ReactNode }) {
  const { me, loading } = useAuth()
  const [features, setFeatures] = useState<Features>(OFF)
  const [fetchedFor, setFetchedFor] = useState<string | null>(null)

  useEffect(() => {
    if (loading) return
    if (!me) {
      setFeatures(OFF)
      setFetchedFor(null)
      return
    }
    let alive = true
    api.get<Features>('/api/features')
      .then((value) => {
        if (!alive) return
        setFeatures({ vocab: Boolean(value?.vocab) })
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
  }, [me?.id, loading])

  const loaded = !loading && (me ? fetchedFor === me.id : true)
  const value = useMemo(() => ({ features, loaded }), [features, loaded])
  return <FeaturesContext.Provider value={value}>{children}</FeaturesContext.Provider>
}

export function useFeatures(): FeaturesState {
  return useContext(FeaturesContext)
}
