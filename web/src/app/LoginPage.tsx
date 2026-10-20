import { useState } from 'react'
import type { FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../api/auth'
import { ApiError } from '../api/client'

export default function LoginPage() {
  const { t } = useTranslation()
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
      <div className="login-hero">
        <h1>{t('app.name')}</h1>
        <p className="sub">{t('app.tagline')}</p>
        <ul>
          <li>客观题当场判分，主观题 AI 给草稿，教师做最终决定。</li>
          <li>标准答案是数据，AI 判分时看不到。</li>
          <li>每份评语都能追溯到是谁批准的。</li>
        </ul>
      </div>
      <form className="card login-card" onSubmit={submit}>
        <h2>{t('login.title')}</h2>
        <div className="sub">{t('login.subtitle')}</div>
        <label className="f"><b>{t('login.email')}</b>
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" required />
        </label>
        <label className="f"><b>{t('login.password')}</b>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
        </label>
        {error && <p className="inline-note">{error}</p>}
        <button className="btn pri" type="submit" disabled={busy} style={{ width: '100%' }}>
          {busy ? t('common.loading') : t('login.submit')}
        </button>
        <div className="hint">{t('login.hint')}</div>
      </form>
    </div>
  )
}
