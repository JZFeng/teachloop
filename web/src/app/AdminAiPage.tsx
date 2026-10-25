import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client'
import { Alert, Button, Field, Input, PageHeader } from '../ui'
import type { AiModelsView, AiProviderRequest, AiProviderView, AiTestResult } from '../api/types'

interface ModelsResult { ok: boolean; models: string[]; message: string }

interface FormState {
  providerId: string
  displayName: string
  baseUrl: string
  protocol: string
  apiKey: string
}

const EMPTY: FormState = {
  providerId: '', displayName: '', baseUrl: '', protocol: 'openai', apiKey: '',
}

/**
 * 管理员 AI 设置（决策 010，2026-10-24 修订）：维护 provider 列表、默认 provider 与当前全站模型。
 * 教师页不再有 AI 入口；全站模型只在这里切换（每个模型行的「设为当前」）。
 * 多个 provider 时用「设为默认」切默认 provider；只有一个 provider 时它天然就是默认。
 */
export default function AdminAiPage() {
  const { t } = useTranslation('translation')
  const [providers, setProviders] = useState<AiProviderView[]>([])
  const [formOpen, setFormOpen] = useState(false)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [form, setForm] = useState<FormState>(EMPTY)
  const [availableModels, setAvailableModels] = useState<string[]>([])
  const [selectedModels, setSelectedModels] = useState<string[]>([])
  const [currentModel, setCurrentModel] = useState<string | null>(null)
  const [modelsMessage, setModelsMessage] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [test, setTest] = useState<AiTestResult | null>(null)
  const [busy, setBusy] = useState(false)

  const load = async () => {
    try {
      const [list, current] = await Promise.all([
        api.get<AiProviderView[]>('/api/admin/ai-providers'),
        api.get<AiModelsView>('/api/ai-models'),
      ])
      setProviders(list)
      setCurrentModel(current.currentModel)
    } catch (e) {
      setError((e as Error).message)
    }
  }

  useEffect(() => { void load() }, [])

  const active = providers.find((p) => p.active)

  const openAdd = () => {
    setEditingId(null)
    setForm(EMPTY)
    setAvailableModels([])
    setSelectedModels([])
    setModelsMessage(null)
    setTest(null)
    setFormOpen(true)
  }

  const openEdit = (p: AiProviderView) => {
    setEditingId(p.id)
    setForm({
      providerId: p.providerId, displayName: p.displayName, baseUrl: p.baseUrl,
      protocol: p.protocol || 'openai', apiKey: '',
    })
    setAvailableModels([])
    setSelectedModels(p.models?.length ? p.models : [p.model])
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
        model: '',
        apiKey: form.apiKey || null,
      })
      setAvailableModels(result.models)
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
        baseUrl: form.baseUrl, model: selectedModels[0] ?? '', apiKey: form.apiKey || null,
      }))
    } catch (e) {
      setError((e as Error).message)
    } finally { setBusy(false) }
  }

  const save = async () => {
    setError(null); setMessage(null)
    if (selectedModels.length === 0) {
      setError(t('app.ai.modelRequired'))
      return
    }
    setBusy(true)
    const body: AiProviderRequest = {
      providerId: form.providerId,
      displayName: form.displayName,
      baseUrl: form.baseUrl,
      protocol: form.protocol,
      apiKey: form.apiKey.trim() || null,
      model: selectedModels[0],
      models: selectedModels,
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

  /** 只切默认 provider，不要求再挑模型：后端保留该 provider 已选的模型，否则用它的默认模型。 */
  const setDefault = async (p: AiProviderView) => {
    setBusy(true); setError(null); setMessage(null)
    try {
      setProviders(await api.put<AiProviderView[]>(`/api/admin/ai-providers/${p.id}/default`, {}))
      await load()
      setMessage(t('app.ai.defaultSet', { name: p.displayName }))
    } catch (e) {
      setError((e as Error).message)
    } finally { setBusy(false) }
  }

  const activate = async (p: AiProviderView, model: string) => {
    setBusy(true); setError(null); setMessage(null)
    try {
      await api.put('/api/ai-models/current', { providerId: p.providerId, model })
      await load()
      setMessage(t('app.ai.activated', { name: `${p.displayName} · ${model}` }))
    } catch (e) {
      setError((e as Error).message)
    } finally { setBusy(false) }
  }

  const toggleModel = (model: string) => {
    setSelectedModels((prev) => prev.includes(model)
      ? prev.filter((m) => m !== model)
      : [...prev, model])
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
            ? <><b>{active.displayName}</b> · <span className="mono">{currentModel ?? active.model}</span> · {active.baseUrl}</>
            : t('app.ai.noProvider')}
        </p>
        {active && (
          <p className="small muted">{t('app.ai.globalHint')}</p>
        )}
        <Button variant="primary" onClick={openAdd}>{t('app.ai.addProvider')}</Button>
      </div>

      <div className="card" style={{ maxWidth: 900, marginTop: 16 }}>
        <h2>{t('app.ai.providers')}</h2>
        {providers.length === 0 && <div className="inline-note">{t('app.ai.noProvider')}</div>}
        {providers.map((p) => (
          <div key={p.id} className="card" style={{ marginTop: 10 }}>
            <div className="row" style={{ justifyContent: 'space-between', alignItems: 'flex-start' }}>
              <div className="grow">
                <b>{p.displayName}</b>
                {p.active && <span className="badge" style={{ marginLeft: 8 }}>{t('app.ai.defaultBadge')}</span>}
                <div className="small muted mono">{p.providerId} · {p.baseUrl}</div>
                <div className="small muted">
                  {p.protocol === 'openai' ? t('app.ai.protocolOpenai') : p.protocol}
                  {p.apiKeyMasked ? ` · ${t('app.ai.keySet', { mask: p.apiKeyMasked })}` : ` · ${t('app.ai.keyOptional')}`}
                </div>
                <div className="row" style={{ gap: 6, flexDirection: 'column', alignItems: 'stretch', marginTop: 8 }}>
                  {(p.models?.length ? p.models : [p.model]).map((model) => (
                    <div key={model} className="row" style={{ gap: 8, justifyContent: 'space-between' }}>
                      <span className="mono">{model}</span>
                      {p.active && currentModel === model
                        ? <span className="badge">{t('app.ai.active')}</span>
                        : <Button size="small" variant="text" onClick={() => void activate(p, model)}>{t('app.ai.activate')}</Button>}
                    </div>
                  ))}
                </div>
              </div>
              <div className="row" style={{ gap: 6 }}>
                {providers.length > 1 && !p.active && (
                  <Button size="small" variant="primary" busy={busy} onClick={() => void setDefault(p)}>
                    {t('app.ai.setDefault')}
                  </Button>
                )}
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
          <Field label={t('app.ai.models')} hint={t('app.ai.modelsHint')}
            extra={<Button size="small" onClick={() => void loadModels()} busy={busy}>{t('app.ai.loadModels')}</Button>}>
            {availableModels.length > 0 && (
              <div className="row" style={{ flexDirection: 'column', alignItems: 'stretch', gap: 6, marginBottom: 8 }}>
                {availableModels.map((model) => (
                  <label key={model} className="row" style={{ gap: 8 }}>
                    <input type="checkbox" checked={selectedModels.includes(model)}
                      onChange={() => toggleModel(model)} />
                    <span className="mono">{model}</span>
                  </label>
                ))}
              </div>
            )}
            {selectedModels.length > 0 && (
              <div className="row" style={{ gap: 6, flexWrap: 'wrap', marginTop: 8 }}>
                {selectedModels.map((model) => (
                  <span key={model} className="mono" style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                    {model}
                    <Button size="small" variant="text" onClick={() => toggleModel(model)}>×</Button>
                  </span>
                ))}
              </div>
            )}
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
