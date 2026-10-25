import { api } from './client'
import type { AiReadinessView } from './types'

/**
 * AI 就绪状态的前端探测（教师 / 管理员可用；学生调用会 403）。
 *
 * **失败一律放行**：`GET /api/ai-readiness` 报错（一期后端可能还没部署、404，或网关抖动）时
 * `getAiReadiness()` 返回 null，`isAIReady()` / `isAIConfigured()` 返回 true。
 * 理由：这个 GET 只是「提前给一句人话提示」的优化，真正的门禁是后端 dispatch 的 409 守卫；
 * 如果前端把一次只读探测失败当成「AI 不可用」，网络一抖就会挡住合法的批改请求，
 * 等于自己制造了一个更严重的故障。所以这里宁可不提示，也不误伤。
 *
 * **不做模块级结果缓存**：管理员刚在资料页配好模型，教师下一次点击就必须拿到新状态，
 * 因此只做「飞行中去重」，请求一落地就清空。
 */
let inflight: Promise<AiReadinessView | null> | null = null

export function getAiReadiness(): Promise<AiReadinessView | null> {
  if (inflight) return inflight
  // 失败吞成 null：见上方「失败一律放行」。
  const promise: Promise<AiReadinessView | null> = api
    .get<AiReadinessView>('/api/ai-readiness')
    .catch(() => null)
    .finally(() => {
      if (inflight === promise) inflight = null
    })
  inflight = promise
  return promise
}

/** 现在批改能不能出分（含 stub 替身）。接口不可用时返回 true，交由后端守卫兜底。 */
export async function isAIReady(): Promise<boolean> {
  const view = await getAiReadiness()
  return view === null ? true : view.ready
}

/**
 * 是否有真的 AI 模型（provider 或环境变量 Key），**不算 stub**。
 * 用于「必须由真模型生成」的入口（如背单词内容生成：stub 不出内容）。
 * 接口不可用时同样返回 true，放行。
 */
export async function isAIConfigured(): Promise<boolean> {
  const view = await getAiReadiness()
  return view === null ? true : view.configured
}
