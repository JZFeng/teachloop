import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../api/auth'
import { api } from '../api/client'
import { LANGUAGES, changeLanguage } from '../i18n'

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
      <div className="card">
        <h2>{t('profile.title')}</h2>
        <p className="small muted">{t('profile.sub')}</p>
        {error && <div className="inline-note danger">{error}</div>}
        {message && <div className="inline-note">{message}</div>}
        {missingRequired && <div className="inline-note danger">{t('profile.requiredWarn')}</div>}
        {profile && (
          <>
            <label className="f"><b>{t('profile.name')}</b>
              <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
            </label>
            <label className="f"><b>{t('profile.phone')} <span className="required-mark">*</span></b>
              <input
                className={phoneMissing ? 'invalid' : undefined}
                aria-invalid={phoneMissing}
                value={phone}
                placeholder="13800000000"
                onChange={(e) => setPhone(e.target.value)}
              />
              {phoneMissing && <span className="field-error">{t('profile.phoneRequired')}</span>}
            </label>
            <label className="f"><b>{t('profile.email')} <span className="required-mark">*</span></b>
              <input
                className={emailMissing ? 'invalid' : undefined}
                aria-invalid={emailMissing}
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
              />
              {emailMissing && <span className="field-error">{t('profile.emailRequired')}</span>}
            </label>
            <button className="btn pri" disabled={missingRequired} onClick={() => void save()}>
              {t('profile.save')}
            </button>
            {missingRequired && <p className="small muted" style={{ marginBottom: 0 }}>{t('profile.saveBlocked')}</p>}
          </>
        )}
      </div>

      <div className="card" style={{ marginTop: 16 }}>
        <h2>{t('profile.preferences')}</h2>
        <label className="f"><b>{t('profile.language')}</b>
          <select value={i18n.language} onChange={(e) => void changeLanguage(e.target.value)}>
            {LANGUAGES.map((language) => (
              <option key={language.code} value={language.code}>{language.label}</option>
            ))}
          </select>
        </label>
        <p className="small muted" style={{ margin: 0 }}>{t('profile.languageHint')}</p>
      </div>
    </>
  )
}
