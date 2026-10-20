import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api/client'

interface Status { needsAdmin: boolean; aiConfigured: boolean; aiModel: string }

/** 首次管理员初始化（对齐 DSH initial setup）：没有 ADMIN 时允许创建，之后到 AI 设置填模型。 */
export default function SetupPage() {
  const navigate = useNavigate()
  const [status, setStatus] = useState<Status | null>(null)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [displayName, setDisplayName] = useState('管理员')
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)

  useEffect(() => {
    api.get<Status>('/api/setup/status').then(setStatus).catch((e: Error) => setError(e.message))
  }, [])

  const createAdmin = async () => {
    setError(null)
    try {
      await api.post('/api/setup/admin', { email, password, displayName })
      setDone(true)
    } catch (e) {
      setError((e as Error).message)
    }
  }

  if (!status) {
    return <div className="card"><h2>初始化</h2>{error ? <div className="inline-note danger">{error}</div> : <p className="muted">加载中…</p>}</div>
  }

  return (
    <div className="card" style={{ maxWidth: 560, margin: '48px auto' }}>
      <h1>TeachLoop 初始设置</h1>
      <p className="small muted">第一次部署：先创建管理员，再配置 AI 大模型。管理员密码不会明文保存。</p>
      {error && <div className="inline-note danger">{error}</div>}
      {done ? (
        <>
          <div className="inline-note">管理员已创建。</div>
          <button className="btn pri" onClick={() => navigate('/login')}>去登录</button>
        </>
      ) : status.needsAdmin ? (
        <>
          <label className="f"><b>管理员姓名</b><input value={displayName} onChange={(e) => setDisplayName(e.target.value)} /></label>
          <label className="f"><b>邮箱</b><input type="email" value={email} onChange={(e) => setEmail(e.target.value)} /></label>
          <label className="f"><b>密码（至少 8 位）</b><input type="password" value={password} onChange={(e) => setPassword(e.target.value)} /></label>
          <button className="btn pri" disabled={!email || password.length < 8} onClick={() => void createAdmin()}>创建管理员</button>
        </>
      ) : (
        <>
          <div className="inline-note">系统已有管理员{status.aiConfigured ? `，AI 已配置（${status.aiModel}）` : '，但 AI 尚未配置'}。</div>
          <button className="btn pri" onClick={() => navigate('/login')}>去登录</button>
          <p className="small muted">登录后从左侧「AI 设置」进入模型配置。</p>
        </>
      )}
    </div>
  )
}
