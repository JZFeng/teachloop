import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { useAuth } from '../api/auth'
import { api } from '../api/client'
import { LANGUAGES, changeLanguage } from '../i18n'
import { Alert, Button, Field, Input, PageHeader, ThemeToggle } from '../ui'
import { UiKitPanel } from './UiKitPanel'
import type { AiModelsResult, AiTestResult, GradingPreference, ProfileAiSettings } from '../api/types'

interface ProfileView {
  id: string
  email: string | null
  phone: string | null
  displayName: string
  locale: string
}

const TIME_ZONES = [
  'Asia/Shanghai', 'Asia/Tokyo', 'Asia/Singapore', 'Asia/Kolkata',
  'UTC', 'Europe/London', 'Europe/Berlin', 'America/New_York',
  'America/Chicago', 'America/Los_Angeles', 'Australia/Sydney',
]

const PRESETS = [
  { key: 'ollama', label: 'Ollama', provider: 'ollama', baseUrl: 'http://localhost:11434/v1' },
  { key: 'lmstudio', label: 'LM Studio', provider: 'lmstudio', baseUrl: 'http://localhost:1234/v1' },
  { key: 'vllm', label: 'vLLM', provider: 'vllm', baseUrl: 'http://localhost:8000/v1' },
  { key: 'deepseek', label: 'DeepSeek', provider: 'deepseek', baseUrl: 'https://api.deepseek.com' },
]

