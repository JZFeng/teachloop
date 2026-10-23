import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client'
import { PageHeader } from '../ui'

interface AiSettingsView {
  provider: string
  baseUrl: string
  model: string
  temperature: string
  configured: boolean
  apiKeySet: boolean
  apiKeyMasked: string | null
  updatedAt: string
  restartRequired: boolean
}

interface TestResult { ok: boolean; latencyMs: number; message: string; model: string }

/** 管理员 AI 设置：保存后下一次调用生效；Key 只显示掩码。 */
export default function AdminAiPage() {
  const { t } = useTranslation('translation')
  const [view, setView] = useState<AiSettingsView | null>(null)
  const [form, setForm] = useState({ provider: 'openai-compatible', baseUrl: '', model: '', apiKey: '', temperature: '0.2' })
  const [saved, setSaved] = useState(false)
  const [test, setTest] = useState<TestResult | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.get<AiSettingsView>('/api/admin/ai-settings').then((v) => {
      setView(v)
      setForm({ provider: v.provider, baseUrl: v.baseUrl, model: v.model, apiKey: '', temperature: v.temperature })
    }).catch((e: Error) => setError(e.message))
  }, [])

  const save = async () => {
    setBusy(true); setError(null); setSaved(false)
    try {
      const v = await api.put<AiSettingsView>('/api/admin/ai-settings', {
        provider: form.provider, baseUrl: form.baseUrl, model: form.model,
        apiKey: form.apiKey || null, temperature: Number(form.temperature),
      })
      setView(v); setSaved(true); setForm({ ...form, apiKey: '' })
    } catch (e) { setError((e as Error).message) } finally { setBusy(false) }
  }

  const runTest = async () => {
    setBusy(true); setError(null); setTest(null)
    try {
      setTest(await api.post<TestResult>('/api/admin/ai-settings/test', {
        baseUrl: form.baseUrl, model: form.model, apiKey: form.apiKey || null,
      }))
    } catch (e) { setError((e as Error).message) } finally { setBusy(false) }
  }

  return (
    <>
      <PageHeader title={t('app.ai.title')} sub={t('app.ai.sub')} />
      <div className="card" style={{ maxWidth: 760 }}>
      {error && <div className="inline-note danger">{error}</div>}
      {view && (
        <div className="inline-note">
          {t('app.ai.current', {
            provider: view.provider,
            model: view.model || t('app.ai.modelUnset'),
            key: view.apiKeySet
              ? t('app.ai.keySet', { mask: view.apiKeyMasked })
              : t('app.ai.keyUnset'),
            time: new Date(view.updatedAt).toLocaleString(),
          })}
          {' · '}{view.configured ? t('app.ai.configured') : t('app.ai.notConfigured')}
        </div>
      )}
      <label className="f"><b>Provider</b>
        <select value={form.provider} onChange={(e) => setForm({ ...form, provider: e.target.value })}>
          <option value="openai-compatible">{t('app.ai.providerOpenaiCompatible')}</option>
          <option value="deepseek">DeepSeek</option>
          <option value="ollama">Ollama</option>
          <option value="lmstudio">LM Studio</option>
          <option value="vllm">vLLM</option>
        </select>
      </label>
      <label className="f"><b>Base URL</b><input value={form.baseUrl} onChange={(e) => setForm({ ...form, baseUrl: e.target.value })} placeholder="http://localhost:11434/v1" /></label>
      <label className="f"><b>{t('app.ai.model')}</b><input value={form.model} onChange={(e) => setForm({ ...form, model: e.target.value })} placeholder="qwen3:8b" /></label>
      <label className="f"><b>Temperature</b><input type="number" min={0} max={1} step={0.1} value={form.temperature} onChange={(e) => setForm({ ...form, temperature: e.target.value })} /></label>
      <label className="f"><b>{t('app.ai.apiKey')}</b>
        <input type="password" value={form.apiKey} onChange={(e) => setForm({ ...form, apiKey: e.target.value })} placeholder={t('app.ai.keyOptionalPlaceholder')} autoComplete="off" />
      </label>
      <div className="row">
        <button className="btn pri" disabled={busy} onClick={() => void save()}>{t('app.ai.save')}</button>
        <button className="btn" disabled={busy} onClick={() => void runTest()}>{t('app.ai.test')}</button>
      </div>
      {saved && <p className="small">{t('app.ai.saved')}</p>}
      {test && (
        <p className="small">
          {t('app.ai.testResult', {
            result: test.ok ? t('app.ai.testOk') : t('app.ai.testFail'),
            message: test.message,
            ms: test.latencyMs,
          })}
        </p>
      )}
      </div>
    </>
  )
}
