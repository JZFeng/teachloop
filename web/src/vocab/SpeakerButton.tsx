import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button, SoundOutlined } from '../ui'

/**
 * 单词发音按钮（T410/T411）：请求后端音频（缓存→本地引擎）；失败时回落浏览器
 * SpeechSynthesis，不弹阻塞错误。点击播放中再点一次停止。
 *
 * <p>播放结束/停止时释放 object URL，避免残留解码器在结尾产生噪音。
 */
export function SpeakerButton({ form, compact = false }: { form: string; compact?: boolean }) {
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
      utterance.lang = 'en-US'
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
      const response = await fetch(`/api/vocab/audio?form=${encodeURIComponent(form)}`, {
        credentials: 'include',
      })
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

  return (
    <Button
      size="small"
      icon={<SoundOutlined />}
      busy={playing}
      aria-label={playing ? t('vocab.audio.stop') : t('vocab.audio.play')}
      title={playing ? t('vocab.audio.stop') : t('vocab.audio.play')}
      onClick={() => void speak()}
    >
      {compact ? null : (playing ? t('vocab.audio.stop') : t('vocab.audio.play'))}
    </Button>
  )
}
