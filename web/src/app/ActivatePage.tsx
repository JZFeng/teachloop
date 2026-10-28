import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import { Alert, Button } from '../ui'
import AuthLayout from './AuthLayout'

type Status = 'loading' | 'OK' | 'EXPIRED' | 'USED' | 'INVALID' | 'DONE'

/**
 * 009 T537：激活确认页。
 *
 * GET 只读（邮件网关会预取链接），必须点按钮才 POST —— 所以这一屏先「展示」再「确认」。
 */
export default function ActivatePage() {
  const { t } = useTranslation('translation')
  const [params] = useSearchParams()
  const token = params.get('token') ?? ''
  const [status, setStatus] = useState<Status>('loading')
  const [email, setEmail] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!token) { setStatus('INVALID'); return }
    api.get<{ status: string; email?: string }>(`/api/auth/activate?token=${encodeURIComponent(token)}`)
      .then((r) => {
        setEmail(r.email ?? null)
        setStatus((['OK', 'EXPIRED', 'USED'] as const).find((s) => s === r.status) ?? 'INVALID')
      })
      .catch(() => setStatus('INVALID'))
  }, [token])

  const confirm = async () => {
    setBusy(true)
    setError(null)
    try {
      await api.post('/api/auth/activate', { token })
      setStatus('DONE')
    } catch (err) {
      setError(err instanceof ApiError ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  const foot = (
    <p className="auth-foot">
      <Link to="/login">{t('activate.toLogin')}</Link>
    </p>
  )

  if (status === 'loading') {
    return <AuthLayout title={t('activate.checking')}><p className="sub">{t('activate.pleaseWait')}</p></AuthLayout>
  }
  if (status === 'DONE') {
    return (
      <AuthLayout title={t('activate.doneTitle')} sub={t('activate.doneSub')}>
        <Alert type="success" showIcon message={t('activate.done')} style={{ marginBottom: 14 }} />
        {foot}
      </AuthLayout>
    )
  }
  if (status === 'OK') {
    return (
      <AuthLayout title={t('activate.title')} sub={email ? t('activate.subFor', { email }) : t('activate.sub')}>
        {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 14 }} />}
        <Button variant="primary" size="large" block busy={busy} onClick={() => void confirm()}>
          {t('activate.confirm')}
        </Button>
        {foot}
      </AuthLayout>
    )
  }
  // 用显式映射而不是拼 key：check-i18n 只认字面量 key，拼出来的会被判成缺失。
  const failure = {
    EXPIRED: { title: t('activate.expiredTitle'), message: t('activate.expired') },
    USED: { title: t('activate.usedTitle'), message: t('activate.used') },
    INVALID: { title: t('activate.invalidTitle'), message: t('activate.invalid') },
  }[status as 'EXPIRED' | 'USED' | 'INVALID']

  return (
    <AuthLayout title={failure.title}>
      <Alert type="error" showIcon message={failure.message} style={{ marginBottom: 14 }} />
      <Button variant="primary" size="large" block onClick={() => { window.location.href = '/register' }}>
        {t('activate.toRegister')}
      </Button>
      {foot}
    </AuthLayout>
  )
}
