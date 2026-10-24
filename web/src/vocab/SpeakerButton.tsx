import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button, SoundOutlined } from '../ui'

/**
 * 单词发音按钮（T410/T411）：请求后端音频（缓存→本地引擎）；失败时回落浏览器
 * SpeechSynthesis，不弹阻塞错误。点击播放中再点一次停止。
 */
export function SpeakerButton({ form, compact = false }: { form: string; compact?: boolean }) {
  const { t } = useTranslation()
  const [playing, setPlaying] = useState(false)
  const [audio, setAudio] = useState<HTMLAudioElement | null>(null)

  const stop = () => {
    audio?.pause()
    setAudio(null)
    setPlaying(false)
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
      const element = new Audio(URL.createObjectURL(blob))
      element.onended = () => { setPlaying(false); setAudio(null) }
      setAudio(element)
      await element.play()
    } catch {
      setPlaying(false)
      if ('speechSynthesis' in window) {
        const utterance = new SpeechSynthesisUtterance(form)
        utterance.lang = 'en-US'
        window.speechSynthesis.speak(utterance)
      }
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
