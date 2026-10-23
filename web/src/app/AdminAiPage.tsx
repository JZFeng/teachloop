import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client'
import { Alert, Button, Field, Input, PageHeader } from '../ui'
import type { AiProviderRequest, AiProviderView, AiTestResult } from '../api/types'

interface ModelsResult { ok: boolean; models: string[]; message: string }

interface FormState {
  providerId: string
  displayName: string
  baseUrl: string
  protocol: string
  apiKey: string
  model: string
}

const EMPTY: FormState = {
  providerId: '', displayName: '', baseUrl: '', protocol: 'openai', apiKey: '', model: '',
}

/**
 * 管理员 AI 设置（决策 010 修订）：维护 provider 列表与当前全站模型。
 * 教师不在这里配置；教师在 /profile 只从列表里选一个。
 */
export default function AdminAiPage() {
  const { t } = useTranslation('translation')
  const [providers, setProviders] = useState<AiProviderView[]>([])
  const [formOpen, setFormOpen] = useState(false)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [form, setForm] = useState<FormState>(EMPTY)
  const [models, setModels] = useState<string[]>([])
  const [modelsMessage, setModelsMessage] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [test, setTest] = useState<AiTestResult | null>(null)
  const [busy, setBusy] = useState(false)

  const load = async () => {
    try {
      setProviders(await api.get<AiProviderView[]>('/api/admin/ai-providers'))
    } catch (e) {
      setError((e as Error).message)
    }
  }

  useEffect(() => { void load() }, [])

  const active = providers.find((p) => p.active)

  const openAdd = () => {
    setEditingId(null)
    setForm(EMPTY)
    setModels([])
    setModelsMessage(null)
    setTest(null)
    setFormOpen(true)
  }

  const openEdit = (p: AiProviderView) => {
    setEditingId(p.id)
    setForm({
      providerId: p.providerId, displayName: p.displayName, baseUrl: p.baseUrl,
      protocol: p.protocol || 'openai', apiKey: '', model: p.model,
    })
    setModels([p.model])
    setModelsMessage(null)
    setTest(null)
    setFormOpen(true)
  }

  const change = (patch: Partial<FormState>) => setForm((prev) => ({ ...prev, ...patch }))

  const loadModels = async () => {
    setBusy(true); setError(null); setModelsMessage(null)
    try {
      const result = await api.post<ModelsResult>('/api/admin/ai-providers/models', {
        baseUrl: form.baseUrl,
        model: form.model,
        apiKey: form.apiKey || null,
      })
      setModels(result.models)
      setModelsMessage(result.ok
        ? t('app.ai.modelsLoaded', { count: result.models.length })
        : t('app.ai.modelsFailed', { message: result.message }))
    } catch (e) {
      setError((e as Error).message)
    } finally { setBusy(false) }
  }

  const runTest = async () => {
    setBusy(true); setError(null); setTest(null)
    try {
      setTest(await api.post<AiTestResult>('/api/admin/ai-providers/test', {
        baseUrl: form.baseUrl, model: form.model, apiKey: form.apiKey || null,
      }))
    } catch (e) {
      setError((e as Error).message)
    } finally { setBusy(false) }
  }

  const save = async () => {
    setBusy(true); setError(null); setMessage(null)
    const body: AiProviderRequest = {
      providerId: form.providerId,
      displayName: form.displayName,
      baseUrl: form.baseUrl,
      protocol: form.protocol,
      apiKey: form.apiKey.trim() || null,
      model: form.model,
    }
    try {
      if (editingId) await api.put<AiProviderView>(`/api/admin/ai-providers/${editingId}`, body)
      else await api.post<AiProviderView>('/api/admin/ai-providers', body)
      await load()
      setMessage(t('app.ai.providerSaved'))
      setFormOpen(false)
    } catch (e) {
      setError((e as Error).message)
    } finally { setBusy(false) }
  }

  const activate = async (p: AiProviderView) => {
    setBusy(true); setError(null); setMessage(null)
    try {
      await api.put('/api/ai-models/current', { providerId: p.providerId })
      await load()
      setMessage(t('app.ai.activated', { name: p.displayName }))
    } catch (e) {
      setError((e as Error).message)
    } finally { setBusy(false) }
  }

  const remove = async (p: AiProviderView) => {
    if (!window.confirm(t('app.ai.deleteConfirm', { name: p.displayName }))) return
    setBusy(true); setError(null); setMessage(null)
    try {
      await api.del(`/api/admin/ai-providers/${p.id}`)
      await load()
      setMessage(t('app.ai.deleted', { name: p.displayName }))
    } catch (e) {
      setError((e as Error).message)
    } finally { setBusy(false) }
  }

  return (
    <>
      <PageHeader title={t('app.ai.title')} sub={t('app.ai.sub')} />

      <div className="card" style={{ maxWidth: 900 }}>
        {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 14 }} />}
        {message && <Alert type="success" showIcon message={message} style={{ marginBottom: 14 }} />}

        <h2>{t('app.ai.currentModel')}</h2>
        <p className="small muted">
          {active
            ? <><b>{active.displayName}</b> · <span className="mono">{active.model}</span> · {active.baseUrl}</>
            : t('app.ai.noProvider')}
        </p>
        {active && (
          <p className="small muted">{t('app.ai.globalHint')}</p>
        )}
        <Button variant="primary" onClick={openAdd}>{t('app.ai.addProvider')}</Button>
      </div>

      <div className="card" style={{ maxWidth: 900, marginTop: 16 }}>
        <h2>{t('app.ai.providers')}</h2>
        <p className="small muted">{t('app.ai.providersHint')}</p>
        {providers.length === 0 && <div className="inline-note">{t('app.ai.noProvider')}</div>}
        {providers.map((p) => (
          <div key={p.id} className="card" style={{ marginTop: 10 }}>
            <div className="row" style={{ justifyContent: 'space-between', alignItems: 'flex-start' }}>
              <div>
                <b>{p.displayName}</b>
                {p.active && <span className="badge" style={{ marginLeft: 8 }}>{t('app.ai.active')}</span>}
                <div className="small muted mono">{p.providerId} · {p.baseUrl}</div>
                <div className="small muted">
                  {p.protocol === 'openai' ? t('app.ai.protocolOpenai') : p.protocol} · <span className="mono">{p.model}</span>
                  {p.apiKeyMasked ? ` · ${t('app.ai.keySet', { mask: p.apiKeyMasked })}` : ` · ${t('app.ai.keyOptional')}`}
                </div>
              </div>
              <div className="row" style={{ gap: 6 }}>
                {!p.active && <Button size="small" onClick={() => void activate(p)}>{t('app.ai.activate')}</Button>}
                <Button size="small" onClick={() => openEdit(p)}>{t('app.ai.edit')}</Button>
                <Button size="small" variant="danger" onClick={() => void remove(p)}>{t('app.ai.delete')}</Button>
              </div>
            </div>
          </div>
        ))}
      </div>

      {formOpen && (
        <div className="card" style={{ maxWidth: 900, marginTop: 16 }}>
          <h2>{editingId ? t('app.ai.editProvider') : t('app.ai.addProvider')}</h2>
          <p className="small muted">{t('app.ai.providerFormHint')}</p>

          <Field label={t('app.ai.providerId')} hint={t('app.ai.providerIdHint')} htmlFor="provider-key">
            <Input id="provider-key" value={form.providerId} placeholder="qwen3.8local"
              onChange={(e) => change({ providerId: e.target.value })} />
          </Field>
          <Field label={t('app.ai.displayName')} hint={t('app.ai.displayNameHint')} htmlFor="provider-name">
            <Input id="provider-name" value={form.displayName} placeholder="qwen3.8"
              onChange={(e) => change({ displayName: e.target.value })} />
          </Field>
          <Field label={t('app.ai.baseUrl')} hint={t('app.ai.baseUrlHint')} htmlFor="provider-base-url">
            <Input id="provider-base-url" value={form.baseUrl} placeholder="http://192.168.68.101:8080/v1"
              onChange={(e) => change({ baseUrl: e.target.value })} />
          </Field>
          <Field label={t('app.ai.protocol')} hint={t('app.ai.protocolHint')} htmlFor="provider-protocol">
            <select id="provider-protocol" value={form.protocol}
              onChange={(e) => change({ protocol: e.target.value })}>
              <option value="openai">{t('app.ai.protocolOpenai')}</option>
              <option value="anthropic" disabled>{t('app.ai.protocolAnthropic')}</option>
            </select>
          </Field>
          <Field label={t('app.ai.apiKey')} hint={t('app.ai.apiKeyHint')} htmlFor="provider-key-secret">
            <Input id="provider-key-secret" type="password" value={form.apiKey}
              placeholder={t('app.ai.keyOptionalPlaceholder')} autoComplete="off"
              onChange={(e) => change({ apiKey: e.target.value })} />
          </Field>
          <Field label={t('app.ai.model')} hint={t('app.ai.modelHint')} htmlFor="provider-model"
            extra={<Button size="small" onClick={() => void loadModels()} busy={busy}>{t('app.ai.loadModels')}</Button>}>
            <Input id="provider-model" list="provider-model-options" value={form.model}
              placeholder="qwen3.8-flash-next-iq2_xs"
              onChange={(e) => change({ model: e.target.value })} />
            <datalist id="provider-model-options">
              {models.map((m) => <option key={m} value={m} />)}
            </datalist>
          </Field>
          {modelsMessage && <p className="small muted">{modelsMessage}</p>}
          {test && (
            <p className="small muted">
              {t('app.ai.testResult', {
                result: test.ok ? t('app.ai.testOk') : t('app.ai.testFail'),
                message: test.message, ms: test.latencyMs,
              })}
            </p>
          )}
          <div className="row" style={{ gap: 8, marginTop: 6 }}>
            <Button variant="primary" busy={busy} onClick={() => void save()}>{t('app.ai.saveProvider')}</Button>
            <Button busy={busy} onClick={() => void runTest()}>{t('app.ai.test')}</Button>
            <Button onClick={() => setFormOpen(false)}>{t('app.ai.cancel')}</Button>
          </div>
        </div>
      )}
    </>
  )
}
