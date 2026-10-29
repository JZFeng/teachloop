import { useEffect, useState } from 'react'

/** `GET /api/vocab/config` 的返回（目前只有例句朗读开关）。 */
interface VocabConfigResponse {
  sentenceAudio?: boolean
  quiz?: boolean
}

export interface VocabAudioConfig {
  /** 是否渲染例句喇叭；请求失败时为 true，靠浏览器语音兜底。 */
  sentenceAudio: boolean
  /** 小测开关（AC-87.10）：默认关，服务端说开才渲染入口。 */
  quiz: boolean
  /** 是否已经拿到服务端答案；`false` 时 `sentenceAudio` 是默认值。 */
  loaded: boolean
}

const DEFAULT_CONFIG: VocabAudioConfig = { sentenceAudio: true, quiz: false, loaded: false }
const FALLBACK_CONFIG: VocabAudioConfig = { sentenceAudio: true, quiz: false, loaded: true }

// 模块级缓存 + in-flight 去重：复习页一屏多个喇叭，或者查词页来回翻面，
// 都只该打一次 /api/vocab/config。
let cached: VocabAudioConfig | null = null
let inflight: Promise<VocabAudioConfig> | null = null

/**
 * 例句朗读开关（决策 019 / AC-57.6）：`sentenceAudio=false` 时不渲染喇叭。
 *
 * <p>失败不阻塞页面：后端没起或端点 404 时按 true 处理，喇叭再走浏览器
 * SpeechSynthesis 回落，播放体验仍然成立。失败结果同样缓存，避免每次挂载
 * 都重试；刷新页面才会重新探测。
 */
export function loadVocabAudioConfig(): Promise<VocabAudioConfig> {
  if (cached) return Promise.resolve(cached)
  if (inflight) return inflight
  const request = fetch('/api/vocab/config', { credentials: 'include' })
    .then(async (response) => {
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      const body = (await response.json()) as VocabConfigResponse
      const config: VocabAudioConfig = { sentenceAudio: body.sentenceAudio !== false, quiz: body.quiz === true, loaded: true }
      cached = config
      return config
    })
    .catch(() => {
      cached = FALLBACK_CONFIG
      return FALLBACK_CONFIG
    })
    .finally(() => {
      inflight = null
    })
  inflight = request
  return request
}

/** 组件里用的开关：首帧先给默认值（true），拿到服务端答案后更新。 */
export function useVocabAudioConfig(): VocabAudioConfig {
  const [config, setConfig] = useState<VocabAudioConfig>(cached ?? DEFAULT_CONFIG)

  useEffect(() => {
    if (cached) {
      setConfig(cached)
      return
    }
    let alive = true
    void loadVocabAudioConfig().then((value) => {
      if (alive) setConfig(value)
    })
    return () => {
      alive = false
    }
  }, [])

  return config
}
