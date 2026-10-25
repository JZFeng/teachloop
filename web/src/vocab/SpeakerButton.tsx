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
 * 单词 / 例句发音按钮（T410/T411/T427 + 019）：请求后端音频（缓存→本地引擎）；
 * 失败时回落浏览器 SpeechSynthesis，不弹阻塞错误。点击播放中再点一次停止。
 *
 * <p>句子模式（`text`）用于明细页与复习卡里的例句：首次点击才合成，服务端关掉
 * 例句朗读时端点返回 404，这里照常回落浏览器语音，所以前端不需要感知开关。
 *
 * <p>播放结束/停止时释放 object URL，避免残留解码器在结尾产生噪音；
 * 浏览器回落保留 utterance 引用，否则被 GC 回收时 Chrome/Safari 会把结尾吞掉。
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
    if (utteranceRef.current && 'speechSynthesis' in window) {
      window.speechSynthesis.cancel()
    }
    utteranceRef.current = null
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

  // 浏览器回落：语速略放慢（默认 1.0 太快）；播放前先 cancel 上一条。
  const fallback = () => {
    setLoading(false)
    if (!sample || !('speechSynthesis' in window)) {
      setPlaying(false)
      return
    }
    const synth = window.speechSynthesis
    synth.cancel()
    const utterance = new SpeechSynthesisUtterance(sample)
    utterance.lang = fallbackSpeechLang(props.lang, accent)
    utterance.rate = 0.9
    utterance.pitch = 1
    utterance.volume = 1
    utteranceRef.current = utterance
    const done = () => {
      if (utteranceRef.current !== utterance) return
      utteranceRef.current = null
      setPlaying(false)
    }
    utterance.onend = done
    utterance.onerror = done
    setPlaying(true)
    synth.speak(utterance)
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
        fallback()
      }
      await element.play()
      if (tokenRef.current !== token) return
      setLoading(false)
    } catch {
      if (tokenRef.current !== token) return
      audioRef.current = null
      releaseUrl()
      fallback()
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
