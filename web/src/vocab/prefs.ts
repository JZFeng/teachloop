import { useEffect, useState } from 'react'
import { api } from '../api/client'

/** US-88：背单词偏好（与后端 `ReviewPrefs` record 对应）。 */
export interface ReviewPrefs {
  dailyNewCards: number
  strategy: 'REVIEW_FIRST' | 'MIXED' | 'NEW_FIRST'
  /** 反面 9 块的顺序（不决定有无；有无看 hidden） */
  blocks: string[]
  hidden: string[]
}

export function getReviewPrefs(): Promise<ReviewPrefs> {
  return api.get<ReviewPrefs>('/api/vocab/review-prefs')
}

export function saveReviewPrefs(prefs: ReviewPrefs): Promise<ReviewPrefs> {
  return api.put<ReviewPrefs>('/api/vocab/review-prefs', prefs)
}

// ---- 只读视图：卡面按偏好渲染（设置页负责写）----

let cachedPrefs: ReviewPrefs | null = null

/** 卡面用：拿到 blocks/hidden；失败就用默认顺序，绝不因为偏好接口挂了而显示不出卡。 */
export function useWordBlocks(): { blocks?: string[]; hidden?: string[] } {
  const [prefs, setPrefs] = useState<ReviewPrefs | null>(cachedPrefs)
  useEffect(() => {
    if (cachedPrefs) return
    getReviewPrefs()
      .then((value) => {
        cachedPrefs = value
        setPrefs(value)
      })
      .catch(() => undefined)
  }, [])
  return prefs ? { blocks: prefs.blocks, hidden: prefs.hidden } : {}
}
