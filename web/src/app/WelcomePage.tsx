import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client'
import { useAuth } from '../api/auth'
import { Button, Field, InlineNote, Input } from '../ui'
import { markPrompted } from './welcomeGate'

/** `GET /api/profile` 里这一页用得到的字段（其余字段不读，避免跟着资料页一起改）。 */
interface ProfileLite {
  phone: string | null
  email: string | null
  displayName: string | null
}

/**
 * `/welcome` 首次引导（US-93 / AC-93.2）：只收一个手机号，可以跳过。
 *
 * 为什么与 `/profile` 分成两个页面（spec §9 第 3 条）：资料页是「管理我的资料」，
 * 有语言、密码、评分偏好一堆东西；首次登录只该问一件事。把整张资料页丢给新用户，
 * 等于让他先读完设置再开始用——所以这里只有一个输入框和两个按钮。
 *
 * 三个行为细节：
 * 1. **保存成功与跳过都写 `localStorage`**（`teachloop.profile.prompted`）：区别只是保存会先 PUT。
 *    不写的话下次刷新又被送回本页，人会被困在一个「以后再说」说了不算的页面里。
 * 2. **手机号空则不允许提交**，用 `Field` 的 `error` 显示必填提示（不自造红色小字样式）。
 * 3. PUT 失败**内联**显示原因，不跳转、不清空输入——失败后重新输一遍手机号是最烦的一种体验。
 *
 * 注意：后端 `PUT /api/profile` 的 `phone` 与 `email` 都是 `@NotBlank`，
 * 所以这里先读一次现有资料，把 email / displayName 原样带回去，只改 phone。
 */
export default function WelcomePage() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const { refreshProfile } = useAuth()
  const [phone, setPhone] = useState('')
  const [profile, setProfile] = useState<ProfileLite | null>(null)
  const [saving, setSaving] = useState(false)
  const [phoneError, setPhoneError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    api
      .get<ProfileLite>('/api/profile')
      .then((value) => {
        if (!alive) return
        setProfile(value)
        if (value.phone) setPhone(value.phone)
      })
      .catch(() => {
        // 读不到就不预填：保存时后端会明确说缺什么，比在这里猜一句错误好。
        if (alive) setProfile(null)
      })
    return () => {
      alive = false
    }
  }, [])

  const leave = (target: string) => {
    markPrompted()
    navigate(target, { replace: true })
  }

  const save = async () => {
    setError(null)
    if (phone.trim().length === 0) {
      setPhoneError(t('today.welcome.phone'))
      return
    }
    setPhoneError(null)
    setSaving(true)
    try {
      await api.put('/api/profile', {
        phone: phone.trim(),
        email: profile?.email ?? '',
        displayName: profile?.displayName ?? '',
        locale: i18n.language,
      })
      // 补全后重算一次资料判定：顶部提示条与 features 都据此恢复（AC-93.3）。
      await refreshProfile()
      leave('/')
    } catch (failure) {
      setError((failure as Error).message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="card" style={{ maxWidth: 560, margin: '48px auto' }}>
      <h1>{t('today.welcome.title')}</h1>
      <p className="small muted">{t('today.welcome.body')}</p>
      {error && <InlineNote tone="error">{error}</InlineNote>}
      <Field
        label={t('today.welcome.phone')}
        required
        htmlFor="welcome-phone"
        error={phoneError}
      >
        <Input
          id="welcome-phone"
          value={phone}
          inputMode="tel"
          autoComplete="tel"
          placeholder={t('today.welcome.phone')}
          onChange={(event) => {
            setPhone(event.target.value)
            if (phoneError) setPhoneError(null)
          }}
          onPressEnter={() => void save()}
        />
      </Field>
      <div className="row" style={{ marginTop: 12 }}>
        <Button variant="primary" busy={saving} onClick={() => void save()}>
          {t('today.welcome.submit')}
        </Button>
        <Button onClick={() => leave('/')}>{t('today.welcome.skip')}</Button>
      </div>
    </div>
  )
}
