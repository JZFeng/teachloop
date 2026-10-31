import { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { api } from './client'
import { useAuth } from './auth'

/**
 * 后端 /api/features 的形状（007-role-nav）：`key → 是否可用` 的扁平布尔。
 *
 * 里面同时装两类 key：
 * - `nav.*`：角色菜单可见性（`RoleNavMatrix` 算好，前端不判断角色）
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
  /**
   * 上一轮的「资料是否补全」（011 / T557 / AC-90.7）。
   *
   * 2026-10-27 的 Bug 需要「补全资料后重取一次开关」；但把 `profileComplete` 的每次变化都当成
   * 需要重取，会在登录后出现第二次取数：`null → true` 时先 `setFetchedFor(null)`，`loaded` 变回
   * false，首页（今日页）被卸载、随后重新挂载，于是同一次打开发出两次 `GET /api/student/today`。
   * 今日页 AC-90.7 要求「首次加载只发一次」，所以这里只认 `false → 非 false` 这一种真实的
   * 「刚补全」，其它变化沿用已有结果。
   */
  const wasIncomplete = useRef(profileComplete === false)
  /** 已经为哪个用户发起过取数（用 ref 而不是 state，避免把它写进依赖造成二次请求）。 */
  // 2026-10-30（AC-3.12）：记忆键从 me.id 改成「id + role」。学生被邀请码升成教师时 id 不变、
  // 只有 role 变；只按 id 记忆会命中下面那句短路 return，侧栏就一直停在学生那套。
  const requestedFor = useRef<string | null>(null)

  useEffect(() => {
    if (loading) return
    if (!me) {
      setFeatures(OFF)
      setFetchedFor(null)
      requestedFor.current = null
      wasIncomplete.current = false
      return
    }
    const justCompleted = wasIncomplete.current && profileComplete !== false
    wasIncomplete.current = profileComplete === false

    /*
     * 2026-10-30 Bug（用户截图里侧栏一个菜单都没有）：这里原先有一句
     * `if (profileComplete === false) { setFeatures(OFF); … return }`，理由是「资料没补全时
     * /api/features 会被资料过滤器拦成 403，这一枪不用打」。T561（同日）已经让后端不再 403、
     * 改成只标记属性，于是这句短路变成一个**前端自伤**：
     *   - 侧栏 nav 全为空（学生只有「主题 / 自己 / 退出」三项）；
     *   - `/` 上 HomeRouter 看到 `nav.home !== true` → `landingFor({})` → `/profile`，
     *     未填手机号的学生因此被顶到资料页，与 AC-93.1（访问 `/` 返回 200）相反。
     * 现在照常取数：接口 200 就按真实 key 渲染，仍然失败（真正 403/网络问题）走下面的 catch → OFF。
     */
    // 已经为「这个用户 + 这个角色」取过、且不是「刚补全」→ 不再重取：重取会把已挂载的页面卸掉再挂回来。
    const requestKey = `${me.id}:${me.role ?? ''}`
    if (!justCompleted && requestedFor.current === requestKey) return

    let alive = true
    requestedFor.current = requestKey
    // 真正需要重取时（换用户 / 刚补全）按「未知」处理，避免把补全前的空集挂到深链上。
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
    // 2026-10-30（AC-3.12）：依赖里必须有 role —— 学生被邀请码升成教师后 me.id 不变，
    // 只有 role 变了；不重取 features 的话侧栏还是学生那套，得重新登录才看到教师菜单。
  }, [me?.id, me?.role, loading, profileComplete])

  const loaded = !loading && (me ? fetchedFor === me.id : true)
  const value = useMemo(() => ({ features, loaded }), [features, loaded])
  return <FeaturesContext.Provider value={value}>{children}</FeaturesContext.Provider>
}

export function useFeatures(): FeaturesState {
  return useContext(FeaturesContext)
}
