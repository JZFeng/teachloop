import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import { Alert, Button, Field, Input } from '../ui'
import AuthLayout from './AuthLayout'
import { emailSignupOpen } from './registrationGate'
import type { ChannelSummary } from './registrationGate'

/** 009 T536：注册页。提交后进入「激活邮件已发送」态，60 秒后可重发。 */
export default function RegisterPage() {
  const { t } = useTranslation('translation')
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  // 2026-10-28 Bug（生产注册恒 404「该注册方式不可用」）：以前这里不看通道状态就渲染表单。
  // 后端只下发「开关打开 + 配置齐全」的通道，未启用时列表为空——那就该直说暂不可用。
  const [signupOpen, setSignupOpen] = useState<boolean | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [sent, setSent] = useState(false)
  const [cooldown, setCooldown] = useState(0)
  const [resent, setResent] = useState(false)

  useEffect(() => {
    let alive = true
    api.get<ChannelSummary[]>('/api/auth/channels')
      .then((rows) => { if (alive) setSignupOpen(emailSignupOpen(rows)) })
      .catch(() => { if (alive) setSignupOpen(false) })
    return () => { alive = false }
  }, [])

  useEffect(() => {
    if (cooldown <= 0) return
    const timer = setTimeout(() => setCooldown((c) => c - 1), 1000)
    return () => clearTimeout(timer)
  }, [cooldown])

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.post('/api/auth/register', {
        channel: 'email',
        email: email.trim(),
        password,
        displayName: name.trim() || undefined,
      })
      setSent(true)
      setCooldown(60)
    } catch (err) {
      // 404 的语义是「这个通道不存在」，照抄给用户只会得到一句看不懂的「该注册方式不可用」。
      setError(err instanceof ApiError && err.status === 404
        ? t('register.unavailable')
        : err instanceof ApiError ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  const resend = async () => {
    setError(null)
    setResent(false)
    try {
      await api.post('/api/auth/activate/resend', { email: email.trim() })
      setResent(true)
      setCooldown(60)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : String(err))
    }
  }

  if (sent) {
    return (
      <AuthLayout title={t('register.sentTitle')} sub={t('register.sentSub', { email })}>
        {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 14 }} />}
        {resent && <Alert type="success" showIcon message={t('register.resent')} style={{ marginBottom: 14 }} />}
        <Button variant="primary" size="large" block disabled={cooldown > 0} onClick={() => void resend()}>
          {cooldown > 0 ? t('register.resendIn', { seconds: cooldown }) : t('register.resend')}
        </Button>
        <p className="auth-foot">
          <Link to="/login">{t('register.backToLogin')}</Link>
        </p>
      </AuthLayout>
    )
  }

  if (signupOpen === false) {
    return (
      <AuthLayout title={t('register.unavailableTitle')}>
        <Alert type="warning" showIcon message={t('register.unavailable')} style={{ marginBottom: 14 }} />
        <p className="auth-foot">
          <Link to="/login">{t('register.backToLogin')}</Link>
        </p>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout title={t('register.title')} sub={t('register.sub')}>
      <form onSubmit={submit}>
        {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 14 }} />}
        <Field label={t('register.name')} htmlFor="register-name">
          <Input id="register-name" size="large" value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field label={t('register.email')} required htmlFor="register-email">
          <Input id="register-email" size="large" type="email" value={email}
            onChange={(e) => setEmail(e.target.value)} autoComplete="username" />
        </Field>
        <Field label={t('register.password')} required htmlFor="register-password">
          <Input id="register-password" size="large" type="password" value={password}
            onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" />
        </Field>
        <Button variant="primary" size="large" block busy={busy} htmlType="submit">
          {t('register.submit')}
        </Button>
      </form>
      <p className="auth-foot">
        <Link to="/login">{t('register.haveAccount')}</Link>
      </p>
    </AuthLayout>
  )
}
