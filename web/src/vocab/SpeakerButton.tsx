import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button, PauseCircleOutlined, SoundOutlined } from '../ui'
import type { ButtonVariant } from '../ui'

export type AudioAccent = 'uk' | 'us'
export type AudioLanguage = 'en' | 'zh' | 'es'

type SpeakerButtonBaseProps = {
  compact?: boolean
  variant?: ButtonVariant
  /** 复习页的 U / A 等快捷键，提示写进 title */
  shortcut?: string
}

/**
 * 两种模式二选一（决策 019）：
 * - 单词：`form`（+ 可选 `accent`），请求 `?form=...&accent=...`；
 * - 例句：`text`（+ 可选 `lang`），请求 `?text=...&lang=...`。
 */
export type SpeakerButtonProps =
  | (SpeakerButtonBaseProps & {
      form: string
      accent?: AudioAccent
      text?: never
      lang?: never
    })
  | (SpeakerButtonBaseProps & {
      text: string
      lang?: AudioLanguage
      accent?: AudioAccent
      form?: never
    })

/**
 * 浏览器 SpeechSynthesis 的兜底语言：例句按 `lang` 选，单词按口音选。
 * 与后端契约保持一致：en→en-US、zh→zh-CN、es→es-ES；传 uk 口音时 en→en-GB。
 */
export function fallbackSpeechLang(
  lang: AudioLanguage | undefined,
  accent: AudioAccent | undefined,
): string {
  if (lang === 'zh') return 'zh-CN'
  if (lang === 'es') return 'es-ES'
  return accent === 'uk' ? 'en-GB' : 'en-US'
}

/**
 * 2026-10-31（Bug：TTS 尾音杂音 / inflect 重音）：
 * 不给 `SpeechSynthesisUtterance` 指定 `voice` 时由浏览器自己挑音色，macOS 上常落到某个
 * 本机音色（Samantha 一类）。同一个词在不同音色上的音高轮廓不同（可测：`inflect` 在
 * Samantha 上 F0 单调下降、第二音节没有音高突起；Piper/Google 音色在第二音节抬起）。
 * 所以这里**显式**挑音色：先 `Google` 开头的音色，再 lang 完全匹配的本机音色。
 */
export function pickFallbackVoice(
  voices: SpeechSynthesisVoice[],
  lang: string,
): SpeechSynthesisVoice | null {
  if (voices.length === 0) return null
  const target = lang.toLowerCase()
  const exact = voices.filter((voice) => voice.lang.toLowerCase() === target)
  // Google 音色的名字跟着浏览器 UI 语言变（英文 Google US English / 繁体 Google 國語（臺灣）/
  // 西语 Google español），所以只认前缀，不写死名字。
  const google = exact.find((voice) => voice.name.startsWith('Google'))
  if (google) return google
  const local = exact.find((voice) => voice.localService)
  if (local) return local
  if (exact.length > 0) return exact[0]
  const prefix = target.split('-')[0]
  return (
    voices.find((voice) => voice.lang.toLowerCase().startsWith(prefix)) ??
    voices.find((voice) => voice.default) ??
    null
  )
}

/** `voiceschanged` 之前 `getVoices()` 可能是空数组（Chrome 异步加载音色），缓存最后一次非空结果。 */
let voiceCache: SpeechSynthesisVoice[] = []

function cachedVoices(): SpeechSynthesisVoice[] {
  if (!('speechSynthesis' in window)) return voiceCache
  const list = window.speechSynthesis.getVoices()
  if (list.length > 0) voiceCache = list
  return voiceCache
}

/** 首次点击时音色可能还没加载完；等一小会儿再挑，别落到浏览器默认音色。 */
function waitForVoices(timeoutMs = 800): Promise<SpeechSynthesisVoice[]> {
  return new Promise((resolve) => {
    const synth = 'speechSynthesis' in window ? window.speechSynthesis : null
    const loaded = cachedVoices()
    if (!synth || loaded.length > 0) {
      resolve(loaded)
      return
    }
    let settled = false
    let timer = 0
    const done = () => {
      if (settled) return
      settled = true
      window.clearTimeout(timer)
      synth.removeEventListener('voiceschanged', done)
      resolve(cachedVoices())
    }
    timer = window.setTimeout(done, timeoutMs)
    synth.addEventListener('voiceschanged', done)
  })
}

