import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../api/auth'
import { api } from '../api/client'
import { LANGUAGES, changeLanguage } from '../i18n'
import { Alert, Button, Field, Input, PageHeader, ThemeToggle } from '../ui'

interface ProfileView {
  id: string
  email: string | null
  phone: string | null
  displayName: string
  locale: string
}

/**
 * T034：微信/首次登录后的资料补全；手机号与邮箱必填（空着就用红色标出来）。
 * T248：界面语言也在这里改。T250：保存后顶部「补全资料」提示立即消失，本页仍可随时修改。
 * 2026-10-21（UI 标准）：改用组件层，偏好设置里补上亮/暗主题开关（计划 A2 说这里也要有）。
 */
export default function ProfilePage() {
  const { t, i18n } = useTranslation('translation')
  const { refresh, refreshProfile } = useAuth()
  const [profile, setProfile] = useState<ProfileView | null>(null)
  const [phone, setPhone] = useState('')
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.get<ProfileView>('/api/profile').then((p) => {
      setProfile(p)
      setPhone(p.phone ?? '')
      setEmail(p.email ?? '')
      setDisplayName(p.displayName ?? '')
      // 本机还没有显式选过语言时，跟随账号里保存的偏好（缺省仍是中文）。
      const deviceChoice = window.localStorage.getItem('teachloop.lang')
      if (!deviceChoice && p.locale && p.locale !== i18n.language) {
        void changeLanguage(p.locale)
      }
    }).catch((e: Error) => setError(e.message))
    // 只在进页面时读一次资料；切换语言不重新拉接口
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const phoneMissing = !phone.trim()
  const emailMissing = !email.trim()
  const missingRequired = phoneMissing || emailMissing

  const save = async () => {
    setError(null)
    setMessage(null)
    try {
      const saved = await api.put<ProfileView>('/api/profile', {
        phone, email, displayName, locale: i18n.language,
      })
      setProfile(saved)
      setMessage(t('profile.saved'))
      // 顶部「补全资料」是否还显示，由这里重算；本机语言偏好同时写回账号。
      await Promise.all([refresh(), refreshProfile()])
    } catch (e) {
      setError((e as Error).message)
    }
  }

  return (
    <>
      <PageHeader title={t('profile.title')} sub={t('profile.sub')} />

      <div className="card" style={{ maxWidth: 720 }}>
        {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 14 }} />}
        {message && <Alert type="success" showIcon message={message} style={{ marginBottom: 14 }} />}
        {missingRequired && <Alert type="warning" showIcon message={t('profile.requiredWarn')} style={{ marginBottom: 14 }} />}

        {profile && (
          <>
            <Field label={t('profile.name')} htmlFor="profile-name">
              <Input
                id="profile-name"
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
              />
            </Field>

            <Field
              label={t('profile.phone')}
              required
              htmlFor="profile-phone"
              error={phoneMissing ? t('profile.phoneRequired') : null}
            >
              <Input
                id="profile-phone"
                status={phoneMissing ? 'error' : undefined}
                aria-invalid={phoneMissing}
                value={phone}
                placeholder="13800000000"
                onChange={(e) => setPhone(e.target.value)}
              />
            </Field>

            <Field
              label={t('profile.email')}
              required
              htmlFor="profile-email"
              error={emailMissing ? t('profile.emailRequired') : null}
            >
              <Input
                id="profile-email"
                type="email"
                status={emailMissing ? 'error' : undefined}
                aria-invalid={emailMissing}
                value={email}
                onChange={(e) => setEmail(e.target.value)}
              />
            </Field>

            <Button variant="primary" disabled={missingRequired} onClick={() => void save()}>
              {t('profile.save')}
            </Button>
            {missingRequired && (
              <p className="small muted" style={{ margin: '10px 0 0' }}>{t('profile.saveBlocked')}</p>
            )}
          </>
        )}
      </div>

      <div className="card" style={{ maxWidth: 720, marginTop: 16 }}>
        <h2>{t('profile.preferences')}</h2>
        <Field label={t('profile.language')} hint={t('profile.languageHint')} htmlFor="profile-language">
          <select
            id="profile-language"
            value={i18n.language}
            onChange={(e) => void changeLanguage(e.target.value)}
          >
            {LANGUAGES.map((language) => (
              <option key={language.code} value={language.code}>{language.label}</option>
            ))}
          </select>
        </Field>
        <ThemeToggle size="middle" />
      </div>
    </>
  )
}
