import { api } from '../api/client'

/**
 * T426：按需准备。学生开始复习（或打开词详情）时，只为马上要用的前 N 个词入队内容生成；
 * 之后按窗口懒加载放大，不再导入即全量准备。
 *
 * 窗口取 20 → 100 → 200 → 400 → 800 → 1600 → 3200：首批保证「点了就有」，
 * 后面在学生真正背到那一段之前提前铺开，避免每次翻页都等。
 */
export const PREPARE_STEPS = [20, 100, 200, 400, 800, 1600, 3200]

export type PrepareScope = 'TODAY' | 'SESSION' | 'WORD'

export interface PrepareResult {
  scope: string
  requested: number
  ready: number
  pending: number
  failed: number
}

/** 当前窗口之后的下一个窗口；已经到最大窗口时返回原值。 */
export function nextPrepareWindow(current: number): number {
  return PREPARE_STEPS.find((step) => step > current) ?? PREPARE_STEPS[PREPARE_STEPS.length - 1]
}

export interface PrepareTarget {
  sessionId?: string
  wordId?: string
  language?: string
}

export function prepareWords(scope: PrepareScope, upTo: number, target: PrepareTarget = {}) {
  return api.post<PrepareResult>('/api/vocab/review/prepare', { scope, upTo, ...target })
}
