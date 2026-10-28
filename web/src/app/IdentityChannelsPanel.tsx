import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, ApiError } from '../api/client'
import { Alert, Button, Field, InlineNote, Input } from '../ui'

interface ChannelView {
  channel: string
  enabled: boolean
  driver: string
  fromAddress: string | null
  baseUrl: string | null
  hasSecret: boolean
  secretMask: string | null
  secretUnreadable: boolean
  dailyQuota: number | null
  extra: string | null
}

const DRIVER_OPTIONS: Record<string, string[]> = {
  email: ['smtp', 'http-api'],
  sms: ['aliyun-sms'],
  wechat: ['wechat_web', 'wechat_mp'],
}

/**
 * 009 T535：身份通道配置面板（嵌在管理端的 AI 设置页里）。
 *
 * 只做三件事：开关、驱动与地址、密钥。密钥输入框**永远不回显**，留空表示不改。
 */
export default function IdentityChannelsPanel() {
  const { t } = useTranslation('translation')
  const [list, setList] = useState<ChannelView[]>([])
  const [secrets, setSecrets] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [savedChannel, setSavedChannel] = useState<string | null>(null)

  const load = () => {
    api.get<ChannelView[]>('/api/admin/identity-channels')
      .then((rows) => setList(rows))
      .catch((e) => setError(e instanceof ApiError ? e.message : String(e)))
  }

  useEffect(load, [])

  const patch = (channel: string, changes: Partial<ChannelView>) => {
    setList((rows) => rows.map((r) => (r.channel === channel ? { ...r, ...changes } : r)))
  }

  const save = async (row: ChannelView) => {
    setSaving(row.channel)
    setError(null)
    setSavedChannel(null)
    try {
      const updated = await api.put<ChannelView>(`/api/admin/identity-channels/${row.channel}`, {
        enabled: row.enabled,
        driver: row.driver,
        fromAddress: row.fromAddress,
        baseUrl: row.baseUrl,
        dailyQuota: row.dailyQuota,
        extra: row.extra,
        secret: secrets[row.channel] ?? null,
      })
      setList((rows) => rows.map((r) => (r.channel === updated.channel ? updated : r)))
      setSecrets((s) => ({ ...s, [row.channel]: '' }))
      setSavedChannel(row.channel)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e))
    } finally {
      setSaving(null)
    }
  }

  return (
    <div className="card" style={{ marginTop: 16 }}>
      <h2>{t('app.channels.title')}</h2>
      <p className="sub">{t('app.channels.sub')}</p>
      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} />}
      {savedChannel && (
        <Alert type="success" showIcon message={t('app.channels.saved')} style={{ marginBottom: 12 }} />
      )}

      {list.map((row) => (
        <div key={row.channel} style={{ borderTop: '1px solid var(--line)', paddingTop: 14, marginTop: 14 }}>
          <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
            <b>{t(`app.channels.name.${row.channel}`, { defaultValue: row.channel })}</b>
            <label className="row" style={{ gap: 8 }}>
              <input type="checkbox" checked={row.enabled}
                onChange={(e) => patch(row.channel, { enabled: e.target.checked })} />
              <span className="small">{row.enabled ? t('app.channels.on') : t('app.channels.off')}</span>
            </label>
          </div>

          <div className="row" style={{ gap: 12, marginTop: 10 }}>
            <Field label={t('app.channels.driver')} htmlFor={`driver-${row.channel}`}>
              <select id={`driver-${row.channel}`} value={row.driver}
                onChange={(e) => patch(row.channel, { driver: e.target.value })}>
                {(DRIVER_OPTIONS[row.channel] ?? [row.driver]).map((d) => (
                  <option key={d} value={d}>{d}</option>
                ))}
              </select>
            </Field>
            <Field label={t('app.channels.from')} htmlFor={`from-${row.channel}`}>
              <Input id={`from-${row.channel}`} value={row.fromAddress ?? ''}
                onChange={(e) => patch(row.channel, { fromAddress: e.target.value })} />
            </Field>
          </div>

          <Field label={t('app.channels.baseUrl')} htmlFor={`url-${row.channel}`}>
            <Input id={`url-${row.channel}`} value={row.baseUrl ?? ''}
              placeholder={row.channel === 'email' ? 'smtp.163.com:465' : ''}
              onChange={(e) => patch(row.channel, { baseUrl: e.target.value })} />
          </Field>

          <Field label={t('app.channels.secret')} htmlFor={`secret-${row.channel}`}>
            <Input id={`secret-${row.channel}`} type="password"
              value={secrets[row.channel] ?? ''}
              placeholder={row.hasSecret ? (row.secretMask ?? '••••••••') : ''}
              onChange={(e) => setSecrets((s) => ({ ...s, [row.channel]: e.target.value }))} />
          </Field>
          {row.secretUnreadable && (
            <InlineNote tone="warning" style={{ marginBottom: 10 }}>
              {t('app.channels.unreadable')}
            </InlineNote>
          )}

          <Button variant="primary" busy={saving === row.channel} onClick={() => void save(row)}>
            {t('app.channels.save')}
          </Button>
        </div>
      ))}

      <InlineNote style={{ marginTop: 12 }}>{t('app.channels.hint')}</InlineNote>
    </div>
  )
}
