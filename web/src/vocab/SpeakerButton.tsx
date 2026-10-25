import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button, SoundOutlined } from '../ui'
import type { ButtonVariant } from '../ui'

export type AudioAccent = 'uk' | 'us'

/**
 * 单词发音按钮（T410/T411/T427）：请求后端音频（缓存→本地引擎）；失败时回落浏览器
 * SpeechSynthesis，不弹阻塞错误。点击播放中再点一次停止。
 *
 * <p>T427：`accent` 决定口音——后端按音色合成，浏览器回落用 en-GB / en-US；
 * `shortcut` 给复习页的 U / A 绑定键盘，提示写进 title。
 *
 * <p>播放结束/停止时释放 object URL，避免残留解码器在结尾产生噪音。
 */
export function SpeakerButton({
  form,
  compact = false,
  accent,
  variant = 'default',
  shortcut,
}: {
  form: string
  compact?: boolean
  accent?: AudioAccent
  variant?: ButtonVariant
  shortcut?: string
}) {
  const { t } = useTranslation()
  const [playing, setPlaying] = useState(false)
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const urlRef = useRef<string | null>(null)

  const release = () => {
    if (urlRef.current) {
      URL.revokeObjectURL(urlRef.current)
      urlRef.current = null
    }
  }

  const stop = () => {
    audioRef.current?.pause()
    audioRef.current = null
    release()
    setPlaying(false)
  }

  useEffect(() => () => {
    audioRef.current?.pause()
    audioRef.current = null
    release()
  }, [])

  const fallback = () => {
    if ('speechSynthesis' in window) {
      const utterance = new SpeechSynthesisUtterance(form)
      utterance.lang = accent === 'uk' ? 'en-GB' : 'en-US'
      window.speechSynthesis.speak(utterance)
    }
  }

  const speak = async () => {
    if (playing) {
      stop()
      return
    }
    setPlaying(true)
    try {
      const accentQuery = accent ? `&accent=${accent}` : ''
      const response = await fetch(
        `/api/vocab/audio?form=${encodeURIComponent(form)}${accentQuery}&v=2`,
        { credentials: 'include' },
      )
      if (!response.ok) throw new Error('tts unavailable')
      const blob = await response.blob()
      release()
      urlRef.current = URL.createObjectURL(blob)
      const element = new Audio(urlRef.current)
      audioRef.current = element
      element.onended = () => {
        setPlaying(false)
        audioRef.current = null
        release()
      }
      element.onerror = () => {
        setPlaying(false)
        audioRef.current = null
        release()
        fallback()
      }
      await element.play()
    } catch {
      setPlaying(false)
      audioRef.current = null
      release()
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
    : accent === 'uk'
      ? t('vocab.audio.playUk')
      : accent === 'us'
        ? t('vocab.audio.playUs')
        : t('vocab.audio.play')
  const title = shortcut ? `${label} · ${shortcut.toUpperCase()}` : label

  return (
    <Button
      size="small"
      variant={variant}
      icon={<SoundOutlined />}
      busy={playing}
      aria-label={label}
      title={title}
      onClick={() => void speak()}
    >
      {compact ? null : label}
    </Button>
  )
}
