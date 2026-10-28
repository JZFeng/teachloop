import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import { Alert, Button, Field, Input } from '../ui'
import AuthLayout from './AuthLayout'

/** 009 T539：忘记密码。先发码，再填码 + 新密码；不自动登录。 */
export default function ForgotPasswordPage() {
  const { t } = useTranslation('translation')
  const [step, setStep] = useState<'email' | 'code' | 'done'>('email')
  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [cooldown, setCooldown] = useState(0)

  useEffect(() => {
    if (cooldown <= 0) return
    const timer = setTimeout(() => setCooldown((c) => c - 1), 1000)
    return () => clearTimeout(timer)
  }, [cooldown])

  const sendCode = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.post('/api/auth/password-reset/challenge', { channel: 'email', email: email.trim() })
      setStep('code')
      setCooldown(60)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  const reset = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.post('/api/auth/password-reset/verify', {
        email: email.trim(), code: code.trim(), newPassword: password,
      })
      setStep('done')
    } catch (err) {
      setError(err instanceof ApiError ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  if (step === 'done') {
    return (
      <AuthLayout title={t('forgot.doneTitle')} sub={t('forgot.doneSub')}>
        <Alert type="success" showIcon message={t('forgot.done')} style={{ marginBottom: 14 }} />
        <p className="auth-foot"><Link to="/login">{t('forgot.toLogin')}</Link></p>
      </AuthLayout>
    )
  }

  if (step === 'email') {
    return (
      <AuthLayout title={t('forgot.title')} sub={t('forgot.sub')}>
        <form onSubmit={sendCode}>
          {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 14 }} />}
          <Field label={t('forgot.email')} required htmlFor="forgot-email">
            <Input id="forgot-email" size="large" type="email" value={email}
              onChange={(e) => setEmail(e.target.value)} autoComplete="username" />
          </Field>
          <Button variant="primary" size="large" block busy={busy} htmlType="submit">
            {t('forgot.sendCode')}
          </Button>
        </form>
        <p className="auth-foot"><Link to="/login">{t('forgot.backToLogin')}</Link></p>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout title={t('forgot.codeTitle')} sub={t('forgot.codeSub', { email })}>
      <form onSubmit={reset}>
        {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 14 }} />}
        <Field label={t('forgot.code')} required htmlFor="forgot-code">
          <Input id="forgot-code" size="large" inputMode="numeric" maxLength={6}
            value={code} onChange={(e) => setCode(e.target.value)} />
        </Field>
        <Field label={t('forgot.newPassword')} required htmlFor="forgot-password">
          <Input id="forgot-password" size="large" type="password" value={password}
            onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" />
        </Field>
        <Button variant="primary" size="large" block busy={busy} htmlType="submit">
          {t('forgot.submit')}
        </Button>
      </form>
      <p className="auth-foot">
        <button type="button" className="link-button" disabled={cooldown > 0}
          onClick={() => { setStep('email'); setCooldown(0) }}>
          {cooldown > 0 ? t('forgot.resendIn', { seconds: cooldown }) : t('forgot.resend')}
        </button>
      </p>
    </AuthLayout>
  )
}
