import { useEffect, useState } from 'react'
import { api } from '../api/client'

interface AiSettingsView {
  provider: string
  baseUrl: string
  model: string
  temperature: string
  apiKeySet: boolean
  apiKeyMasked: string | null
  updatedAt: string
  restartRequired: boolean
}

interface TestResult { ok: boolean; latencyMs: number; message: string; model: string }

/** 管理员 AI 设置：保存后需重启应用生效；Key 只显示掩码。 */
export default function AdminAiPage() {
  const [view, setView] = useState<AiSettingsView | null>(null)
  const [form, setForm] = useState({ provider: 'deepseek', baseUrl: 'https://api.deepseek.com', model: 'deepseek-flash', apiKey: '', temperature: '0.2' })
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
    <div className="card" style={{ maxWidth: 760 }}>
      <h1>AI 大模型设置</h1>
      <p className="small muted">支持 DeepSeek / OpenAI 兼容端点。保存后需要重启应用才生效；API Key 以加密形式存库，不会回显。</p>
      {error && <div className="inline-note danger">{error}</div>}
      {view && (
        <div className="inline-note">
          当前：{view.provider} / {view.model} · Key {view.apiKeySet ? `已配置 ${view.apiKeyMasked}` : '未配置'} · 更新于 {new Date(view.updatedAt).toLocaleString()}
        </div>
      )}
      <label className="f"><b>Provider</b>
        <select value={form.provider} onChange={(e) => setForm({ ...form, provider: e.target.value })}>
          <option value="deepseek">DeepSeek</option>
          <option value="openai-compatible">OpenAI 兼容</option>
        </select>
      </label>
      <label className="f"><b>Base URL</b><input value={form.baseUrl} onChange={(e) => setForm({ ...form, baseUrl: e.target.value })} placeholder="https://api.deepseek.com" /></label>
      <label className="f"><b>模型</b><input value={form.model} onChange={(e) => setForm({ ...form, model: e.target.value })} placeholder="deepseek-flash" /></label>
      <label className="f"><b>Temperature</b><input type="number" min={0} max={1} step={0.1} value={form.temperature} onChange={(e) => setForm({ ...form, temperature: e.target.value })} /></label>
      <label className="f"><b>API Key（留空表示不修改）</b>
        <input type="password" value={form.apiKey} onChange={(e) => setForm({ ...form, apiKey: e.target.value })} placeholder="sk-..." autoComplete="off" />
      </label>
      <div className="row">
        <button className="btn pri" disabled={busy} onClick={() => void save()}>保存</button>
        <button className="btn" disabled={busy} onClick={() => void runTest()}>测试连接</button>
      </div>
      {saved && <p className="small">已保存。重启应用后新的 AI 配置生效。</p>}
      {test && <p className="small">测试：{test.ok ? '通过' : '失败'} · {test.message} · {test.latencyMs}ms</p>}
    </div>
  )
}
