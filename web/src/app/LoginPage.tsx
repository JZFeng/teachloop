import { useState } from 'react'
import type { FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../api/auth'
import { ApiError } from '../api/client'
import { Alert, Button, Field, Input, Password } from '../ui'

/**
 * 登录页（UI 标准迁移第 1 屏）。
 * 改动：输入框与按钮交给组件层；错误改成 antd Alert（不再是一段灰字）；
 * 主按钮去掉渐变与彩色投影，改成实色 #1D5FBF。
 */
export default function LoginPage() {
  const { t } = useTranslation('translation')
  const { login } = useAuth()
  const navigate = useNavigate()
  const [email, setEmail] = useState('teacher@teachloop.local')
  const [password, setPassword] = useState('teachloop123')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await login(email.trim(), password)
      navigate('/', { replace: true })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login-page">
      <div className="login-panel">
      <div className="login-hero">
        <h1>{t('app.name')}</h1>
        <p className="sub">{t('app.tagline')}</p>
        <ul>
          <li>{t('login.bullet1')}</li>
          <li>{t('login.bullet2')}</li>
          <li>{t('login.bullet3')}</li>
        </ul>
      </div>
      <form className="card login-card" onSubmit={submit}>
        <h2>{t('login.title')}</h2>
        <div className="sub">{t('login.subtitle')}</div>
        <Field label={t('login.email')} required htmlFor="login-email">
          <Input
            id="login-email"
            size="large"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoComplete="username"
            status={error ? 'error' : undefined}
          />
        </Field>
        <Field label={t('login.password')} required htmlFor="login-password">
          <Password
            id="login-password"
            size="large"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            status={error ? 'error' : undefined}
          />
        </Field>
        {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 14 }} />}
        <Button variant="primary" size="large" block busy={busy} htmlType="submit">
          {t('login.submit')}
        </Button>
        <div className="hint">{t('login.hint')}</div>
      </form>
      </div>
    </div>
  )
}
