import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, fmtDateTime } from '../api/client'
import type { AiCircuitView } from '../api/types'
import { Alert } from '../ui'

/**
 * D-31：熔断横幅。只在 OPEN / HALF_OPEN 时出现；每 30 秒刷新一次，
 * 恢复后自动消失，不需要刷新页面。
 */
export function AiCircuitBanner() {
  const { t } = useTranslation('translation')
  const [view, setView] = useState<AiCircuitView | null>(null)

  useEffect(() => {
    let alive = true
    const load = () => {
      api.get<AiCircuitView>('/api/ai-circuit')
        .then((next) => { if (alive) setView(next) })
        .catch(() => { if (alive) setView(null) })
    }
    load()
    const timer = window.setInterval(load, 30_000)
    return () => {
      alive = false
      window.clearInterval(timer)
    }
  }, [])

  if (!view || view.state === 'CLOSED') return null
  const provider = view.providerDisplayName ?? view.providerKey ?? ''
  return (
    <Alert
      type="warning"
      showIcon
      style={{ marginBottom: 16 }}
      message={t('aiCircuit.title', { provider })}
      description={t('aiCircuit.body', {
        count: view.consecutiveFailures,
        reason: view.lastErrorKind ?? '—',
        next: view.nextProbeAt ? fmtDateTime(view.nextProbeAt) : '—',
      })}
    />
  )
}