/**
 * 资料 / 教师设置。
 *
 * 学生只看到账号资料与界面偏好；教师额外看到「AI 与批改设置」（决策 010）。
 * AI 配置按用户自定义 provider 设计：provider ID、显示名、API 地址、Key 可空、连接后选模型。
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
  const [aiProvider, setAiProvider] = useState('openai-compatible')
  const [aiDisplayName, setAiDisplayName] = useState('')
  const [aiBaseUrl, setAiBaseUrl] = useState('')
  const [aiModel, setAiModel] = useState('')
  const [aiKey, setAiKey] = useState('')
  const [aiModels, setAiModels] = useState<string[]>([])
  const [aiMessage, setAiMessage] = useState<string | null>(null)
  const [aiError, setAiError] = useState<string | null>(null)

  const [grading, setGrading] = useState<GradingPreference | null>(null)
  const [gradingMode, setGradingMode] = useState<'FIXED_TIME' | 'DUE_DATE'>('FIXED_TIME')
  const [fixedTime, setFixedTime] = useState('02:30')
  const [timeZone, setTimeZone] = useState('Asia/Shanghai')
  const [catchUp, setCatchUp] = useState(true)
  const [gradingMessage, setGradingMessage] = useState<string | null>(null)
  const [gradingError, setGradingError] = useState<string | null>(null)

  const isTeacher = me?.role === 'TEACHER'
  const canConfigureAi = isTeacher || me?.role === 'ADMIN'
  const [params, setParams] = useSearchParams()
  const canViewDesign = canConfigureAi
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
    if (!canConfigureAi) return
    api.get<ProfileAiSettings>('/api/profile/ai-settings').then((value) => {
      setAi(value)
      setAiProvider(value.provider || 'openai-compatible')
      setAiDisplayName(value.displayName || value.provider || '')
      setAiBaseUrl(value.baseUrl)
      setAiModel(value.model)
    }).catch((e: Error) => setAiError(e.message))
  }, [canConfigureAi])

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
    } catch (e) {
      setError((e as Error).message)
    }
  }

  const saveAi = async () => {
    setAiError(null)
    setAiMessage(null)
    try {
      const saved = await api.put<ProfileAiSettings>('/api/profile/ai-settings', {
        provider: aiProvider,
        displayName: aiDisplayName,
        baseUrl: aiBaseUrl,
        model: aiModel,
        apiKey: aiKey.trim() || null,
      })
      setAi(saved)
      setAiProvider(saved.provider || 'openai-compatible')
      setAiDisplayName(saved.displayName || '')
      setAiBaseUrl(saved.baseUrl)
      setAiModel(saved.model)
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

  const loadModels = async () => {
    setAiError(null)
    setAiMessage(null)
    try {
      const result = await api.post<AiModelsResult>('/api/profile/ai-settings/models', {
        baseUrl: aiBaseUrl,
        model: '',
        apiKey: aiKey.trim() || null,
      })
      setAiModels(result.models)
      if (result.ok) setAiMessage(t('profile.aiModelsLoaded', { count: result.models.length }))
      else setAiError(t('profile.aiModelsFailed', { message: result.message }))
    } catch (e) {
      setAiError((e as Error).message)
    }
  }

  const applyPreset = (preset: typeof PRESETS[number]) => {
    setAiProvider(preset.provider)
    setAiDisplayName(preset.label)
    setAiBaseUrl(preset.baseUrl)
    setAiModels([])
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
              <p className="small muted" style={{ margin: '10px 0 0' }}>{t('profile.saveBlocked')}</p>
            )}
          </>
        )}
      </div>

      <div className="card" style={{ maxWidth: 760, marginTop: 16 }}>
        <h2>{t('profile.preferences')}</h2>
        <Field label={t('profile.language')} hint={t('profile.languageHint')} htmlFor="profile-language">
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

      {canConfigureAi && (
        <div className="card" style={{ maxWidth: 760, marginTop: 16 }}>
          <h2>{t('profile.aiTitle')}</h2>
          <p className="small muted">{t('profile.aiSub')}</p>
          {aiError && <Alert type="error" showIcon message={aiError} style={{ marginBottom: 12 }} />}
          {aiMessage && <Alert type="success" showIcon message={aiMessage} style={{ marginBottom: 12 }} />}
          {ai && (
            <p className="small muted">
              {ai.source === 'user' ? t('profile.aiSourceUser')
                : ai.source === 'global' ? t('profile.aiSourceGlobal')
                  : t('profile.aiSourceNone')}
              {' · '}{ai.configured ? t('profile.aiConfigured') : t('profile.aiNotConfigured')}
              {ai.apiKeyMasked ? ` · ${t('profile.aiMasked', { masked: ai.apiKeyMasked })}` : ''}
            </p>
          )}

          <div className="row" style={{ gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
            <span className="small muted">{t('profile.aiPresets')}</span>
            {PRESETS.map((preset) => (
              <Button key={preset.key} size="small" onClick={() => applyPreset(preset)}>{preset.label}</Button>
            ))}
          </div>

          <Field label={t('profile.aiProviderId')} hint={t('profile.aiProviderHint')} htmlFor="ai-provider">
            <Input id="ai-provider" value={aiProvider} onChange={(e) => setAiProvider(e.target.value)}
              placeholder="ollama / lmstudio / openai-compatible" />
          </Field>
          <Field label={t('profile.aiDisplayName')} hint={t('profile.aiDisplayNameHint')} htmlFor="ai-display-name">
            <Input id="ai-display-name" value={aiDisplayName} onChange={(e) => setAiDisplayName(e.target.value)}
              placeholder={t('profile.aiDisplayNamePlaceholder')} />
          </Field>
          <Field label={t('profile.aiBaseUrl')} hint={t('profile.aiLocalHint')} htmlFor="ai-base-url">
            <Input id="ai-base-url" value={aiBaseUrl} onChange={(e) => setAiBaseUrl(e.target.value)}
              placeholder="http://localhost:11434/v1" />
          </Field>
          <Field label={t('profile.aiModel')} hint={t('profile.aiModelHint')} htmlFor="ai-model"
            extra={<Button size="small" onClick={() => void loadModels()}>{t('profile.aiLoadModels')}</Button>}>
            <Input id="ai-model" list="ai-model-options" value={aiModel} onChange={(e) => setAiModel(e.target.value)}
              placeholder="qwen3:8b" />
            <datalist id="ai-model-options">
              {aiModels.map((model) => <option key={model} value={model} />)}
            </datalist>
          </Field>
          <Field label={t('profile.aiKey')} hint={t('profile.aiKeyOptional')} htmlFor="ai-key">
            <Input id="ai-key" type="password" value={aiKey} placeholder={t('profile.aiKeyPlaceholder')}
              onChange={(e) => setAiKey(e.target.value)} autoComplete="off" />
          </Field>
          <div className="row" style={{ gap: 8 }}>
            <Button variant="primary" onClick={() => void saveAi()}>{t('profile.aiSave')}</Button>
            <Button onClick={() => void testAi()}>{t('profile.aiTest')}</Button>
          </div>
        </div>
      )}

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
              <Field label={t('profile.gradingTime')} hint={t('profile.gradingFixedHint')} htmlFor="grading-time">
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
