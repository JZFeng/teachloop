import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { useAuth } from '../api/auth'
import { api } from '../api/client'
import { LANGUAGES, changeLanguage } from '../i18n'
import { Alert, Button, Field, Input, PageHeader, ThemeToggle } from '../ui'
import { UiKitPanel } from './UiKitPanel'
import type { AiTestResult, ProfileAiSettings } from '../api/types'

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
  const { me, refresh, refreshProfile } = useAuth()
  const [profile, setProfile] = useState<ProfileView | null>(null)
  const [phone, setPhone] = useState('')
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [ai, setAi] = useState<ProfileAiSettings | null>(null)
  const [aiKey, setAiKey] = useState('')
  const [aiBaseUrl, setAiBaseUrl] = useState('')
  const [aiModel, setAiModel] = useState('')
  const [aiMessage, setAiMessage] = useState<string | null>(null)
  const [aiError, setAiError] = useState<string | null>(null)
  const canConfigureAi = me?.role === 'TEACHER' || me?.role === 'ADMIN'
  // 界面标准（组件与令牌）收在偏好设置里，不再占一个导航入口；旧链接 /ui-kit 带参数跳过来。
  const [params, setParams] = useSearchParams()
  const canViewDesign = me?.role === 'TEACHER' || me?.role === 'ADMIN'
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
    // 展开后把面板滚进视野：从 /ui-kit 旧链接进来时页面还停在顶部。
    const frame = window.requestAnimationFrame(() => {
      document.getElementById('ui-kit-panel')?.scrollIntoView({ block: 'start' })
    })
    return () => window.cancelAnimationFrame(frame)
  }, [designOpen])

  useEffect(() => {
    if (!canConfigureAi) return
    api.get<ProfileAiSettings>('/api/profile/ai-settings').then((value) => {
      setAi(value)
      setAiBaseUrl(value.baseUrl)
      setAiModel(value.model)
    }).catch((e: Error) => setAiError(e.message))
  }, [canConfigureAi])

  const saveAi = async () => {
    setAiError(null)
    setAiMessage(null)
    try {
      const saved = await api.put<ProfileAiSettings>('/api/profile/ai-settings', {
        provider: ai?.provider ?? 'deepseek',
        baseUrl: aiBaseUrl,
        model: aiModel,
        apiKey: aiKey.trim() || null,
      })
      setAi(saved)
      setAiKey('')
      setAiMessage(t('profile.aiSaved'))
    } catch (e) {
      setAiError((e as Error).message)
    }
  }

  const testAi = async () => {
    setAiError(null)
    setAiMessage(null)
    try {
      const result = await api.post<AiTestResult>('/api/profile/ai-settings/test', {
        baseUrl: aiBaseUrl,
        model: aiModel,
        apiKey: aiKey.trim() || null,
      })
      if (result.ok) setAiMessage(t('profile.aiTestOk', { ms: result.latencyMs }))
      else setAiError(t('profile.aiTestFail', { message: result.message }))
    } catch (e) {
      setAiError((e as Error).message)
    }
  }

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

        {canViewDesign && (
          <>
            <div className="tl-pref-divider" aria-hidden="true" />
            <div className="tl-pref-extra">
              <div className="tl-pref-extra-text">
                <b>{t('profile.designTitle')}</b>
                <p className="small muted">{t('profile.designHint')}</p>
              </div>
              <Button
                size="small"
                aria-expanded={designOpen}
                aria-controls="ui-kit-panel"
                onClick={toggleDesign}
              >
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

      {canConfigureAi && (
        <div className="card" style={{ maxWidth: 720, marginTop: 16 }}>
          <h2>{t('profile.aiTitle')}</h2>
          <p className="small muted">{t('profile.aiSub')}</p>
          {aiError && <Alert type="error" showIcon message={aiError} style={{ marginBottom: 12 }} />}
          {aiMessage && <Alert type="success" showIcon message={aiMessage} style={{ marginBottom: 12 }} />}
          {ai && (
            <p className="small muted">
              {ai.source === 'user' ? t('profile.aiSourceUser')
                : ai.source === 'global' ? t('profile.aiSourceGlobal')
                  : t('profile.aiSourceNone')}
              {ai.apiKeyMasked ? ` · ${t('profile.aiMasked', { masked: ai.apiKeyMasked })}` : ''}
            </p>
          )}
          <Field label={t('profile.aiBaseUrl')} htmlFor="ai-base-url">
            <Input id="ai-base-url" value={aiBaseUrl} onChange={(e) => setAiBaseUrl(e.target.value)} />
          </Field>
          <Field label={t('profile.aiModel')} htmlFor="ai-model">
            <Input id="ai-model" value={aiModel} onChange={(e) => setAiModel(e.target.value)} />
          </Field>
          <Field label={t('profile.aiKey')} hint={t('profile.aiKeyHint')} htmlFor="ai-key">
            <Input id="ai-key" type="password" value={aiKey}
              placeholder={t('profile.aiKeyPlaceholder')}
              onChange={(e) => setAiKey(e.target.value)} autoComplete="off" />
          </Field>
          <div className="row" style={{ gap: 8 }}>
            <Button variant="primary" onClick={() => void saveAi()}>{t('profile.aiSave')}</Button>
            <Button onClick={() => void testAi()}>{t('profile.aiTest')}</Button>
          </div>
        </div>
      )}
    </>
  )
}
