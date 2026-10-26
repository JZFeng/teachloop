import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import { InlineNote } from '../ui'

interface Status { needsAdmin: boolean; aiConfigured: boolean; aiModel: string }

/** 首次管理员初始化（对齐 DSH initial setup）：没有 ADMIN 时允许创建，之后到 AI 设置填模型。 */
export default function SetupPage() {
  const { t } = useTranslation('translation')
  const navigate = useNavigate()
  const [status, setStatus] = useState<Status | null>(null)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [apiKey, setApiKey] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)

  useEffect(() => {
    api.get<Status>('/api/setup/status').then(setStatus).catch((e: Error) => setError(e.message))
  }, [])

  const createAdmin = async () => {
    setError(null)
    try {
      await api.post('/api/setup/admin', { email, password, displayName, apiKey })
      setDone(true)
    } catch (e) {
      setError((e as Error).message)
    }
  }

  if (!status) {
    return <div className="card"><h2>{t('app.setup.title')}</h2>{error ? <InlineNote tone="error">{error}</InlineNote> : <p className="muted">{t('app.setup.loading')}</p>}</div>
  }

  return (
    <div className="card" style={{ maxWidth: 560, margin: '48px auto' }}>
      <h1>{t('app.setup.pageTitle')}</h1>
      <p className="small muted">{t('app.setup.sub')}</p>
      {error && <InlineNote tone="error">{error}</InlineNote>}
      {done ? (
        <>
          <div className="inline-note">{t('app.setup.adminDone')}</div>
          <button className="btn pri" onClick={() => navigate('/login')}>{t('app.setup.goLogin')}</button>
        </>
      ) : status.needsAdmin ? (
        <>
          <label className="f"><b>{t('app.setup.adminName')}</b><input value={displayName} placeholder={t('app.setup.defaultAdminName')} onChange={(e) => setDisplayName(e.target.value)} /></label>
          <label className="f"><b>{t('app.setup.email')}</b><input type="email" value={email} onChange={(e) => setEmail(e.target.value)} /></label>
          <label className="f"><b>{t('app.setup.password')}</b><input type="password" value={password} onChange={(e) => setPassword(e.target.value)} /></label>
          <label className="f"><b>{t('app.setup.apiKey')}</b><input type="password" value={apiKey} placeholder={t('app.setup.apiKeyPlaceholder')} onChange={(e) => setApiKey(e.target.value)} autoComplete="off" /></label>
          <p className="small muted">{t('app.setup.apiKeyHint')}</p>
          <button className="btn pri" disabled={!email || password.length < 8} onClick={() => void createAdmin()}>{t('app.setup.createAdmin')}</button>
        </>
      ) : (
        <>
          <div className="inline-note">{t('app.setup.exists')}{status.aiConfigured
            ? t('app.setup.aiConfigured', { model: status.aiModel })
            : t('app.setup.aiMissing')}。</div>
          <button className="btn pri" onClick={() => navigate('/login')}>{t('app.setup.goLogin')}</button>
          <p className="small muted">{t('app.setup.afterLogin')}</p>
        </>
      )}
    </div>
  )
}