/**
 * 单词 / 例句发音按钮（T410/T411/T427 + 019）：请求后端音频（缓存→本地引擎）；
 * 失败时回落浏览器 SpeechSynthesis，不弹阻塞错误。点击播放中再点一次停止。
 *
 * <p>句子模式（`text`）用于明细页与复习卡里的例句：首次点击才合成，服务端关掉
 * 例句朗读时端点返回 404，这里照常回落浏览器语音，所以前端不需要感知开关。
 *
 * <p>播放结束/停止时释放 object URL，避免残留解码器在结尾产生噪音；
 * 浏览器回落保留 utterance 引用，否则被 GC 回收时 Chrome/Safari 会把结尾吞掉。
 *
 * <p>2026-10-31：浏览器回落的音色改为显式挑选（Google 优先、本机 lang 匹配次之），
 * 不再交给浏览器默认音色；结束/停止时也只在真的还在播时 cancel，避免结尾那一声杂音。
 */
export function SpeakerButton(props: SpeakerButtonProps) {
  const { compact = false, variant = 'default', shortcut, accent } = props
  const { t } = useTranslation()
  const sample = props.text ?? props.form
  const sentenceMode = typeof props.text === 'string' && props.text.length > 0

  const [playing, setPlaying] = useState(false)
  const [loading, setLoading] = useState(false)
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const urlRef = useRef<string | null>(null)
  const utteranceRef = useRef<SpeechSynthesisUtterance | null>(null)
  // 每次播放/停止 +1：请求回来时若已停止或卸载，就不再出声、不再改 state。
  const tokenRef = useRef(0)

  const releaseUrl = () => {
    if (urlRef.current) {
      URL.revokeObjectURL(urlRef.current)
      urlRef.current = null
    }
  }

  const cancelSpeech = () => {
    const utterance = utteranceRef.current
    utteranceRef.current = null
    // 只在真的还有声音时打断：已经结束的 utterance 再 cancel 一次，尾音处会留一声「咔哒」
    if (utterance && 'speechSynthesis' in window && window.speechSynthesis.speaking) {
      window.speechSynthesis.cancel()
    }
  }

  const stop = () => {
    tokenRef.current += 1
    audioRef.current?.pause()
    audioRef.current = null
    releaseUrl()
    cancelSpeech()
    setPlaying(false)
    setLoading(false)
  }

  useEffect(() => () => {
    tokenRef.current += 1
    audioRef.current?.pause()
    audioRef.current = null
    releaseUrl()
    cancelSpeech()
  }, [])

  // 浏览器回落：语速略放慢（默认 1.0 太快）；音色显式挑（pickFallbackVoice）。
  // onend 之后不能再 cancel：Chrome 会把最后一小段吞掉，听感就是结尾被剪/有杂音。
  const fallback = async (token: number) => {
    setLoading(false)
    if (!sample || !('speechSynthesis' in window)) {
      setPlaying(false)
      return
    }
    const voices = await waitForVoices()
    if (tokenRef.current !== token) return
    // element.onerror 与 play() 的 catch 可能同时进来；已经有一条在播就不重复开口
    if (utteranceRef.current) return
    const synth = window.speechSynthesis
    const lang = fallbackSpeechLang(props.lang, accent)
    const speakWith = (voice: SpeechSynthesisVoice | null, allowRetry: boolean) => {
      const utterance = new SpeechSynthesisUtterance(sample)
      utterance.lang = lang
      if (voice) utterance.voice = voice
      utterance.rate = 0.9
      utterance.pitch = 1
      utterance.volume = 1
      utteranceRef.current = utterance
      utterance.onend = () => {
        if (utteranceRef.current !== utterance) return
        utteranceRef.current = null
        setPlaying(false)
      }
      utterance.onerror = (event) => {
        if (utteranceRef.current !== utterance) return
        const error = (event as SpeechSynthesisErrorEvent).error
        // 被我们自己 cancel 掉的旧 utterance 也会走到 error，不算失败
        if (error === 'canceled' || error === 'interrupted') return
        // Google 音色需要联网：断网时换本机音色重放一次，别让「回落」变成没声音
        const local = voices.find(
          (candidate) => candidate.lang.toLowerCase() === lang.toLowerCase() && candidate.localService,
        )
        if (allowRetry && local && local !== voice) {
          utteranceRef.current = null
          speakWith(local, false)
          return
        }
        utteranceRef.current = null
        setPlaying(false)
      }
      if (synth.speaking || synth.pending) synth.cancel()
      setPlaying(true)
      synth.speak(utterance)
    }
    speakWith(pickFallbackVoice(voices, lang), true)
  }

  const speak = async () => {
    if (playing) {
      stop()
      return
    }
    if (!sample) return
    const token = tokenRef.current + 1
    tokenRef.current = token
    setPlaying(true)
    setLoading(true)
    try {
      const path = sentenceMode
        ? `/api/vocab/audio?text=${encodeURIComponent(sample)}&lang=${props.lang ?? 'en'}`
        : `/api/vocab/audio?form=${encodeURIComponent(sample)}${accent ? `&accent=${accent}` : ''}&v=2`
      const response = await fetch(path, { credentials: 'include' })
      if (!response.ok) throw new Error('tts unavailable')
      const blob = await response.blob()
      if (tokenRef.current !== token) return
      releaseUrl()
      urlRef.current = URL.createObjectURL(blob)
      const element = new Audio(urlRef.current)
      audioRef.current = element
      element.onended = () => {
        if (tokenRef.current !== token) return
        audioRef.current = null
        releaseUrl()
        setPlaying(false)
        setLoading(false)
      }
      element.onerror = () => {
        if (tokenRef.current !== token) return
        audioRef.current = null
        releaseUrl()
        void fallback(token)
      }
      await element.play()
      if (tokenRef.current !== token) return
      setLoading(false)
    } catch {
      if (tokenRef.current !== token) return
      audioRef.current = null
      releaseUrl()
      void fallback(token)
    }
  }

  // 键盘绑定放在组件里：图标与快捷键走同一条播放路径，播放态也能落在图标上。
  const speakRef = useRef<() => void>(() => {})
  speakRef.current = () => void speak()

  useEffect(() => {
    if (!shortcut) return
    const onKey = (event: KeyboardEvent) => {
      if (event.metaKey || event.ctrlKey || event.altKey) return
      const target = event.target as HTMLElement | null
      if (target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) return
      if (event.key.toLowerCase() !== shortcut.toLowerCase()) return
      event.preventDefault()
      speakRef.current()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [shortcut])

  const label = playing
    ? t('vocab.audio.stop')
    : sentenceMode
      ? t('vocab.audio.playExample')
      : accent === 'uk'
        ? t('vocab.audio.playUk')
        : accent === 'us'
          ? t('vocab.audio.playUs')
          : t('vocab.audio.play')
  const title = shortcut ? `${label} · ${shortcut.toUpperCase()}` : label
  // 句子喇叭在正文里是「轻操作」：图标按钮，不占一行文字。
  const effectiveCompact = sentenceMode || compact
  const effectiveVariant: ButtonVariant = sentenceMode ? 'text' : variant

  return (
    <Button
      size="small"
      variant={effectiveVariant}
      icon={playing && !loading ? <PauseCircleOutlined /> : <SoundOutlined />}
      busy={loading}
      aria-label={label}
      aria-pressed={playing}
      title={title}
      onClick={() => void speak()}
    >
      {effectiveCompact ? null : label}
    </Button>
  )
}
