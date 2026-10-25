import { useEffect, useState } from 'react'
import { getAiReadiness } from '../api/ai'
import type { AiReadinessView } from '../api/types'

export interface AiReadinessState {
  /** 探测结果；接口 404 / 失败时为 null（= 不知道，按放行处理）。 */
  view: AiReadinessView | null
  loading: boolean
  /** 管理员刚配好模型后，页面上的「已检查」结果需要手动再拉一次。 */
  reload: () => void
}

/**
 * 页面级 AI 就绪状态。挂载时拉一次；需要重新检查时调用 reload()。
 *
 * 与 `useApiData` / `useListState` 保持同一套路：alive 标记防止卸载后 setState，
 * reload 用自增 tick 触发 effect。失败不抛错（getAiReadiness 已把失败吞成 null），
 * 所以页面上不会出现错误态，只是不显示「未配置 AI」的告警。
 */
export function useAiReadiness(): AiReadinessState {
  const [view, setView] = useState<AiReadinessView | null>(null)
  const [loading, setLoading] = useState(true)
  const [tick, setTick] = useState(0)

  useEffect(() => {
    let alive = true
    setLoading(true)
    getAiReadiness()
      .then((next) => {
        if (alive) setView(next)
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [tick])

  return { view, loading, reload: () => setTick((n) => n + 1) }
}
