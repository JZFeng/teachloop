import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useAuth } from '../api/auth'
import { api } from '../api/client'
import { LANGUAGES, changeLanguage } from '../i18n'
import { Alert, Button, Field, InlineNote, Input, PageHeader, ThemeToggle } from '../ui'
import { UiKitPanel } from './UiKitPanel'
import { nextAfterProfile } from './profileGate'
import type { GradingPreference } from '../api/types'

interface ProfileView {
  id: string
  email: string | null
  phone: string | null
  displayName: string
  locale: string
  hasPassword: boolean
}

const TIME_ZONES = [
  'Asia/Shanghai', 'Asia/Tokyo', 'Asia/Singapore', 'Asia/Kolkata',
  'UTC', 'Europe/London', 'Europe/Berlin', 'America/New_York',
  'America/Chicago', 'America/Los_Angeles', 'Australia/Sydney',
]

/**
 * 资料 / 教师设置。
 *
 * 学生只看到账号资料与界面偏好；教师额外看到「作业批改时机」（决策 010）。
 * 模型选择只属于管理员（/admin/ai）：教师页不再出现任何 AI 配置或模型下拉（2026-10-24 用户拍板）。
 */
export default function ProfilePage() {
  const { t, i18n } = useTranslation('translation')
  const { me, refresh, refreshProfile } = useAuth()
  const [profile, setProfile] = useState<ProfileView | null>(null)
  const [phone, setPhone] = useState('')
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const [grading, setGrading] = useState<GradingPreference | null>(null)
  const [gradingMode, setGradingMode] = useState<'FIXED_TIME' | 'DUE_DATE'>('FIXED_TIME')
  const [fixedTime, setFixedTime] = useState('02:30')
  const [timeZone, setTimeZone] = useState('Asia/Shanghai')
  const [catchUp, setCatchUp] = useState(true)
  const [gradingMessage, setGradingMessage] = useState<string | null>(null)
  const [gradingError, setGradingError] = useState<string | null>(null)

  // 2026-10-27：改密码（决策 029 之后的独立小改动）。改完踢掉其他设备，当前设备不掉线。
  const [oldPassword, setOldPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [passwordMessage, setPasswordMessage] = useState<string | null>(null)
  const [passwordError, setPasswordError] = useState<string | null>(null)
  const [passwordBusy, setPasswordBusy] = useState(false)

  const isTeacher = me?.role === 'TEACHER'
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  // 界面标准（组件与样式）只给教师与管理员看。
  const canViewDesign = isTeacher || me?.role === 'ADMIN'
  const designOpen = canViewDesign && params.get('panel') === 'ui-kit'

  const toggleDesign = () => {
    setParams((prev) => {
      const next = new URLSearchParams(prev)
      if (next.get('panel') === 'ui-kit') next.delete('panel')
      else next.set('panel', 'ui-kit')
      return next
    }, { replace: true })
  }

  useEffect(() => {
    if (!designOpen) return
    const frame = window.requestAnimationFrame(() => {
      document.getElementById('ui-kit-panel')?.scrollIntoView({ block: 'start' })
    })
    return () => window.cancelAnimationFrame(frame)
  }, [designOpen])

  useEffect(() => {
    api.get<ProfileView>('/api/profile').then((p) => {
      setProfile(p)
      setPhone(p.phone ?? '')
      setEmail(p.email ?? '')
      setDisplayName(p.displayName ?? '')
      const deviceChoice = window.localStorage.getItem('teachloop.lang')
      if (!deviceChoice && p.locale && p.locale !== i18n.language) {
        void changeLanguage(p.locale)
      }
    }).catch((e: Error) => setError(e.message))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (!isTeacher) return
    api.get<GradingPreference>('/api/profile/grading-preferences').then((value) => {
      setGrading(value)
      setGradingMode(value.triggerMode)
      setFixedTime(value.fixedTime || '02:30')
      setTimeZone(value.timeZone || 'Asia/Shanghai')
      setCatchUp(value.catchUp)
    }).catch((e: Error) => setGradingError(e.message))
  }, [isTeacher])

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
      await Promise.all([refresh(), refreshProfile()])
      // 被改道过来的（/profile?next=…）：补全并保存后回原页面，别把人留在资料页。
      const next = nextAfterProfile(params.get('next'))
      if (next && saved.phone?.trim() && saved.email?.trim()) {
        navigate(next, { replace: true })
      }
    } catch (e) {
      setError((e as Error).message)
    }
  }

  const saveGrading = async () => {
    setGradingError(null)
    setGradingMessage(null)
    try {
      const saved = await api.put<GradingPreference>('/api/profile/grading-preferences', {
        triggerMode: gradingMode,
        fixedTime,
        timeZone,
        catchUp,
      })
      setGrading(saved)
      setGradingMode(saved.triggerMode)
      setFixedTime(saved.fixedTime)
      setTimeZone(saved.timeZone)
      setCatchUp(saved.catchUp)
      setGradingMessage(t('profile.gradingSaved'))
    } catch (e) {
      setGradingError((e as Error).message)
    }
  }

  const savePassword = async () => {
    setPasswordError(null)
    setPasswordMessage(null)
    if (newPassword.length < 8) {
      setPasswordError(t('profile.passwordTooShort'))
      return
    }
    if (newPassword !== confirmPassword) {
      setPasswordError(t('profile.passwordMismatch'))
      return
    }
    setPasswordBusy(true)
    try {
      await api.post('/api/profile/password', { oldPassword, newPassword })
      setOldPassword('')
      setNewPassword('')
      setConfirmPassword('')
      setPasswordMessage(t('profile.passwordSaved'))
    } catch (e) {
      setPasswordError((e as Error).message)
    } finally {
      setPasswordBusy(false)
    }
  }

  return (
    <>
      <PageHeader
        title={isTeacher ? t('profile.teacherTitle') : t('profile.title')}
        sub={isTeacher ? t('profile.teacherSub') : t('profile.sub')}
      />

      <div className="card" style={{ maxWidth: 760 }}>
        {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 14 }} />}
        {message && <Alert type="success" showIcon message={message} style={{ marginBottom: 14 }} />}
        {missingRequired && <Alert type="warning" showIcon message={t('profile.requiredWarn')} style={{ marginBottom: 14 }} />}

        {profile && (
          <>
            <Field label={t('profile.name')} htmlFor="profile-name">
              <Input id="profile-name" value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
            </Field>

            <Field label={t('profile.phone')} required htmlFor="profile-phone"
              error={phoneMissing ? t('profile.phoneRequired') : null}>
              <Input id="profile-phone" status={phoneMissing ? 'error' : undefined} aria-invalid={phoneMissing}
                value={phone} placeholder="13800000000" onChange={(e) => setPhone(e.target.value)} />
            </Field>

            <Field label={t('profile.email')} required htmlFor="profile-email"
              error={emailMissing ? t('profile.emailRequired') : null}>
              <Input id="profile-email" type="email" status={emailMissing ? 'error' : undefined}
                aria-invalid={emailMissing} value={email} onChange={(e) => setEmail(e.target.value)} />
            </Field>

            <Button variant="primary" disabled={missingRequired} onClick={() => void save()}>
              {t('profile.save')}
            </Button>
            {missingRequired && (
              <InlineNote tone="warning" style={{ margin: '10px 0 0' }}>{t('profile.saveBlocked')}</InlineNote>
            )}
          </>
        )}
      </div>

      {profile?.hasPassword && (
        <div className="card" style={{ maxWidth: 760, marginTop: 16 }}>
          <h2>{t('profile.passwordTitle')}</h2>
          <p className="small muted">{t('profile.passwordSub')}</p>
          {passwordError && <Alert type="error" showIcon message={passwordError} style={{ marginBottom: 12 }} />}
          {passwordMessage && <Alert type="success" showIcon message={passwordMessage} style={{ marginBottom: 12 }} />}
          <Field label={t('profile.passwordOld')} htmlFor="password-old">
            <Input id="password-old" type="password" autoComplete="current-password"
              value={oldPassword} onChange={(e) => setOldPassword(e.target.value)} />
          </Field>
          <Field label={t('profile.passwordNew')} htmlFor="password-new">
            <Input id="password-new" type="password" autoComplete="new-password"
              value={newPassword} onChange={(e) => setNewPassword(e.target.value)} />
          </Field>
          <Field label={t('profile.passwordConfirm')} htmlFor="password-confirm">
            <Input id="password-confirm" type="password" autoComplete="new-password"
              value={confirmPassword} onChange={(e) => setConfirmPassword(e.target.value)} />
          </Field>
          <Button variant="primary" disabled={passwordBusy || !oldPassword || !newPassword}
            onClick={() => void savePassword()}>
            {t('profile.passwordSave')}
          </Button>
        </div>
      )}

      <div className="card" style={{ maxWidth: 760, marginTop: 16 }}>
        <h2>{t('profile.preferences')}</h2>
        <Field label={t('profile.language')} info={t('profile.languageHint')} htmlFor="profile-language">
          <select id="profile-language" value={i18n.language} onChange={(e) => void changeLanguage(e.target.value)}>
            {LANGUAGES.map((language) => (
              <option key={language.code} value={language.code}>{language.label}</option>
            ))}
          </select>
        </Field>
        <ThemeToggle size="middle" />

        {canViewDesign && (
          <>
            <div className="tl-pref-divider" aria-hidden="true" />
            <div className="tl-pref-extra">
              <div className="tl-pref-extra-text">
                <b>{t('profile.designTitle')}</b>
                <p className="small muted">{t('profile.designHint')}</p>
              </div>
              <Button size="small" aria-expanded={designOpen} aria-controls="ui-kit-panel" onClick={toggleDesign}>
                {designOpen ? t('profile.designHide') : t('profile.designShow')}
              </Button>
            </div>
            {designOpen && (
              <div id="ui-kit-panel" className="tl-kit-embed" role="region" aria-label={t('ui.kit.title')}>
                <p className="small muted tl-kit-embed-sub">{t('ui.kit.sub')}</p>
                <UiKitPanel />
              </div>
            )}
          </>
        )}
      </div>

      {isTeacher && (
        <div className="card" style={{ maxWidth: 760, marginTop: 16 }}>
          <h2>{t('profile.gradingTitle')}</h2>
          <p className="small muted">{t('profile.gradingSub')}</p>
          {gradingError && <Alert type="error" showIcon message={gradingError} style={{ marginBottom: 12 }} />}
          {gradingMessage && <Alert type="success" showIcon message={gradingMessage} style={{ marginBottom: 12 }} />}
          {grading && (
            <p className="small muted">
              {t('profile.gradingLastRun', { time: grading.lastFixedRunAt ?? t('profile.gradingNever') })}
            </p>
          )}
          <Field label={t('profile.gradingMode')} htmlFor="grading-mode">
            <select id="grading-mode" value={gradingMode}
              onChange={(e) => setGradingMode(e.target.value as 'FIXED_TIME' | 'DUE_DATE')}>
              <option value="FIXED_TIME">{t('profile.gradingModeFixed')}</option>
              <option value="DUE_DATE">{t('profile.gradingModeDue')}</option>
            </select>
          </Field>
          {gradingMode === 'FIXED_TIME' ? (
            <>
              <Field label={t('profile.gradingTime')} info={t('profile.gradingFixedHint')} htmlFor="grading-time">
                <Input id="grading-time" type="time" value={fixedTime} onChange={(e) => setFixedTime(e.target.value)} />
              </Field>
              <Field label={t('profile.gradingZone')} htmlFor="grading-zone">
                <select id="grading-zone" value={timeZone} onChange={(e) => setTimeZone(e.target.value)}>
                  {TIME_ZONES.concat(TIME_ZONES.includes(timeZone) ? [] : [timeZone]).map((zone) => (
                    <option key={zone} value={zone}>{zone}</option>
                  ))}
                </select>
              </Field>
              <label className="row" style={{ gap: 8, marginBottom: 12 }}>
                <input type="checkbox" checked={catchUp} onChange={(e) => setCatchUp(e.target.checked)} />
                <span>{t('profile.gradingCatchUp')}</span>
              </label>
              <p className="small muted">{t('profile.gradingCatchUpHint')}</p>
            </>
          ) : (
            <p className="small muted">{t('profile.gradingDueHint')}</p>
          )}
          <div className="row" style={{ gap: 8, marginTop: 12 }}>
            <Button variant="primary" onClick={() => void saveGrading()}>{t('profile.gradingSave')}</Button>
          </div>
          <p className="small muted" style={{ marginTop: 10 }}>{t('profile.gradingManualHint')}</p>
        </div>
      )}
    </>
  )
}
