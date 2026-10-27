import { useState } from 'react'
import type { FormEvent } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../api/auth'
import { ApiError } from '../api/client'
import { safeNextPath } from './redirect'
import {
  Alert,
  BarChartOutlined,
  Brand,
  Button,
  CheckCircleOutlined,
  CheckOutlined,
  Field,
  FileTextOutlined,
  Input,
  Password,
  SyncOutlined,
} from '../ui'

/**
 * 登录页（品牌视觉第 1 屏）。
 *
 * 版式与 `docs/mock/brand-2026-10-22` 的登录屏一致：
 *   左侧品牌区：整标（亮色位图 / 暗色 mark + 文本字标）、tagline、三条卖点、四个功能位；
 *   右侧表单：沿用组件层的 Field / Input / Alert / Button，颜色与圆角来自令牌。
 *
 * 四个功能位对应新 logo 的四栏；Practice 还没上线，所以带「规划中」小字。
 */
export default function LoginPage() {
  const { t } = useTranslation('translation')
  const { login } = useAuth()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  // 学生从班群点开预习链接但没登录：登录后回到那次布置（只接受站内路径）。
  const next = safeNextPath(params.get('next'))
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await login(email.trim(), password)
      navigate(next ?? '/', { replace: true })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login-page">
      <div className="login-shell">
        <div className="login-hero">
          <Brand variant="lockup" size="xl" />
          <p className="login-tagline">{t('app.brandLine')}</p>
          {t('app.tagline') !== t('app.brandLine') && (
            <p className="login-subtagline">{t('app.tagline')}</p>
          )}
          <ul className="login-points">
            {[t('login.bullet1'), t('login.bullet2'), t('login.bullet3')].map((line) => (
              <li key={line}>
                <span className="login-check" aria-hidden="true"><CheckOutlined /></span>
                <span>{line}</span>
              </li>
            ))}
          </ul>
          <div className="login-pillars" aria-label={t('login.pillars')}>
            <span className="login-pillar" data-f="create">
              <span className="login-pillar-ico" aria-hidden="true"><FileTextOutlined /></span>
              {t('login.pillarCreate')}
            </span>
            <span className="login-pillar" data-f="assess">
              <span className="login-pillar-ico" aria-hidden="true"><CheckCircleOutlined /></span>
              {t('login.pillarAssess')}
            </span>
            <span className="login-pillar" data-f="practice">
              <span className="login-pillar-ico" aria-hidden="true"><SyncOutlined /></span>
              {t('login.pillarPractice')}
            </span>
            <span className="login-pillar" data-f="insights">
              <span className="login-pillar-ico" aria-hidden="true"><BarChartOutlined /></span>
              {t('login.pillarInsights')}
            </span>
          </div>
          <p className="login-plan-note">{t('login.practiceNote')}</p>
          <div className="login-hero-foot">TeachLoop · {t('app.descriptor')}</div>
        </div>

        <form className="login-card" onSubmit={submit}>
          <h2>{t('login.title')}</h2>
          <p className="sub">{t('login.subtitle')}</p>
          {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 14 }} />}
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
          <Button variant="primary" size="large" block busy={busy} htmlType="submit">
            {t('login.submit')}
          </Button>
        </form>
      </div>
    </div>
  )
}
