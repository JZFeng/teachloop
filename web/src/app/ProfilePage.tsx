import { useEffect, useState } from 'react'
import { api } from '../api/client'

interface ProfileView {
  id: string
  email: string | null
  phone: string | null
  displayName: string
  locale: string
}

/** T034：微信/首次登录后的资料补全；手机号与邮箱必填。 */
export default function ProfilePage() {
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
    }).catch((e: Error) => setError(e.message))
  }, [])

  const save = async () => {
    setError(null)
    setMessage(null)
    try {
      const saved = await api.put<ProfileView>('/api/profile', { phone, email, displayName })
      setProfile(saved)
      setMessage('已保存')
    } catch (e) {
      setError((e as Error).message)
    }
  }

  return (
    <div className="card">
      <h2>资料补全</h2>
      <p className="small muted">首次登录后必须补全手机号与邮箱才能使用其他功能。</p>
      {error && <div className="inline-note danger">{error}</div>}
      {message && <div className="inline-note">{message}</div>}
      {profile && (
        <>
          <label className="f"><b>姓名</b>
            <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
          </label>
          <label className="f"><b>手机号</b>
            <input value={phone} placeholder="13800000000" onChange={(e) => setPhone(e.target.value)} />
          </label>
          <label className="f"><b>邮箱</b>
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
          </label>
          <button className="btn pri" disabled={!phone.trim() || !email.trim()} onClick={() => void save()}>
            保存
          </button>
        </>
      )}
    </div>
  )
}
